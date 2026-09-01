package cn.zimu.fulfillment.demo;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 隔离的 Mock DemoScenario：只写 DEMO 订单、DemoRun 与 DEMO OrderEvent。 */
@Service
public class DemoScenarioService {

    private static final String HAPPY_PATH = "HAPPY_PATH";
    private static final String AI_EXTRACTED_ORDER = "AI_EXTRACTED_ORDER";
    private static final List<String> EVENT_TYPES = List.of(
            "ORDER_RECEIVED",
            "SKU_MAPPED",
            "JD_STOCK_CHECKED",
            "JD_OUTBOUND_SUBMITTED",
            "JD_OUTBOUND_ACCEPTED",
            "JD_SHIPPED",
            "SHIPMENT_CREATED",
            "TRACKING_RECEIVED",
            "SOURCE_SYNCED");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final OrderVersionService orderVersionService;
    private final AuditLogService auditLogService;

    public DemoScenarioService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotencyService,
            OrderVersionService orderVersionService,
            AuditLogService auditLogService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.orderVersionService = orderVersionService;
        this.auditLogService = auditLogService;
    }

    public List<Map<String, String>> scenarios() {
        return List.of(Map.of(
                "scenario_code", HAPPY_PATH,
                "scenario_name", "京东仓完整履约",
                "description", "Mock 演示从接单、SKU 映射、京东出库到运单回传的完整 Timeline"));
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> run(
            String idempotencyKey, DemoScenarioInput input, CommandContext context) {
        return idempotencyService.execute(
                "demo.run",
                idempotencyKey,
                input,
                201,
                () -> createCompletedRun(input.scenarioCode(), null, input, context));
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> runExtracted(
            String idempotencyKey, DemoExtractedOrderInput input, CommandContext context) {
        if (input.source() != SourceChannel.WECOM) {
            throw BusinessException.unprocessable(
                    "DEMO_AI_SOURCE_UNSUPPORTED", "AI 演示订单当前只接受企业微信来源");
        }
        return idempotencyService.execute(
                "demo.ai-order.run",
                idempotencyKey,
                input,
                201,
                () -> createCompletedRun(AI_EXTRACTED_ORDER, input, input, context));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long runId) {
        return loadRun(runId);
    }

    private Map<String, Object> createCompletedRun(
            String scenarioCode,
            DemoExtractedOrderInput extractedOrder,
            Object requestPayload,
            CommandContext context) {
        long startedNanos = System.nanoTime();
        if (!HAPPY_PATH.equals(scenarioCode) && !AI_EXTRACTED_ORDER.equals(scenarioCode)) {
            throw BusinessException.unprocessable("DEMO_SCENARIO_NOT_FOUND", "未知演示场景: " + scenarioCode);
        }
        String token = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String orderNo = "DEMO-ORD-" + token;
        String sourceRef = "DEMO-" + token;
        String customerName = extractedOrder == null ? "演示客户" : extractedOrder.customer().customerName();
        Long customerId = jdbc.queryForObject(
                """
                    INSERT INTO app.customers (customer_code, customer_name, data_scope, status, profile)
                    VALUES (?, ?, 'DEMO', 'ACTIVE', '{}'::jsonb)
                    ON CONFLICT (customer_code) DO UPDATE SET customer_name = EXCLUDED.customer_name
                    RETURNING id
                    """,
                Long.class,
                "DEMO-CUSTOMER-" + token,
                customerName);
        String inputSourceRef = extractedOrder == null ? sourceRef : extractedOrder.sourceRef();
        String receiverName = extractedOrder == null ? "演示客户" : extractedOrder.receiver().receiverName();
        String receiverPhone = extractedOrder == null ? "13800000000" : extractedOrder.receiver().receiverPhone();
        String receiverAddress = extractedOrder == null
                ? "上海市 DEMO 隔离地址"
                : extractedOrder.receiver().address();
        String remark = extractedOrder == null ? "固定演示场景" : extractedOrder.remark();
        String settlementMethod = extractedOrder == null
                ? "OTHER"
                : settlementMethod(extractedOrder.settlement().settlementMethod());
        OffsetDateTime settlementTime = extractedOrder == null
                ? OffsetDateTime.now()
                : extractedOrder.settlement().settlementTime();
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, customer_id,
                     order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address, remark, evidence_refs)
                VALUES (?, 'DEMO', 'WECOM', ?, 'SYNTHETIC', ?, 'SYNCED', ?, ?, ?, ?, ?, ?, '[]'::jsonb)
                RETURNING id
                """,
                Long.class,
                orderNo,
                inputSourceRef,
                customerId,
                settlementMethod,
                settlementTime,
                receiverName,
                receiverPhone,
                receiverAddress,
                remark);
        if (extractedOrder == null) {
            insertLine(orderId, 1, "子牧羊小腿", null, "500g/盒", 2, "盒");
        } else {
            for (int index = 0; index < extractedOrder.items().size(); index++) {
                DemoExtractedOrderInput.Item item = extractedOrder.items().get(index);
                insertLine(orderId, index + 1, item.productName(), item.skuCode(), item.specification(), item.quantity(), item.unit());
            }
        }
        for (int index = 0; index < EVENT_TYPES.size(); index++) {
            jdbc.update(
                    """
                    INSERT INTO app.order_events
                        (order_id, sequence_no, event_type_code, data_scope, payload, operator)
                    VALUES (?, ?, ?, 'DEMO', ?::jsonb, ?)
                    """,
                    orderId,
                    index + 1,
                    EVENT_TYPES.get(index),
                    json(Map.of("scenario_code", scenarioCode, "mock_step", index + 1)),
                    context.operator());
        }
        Map<String, Object> runResult = new LinkedHashMap<>();
        runResult.put("timeline_complete", true);
        if (extractedOrder != null) {
            runResult.put("extracted_order", extractedOrder);
        }
        Long runId = jdbc.queryForObject(
                """
                INSERT INTO app.demo_runs
                    (run_no, scenario_code, order_id, run_status, result, finished_at)
                VALUES (?, ?, ?, 'SUCCEEDED', ?::jsonb, CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "RUN-" + token,
                scenarioCode,
                orderId,
                json(runResult));
        Map<String, Object> completedRun = loadRun(runId);
        orderVersionService.append(
                orderId,
                null,
                "Demo 场景完成",
                context.operator(),
                new LinkedHashMap<>(completedRun));
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.DEMO)
                .orderId(orderId)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.SYSTEM)
                .service("demo")
                .operation("demo.run")
                .requestPayload(requestPayload)
                .responsePayload(completedRun)
                .httpStatus(201)
                .businessCode("DEMO_RUN_CREATED")
                .latencyMs((int) ((System.nanoTime() - startedNanos) / 1_000_000)));
        return completedRun;
    }

    private void insertLine(
            long orderId,
            int lineNo,
            String productName,
            String skuCode,
            String specification,
            int quantity,
            String unit) {
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, product_name_snapshot, sku_code_snapshot,
                     specification_snapshot, unit_snapshot, requested_quantity, processing_stage)
                VALUES (?, ?, 'SINGLE', ?, ?, ?, ?, ?, 'COMPLETED')
                """,
                orderId,
                lineNo,
                productName,
                skuCode,
                specification,
                unit,
                quantity);
    }

    private String settlementMethod(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.contains("月结") || normalized.equals("MONTHLY")) return "MONTHLY";
        if (normalized.contains("现结") || normalized.equals("IMMEDIATE")) return "IMMEDIATE";
        if (normalized.contains("账期") || normalized.equals("CREDIT_TERM")) return "CREDIT_TERM";
        if (normalized.contains("预付") || normalized.equals("PREPAID")) return "PREPAID";
        if (normalized.contains("货到付款") || normalized.equals("COD")) return "COD";
        return "OTHER";
    }

    private Map<String, Object> loadRun(long runId) {
        return jdbc.query(
                """
                SELECT dr.id, dr.run_no, dr.scenario_code, dr.run_status, dr.order_id, dr.result,
                       dr.started_at, dr.finished_at,
                       o.order_no, o.source_channel, o.source_ref, o.customer_id, c.customer_name,
                       o.receiver_name, o.receiver_phone, o.receiver_address,
                       o.order_status, o.created_at, o.updated_at, o.lock_version,
                       (SELECT count(*) FROM app.order_lines ol WHERE ol.order_id = o.id) AS total_count,
                       (SELECT count(*) FROM app.order_lines ol
                          WHERE ol.order_id = o.id AND ol.processing_stage = 'COMPLETED') AS completed_count
                FROM app.demo_runs dr
                JOIN app.orders o ON o.id = dr.order_id AND o.data_scope = 'DEMO'
                LEFT JOIN app.customers c ON c.id = o.customer_id
                WHERE dr.id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        throw BusinessException.notFound("演示运行不存在: " + runId);
                    }
                    return mapRun(rs);
                },
                runId);
    }

    private Map<String, Object> mapRun(ResultSet rs) throws SQLException {
        long orderId = rs.getLong("order_id");
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("id", String.valueOf(orderId));
        order.put("order_no", rs.getString("order_no"));
        order.put("source_channel", rs.getString("source_channel"));
        order.put("source_ref", rs.getString("source_ref"));
        order.put("customer_id", String.valueOf(rs.getLong("customer_id")));
        order.put("customer_name", rs.getString("customer_name"));
        order.put("receiver_name", rs.getString("receiver_name"));
        order.put("receiver_phone", rs.getString("receiver_phone"));
        order.put("receiver_address", rs.getString("receiver_address"));
        order.put("order_status", rs.getString("order_status"));
        order.put("processing_stage", "COMPLETED");
        order.put("processing_health", "GREEN");
        order.put("completed_count", rs.getInt("completed_count"));
        order.put("total_count", rs.getInt("total_count"));
        order.put("attention_reason", null);
        order.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
        order.put("updated_at", rs.getObject("updated_at", OffsetDateTime.class));
        order.put("version", rs.getLong("lock_version"));
        order.put("lines", lines(orderId));

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", String.valueOf(rs.getLong("id")));
        run.put("run_no", rs.getString("run_no"));
        run.put("scenario_code", rs.getString("scenario_code"));
        run.put("status", rs.getString("run_status"));
        run.put("data_scope", "DEMO");
        run.put("order_id", String.valueOf(orderId));
        run.put("order", order);
        run.put("timeline", timeline(orderId));
        Object result = readJson(rs.getObject("result"));
        if (result instanceof Map<?, ?> resultMap && resultMap.containsKey("extracted_order")) {
            run.put("extracted_order", resultMap.get("extracted_order"));
        }
        run.put("started_at", rs.getObject("started_at", OffsetDateTime.class));
        run.put("finished_at", rs.getObject("finished_at", OffsetDateTime.class));
        return run;
    }

    private List<Map<String, Object>> lines(long orderId) {
        return jdbc.query(
                """
                SELECT line_no, product_name_snapshot, sku_code_snapshot,
                       specification_snapshot, requested_quantity::text AS quantity,
                       unit_snapshot, processing_stage
                FROM app.order_lines
                WHERE order_id = ?
                ORDER BY line_no
                """,
                (rs, rowNum) -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("line_no", rs.getInt("line_no"));
                    line.put("product_name", rs.getString("product_name_snapshot"));
                    line.put("sku_code", rs.getString("sku_code_snapshot"));
                    line.put("specification", rs.getString("specification_snapshot"));
                    line.put("quantity", rs.getString("quantity"));
                    line.put("unit", rs.getString("unit_snapshot"));
                    line.put("processing_stage", rs.getString("processing_stage"));
                    return line;
                },
                orderId);
    }

    private List<Map<String, Object>> timeline(long orderId) {
        return jdbc.query(
                """
                SELECT id, sequence_no, event_type_code, order_line_id, fulfillment_id,
                       shipment_id, procurement_ticket_id, operator, payload, created_at
                FROM app.order_events
                WHERE order_id = ? AND data_scope = 'DEMO'
                ORDER BY sequence_no
                """,
                (rs, rowNum) -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("id", String.valueOf(rs.getLong("id")));
                    event.put("sequence_no", rs.getLong("sequence_no"));
                    event.put("event_type_code", rs.getString("event_type_code"));
                    putNullableId(event, "order_line_id", rs, "order_line_id");
                    putNullableId(event, "fulfillment_id", rs, "fulfillment_id");
                    putNullableId(event, "shipment_id", rs, "shipment_id");
                    putNullableId(event, "procurement_ticket_id", rs, "procurement_ticket_id");
                    event.put("operator", rs.getString("operator"));
                    event.put("payload", readJson(rs.getObject("payload")));
                    event.put("created_at", rs.getObject("created_at", OffsetDateTime.class));
                    return event;
                },
                orderId);
    }

    private static void putNullableId(
            Map<String, Object> target, String key, ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        target.put(key, rs.wasNull() ? null : String.valueOf(value));
    }

    private Object readJson(Object value) {
        try {
            String json = value instanceof PGobject pg ? pg.getValue() : String.valueOf(value);
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
            throw new IllegalStateException("Demo Timeline payload 解析失败", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Demo payload 序列化失败", ex);
        }
    }
}
