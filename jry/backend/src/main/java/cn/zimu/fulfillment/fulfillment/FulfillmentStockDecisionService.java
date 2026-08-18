package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventRepository;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.stock.JDStockService;
import cn.zimu.fulfillment.order.OrderQueryService;
import cn.zimu.fulfillment.sku.ProviderType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 履约库存决策应用缝。
 *
 * <p>仅保留第三方履约方（THIRD_PARTY）的标准库存事实决策。旧 JD 分支会聚合全部仓、
 * 取整并在缺货时创建采购工单，已被 Shipment 级的目标仓/精确件数实时库存检查取代；
 * 为避免未来误接入绕过新门禁，JD_WAREHOUSE 在本 seam 明确 fail closed。
 *
 * <p>BUSINESS 订单仅在上游显式调用本用例时推进；默认 Mock 客户端不会自动调用这里。
 */
@Service
public class FulfillmentStockDecisionService {

    private static final String SCOPE = "fulfillment.stock_decision";
    private static final String READY_TO_EXPORT = "READY_TO_EXPORT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderEventRepository eventRepository;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final OrderQueryService orderQuery;
    private final FulfillmentReadService fulfillmentRead;
    private final JDStockService jdStock;
    private final TransactionTemplate requiresNew;

    public FulfillmentStockDecisionService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderEventRepository eventRepository,
            OrderVersionService versions,
            AuditLogService audits,
            OrderQueryService orderQuery,
            FulfillmentReadService fulfillmentRead,
            JDStockService jdStock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.events = events;
        this.eventRepository = eventRepository;
        this.versions = versions;
        this.audits = audits;
        this.orderQuery = orderQuery;
        this.fulfillmentRead = fulfillmentRead;
        this.jdStock = jdStock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public IdempotentResult<StockDecisionResult> decide(
            long fulfillmentId,
            StockDecisionCommand command,
            String idempotencyKey,
            CommandContext context) {
        validateEnvelope(command);
        if (context == null || blankToNull(context.operator()) == null) {
            throw BusinessException.unprocessable("STOCK_DECISION_CONTEXT_INVALID", "库存决策操作人不能为空");
        }
        return idempotency.execute(
                SCOPE,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                Map.of("fulfillment_id", fulfillmentId, "decision", command),
                200,
                () -> apply(fulfillmentId, command, context));
    }

    private StockDecisionResult apply(
            long fulfillmentId, StockDecisionCommand command, CommandContext context) {
        validateEnvelope(command);
        FulfillmentContext fulfillment = lockFulfillment(fulfillmentId);
        if (ProviderType.JD_WAREHOUSE.name().equals(fulfillment.providerType())) {
            throw BusinessException.conflict(
                    "JD_STOCK_DECISION_RETIRED",
                    "旧京东库存决策入口已停用，请使用 Shipment 京东实时库存检查");
        }
        return applyNormalized(fulfillment, command, context);
    }

    /** 归一化库存事实路径（第三方履约且由本系统托管库存）：行为与历史一致。 */
    private StockDecisionResult applyNormalized(
            FulfillmentContext fulfillment, StockDecisionCommand command, CommandContext context) {
        if (!fulfillment.inventoryManagedByUs()) {
            throw BusinessException.unprocessable(
                    "INVENTORY_NOT_MANAGED", "第三方履约库存不由本系统预判");
        }
        requireReadyToExport(fulfillment);

        List<ExpectedItem> expected = expectedItems(fulfillment);
        Map<Long, StockDecisionCommand.Item> provided = normalizedItems(command.items());
        Set<Long> expectedSkuIds = expected.stream().map(ExpectedItem::skuId).collect(Collectors.toSet());
        if (!provided.keySet().equals(expectedSkuIds)) {
            throw BusinessException.unprocessable(
                    "STOCK_DECISION_ITEMS_MISMATCH", "库存决策必须完整覆盖当前订单行的 SKU");
        }

        List<Shortage> shortages = new ArrayList<>();
        for (ExpectedItem item : expected) {
            StockDecisionCommand.Item observation = provided.get(item.skuId());
            BigDecimal stock = quantity(observation.stockQuantity(), "stock_quantity");
            BigDecimal usable = quantity(observation.usableQuantity(), "usable_quantity");
            if (usable.compareTo(stock) > 0) {
                throw BusinessException.unprocessable(
                        "USABLE_STOCK_EXCEEDS_STOCK", "可用库存不得超过总库存");
            }
            jdbc.update(
                    """
                    INSERT INTO app.provider_stock_snapshots
                        (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                         quantity_unit, source_type, synced_at, source_ref, raw_payload)
                    VALUES (?, ?, ?, ?, ?, 'INTERNAL_UNIT', 'NORMALIZED_PROVIDER_SNAPSHOT', ?, ?, ?::jsonb)
                    """,
                    fulfillment.providerId(),
                    item.skuId(),
                    required(observation.warehouseCode(), "warehouse_code"),
                    stock,
                    usable,
                    OffsetDateTime.ofInstant(command.observedAt(), ZoneOffset.UTC),
                    blankToNull(observation.sourceRef()),
                    json(Map.of("normalized", true, "fulfillment_id", String.valueOf(fulfillment.id()))));
            BigDecimal shortage = item.requiredQuantity().subtract(usable).max(BigDecimal.ZERO);
            if (shortage.signum() > 0) {
                shortages.add(new Shortage(item, shortage, item.unit()));
            }
        }

        boolean available = shortages.isEmpty();
        if (available != (command.decision() == StockDecisionCommand.Decision.AVAILABLE)) {
            throw BusinessException.unprocessable(
                    "STOCK_DECISION_CONTRADICTS_QUANTITY",
                    available ? "库存足够时决策必须为 AVAILABLE" : "存在库存缺口时决策必须为 OUT_OF_STOCK");
        }
        return complete(
                fulfillment,
                command,
                context,
                command.decision(),
                shortages,
                Map.of("decision", command.decision().name(), "shortage_item_count", shortages.size()));
    }

