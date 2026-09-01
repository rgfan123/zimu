package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.sku.ShipmentJdSkuMappingGateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 使用 Ticket 02 的 immutable plan 作为唯一数量来源，执行 Shipment 级京东实时库存判定。
 * 库存查询是 advisory fact：不预占、不改变履约方、不创建采购单，提交前仍必须重新查询。
 */
@Service
public class ShipmentJdStockCheckService {

    private static final String SCOPE = "shipment.jd_stock.check";
    private static final String BLOCK_REASON = "JD_STOCK_BLOCKED";
    private static final String EVENT_TYPE = "JD_STOCK_CHECKED";
    private static final String SOURCE_TYPE = "JD_ISC_QUERY_STOCK";
    private static final String QUANTITY_UNIT = "JD_PIECE";

    private final ShipmentJdOutboundPreparer preparer;
    private final ShipmentJdSkuMappingGateService skuGate;
    private final JDWarehouseService jdWarehouse;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final ObjectMapper objectMapper;

    public ShipmentJdStockCheckService(
            ShipmentJdOutboundPreparer preparer,
            ShipmentJdSkuMappingGateService skuGate,
            JDWarehouseService jdWarehouse,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            ObjectMapper objectMapper) {
        this.preparer = preparer;
        this.skuGate = skuGate;
        this.jdWarehouse = jdWarehouse;
        this.idempotency = idempotency;
        this.jdbc = jdbc;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
        this.objectMapper = objectMapper;
    }

    public IdempotentResult<Map<String, Object>> check(
            long shipmentId, String idempotencyKey, CommandContext context) {
        return idempotency.executeWithPreparedReadOnlyExternalWork(
                SCOPE,
                idempotencyKey,
                200,
                () -> preparer.plan(shipmentId),
                plan -> Map.of(
                        "shipment_id", shipmentId,
                        "shipment_version", plan.shipmentVersion(),
                        "preview_hash", plan.requestHash()),
                plan -> probe(plan, idempotencyKey, context),
                (plan, probe) -> persist(plan, probe, context));
    }

