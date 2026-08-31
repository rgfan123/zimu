package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.rawmaterial.RawMaterialReadException;
import cn.zimu.fulfillment.rawmaterial.RawMaterialWriteException;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcInboundOrder;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcReadGateway;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcScrapOrder;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcStockRow;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcStockTransaction;
import cn.zimu.fulfillment.rawmaterial.YuanliaokcWriteGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 原料库存（yuanliaokc）出入库 MCP 工具：3 个只读 + 4 个写。
 *
 * <p>全部工具委托 {@link YuanliaokcReadGateway}/{@link YuanliaokcWriteGateway}，本类不触网、
 * 不直写业务表。读写同属 {@value #MODULE} 模块，但写工具显式 {@code readOnly=false}——
 * 外部 MCP 协议面（{@link McpServer}）按只读元数据把写工具投影为「不存在」，即使部署把
 * rawmaterial 开进 {@code app.mcp.protocol-modules}，tools/list 也只见 3 个读工具；
 * 写工具只能经 Agent 面（allow_write 绑定）调用。
 *
 * <p>写纪律与 {@link McpWriteTools} 同款：idempotency_key 必填 + 幂等注册表重放 +
 * AGENT 审计（成功/重放/失败）。审批即入账/出账、不可逆，工具描述里向模型言明。
 * kg 重量是小数（BigDecimal 承载、decimal-string 出 JSON），不适用商品数量整数纪律；
 * piece_count 是整数件数。
 */
@Component
public class McpRawMaterialTools {

    public static final String MODULE = "rawmaterial";

    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_LINES = 50;
    private static final int DEFAULT_TRANSACTION_LIMIT = 50;
    private static final int MAX_TRANSACTION_LIMIT = 200;
    /** 上游枚举值形状（小写下划线）：只作形状闸门，值本身原样传，不抄枚举清单以免漂移。 */
    private static final java.util.regex.Pattern UPSTREAM_ENUM_SHAPE =
            java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final YuanliaokcReadGateway reads;
    private final YuanliaokcWriteGateway writes;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;
    private final List<McpTool> tools;

    public McpRawMaterialTools(
            YuanliaokcReadGateway reads,
            YuanliaokcWriteGateway writes,
            IdempotencyService idempotency,
            AuditLogService audits,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.reads = reads;
        this.writes = writes;
        this.idempotency = idempotency;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.tools = List.of(
                new McpToolRegistry.SimpleTool(
                        "search_raw_material_stock",
                        "查询原料库存实时结存（按物料聚合）：在库/可用/冻结公斤数（decimal-string）、"
                                + "件数与批次数，可按物料名/编码关键词过滤。",
                        schema(
                                Map.of("keyword", stringProperty("物料名/编码模糊词，可选")),
                                List.of()),
                        this::searchRawMaterialStock,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "list_raw_inbound_orders",
                        "查询原料入库单列表（含行明细，上游上限 200 单，按 id 倒序）。"
                                + "status 可选、上游枚举原样传：draft/pending_approval/posted/rejected。",
                        schema(
                                Map.of("status", stringProperty(
                                        "入库单状态，上游枚举原样传（draft/pending_approval/posted/rejected），可选")),
                                List.of()),
                        this::listRawInboundOrders,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "list_raw_stock_transactions",
                        "查询原料库存流水（按 id 倒序）。可按 material_id 与 transaction_type"
                                + "（purchase_in/scrap_out/production_out 等上游枚举原样传）过滤；"
                                + "limit 默认 50、上限 200。变动量为公斤小数，出库为负数。",
                        schema(
                                Map.of(
                                        "material_id", stringProperty("按物料 ID 过滤，可选"),
                                        "transaction_type", stringProperty("流水类型，上游枚举原样传，可选"),
                                        "limit", integerProperty("返回条数，1-200，默认 50")),
                                List.of()),
                        this::listRawStockTransactions,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "create_raw_inbound_order",
                        "创建原料采购入库单：建单即待审核（pending_approval），不入账；"
                                + "须经 approve_raw_inbound_order 审批后才生成批次与入库流水。"
                                + "lines 每行必含 material_id 与 quantity_kg（公斤小数字符串，如 \"12.5\"）。"
                                + "幂等：相同 idempotency_key 重放返回首次结果，不重复建单。",
                        schema(
                                Map.of(
                                        "warehouse_id", stringProperty("入库仓库 ID"),
                                        "supplier_name", stringProperty("供应商名称，可选"),
                                        "notes", stringProperty("备注，可选；建议带上调用方业务单号便于对账"),
                                        "lines", arrayProperty("入库行，至少 1 行", inboundLineSchema()),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("warehouse_id", "lines", "idempotency_key")),
                        this::createRawInboundOrder,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "approve_raw_inbound_order",
                        "审批原料入库单：审批即入账（逐行生成采购批次与入库流水），不可逆。"
                                + "仅待审核（pending_approval）单据可审批；请先经 list_raw_inbound_orders 核对行明细。",
                        schema(
                                Map.of(
                                        "order_id", stringProperty("入库单 ID"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("order_id", "idempotency_key")),
                        this::approveRawInboundOrder,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "create_raw_scrap_order",
                        "创建原料报废出库单：按批次 batch_id 报废 quantity_kg 公斤（小数字符串），"
                                + "reason 必填（至少 2 字）。建单即冻结报废量、待审核，不出账；"
                                + "须经 approve_raw_scrap_order 审批后才扣减出账。"
                                + "幂等：相同 idempotency_key 重放返回首次结果，不重复建单。",
                        schema(
                                Map.of(
                                        "batch_id", stringProperty("报废批次 ID"),
                                        "quantity_kg", stringProperty("报废公斤数，正小数字符串，如 \"3.25\""),
                                        "piece_count", stringProperty("报废件数，正整数字符串，可选"),
                                        "reason", stringProperty("报废原因，至少 2 字"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("batch_id", "quantity_kg", "reason", "idempotency_key")),
                        this::createRawScrapOrder,
                        false,
                        MODULE),
                new McpToolRegistry.SimpleTool(
                        "approve_raw_scrap_order",
                        "审批原料报废单：审批即出账（扣减批次结存并记报废流水），不可逆。"
                                + "仅待审核（pending_approval）单据可审批。",
                        schema(
                                Map.of(
                                        "order_id", stringProperty("报废单 ID"),
                                        "idempotency_key", stringProperty("幂等键，至少 8 个字符")),
                                List.of("order_id", "idempotency_key")),
                        this::approveRawScrapOrder,
                        false,
                        MODULE));
    }

    /** 工具集合，由 {@link McpToolRegistry} 聚合。 */
    public List<McpTool> tools() {
        return tools;
    }

    // ------------------------------------------------------------------
    // 读工具
    // ------------------------------------------------------------------

    private JsonNode searchRawMaterialStock(McpRequestContext context, Map<String, Object> args) {
        String keyword = optionalQuery(args, "keyword");
        List<YuanliaokcStockRow> rows = callRead(() -> reads.stock(keyword));
        ObjectNode body = sourceEnvelope();
        ArrayNode items = body.putArray("items");
        for (YuanliaokcStockRow row : rows) {
            ObjectNode item = items.addObject();
            item.put("material_id", row.materialId());
            item.put("material_code", row.materialCode());
            item.put("material_name", row.materialName());
            item.put("category", row.category());
            item.put("spec", row.spec());
            item.put("unit", row.unit());
            putNullableLong(item, "piece_count", row.pieceCount());
            item.put("current_kg", kg(row.currentKg()));
            item.put("available_kg", kg(row.availableKg()));
            item.put("frozen_kg", kg(row.frozenKg()));
            item.put("batch_count", row.batchCount());
            item.put("earliest_expiry", row.earliestExpiry());
            item.put("status", row.status());
        }
        return body;
    }

    private JsonNode listRawInboundOrders(McpRequestContext context, Map<String, Object> args) {
        String status = optionalUpstreamEnum(args, "status");
        List<YuanliaokcInboundOrder> orders = callRead(() -> reads.inboundOrders(status));
        ObjectNode body = sourceEnvelope();
        ArrayNode items = body.putArray("items");
        orders.forEach(order -> items.add(inboundOrderNode(order)));
        return body;
    }

    private JsonNode listRawStockTransactions(McpRequestContext context, Map<String, Object> args) {
        Long materialId = optionalIdentifier(args, "material_id");
        String transactionType = optionalUpstreamEnum(args, "transaction_type");
        int limit = transactionLimit(args);
        List<YuanliaokcStockTransaction> rows =
                callRead(() -> reads.stockTransactions(materialId, transactionType, limit, 0));
        ObjectNode body = sourceEnvelope();
        ArrayNode items = body.putArray("items");
        for (YuanliaokcStockTransaction row : rows) {
            ObjectNode item = items.addObject();
            item.put("id", row.id());
            item.put("material_id", row.materialId());
            item.put("material_name", row.materialName());
            putNullableLong(item, "batch_id", row.batchId());
            item.put("batch_no", row.batchNo());
            item.put("transaction_type", row.transactionType());
            item.put("quantity_change_kg", kg(row.quantityChangeKg()));
            item.put("quantity_after_kg", kg(row.quantityAfterKg()));
            item.put("source_document_type", row.sourceDocumentType());
            putNullableLong(item, "source_document_id", row.sourceDocumentId());
            item.put("notes", row.notes());
            putNullableLong(item, "operator_id", row.operatorId());
            item.put("created_at", row.createdAt());
        }
        return body;
    }

    // ------------------------------------------------------------------
    // 写工具：幂等 + AGENT 审计（与 McpWriteTools 同款纪律）
    // ------------------------------------------------------------------

    private JsonNode createRawInboundOrder(McpRequestContext context, Map<String, Object> args) {
        String idempotencyKey = requireIdempotencyKey(args);
        ObjectNode payload = inboundCreatePayload(args);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("payload", payload.toString());
        return executeWrite(
                "create_raw_inbound_order",
                auditPayload,
                "RAW_INBOUND_ORDER_CREATED",
                context,
                () -> idempotency.execute(
                        "mcp.rawmaterial.create_inbound_order",
                        idempotencyKey,
                        auditPayload,
                        200,
                        () -> inboundOrderNode(callWrite(() -> writes.createInboundOrder(payload)))));
    }

    private JsonNode approveRawInboundOrder(McpRequestContext context, Map<String, Object> args) {
        long orderId = identifier(args, "order_id");
        String idempotencyKey = requireIdempotencyKey(args);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("order_id", orderId);
        return executeWrite(
                "approve_raw_inbound_order",
                auditPayload,
                "RAW_INBOUND_ORDER_POSTED",
                context,
                () -> idempotency.execute(
                        "mcp.rawmaterial.approve_inbound_order",
                        idempotencyKey,
                        auditPayload,
                        200,
                        () -> inboundOrderNode(callWrite(() -> writes.approveInboundOrder(orderId)))));
    }

    private JsonNode createRawScrapOrder(McpRequestContext context, Map<String, Object> args) {
        String idempotencyKey = requireIdempotencyKey(args);
        ObjectNode payload = scrapCreatePayload(args);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("payload", payload.toString());
        return executeWrite(
                "create_raw_scrap_order",
                auditPayload,
                "RAW_SCRAP_ORDER_CREATED",
                context,
                () -> idempotency.execute(
                        "mcp.rawmaterial.create_scrap_order",
                        idempotencyKey,
                        auditPayload,
                        200,
                        () -> scrapOrderNode(callWrite(() -> writes.createScrapOrder(payload)))));
    }

    private JsonNode approveRawScrapOrder(McpRequestContext context, Map<String, Object> args) {
        long orderId = identifier(args, "order_id");
        String idempotencyKey = requireIdempotencyKey(args);
        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("order_id", orderId);
        return executeWrite(
                "approve_raw_scrap_order",
                auditPayload,
                "RAW_SCRAP_ORDER_POSTED",
                context,
                () -> idempotency.execute(
                        "mcp.rawmaterial.approve_scrap_order",
                        idempotencyKey,
                        auditPayload,
                        200,
                        () -> scrapOrderNode(callWrite(() -> writes.approveScrapOrder(orderId)))));
    }

    // ------------------------------------------------------------------
    // 上游载荷构造（InboundCreate/ScrapCreate 实际字段，2026-08-31 自
    // /srv/app/routers/warehouse_ops.py 核对）
    // ------------------------------------------------------------------

    private ObjectNode inboundCreatePayload(Map<String, Object> args) {
        ObjectNode payload = objectMapper.createObjectNode();
        // InboundCreate.warehouse_id 是必填字段（上游 schema 实测），不能省
        payload.put("warehouse_id", identifier(args, "warehouse_id"));
        String supplierName = optionalLimitedString(args, "supplier_name", 128);
        if (supplierName != null) {
            payload.put("supplier_name", supplierName);
        }
        String notes = optionalLimitedString(args, "notes", 500);
        if (notes != null) {
            payload.put("notes", notes);
        }
        Object linesValue = args.get("lines");
        if (!(linesValue instanceof List<?> lines) || lines.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 lines 必须提供至少一行");
        }
        if (lines.size() > MAX_LINES) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 lines 不能超过 " + MAX_LINES + " 行");
        }
        ArrayNode lineArray = payload.putArray("lines");
        for (Object item : lines) {
            if (!(item instanceof Map<?, ?> map)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "lines 每项必须是对象");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) map;
            ObjectNode line = lineArray.addObject();
            line.put("material_id", identifier(entry, "material_id"));
            // 重量小数：BigDecimal 精确入 JSON number，杜绝 double 串扰
            line.put("quantity_kg", positiveDecimal(entry, "quantity_kg"));
            Long pieceCount = optionalPositiveInteger(entry, "piece_count");
            if (pieceCount != null) {
                line.put("piece_count", pieceCount);
            }
            String batchNo = optionalLimitedString(entry, "batch_no", 64);
            if (batchNo != null) {
                line.put("batch_no", batchNo);
            }
            String supplierBatchNo = optionalLimitedString(entry, "supplier_batch_no", 64);
            if (supplierBatchNo != null) {
                line.put("supplier_batch_no", supplierBatchNo);
            }
            Long locationId = optionalIdentifier(entry, "location_id");
            if (locationId != null) {
                line.put("location_id", locationId);
            }
            String productionDate = optionalIsoDate(entry, "production_date");
            if (productionDate != null) {
                line.put("production_date", productionDate);
            }
            String expiryDate = optionalIsoDate(entry, "expiry_date");
            if (expiryDate != null) {
                line.put("expiry_date", expiryDate);
            }
        }
        return payload;
    }

    private ObjectNode scrapCreatePayload(Map<String, Object> args) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("batch_id", identifier(args, "batch_id"));
        payload.put("quantity_kg", positiveDecimal(args, "quantity_kg"));
        Long pieceCount = optionalPositiveInteger(args, "piece_count");
        if (pieceCount != null) {
            payload.put("piece_count", pieceCount);
        }
        String reason = optionalLimitedString(args, "reason", 200);
        if (reason == null || reason.length() < 2) {
            // 上游 ScrapCreate.reason 声明 min_length=2；本地先拦，避免白耗一次上游往返
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 reason 必填且至少 2 字");
        }
        payload.put("reason", reason);
        return payload;
    }

    // ------------------------------------------------------------------
    // 投影：白名单字段，kg 一律 decimal-string
    // ------------------------------------------------------------------

    private ObjectNode inboundOrderNode(YuanliaokcInboundOrder order) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", order.id());
        node.put("order_no", order.orderNo());
        node.put("supplier_name", order.supplierName());
        node.put("warehouse_id", order.warehouseId());
        node.put("warehouse_name", order.warehouseName());
        node.put("status", order.status());
        node.put("notes", order.notes());
        node.put("created_at", order.createdAt());
        ArrayNode lines = node.putArray("lines");
        for (YuanliaokcInboundOrder.Line line : order.lines()) {
            ObjectNode item = lines.addObject();
            item.put("id", line.id());
            item.put("material_id", line.materialId());
            item.put("material_name", line.materialName());
            item.put("batch_no", line.batchNo());
            item.put("supplier_batch_no", line.supplierBatchNo());
            putNullableLong(item, "piece_count", line.pieceCount());
            item.put("quantity_kg", kg(line.quantityKg()));
            item.put("production_date", line.productionDate());
            item.put("expiry_date", line.expiryDate());
            putNullableLong(item, "created_batch_id", line.createdBatchId());
        }
        return node;
    }

    private ObjectNode scrapOrderNode(YuanliaokcScrapOrder order) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", order.id());
        node.put("order_no", order.orderNo());
        node.put("batch_id", order.batchId());
        node.put("batch_no", order.batchNo());
        node.put("material_name", order.materialName());
        putNullableLong(node, "piece_count", order.pieceCount());
        node.put("quantity_kg", kg(order.quantityKg()));
        node.put("reason", order.reason());
        node.put("status", order.status());
        node.put("created_at", order.createdAt());
        return node;
    }

    private ObjectNode sourceEnvelope() {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("source", "YUANLIAOKC");
        return body;
    }

    private static void putNullableLong(ObjectNode node, String field, Long value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.longValue());
        }
    }

    /**
     * kg 重量 decimal-string 出口：统一 3 位（克级）刻度再去尾零，
     * 与 {@code RawMaterialInventoryController} 的取数面同一纪律，吸掉上游 float 长尾噪声。
     */
    private static String kg(BigDecimal value) {
        BigDecimal scaled = value.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        if (scaled.scale() < 0) {
            scaled = scaled.setScale(0);
        }
        return scaled.toPlainString();
    }

    // ------------------------------------------------------------------
    // 网关失败翻译：稳定错误码进 BusinessException，经 MCP/Agent 面以 isError 返回
    // ------------------------------------------------------------------

    private <T> T callRead(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RawMaterialReadException failure) {
            throw translate(failure);
        }
    }

    private <T> T callWrite(java.util.function.Supplier<T> work) {
        try {
            return work.get();
        } catch (RawMaterialWriteException failure) {
            throw translate(failure);
        }
    }

    /** 与 {@code RawMaterialInventoryController} 的四类读失败翻译保持同一 business_code。 */
    private static BusinessException translate(RawMaterialReadException failure) {
        return switch (failure.code()) {
            case RAW_MATERIAL_NOT_CONFIGURED ->
                    new BusinessException(503, "RAW_MATERIAL_NOT_CONFIGURED", "本部署未开放原料库存只读接入");
            case RAW_MATERIAL_UNAVAILABLE ->
                    new BusinessException(503, "RAW_MATERIAL_UNAVAILABLE", "原料库存上游暂不可用，请稍后重试");
            case RAW_MATERIAL_UNAUTHORIZED ->
                    new BusinessException(502, "RAW_MATERIAL_UNAUTHORIZED", "原料库存上游拒绝了本系统的只读凭据");
            case RAW_MATERIAL_CONTRACT_DRIFT ->
                    new BusinessException(502, "RAW_MATERIAL_CONTRACT_DRIFT", "原料库存上游返回结构与约定不一致，已停止解析");
        };
    }

    private static BusinessException translate(RawMaterialWriteException failure) {
        return switch (failure.code()) {
            case RAW_MATERIAL_WRITE_DISABLED ->
                    new BusinessException(503, "RAW_MATERIAL_WRITE_DISABLED", "本部署未开放原料库存写入");
            // REJECTED 携带上游 detail 原样透传：这是入参可修正的业务拒绝（UNPROCESSABLE 语义）
            case RAW_MATERIAL_WRITE_REJECTED ->
                    BusinessException.unprocessable("RAW_MATERIAL_WRITE_REJECTED", failure.getMessage());
            case RAW_MATERIAL_WRITE_UNAUTHORIZED ->
                    new BusinessException(502, "RAW_MATERIAL_WRITE_UNAUTHORIZED", "原料库存上游拒绝了本系统的写凭据");
            case RAW_MATERIAL_WRITE_UNAVAILABLE ->
                    new BusinessException(503, "RAW_MATERIAL_WRITE_UNAVAILABLE", "原料库存上游暂不可用，写操作请稍后重试");
            case RAW_MATERIAL_WRITE_CONTRACT_DRIFT ->
                    new BusinessException(502, "RAW_MATERIAL_WRITE_CONTRACT_DRIFT", "原料库存上游返回结构与约定不一致，已停止解析");
        };
    }

    // ------------------------------------------------------------------
    // 写执行公共流程：与 McpWriteTools.executeWrite 同款（幂等结果 + AGENT 审计）
    // ------------------------------------------------------------------

    private <T> JsonNode executeWrite(
            String toolName,
            Map<String, Object> auditPayload,
            String successCode,
            McpRequestContext context,
            java.util.function.Supplier<IdempotentResult<T>> work) {
        context.requireCommandContext();
        long startedNanos = System.nanoTime();
        try {
            IdempotentResult<T> result = work.get();
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.agentIdentity())
                    .actorType(AuditActorType.AGENT)
                    .service("mcp")
                    .operation("mcp." + toolName)
                    .requestPayload(auditPayload)
                    .responsePayload(Map.of(
                            "replayed", result.replayed(),
                            "http_status", result.httpStatus(),
                            "result", result.replayed() ? result.replayedBody() : result.result()))
                    .httpStatus(result.httpStatus())
                    .businessCode(result.replayed() ? "IDEMPOTENT_REPLAY" : successCode)
                    .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
            return result.replayed() ? result.replayedBody() : objectMapper.valueToTree(result.result());
        } catch (BusinessException ex) {
            recordFailureAudit(toolName, auditPayload, context, ex);
            throw ex;
        }
    }

    /** 失败审计在独立事务中落盘，避免随业务回滚丢失；审计自身失败不得掩盖原始异常。 */
    private void recordFailureAudit(
            String toolName, Map<String, Object> payload, McpRequestContext context, BusinessException ex) {
        try {
            requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.agentIdentity())
                    .actorType(AuditActorType.AGENT)
                    .service("mcp")
                    .operation("mcp." + toolName)
                    .requestPayload(payload)
                    .responsePayload(Map.of("business_code", ex.getBusinessCode()))
                    .httpStatus(ex.getHttpStatus())
                    .businessCode(ex.getBusinessCode())));
        } catch (RuntimeException auditFailure) {
            // 审计失败不掩盖业务异常
        }
    }

    // ------------------------------------------------------------------
    // 参数解析与校验（口径对齐 McpWriteTools：ID/数量一律字符串，先本地校验再出网）
    // ------------------------------------------------------------------

    private static long identifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text) || !text.matches("^[1-9][0-9]*$")) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须是正整数 ID");
        }
        return WriteCommands.parseIdentifier(text);
    }

    private static Long optionalIdentifier(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        return identifier(args, key);
    }

    private static String requireIdempotencyKey(Map<String, Object> args) {
        Object value = args.get("idempotency_key");
        if (!(value instanceof String text)) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 idempotency_key 必须提供");
        }
        return WriteCommands.requireIdempotencyKey(text);
    }

    /** 公斤数：正小数字符串（最多 6 位小数，对齐上游结存刻度），拒绝 0 与负数。 */
    private static BigDecimal positiveDecimal(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String text)
                || !text.matches("^(0|[1-9][0-9]{0,9})(\\.[0-9]{1,6})?$")) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS",
                    "参数 " + key + " 必须是正小数字符串（最多 6 位小数），如 \"12.5\"");
        }
        BigDecimal decimal = new BigDecimal(text);
        if (decimal.signum() <= 0) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 " + key + " 必须大于 0");
        }
        return decimal;
    }

    private static Long optionalPositiveInteger(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        if (!(value instanceof String text) || !text.matches("^[1-9][0-9]{0,8}$")) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是正整数字符串");
        }
        return Long.parseLong(text);
    }

    private static String optionalLimitedString(Map<String, Object> args, String key, int maxLength) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        if (stripped.length() > maxLength) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 长度不能超过 " + maxLength);
        }
        return stripped;
    }

    private static String optionalIsoDate(Map<String, Object> args, String key) {
        String value = optionalLimitedString(args, key, 10);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException ex) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是 ISO 日期，如 2026-08-31");
        }
    }

    private static String optionalQuery(Map<String, Object> args, String key) {
        return optionalLimitedString(args, key, MAX_QUERY_LENGTH);
    }

    /** 上游枚举原样传，但先过形状闸门：小写下划线、不含空白，防把任意串拼进上游查询。 */
    private static String optionalUpstreamEnum(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        String text = String.valueOf(value).strip();
        if (!UPSTREAM_ENUM_SHAPE.matcher(text).matches()) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 " + key + " 必须是上游枚举值（小写字母/数字/下划线）");
        }
        return text;
    }

    private static int transactionLimit(Map<String, Object> args) {
        Object value = args.get("limit");
        if (value == null) {
            return DEFAULT_TRANSACTION_LIMIT;
        }
        int parsed;
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (raw != Math.floor(raw)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 limit 必须是整数");
            }
            parsed = number.intValue();
        } else if (value instanceof String text && text.matches("^[0-9]+$")) {
            parsed = Integer.parseInt(text);
        } else {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "参数 limit 必须是整数");
        }
        if (parsed < 1 || parsed > MAX_TRANSACTION_LIMIT) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "参数 limit 必须在 1-" + MAX_TRANSACTION_LIMIT + " 之间");
        }
        return parsed;
    }

    private static ObjectNode inboundLineSchema() {
        ObjectNode item = McpToolRegistry.objectProperty("入库行");
        ObjectNode props = item.putObject("properties");
        props.set("material_id", stringProperty("物料 ID，正整数字符串"));
        props.set("quantity_kg", stringProperty("入库公斤数，正小数字符串，如 \"12.5\""));
        props.set("piece_count", stringProperty("件数，正整数字符串，可选"));
        props.set("batch_no", stringProperty("批次号，可选；缺省由上游审批时生成"));
        props.set("supplier_batch_no", stringProperty("供应商批次号，可选"));
        props.set("location_id", stringProperty("库位 ID，可选"));
        props.set("production_date", stringProperty("生产日期 ISO 格式（如 2026-08-31），可选"));
        props.set("expiry_date", stringProperty("到期日期 ISO 格式，可选；缺省时上游按保质期天数推算"));
        return item;
    }

    private static ObjectNode schema(Map<String, ObjectNode> properties, List<String> required) {
        return McpToolRegistry.schema(properties, required);
    }

    private static ObjectNode stringProperty(String description) {
        return McpToolRegistry.stringProperty(description);
    }

    private static ObjectNode integerProperty(String description) {
        return McpToolRegistry.integerProperty(description);
    }

    private static ObjectNode arrayProperty(String description, ObjectNode itemSchema) {
        return McpToolRegistry.arrayProperty(description, itemSchema);
    }
}