    /**
     * 京东云仓实时库存判定路径：SKU 映射为京东 goodsNo 后查询正品可用库存快照，
     * 需求按「1 系统单位 = N 件」换算并向上取整，与京东可用件数比较得出可履约/不可履约。
     * 命令中的 decision/items 仅作信封，判定以京东实时库存为权威。
     */
    private StockDecisionResult applyJdRealTime(
            FulfillmentContext fulfillment, StockDecisionCommand command, CommandContext context) {
        requireReadyToExport(fulfillment);

        List<ExpectedItem> expected = expectedItems(fulfillment);
        Map<Long, JdGoodsRef> goodsRefs = resolveJdGoods(fulfillment, expected, command, context);

        JdResult query = jdStock.queryStockSnapshot(snapshotRequest(goodsRefs));
        if (query == null || !query.success()) {
            rejectJdQueryFailure(fulfillment, command, context, query, "query_failed");
        }
        Map<String, JdWarehouseStock> stockByGoodsNo = parseSnapshot(query.data());
        if (stockByGoodsNo == null) {
            rejectJdQueryFailure(fulfillment, command, context, query, "malformed_payload");
        }

        List<Shortage> shortages = new ArrayList<>();
        Map<Long, BigDecimal> availablePiecesBySku = new LinkedHashMap<>();
        for (ExpectedItem item : expected) {
            JdGoodsRef ref = goodsRefs.get(item.skuId());
            JdWarehouseStock stock = stockByGoodsNo.getOrDefault(ref.goodsNo(), JdWarehouseStock.empty());
            BigDecimal requiredPieces =
                    JdStockUnitConverter.requiredPieces(item.requiredQuantity(), ref.piecesPerUnit());
            BigDecimal availablePieces = stock.availablePieces();
            availablePiecesBySku.put(item.skuId(), availablePieces);
            insertJdSnapshot(fulfillment, item, ref.goodsNo(), stock, command, query);
            BigDecimal shortage = requiredPieces.subtract(availablePieces).max(BigDecimal.ZERO);
            if (shortage.signum() > 0) {
                shortages.add(new Shortage(item, shortage.setScale(3), JdStockUnitConverter.PIECES_UNIT));
            }
        }

        StockDecisionCommand.Decision decision =
                shortages.isEmpty() ? StockDecisionCommand.Decision.AVAILABLE : StockDecisionCommand.Decision.OUT_OF_STOCK;
        Map<String, Object> checkedPayload = new LinkedHashMap<>();
        checkedPayload.put("decision", decision.name());
        checkedPayload.put("shortage_item_count", shortages.size());
        checkedPayload.put("source", "jd_realtime");
        checkedPayload.put("available_pieces_by_sku", availablePiecesBySku);
        return complete(fulfillment, command, context, decision, shortages, checkedPayload);
    }