    private Probe probe(
            JdShipmentSubmissionPlan plan,
            String stockIdempotencyKey,
            CommandContext context) {
        if (!plan.submittable()) {
            throw BusinessException.conflict(
                    "JD_STOCK_PREVIEW_BLOCKED",
                    "当前京东出库预览仍有阻断项，请先修复预览后再查询库存");
        }

        IdempotentResult<Map<String, Object>> gateResult = skuGate.check(
                plan.shipmentId(),
                "jd-stock-gate-" + UUID.randomUUID().toString(),
                context);
        Map<String, Object> gate = gateResult.replayed()
                ? objectMapper.convertValue(gateResult.replayedBody(), new TypeReference<>() {})
                : gateResult.result();
        if (!"PASSED".equals(text(gate.get("gate_status")))
                || integer(gate.get("blocking_issue_count")) != 0) {
            return Probe.blocked(
                    "SKU_MAPPING_BLOCKED",
                    mappingGateBlockers(gate),
                    null,
                    List.of(),
                    requiredText(gate.get("local_gate_fingerprint"), "local_gate_fingerprint"));
        }
        String localGateFingerprint = requiredText(
                gate.get("local_gate_fingerprint"), "local_gate_fingerprint");

        List<Demand> demands = aggregateDemands(plan.stockDemands());
        Map<String, Object> request = stockRequest(plan, demands);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD stock query must run outside a database transaction");
        }
        JdResult result;
        try {
            result = jdWarehouse.queryStock(request);
        } catch (RuntimeException exception) {
            result = new JdResult(false, "CLIENT_EXCEPTION", "京东库存查询调用失败", null, null);
        }
        return evaluate(result, plan, demands, localGateFingerprint);
    }

    /**
     * 映射门禁阻断时，把 ShipmentJdSkuMappingGateService 已经算好的逐项 issue（含
     * goods_no/product_name/sku_code/missing_field）原样透传成 stock-check 的 blocker，
     * 而不是像此前那样把这些信息全部丢掉、只留一条没有任何商品身份的通用文案——
     * review_cases#42 手动复算实测：运营看到的 blocker 只有「京东 SKU 映射门禁未通过」，
     * 连是哪个商品、缺哪个字段都不知道，案子挂着却没法自救（2026-08-27）。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mappingGateBlockers(Map<String, Object> gate) {
        List<Map<String, Object>> blockers = new ArrayList<>();
        Object rawItems = gate.get("affected_shipment_items");
        if (rawItems instanceof List<?> items) {
            for (Object itemObj : items) {
                if (!(itemObj instanceof Map<?, ?> itemMap)) continue;
                Map<String, Object> item = (Map<String, Object>) itemMap;
                Object rawIssues = item.get("issues");
                if (!(rawIssues instanceof List<?> issues)) continue;
                for (Object issueObj : issues) {
                    if (issueObj instanceof Map<?, ?> issueMap) {
                        blockers.add(mappingGateBlocker(item, (Map<String, Object>) issueMap));
                    }
                }
            }
        }
        if (blockers.isEmpty()) {
            // 防御性兜底：门禁判了 BLOCKED 却拿不到逐项明细时绝不能返回空 blockers——
            // persist() 用 blockers.isEmpty() 判定是否 PASSED，空列表会被误判为通过。
            blockers.add(blocker(
                    "JD_SKU_MAPPING_GATE_BLOCKED",
                    "京东 SKU 映射门禁未通过，请先维护映射并重新核对"));
        }
        return blockers;
    }

    /** code 固定为 JD_SKU_MAPPING_GATE_BLOCKED（前端/既有消费方按 code 分组的口径不变）；
     * message 换成该 issue 的具体文案，其余字段是「拿得到就带」的商品身份与换货所需定位信息。 */
    private Map<String, Object> mappingGateBlocker(Map<String, Object> item, Map<String, Object> issue) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", "JD_SKU_MAPPING_GATE_BLOCKED");
        String message = text(issue.get("message"));
        value.put("message", message == null
                ? "京东 SKU 映射门禁未通过，请先维护映射并重新核对"
                : message);
        putIfPresent(value, "product_name", item.get("product_name"));
        putIfPresent(value, "goods_no", item.get("goods_no"));
        putIfPresent(value, "sku_code", item.get("sku_code"));
        putIfPresent(value, "sku_id", item.get("sku_id"));
        Object orderLineId = item.get("order_line_id");
        if (orderLineId != null) value.put("order_line_ids", List.of(String.valueOf(orderLineId)));
        putIfPresent(value, "missing_field", issue.get("missing_field"));
        putIfPresent(value, "mapping_issue_code", issue.get("code"));
        return value;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) target.put(key, value);
    }

    private Map<String, Object> persist(
            JdShipmentSubmissionPlan prepared,
            Probe probe,
            CommandContext context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD stock result persistence requires a database transaction");
        }
        JdShipmentSubmissionPlan current = preparer.plan(prepared.shipmentId());
        if (current.shipmentVersion() != prepared.shipmentVersion()
                || !Objects.equals(current.requestHash(), prepared.requestHash())
                || !current.submittable()) {
            throw BusinessException.conflict(
                    "JD_STOCK_PREVIEW_CHANGED_DURING_CHECK",
                    "发货批次或预览在京东库存查询期间已变更，本次结果未写入，请重试");
        }
        skuGate.requireLocalFingerprintCurrent(
                prepared.shipmentId(), probe.localGateFingerprint());

        for (StockObservation row : probe.observations()) {
            if (!row.observed()) continue;
            jdbc.update(
                    """
                    INSERT INTO app.provider_stock_snapshots
                        (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                         quantity_unit, source_type, synced_at, source_ref, raw_payload)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """,
                    current.providerId(),
                    row.skuId(),
                    row.warehouseCode(),
                    row.stock(),
                    row.usable(),
                    QUANTITY_UNIT,
                    SOURCE_TYPE,
                    OffsetDateTime.ofInstant(probe.observedAt(), ZoneOffset.UTC),
                    blankToNull(probe.requestId()),
                    json(Map.of(
                            "shipment_id", String.valueOf(current.shipmentId()),
                            "goods_no", row.goodsNo(),
                            "warehouse_no", row.warehouseCode(),
                            "required_pieces", row.requiredPieces(),
                            "observation_status", row.observationStatus(),
                            "not_reserved", true)));
        }

        boolean passed = probe.blockers().isEmpty();
        Map<Long, SkuLabel> skuLabels = loadSkuLabels(
                probe.observations().stream().map(StockObservation::skuId).distinct().toList());
        Long reviewCaseId = reconcileCase(current, probe, skuLabels, passed, context.operator());
        Map<String, Object> response = response(current, probe, skuLabels, passed, reviewCaseId);
        Map<String, Object> eventPayload = new LinkedHashMap<>();
        eventPayload.put("shipment_id", String.valueOf(current.shipmentId()));
        eventPayload.put("preview_hash", current.requestHash());
        eventPayload.put("stock_status", passed ? "PASSED" : "BLOCKED");
        eventPayload.put("observation_status", overallObservation(probe.observations()));
        eventPayload.put("blocker_codes", probe.blockers().stream().map(item -> item.get("code")).toList());
        eventPayload.put("not_reserved", true);
        events.append(
                current.orderId(), EVENT_TYPE, null, null, current.shipmentId(), null,
                DataScope.BUSINESS, eventPayload, context.operator());
        versions.append(
                current.orderId(), null, "京东实时库存判定", context.operator(), orderSnapshot(current.orderId()));
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(current.orderId())
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("fulfillment")
                .operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(current.shipmentId()),
                        "preview_hash", current.requestHash()))
                .responsePayload(Map.of(
                        "stock_status", passed ? "PASSED" : "BLOCKED",
                        "observation_status", overallObservation(probe.observations()),
                        "blocker_codes", probe.blockers().stream().map(item -> item.get("code")).toList(),
                        "not_reserved", true))
                .httpStatus(200)
                .businessCode(passed ? "JD_STOCK_CHECK_PASSED" : "JD_STOCK_CHECK_BLOCKED"));
        return response;
    }

    private Probe evaluate(
            JdResult result,
            JdShipmentSubmissionPlan plan,
            List<Demand> demands,
            String localGateFingerprint) {
        Instant observedAt = Instant.now();
        if (result == null || !result.success()) {
            String code = result == null ? "NO_RESPONSE" : safeCode(result.businessCode());
            return Probe.blocked(
                    "QUERY_FAILED",
                    List.of(blocker("JD_STOCK_QUERY_FAILED", "京东库存查询失败（" + code + "），默认阻断")),
                    result == null ? null : result.requestId(),
                    List.of(),
                    observedAt,
                    localGateFingerprint);
        }
        List<Map<String, Object>> rows = resultRows(result.data());
        if (rows == null) {
            return Probe.blocked(
                    "MALFORMED_RESPONSE",
                    List.of(blocker("JD_STOCK_RESPONSE_INVALID", "京东库存响应无法安全解析，默认阻断")),
                    result.requestId(),
                    List.of(),
                    observedAt,
                    localGateFingerprint);
        }

        String warehouse = text(plan.request().get("warehouseNo"));
        List<Map<String, Object>> blockers = new ArrayList<>();
        List<Long> demandSkuIds = demands.stream().map(Demand::skuId).distinct().toList();
        Map<Long, SkuLabel> skuLabels = loadSkuLabels(demandSkuIds);
        // 库存类阻断此前只在 JD_STOCK_INSUFFICIENT 一处带商品身份，另外三种（目标仓缺行/
        // 响应歧义/响应无效）跟 JD_SKU_MAPPING_GATE_BLOCKED 一样只有通用文案。「阻断明细
        // 全量透传」要求所有生成点一律带 item 级上下文，这里统一取一次订单行归属，
        // 四处 blocker 共用（换货动作按 order_line_id 定位，同一 SKU 可能被多条行引用）。
        Map<Long, List<Long>> orderLineIds = orderLineIdsBySku(plan.shipmentId(), demandSkuIds);
        List<StockObservation> observations = new ArrayList<>();
        for (Demand demand : demands) {
            SkuLabel label = skuLabels.get(demand.skuId());
            List<Long> lineIds = orderLineIds.getOrDefault(demand.skuId(), List.of());
            List<Map<String, Object>> matches = rows.stream()
                    .filter(row -> demand.goodsNo().equals(text(row.get("goodsNo"))))
                    .filter(row -> warehouse.equals(text(row.get("warehouseNo"))))
                    .toList();
            if (matches.isEmpty()) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(stockBlocker(
                        "JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED",
                        "京东响应缺少目标仓商品行，不能把缺行解释为 0 库存",
                        demand, label, lineIds));
                continue;
            }
            if (matches.size() != 1) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(stockBlocker(
                        "JD_STOCK_RESPONSE_AMBIGUOUS",
                        "京东响应包含重复的目标仓商品行，默认阻断",
                        demand, label, lineIds));
                continue;
            }
            StockObservation observation = parseObservation(matches.getFirst(), demand, warehouse);
            if (observation == null) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(stockBlocker(
                        "JD_STOCK_RESPONSE_INVALID",
                        "京东目标仓库存行缺字段、含负数或数量关系无效，默认阻断",
                        demand, label, lineIds));
                continue;
            }
            observations.add(observation);
            if (observation.usable() < demand.requiredPieces()) {
                // 不足的是哪个商品必须写进文案：运营看到「需要 2 件可用 0 件」却不知道
                // 缺的是什么，补货无从下手（2026-08-26 用户实测反馈）。循环里本来就攥着
                // demand.goodsNo()，此前只是没写进去。
                String productName = label == null || label.productName() == null ? "" : label.productName();
                blockers.add(stockBlocker(
                        "JD_STOCK_INSUFFICIENT",
                        (productName.isEmpty() ? "" : "「" + productName + "」")
                                + "（京东商品编码 " + demand.goodsNo() + "）目标仓可用库存不足："
                                + "需要 " + demand.requiredPieces()
                                + " 件，可用 " + observation.usable() + " 件",
                        demand, label, lineIds));
            }
        }
        return new Probe(
                blockers.isEmpty() ? "PASSED" : "BLOCKED",
                List.copyOf(blockers),
                result.requestId(),
                List.copyOf(observations),
                observedAt,
                localGateFingerprint);
    }

    private StockObservation parseObservation(Map<String, Object> row, Demand demand, String warehouse) {
        if (!"100".equals(text(row.get("goodsLevel")))
                || !"1".equals(text(row.get("stockStatus")))
                || !"1".equals(text(row.get("stockType")))) {
            return null;
        }
        Integer stock = countValue(row.get("stockNum"));
        Integer usable = countValue(row.get("usableNum"));
        if (stock == null || usable == null || usable > stock) {
            return null;
        }
        return new StockObservation(
                demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces(),
                stock, usable, true,
                stock == 0 && usable == 0 ? "OBSERVED_ZERO" : "OBSERVED");
    }

    private List<Demand> aggregateDemands(List<JdShipmentSubmissionPlan.StockDemand> source) {
        Map<String, Demand> values = new LinkedHashMap<>();
        for (JdShipmentSubmissionPlan.StockDemand item : source) {
            String key = item.skuId() + ":" + item.goodsNo();
            Demand current = values.get(key);
            int quantity = current == null
                    ? item.requiredPieces()
                    : Math.addExact(current.requiredPieces(), item.requiredPieces());
            values.put(key, new Demand(item.skuId(), item.goodsNo(), quantity));
        }
        if (values.isEmpty()) {
            throw BusinessException.unprocessable(
                    "JD_STOCK_DEMAND_EMPTY", "京东出库预览没有可查询库存的商品需求");
        }
        return values.values().stream()
                .sorted(Comparator.comparing(Demand::goodsNo).thenComparingLong(Demand::skuId))
                .toList();
    }

    private Map<String, Object> stockRequest(
            JdShipmentSubmissionPlan plan, List<Demand> demands) {
        Map<String, Object> customer = map(plan.request().get("customerInfo"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pin", plan.request().get("pin"));
        request.put("ownerNo", customer.get("ownerNo"));
        request.put("warehouseNo", plan.request().get("warehouseNo"));
        request.put("stockIndexes", "1");
        request.put("goodsNo", String.join(",", demands.stream().map(Demand::goodsNo).distinct().toList()));
        request.put("goodsLevel", "100");
        request.put("stockType", "1");
        request.put("warehouseStock", Map.of("stockStatus", "1", "returnZeroStock", "2"));
        request.put("currentPage", "1");
        request.put("pageSize", String.valueOf(demands.stream().map(Demand::goodsNo).distinct().count()));
        return request;
    }

    private Long reconcileCase(
            JdShipmentSubmissionPlan plan,
            Probe probe,
            Map<Long, SkuLabel> skuLabels,
            boolean passed,
            String operator) {
        List<Long> existing = jdbc.queryForList(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                FOR UPDATE
                """,
                Long.class,
                plan.shipmentId(),
                BLOCK_REASON);
        if (passed) {
            if (existing.isEmpty()) return null;
            jdbc.update(
                    """
                    UPDATE app.review_cases
                    SET status='RESOLVED', resolution=?::jsonb,
                        resolution_version=resolution_version+1,
                        resolved_by=?, resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """,
                    json(Map.of(
                            "resolution_type", "JD_STOCK_RECHECK_PASSED",
                            "preview_hash", plan.requestHash(),
                            "not_reserved", true)),
                    operator,
                    existing.getFirst());
            return null;
        }
        String detail = json(Map.of(
                "shipment_id", String.valueOf(plan.shipmentId()),
                "preview_hash", plan.requestHash(),
                "blockers", probe.blockers(),
                "observations", probe.observations().stream()
                        .map(row -> observationMap(row, skuLabels))
                        .toList(),
                "not_reserved", true,
                "maintenance_action", Map.of(
                        "action", "RERUN_JD_STOCK_CHECK",
                        "api", "/api/v1/shipments/" + plan.shipmentId() + "/jd-stock-check")));
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, shipment_id, detail)
                    VALUES (?, 'JD_STOCK', 'OPEN', 'FULFILLMENT_OPS', ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                    """,
                    "RC-JD-STOCK-" + token(),
                    BLOCK_REASON,
                    plan.orderId(),
                    plan.shipmentId(),
                    detail);
        } else {
            jdbc.update(
                    "UPDATE app.review_cases SET detail=?::jsonb, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    detail,
                    existing.getFirst());
        }
        return jdbc.queryForObject(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                """,
                Long.class,
                plan.shipmentId(),
                BLOCK_REASON);
    }

    private Map<String, Object> response(
            JdShipmentSubmissionPlan plan,
            Probe probe,
            Map<Long, SkuLabel> skuLabels,
            boolean passed,
            Long reviewCaseId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(plan.shipmentId()));
        response.put("shipment_version", plan.shipmentVersion());
        response.put("preview_hash", plan.requestHash());
        response.put("target_warehouse_code", text(plan.request().get("warehouseNo")));
        response.put("stock_status", passed ? "PASSED" : "BLOCKED");
        response.put("observation_status", overallObservation(probe.observations()));
        response.put("observed_at", probe.observedAt());
        response.put("not_reserved", true);
        response.put("blockers", probe.blockers());
        response.put("items", probe.observations().stream()
                .map(row -> observationMap(row, skuLabels))
                .toList());
        if (reviewCaseId != null) {
            response.put("review_case", Map.of(
                    "id", String.valueOf(reviewCaseId),
                    "reason_code", BLOCK_REASON,
                    "status", "OPEN"));
        }
        return response;
    }

    private Map<String, Object> observationMap(StockObservation row, Map<Long, SkuLabel> skuLabels) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sku_id", String.valueOf(row.skuId()));
        SkuLabel label = skuLabels.get(row.skuId());
        if (label != null) {
            value.put("sku_code", label.skuCode());
            value.put("product_name", label.productName());
        }
        value.put("goods_no", row.goodsNo());
        value.put("warehouse_code", row.warehouseCode());
        value.put("required_quantity", row.requiredPieces());
        value.put("quantity_unit", QUANTITY_UNIT);
        value.put("observation_status", row.observationStatus());
        if (row.observed()) {
            value.put("stock_quantity", row.stock());
            value.put("usable_quantity", row.usable());
        }
        return value;
    }

    private Map<Long, SkuLabel> loadSkuLabels(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(skuIds.size(), "?"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT sku.id sku_id, sku.sku_code, p.product_name
                FROM app.skus sku
                JOIN app.products p ON p.id = sku.product_id
                WHERE sku.id IN (%s)
                ORDER BY sku.id
                """.formatted(placeholders),
                skuIds.toArray());
        Map<Long, SkuLabel> labels = new LinkedHashMap<>();
        for (Map<String, Object> found : rows) {
            labels.put(((Number) found.get("sku_id")).longValue(), new SkuLabel(
                    text(found.get("sku_code")),
                    text(found.get("product_name"))));
        }
        return labels;
    }

    private record SkuLabel(String skuCode, String productName) {
    }

    private Map<String, Object> orderSnapshot(long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", jdbc.queryForMap("SELECT * FROM app.orders WHERE id=?", orderId));
        result.put("lines", jdbc.queryForList(
                "SELECT * FROM app.order_lines WHERE order_id=? ORDER BY line_no", orderId));
        result.put("fulfillments", jdbc.queryForList(
                "SELECT f.* FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id "
                        + "WHERE ol.order_id=? ORDER BY f.id",
                orderId));
        result.put("shipments", jdbc.queryForList(
                "SELECT * FROM app.shipments WHERE order_id=? ORDER BY shipment_sequence", orderId));
        result.put("review_cases", jdbc.queryForList(
                "SELECT id, reason_code, status FROM app.review_cases WHERE order_id=? ORDER BY id", orderId));
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> resultRows(Object data) {
        if (!(data instanceof Map<?, ?> map)) return null;
        Object value = map.get("resultList");
        if (!(value instanceof List<?> list)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) return null;
            result.add((Map<String, Object>) row);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String overallObservation(List<StockObservation> observations) {
        if (observations.isEmpty()) return "NOT_OBSERVED";
        if (observations.stream().anyMatch(row -> !row.observed())) return "NOT_OBSERVED";
        if (observations.stream().allMatch(row -> "OBSERVED_ZERO".equals(row.observationStatus()))) {
            return "OBSERVED_ZERO";
        }
        return "OBSERVED";
    }

    private static Map<String, Object> blocker(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /**
     * 库存类阻断（目标仓缺行/响应歧义/响应无效/库存不足，四个生成点共用）：一律携带商品身份
     * （goods_no/sku_code/product_name/sku_id）与「换货」定位所需的 order_line_ids——
     * 此前只有 JD_STOCK_INSUFFICIENT 一条带 goods_no/product_name，另外三条只有通用文案，
     * 运营连是哪个商品都不知道（阻断明细全量透传，2026-08-27）。
     */
    private static Map<String, Object> stockBlocker(
            String code, String message, Demand demand, SkuLabel label, List<Long> orderLineIds) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("code", code);
        value.put("message", message);
        value.put("goods_no", demand.goodsNo());
        value.put("sku_id", String.valueOf(demand.skuId()));
        if (label != null) {
            if (label.skuCode() != null && !label.skuCode().isBlank()) value.put("sku_code", label.skuCode());
            if (label.productName() != null && !label.productName().isBlank()) {
                value.put("product_name", label.productName());
            }
        }
        if (!orderLineIds.isEmpty()) {
            value.put("order_line_ids", orderLineIds.stream().map(String::valueOf).toList());
        }
        return value;
    }

    /**
     * demand.skuId → 该 shipment 内引用此 SKU 的 order_line_id 列表（SINGLE 行本身，或礼包
     * 组件所在行）。库存判定按 SKU 聚合需求（aggregateDemands），但「换货」是按订单行操作的，
     * 同一 SKU 也可能被多条订单行引用，所以这里把候选行清单一并带出，供前端定位。
     */
    private Map<Long, List<Long>> orderLineIdsBySku(long shipmentId, List<Long> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(skuIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(shipmentId);
        params.addAll(skuIds);
        params.add(shipmentId);
        params.addAll(skuIds);
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT ol.sku_id sku_id, ol.id order_line_id
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE si.shipment_id=? AND ol.sku_id IN (%1$s)
                UNION
                SELECT c.sku_id sku_id, c.order_line_id order_line_id
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                JOIN app.order_line_components c ON c.order_line_id=f.order_line_id
                WHERE si.shipment_id=? AND c.sku_id IN (%1$s)
                """.formatted(placeholders),
                params.toArray());
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long skuId = ((Number) row.get("sku_id")).longValue();
            long orderLineId = ((Number) row.get("order_line_id")).longValue();
            result.computeIfAbsent(skuId, ignored -> new ArrayList<>()).add(orderLineId);
        }
        return result;
    }

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? -1 : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static Integer countValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromNonNegativeFileValue(value.toString());
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
            return null;
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String safeCode(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String requiredText(Object value, String field) {
        String text = text(value);
        if (text == null) {
            throw new IllegalStateException("missing " + field + " from JD SKU gate result");
        }
        return text;
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Demand(long skuId, String goodsNo, int requiredPieces) {
    }

    private record StockObservation(
            long skuId,
            String goodsNo,
            String warehouseCode,
            int requiredPieces,
            Integer stock,
            Integer usable,
            boolean observed,
            String observationStatus) {

        static StockObservation notObserved(
                long skuId, String goodsNo, String warehouseCode, int requiredPieces) {
            return new StockObservation(
                    skuId, goodsNo, warehouseCode, requiredPieces,
                    null, null, false, "NOT_OBSERVED");
        }
    }

    private record Probe(
            String status,
            List<Map<String, Object>> blockers,
            String requestId,
            List<StockObservation> observations,
            Instant observedAt,
            String localGateFingerprint) {

        static Probe blocked(
                String status,
                List<Map<String, Object>> blockers,
                String requestId,
                List<StockObservation> observations,
                String localGateFingerprint) {
            return blocked(status, blockers, requestId, observations, Instant.now(), localGateFingerprint);
        }

        static Probe blocked(
                String status,
                List<Map<String, Object>> blockers,
                String requestId,
                List<StockObservation> observations,
                Instant observedAt,
                String localGateFingerprint) {
            return new Probe(
                    status, List.copyOf(blockers), requestId, List.copyOf(observations),
                    observedAt, localGateFingerprint);
        }
    }
}