    /** 两分支共用的收尾编排：事件、采购工单与告警、版本、结果与审计。 */
    private StockDecisionResult complete(
            FulfillmentContext fulfillment,
            StockDecisionCommand command,
            CommandContext context,
            StockDecisionCommand.Decision decision,
            List<Shortage> shortages,
            Map<String, Object> checkedPayload) {
        events.append(
                fulfillment.orderId(),
                "JD_STOCK_CHECKED",
                fulfillment.orderLineId(),
                fulfillment.id(),
                null,
                null,
                DataScope.BUSINESS,
                checkedPayload,
                context.operator());
        eventRepository.flush();

        Long ticketId = null;
        String nextStage = READY_TO_EXPORT;
        if (!shortages.isEmpty()) {
            ticketId = createTicket(fulfillment, shortages, context);
            nextStage = "PROCUREMENT_IN_PROGRESS";
            jdbc.update(
                    "UPDATE app.order_lines SET processing_stage='PROCUREMENT_IN_PROGRESS', updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    fulfillment.orderLineId());
            jdbc.update(
                    "UPDATE app.orders SET order_status='PROCUREMENT_PENDING', lock_version=lock_version+1, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    fulfillment.orderId());
            jdbc.update(
                    """
                    INSERT INTO app.operational_alerts
                        (alert_no, alert_type, severity, order_id, order_line_id, fulfillment_id, message, detail)
                    VALUES (?, 'PROCUREMENT_REQUIRED', 'YELLOW', ?, ?, ?, ?, ?::jsonb)
                    """,
                    "ALERT-" + token(), fulfillment.orderId(), fulfillment.orderLineId(), fulfillment.id(),
                    "库存不足，已创建采购工单",
                    json(Map.of("procurement_ticket_id", String.valueOf(ticketId), "shortage_item_count", shortages.size())));
            events.append(
                    fulfillment.orderId(),
                    "PROCUREMENT_REQUESTED",
                    fulfillment.orderLineId(),
                    fulfillment.id(),
                    null,
                    ticketId,
                    DataScope.BUSINESS,
                    Map.of("ticket_id", String.valueOf(ticketId), "shortage_item_count", shortages.size()),
                    context.operator());
            eventRepository.flush();
        }

        Map<String, Object> snapshot = new LinkedHashMap<>(objectMapper.convertValue(
                orderQuery.getDetail(fulfillment.orderId()), new TypeReference<Map<String, Object>>() {}));
        snapshot.put("fulfillment", fulfillmentRead.fulfillment(fulfillment.id()));
        versions.append(fulfillment.orderId(), null, "库存决策: " + decision, context.operator(), snapshot);

        StockDecisionResult result = new StockDecisionResult(
                String.valueOf(fulfillment.id()), decision, nextStage,
                ticketId == null ? null : String.valueOf(ticketId));
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(fulfillment.orderId())
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.SYSTEM)
                .service("FulfillmentStockDecisionService")
                .operation(SCOPE)
                .requestPayload(command)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode(decision.name()));
        return result;
    }

    /** 系统 SKU → 京东 goodsNo 与单位换算系数；查不到映射或系数非法时拒绝判定。 */
    private Map<Long, JdGoodsRef> resolveJdGoods(
            FulfillmentContext fulfillment,
            List<ExpectedItem> expected,
            StockDecisionCommand command,
            CommandContext context) {
        Map<Long, JdGoodsRef> result = new LinkedHashMap<>();
        for (ExpectedItem item : expected) {
            List<Map<String, Object>> rows = jdbc.query(
                    """
                    SELECT provider_sku_code, external_codes::text AS external_codes
                    FROM app.provider_skus
                    WHERE fulfillment_provider_id=? AND sku_id=? AND active=true
                    """,
                    (rs, rowNum) -> Map.of(
                            "provider_sku_code", rs.getString("provider_sku_code"),
                            "external_codes", rs.getString("external_codes")),
                    fulfillment.providerId(), item.skuId());
            if (rows.isEmpty()) {
                recordRejectionAudit(
                        "JD_STOCK_SKU_MAPPING_MISSING", command, "SKU 未配置京东商品编码，无法判定库存", context, 422);
                throw BusinessException.unprocessable(
                        "JD_STOCK_SKU_MAPPING_MISSING", "SKU " + item.skuId() + " 未配置京东商品编码，无法判定库存");
            }
            Map<String, Object> row = rows.getFirst();
            String goodsNo = (String) row.get("provider_sku_code");
            BigDecimal factor = JdStockUnitConverter.factorOrNull(parseJsonMap((String) row.get("external_codes")));
            if (factor == null) {
                recordRejectionAudit(
                        "JD_STOCK_UNIT_CONFIG_INVALID", command, "SKU 单位换算系数非法，无法判定库存", context, 422);
                throw BusinessException.unprocessable(
                        "JD_STOCK_UNIT_CONFIG_INVALID", "SKU " + item.skuId() + " 的单位换算系数非法");
            }
            result.put(item.skuId(), new JdGoodsRef(goodsNo, factor));
        }
        return result;
    }

    private Map<String, Object> snapshotRequest(Map<Long, JdGoodsRef> goodsRefs) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("goodsNoList", goodsRefs.values().stream().map(JdGoodsRef::goodsNo).toList());
        // 只把正品可用库存（goodsLevel=1 正品、stockType=1 可用）计入可履约，避免残次/锁定库存造成超卖误判
        request.put("goodsLevelList", List.of("1"));
        request.put("stockTypeList", List.of(1));
        return request;
    }

    /**
     * 解析库存快照载荷为「goodsNo → 各仓可用/占用」。两种客户端框架兼容：
     * Mock 客户端把业务载荷包在 data.response 键下，REAL 客户端直接放在 data。
     * 形状无法解析时返回 null（视为查询失败）；合法载荷但某 goodsNo 无行时视为可用量 0。
     */
    private Map<String, JdWarehouseStock> parseSnapshot(Object data) {
        Object payload = data;
        if (payload instanceof Map<?, ?> envelope && envelope.containsKey("response")) {
            payload = envelope.get("response");
        }
        if (!(payload instanceof Map<?, ?> body)) {
            return null;
        }
        Object rows = body.get("warehouseStockSnapshotList");
        if (!(rows instanceof List<?> list)) {
            return null;
        }
        Map<String, JdWarehouseStock> result = new LinkedHashMap<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> row)) {
                return null;
            }
            String goodsNo = text(row.get("goodsNo"));
            String warehouseNo = text(row.get("warehouseNo"));
            BigDecimal available = number(row.get("availableQuantity"));
            if (goodsNo == null || warehouseNo == null || available == null || available.signum() < 0) {
                return null;
            }
            BigDecimal occupied = number(row.get("occupiedQuantity"));
            if (occupied == null) {
                occupied = BigDecimal.ZERO;
            }
            result.computeIfAbsent(goodsNo, ignored -> new JdWarehouseStock())
                    .add(warehouseNo, available, occupied);
        }
        return result;
    }

    /** 京东快照落库：每个（SKU，仓）一行；goodsNo 无任何库存行时视为 0 不落行，证据留在事件载荷。 */
    private void insertJdSnapshot(
            FulfillmentContext fulfillment,
            ExpectedItem item,
            String goodsNo,
            JdWarehouseStock stock,
            StockDecisionCommand command,
            JdResult query) {
        for (String warehouseNo : stock.warehouses()) {
            BigDecimal usable = stock.availableAt(warehouseNo);
            BigDecimal onHand = usable.add(stock.occupiedAt(warehouseNo));
            jdbc.update(
                    """
                    INSERT INTO app.provider_stock_snapshots
                        (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                         quantity_unit, source_type, synced_at, source_ref, raw_payload)
                    VALUES (?, ?, ?, ?, ?, 'JD_PIECE', 'JD_ISC_QUERY_STOCK', ?, ?, ?::jsonb)
                    """,
                    fulfillment.providerId(),
                    item.skuId(),
                    warehouseNo,
                    onHand,
                    usable,
                    OffsetDateTime.ofInstant(command.observedAt(), ZoneOffset.UTC),
                    blankToNull(query.requestId()),
                    json(Map.of(
                            "source", "jd_realtime",
                            "goods_no", goodsNo,
                            "warehouse_no", warehouseNo,
                            "available_pieces", usable,
                            "occupied_pieces", stock.occupiedAt(warehouseNo))));
        }
    }

    /** 京东查询失败/载荷无法解析：拒绝履约，告警与审计独立提交不受业务事务回滚影响。 */
    private void rejectJdQueryFailure(
            FulfillmentContext fulfillment,
            StockDecisionCommand command,
            CommandContext context,
            JdResult query,
            String reason) {
        String businessCode = query == null ? null : text(query.businessCode());
        final String effectiveBusinessCode = businessCode == null ? "UNKNOWN" : businessCode;
        String requestId = query == null ? null : text(query.requestId());
        try {
            requiresNew.executeWithoutResult(status -> {
                jdbc.update(
                        """
                        INSERT INTO app.operational_alerts
                            (alert_no, alert_type, severity, order_id, order_line_id, fulfillment_id, message, detail)
                        VALUES (?, 'JD_STOCK_QUERY_FAILED', 'YELLOW', ?, ?, ?, ?, ?::jsonb)
                        """,
                        "ALERT-" + token(), fulfillment.orderId(), fulfillment.orderLineId(), fulfillment.id(),
                        "京东实时库存查询失败，已拒绝履约",
                        json(Map.of(
                                "business_code", effectiveBusinessCode,
                                "request_id", requestId == null ? "" : requestId,
                                "reason", reason)));
                audits.record(new AuditLogService.AuditCommand()
                        .dataScope(DataScope.BUSINESS)
                        .orderId(fulfillment.orderId())
                        .requestId(context.requestId())
                        .traceId(context.traceId())
                        .operator(context.operator())
                        .actorType(AuditActorType.SYSTEM)
                        .service("FulfillmentStockDecisionService")
                        .operation(SCOPE)
                        .requestPayload(command)
                        .responsePayload(Map.of("business_code", effectiveBusinessCode, "reason", reason))
                        .httpStatus(502)
                        .businessCode("JD_STOCK_QUERY_FAILED"));
            });
        } catch (RuntimeException ignored) {
            // 降级留痕失败不掩盖原始业务异常
        }
        throw new BusinessException(
                502, "JD_STOCK_QUERY_FAILED", "京东实时库存查询失败，已拒绝履约：" + effectiveBusinessCode);
    }

    /** 主数据/入参被拒的审计证据：REQUIRES_NEW 独立提交，不受业务事务回滚影响。 */
    private void recordRejectionAudit(
            String businessCode,
            Object request,
            String message,
            CommandContext context,
            int httpStatus) {
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.SYSTEM)
                    .service("FulfillmentStockDecisionService")
                    .operation(SCOPE)
                    .requestPayload(request)
                    .responsePayload(Map.of("message", message))
                    .httpStatus(httpStatus)
                    .businessCode(businessCode)));
        } catch (RuntimeException ignored) {
            // 拒绝审计失败不掩盖原始业务异常
        }
    }

    private void requireReadyToExport(FulfillmentContext fulfillment) {
        if (!READY_TO_EXPORT.equals(fulfillment.processingStage())) {
            throw BusinessException.conflict(
                    "STOCK_DECISION_ALREADY_APPLIED", "履约行已离开待库存判断阶段");
        }
    }

    private FulfillmentContext lockFulfillment(long fulfillmentId) {
        FulfillmentContext value = jdbc.query(
                """
                SELECT f.id, f.fulfillment_provider_id, f.requested_quantity,
                       ol.id order_line_id, ol.line_type, ol.sku_id, ol.processing_stage,
                       o.id order_id, o.receiver_address, fp.inventory_managed_by_us, fp.provider_type
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                WHERE f.id=? FOR UPDATE OF f, ol, o
                """,
                rs -> rs.next() ? new FulfillmentContext(
                        rs.getLong("id"), rs.getLong("order_id"), rs.getLong("order_line_id"),
                        rs.getLong("fulfillment_provider_id"), rs.getBigDecimal("requested_quantity"),
                        rs.getString("line_type"), rs.getObject("sku_id", Long.class),
                        rs.getString("processing_stage"), rs.getString("receiver_address"),
                        rs.getBoolean("inventory_managed_by_us"), rs.getString("provider_type")) : null,
                fulfillmentId);
        if (value == null) throw BusinessException.notFound("BUSINESS 履约任务不存在");
        return value;
    }

    private List<ExpectedItem> expectedItems(FulfillmentContext fulfillment) {
        if ("SINGLE".equals(fulfillment.lineType())) {
            return List.of(new ExpectedItem(
                    fulfillment.skuId(), null, fulfillment.requestedQuantity(),
                    jdbc.queryForObject("SELECT unit_snapshot FROM app.order_lines WHERE id=?", String.class,
                            fulfillment.orderLineId())));
        }
        return jdbc.query(
                """
                SELECT id, sku_id, total_quantity, unit_snapshot
                FROM app.order_line_components WHERE order_line_id=? ORDER BY component_no
                """,
                (rs, row) -> new ExpectedItem(
                        rs.getLong("sku_id"), rs.getLong("id"), rs.getBigDecimal("total_quantity"),
                        rs.getString("unit_snapshot")),
                fulfillment.orderLineId());
    }

    private Map<Long, StockDecisionCommand.Item> normalizedItems(List<StockDecisionCommand.Item> items) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.unprocessable("STOCK_DECISION_ITEMS_REQUIRED", "库存决策至少包含一个 SKU");
        }
        Map<Long, StockDecisionCommand.Item> result = new HashMap<>();
        for (StockDecisionCommand.Item item : items) {
            if (item == null) throw BusinessException.unprocessable("STOCK_DECISION_ITEM_INVALID", "库存决策明细不能为空");
            long skuId = WriteCommands.parseIdentifier(item.skuId());
            if (result.put(skuId, item) != null) {
                throw BusinessException.unprocessable("STOCK_DECISION_ITEM_DUPLICATE", "库存决策不能重复 SKU");
            }
        }
        return result;
    }

    private Long createTicket(
            FulfillmentContext fulfillment, List<Shortage> shortages, CommandContext context) {
        Long ticketId = jdbc.queryForObject(
                """
                INSERT INTO app.procurement_tickets
                    (ticket_no, fulfillment_id, priority, delivery_address, remark, created_by)
                VALUES (?, ?, 'NORMAL', ?, '库存决策自动创建', ?) RETURNING id
                """,
                Long.class, "PROC-" + token(), fulfillment.id(), fulfillment.receiverAddress(), context.operator());
        for (Shortage shortage : shortages) {
            jdbc.update(
                    """
                    INSERT INTO app.procurement_ticket_items
                        (procurement_ticket_id, sku_id, order_line_component_id, requested_quantity, unit_snapshot)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    ticketId, shortage.item().skuId(), shortage.item().componentId(),
                    shortage.quantity(), shortage.unit());
        }
        return ticketId;
    }

    private static void validateEnvelope(StockDecisionCommand command) {
        if (command == null || command.decision() == null || command.observedAt() == null) {
            throw BusinessException.unprocessable(
                    "STOCK_DECISION_INVALID", "decision 与 observed_at 不能为空");
        }
    }

    private static BigDecimal quantity(String value, String field) {
        try {
            BigDecimal quantity = new BigDecimal(value);
            if (quantity.signum() < 0 || quantity.scale() > 3) throw new NumberFormatException();
            return quantity.setScale(3);
        } catch (RuntimeException ex) {
            throw BusinessException.unprocessable("STOCK_QUANTITY_INVALID", field + " 必须是非负三位小数");
        }
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private Map<String, Object> parseJsonMap(String json) {
        try {
            return json == null || json.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("库存决策 JSON 序列化失败", ex);
        }
    }

    private static String required(String value, String field) {
        String result = blankToNull(value);
        if (result == null) throw BusinessException.unprocessable("STOCK_DECISION_INVALID", field + " 不能为空");
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private record FulfillmentContext(
            long id,
            long orderId,
            long orderLineId,
            long providerId,
            BigDecimal requestedQuantity,
            String lineType,
            Long skuId,
            String processingStage,
            String receiverAddress,
            boolean inventoryManagedByUs,
            String providerType) {}

    private record ExpectedItem(long skuId, Long componentId, BigDecimal requiredQuantity, String unit) {}

    private record Shortage(ExpectedItem item, BigDecimal quantity, String unit) {}

    private record JdGoodsRef(String goodsNo, BigDecimal piecesPerUnit) {}

    /** 单个京东商品（goodsNo）按仓聚合的可用/占用件数。 */
    private static final class JdWarehouseStock {

        private final Map<String, BigDecimal> availableByWarehouse = new LinkedHashMap<>();
        private final Map<String, BigDecimal> occupiedByWarehouse = new LinkedHashMap<>();

        static JdWarehouseStock empty() {
            return new JdWarehouseStock();
        }

        void add(String warehouseNo, BigDecimal available, BigDecimal occupied) {
            availableByWarehouse.merge(warehouseNo, available, BigDecimal::add);
            occupiedByWarehouse.merge(warehouseNo, occupied, BigDecimal::add);
        }

        Set<String> warehouses() {
            return availableByWarehouse.keySet();
        }

        BigDecimal availableAt(String warehouseNo) {
            return availableByWarehouse.getOrDefault(warehouseNo, BigDecimal.ZERO);
        }

        BigDecimal occupiedAt(String warehouseNo) {
            return occupiedByWarehouse.getOrDefault(warehouseNo, BigDecimal.ZERO);
        }

        BigDecimal availablePieces() {
            return availableByWarehouse.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }
}
