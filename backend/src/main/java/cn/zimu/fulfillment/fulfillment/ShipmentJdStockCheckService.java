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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * 使用 Ticket 02 的 immutable preview 作为唯一数量来源，执行 Shipment 级京东实时库存判定。
 * 库存查询是 advisory fact：不预占、不改变履约方、不创建采购单，提交前仍必须重新查询。
 */
@Service
public class ShipmentJdStockCheckService {

    private static final String SCOPE = "shipment.jd_stock.check";
    private static final String BLOCK_REASON = "JD_STOCK_BLOCKED";
    private static final String EVENT_TYPE = "JD_STOCK_CHECKED";
    private static final String SOURCE_TYPE = "JD_ISC_QUERY_STOCK";
    private static final String QUANTITY_UNIT = "JD_PIECE";

    private final ShipmentJdOutboundService outbound;
    private final ShipmentJdSkuMappingGateService skuGate;
    private final JDWarehouseService jdWarehouse;
    private final IdempotencyService idempotency;
    private final JdbcTemplate jdbc;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final ObjectMapper objectMapper;

    public ShipmentJdStockCheckService(
            ShipmentJdOutboundService outbound,
            ShipmentJdSkuMappingGateService skuGate,
            JDWarehouseService jdWarehouse,
            IdempotencyService idempotency,
            JdbcTemplate jdbc,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            ObjectMapper objectMapper) {
        this.outbound = outbound;
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
                () -> outbound.preparePreview(shipmentId),
                preview -> Map.of(
                        "shipment_id", shipmentId,
                        "shipment_version", preview.shipmentVersion(),
                        "preview_hash", preview.requestHash()),
                preview -> probe(preview, idempotencyKey, context),
                (preview, probe) -> persist(preview, probe, context));
    }

    private Probe probe(
            ShipmentJdOutboundPreviewSnapshot preview,
            String stockIdempotencyKey,
            CommandContext context) {
        if (!preview.submittable()) {
            throw BusinessException.conflict(
                    "JD_STOCK_PREVIEW_BLOCKED",
                    "当前京东出库预览仍有阻断项，请先修复预览后再查询库存");
        }

        IdempotentResult<Map<String, Object>> gateResult = skuGate.check(
                preview.shipmentId(),
                "jd-stock-gate-" + UUID.randomUUID().toString(),
                context);
        Map<String, Object> gate = gateResult.replayed()
                ? objectMapper.convertValue(gateResult.replayedBody(), new TypeReference<>() {})
                : gateResult.result();
        if (!"PASSED".equals(text(gate.get("gate_status")))
                || integer(gate.get("blocking_issue_count")) != 0) {
            return Probe.blocked(
                    "SKU_MAPPING_BLOCKED",
                    List.of(blocker(
                            "JD_SKU_MAPPING_GATE_BLOCKED",
                            "京东 SKU 映射门禁未通过，请先维护映射并重新核对")),
                    null,
                    List.of(),
                    requiredText(gate.get("local_gate_fingerprint"), "local_gate_fingerprint"));
        }
        String localGateFingerprint = requiredText(
                gate.get("local_gate_fingerprint"), "local_gate_fingerprint");

        List<Demand> demands = aggregateDemands(preview.stockDemands());
        Map<String, Object> request = stockRequest(preview, demands);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD stock query must run outside a database transaction");
        }
        JdResult result;
        try {
            result = jdWarehouse.queryStock(request);
        } catch (RuntimeException exception) {
            result = new JdResult(false, "CLIENT_EXCEPTION", "京东库存查询调用失败", null, null);
        }
        return evaluate(result, preview, demands, localGateFingerprint);
    }

    private Map<String, Object> persist(
            ShipmentJdOutboundPreviewSnapshot prepared,
            Probe probe,
            CommandContext context) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD stock result persistence requires a database transaction");
        }
        ShipmentJdOutboundPreviewSnapshot current = outbound.preparePreview(prepared.shipmentId());
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
        Long reviewCaseId = reconcileCase(current, probe, passed, context.operator());
        Map<String, Object> response = response(current, probe, passed, reviewCaseId);
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
            ShipmentJdOutboundPreviewSnapshot preview,
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

        String warehouse = text(preview.request().get("warehouseNo"));
        List<Map<String, Object>> blockers = new ArrayList<>();
        List<StockObservation> observations = new ArrayList<>();
        for (Demand demand : demands) {
            List<Map<String, Object>> matches = rows.stream()
                    .filter(row -> demand.goodsNo().equals(text(row.get("goodsNo"))))
                    .filter(row -> warehouse.equals(text(row.get("warehouseNo"))))
                    .toList();
            if (matches.isEmpty()) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(blocker(
                        "JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED",
                        "京东响应缺少目标仓商品行，不能把缺行解释为 0 库存"));
                continue;
            }
            if (matches.size() != 1) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(blocker(
                        "JD_STOCK_RESPONSE_AMBIGUOUS",
                        "京东响应包含重复的目标仓商品行，默认阻断"));
                continue;
            }
            StockObservation observation = parseObservation(matches.getFirst(), demand, warehouse);
            if (observation == null) {
                observations.add(StockObservation.notObserved(
                        demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces()));
                blockers.add(blocker(
                        "JD_STOCK_RESPONSE_INVALID",
                        "京东目标仓库存行缺字段、含负数或数量关系无效，默认阻断"));
                continue;
            }
            observations.add(observation);
            if (observation.usable().compareTo(BigDecimal.valueOf(demand.requiredPieces())) < 0) {
                blockers.add(blocker(
                        "JD_STOCK_INSUFFICIENT",
                        "京东目标仓可用库存不足：需要 " + demand.requiredPieces()
                                + " 件，可用 " + decimal(observation.usable()) + " 件"));
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
        BigDecimal stock = decimalValue(row.get("stockNum"));
        BigDecimal usable = decimalValue(row.get("usableNum"));
        if (stock == null || usable == null || stock.signum() < 0 || usable.signum() < 0
                || usable.compareTo(stock) > 0
                || !fitsSnapshotQuantity(stock)
                || !fitsSnapshotQuantity(usable)) {
            return null;
        }
        return new StockObservation(
                demand.skuId(), demand.goodsNo(), warehouse, demand.requiredPieces(),
                stock, usable, true,
                stock.signum() == 0 && usable.signum() == 0 ? "OBSERVED_ZERO" : "OBSERVED");
    }

    private List<Demand> aggregateDemands(List<ShipmentJdOutboundPreviewSnapshot.StockDemand> source) {
        Map<String, Demand> values = new LinkedHashMap<>();
        for (ShipmentJdOutboundPreviewSnapshot.StockDemand item : source) {
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
            ShipmentJdOutboundPreviewSnapshot preview, List<Demand> demands) {
        Map<String, Object> customer = map(preview.request().get("customerInfo"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pin", preview.request().get("pin"));
        request.put("ownerNo", customer.get("ownerNo"));
        request.put("warehouseNo", preview.request().get("warehouseNo"));
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
            ShipmentJdOutboundPreviewSnapshot preview,
            Probe probe,
            boolean passed,
            String operator) {
        List<Long> existing = jdbc.queryForList(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                FOR UPDATE
                """,
                Long.class,
                preview.shipmentId(),
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
                            "preview_hash", preview.requestHash(),
                            "not_reserved", true)),
                    operator,
                    existing.getFirst());
            return null;
        }
        String detail = json(Map.of(
                "shipment_id", String.valueOf(preview.shipmentId()),
                "preview_hash", preview.requestHash(),
                "blockers", probe.blockers(),
                "observations", probe.observations().stream().map(this::observationMap).toList(),
                "not_reserved", true,
                "maintenance_action", Map.of(
                        "action", "RERUN_JD_STOCK_CHECK",
                        "api", "/api/v1/shipments/" + preview.shipmentId() + "/jd-stock-check")));
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
                    preview.orderId(),
                    preview.shipmentId(),
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
                preview.shipmentId(),
                BLOCK_REASON);
    }

    private Map<String, Object> response(
            ShipmentJdOutboundPreviewSnapshot preview,
            Probe probe,
            boolean passed,
            Long reviewCaseId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(preview.shipmentId()));
        response.put("shipment_version", preview.shipmentVersion());
        response.put("preview_hash", preview.requestHash());
        response.put("target_warehouse_code", text(preview.request().get("warehouseNo")));
        response.put("stock_status", passed ? "PASSED" : "BLOCKED");
        response.put("observation_status", overallObservation(probe.observations()));
        response.put("observed_at", probe.observedAt());
        response.put("not_reserved", true);
        response.put("blockers", probe.blockers());
        response.put("items", probe.observations().stream().map(this::observationMap).toList());
        if (reviewCaseId != null) {
            response.put("review_case", Map.of(
                    "id", String.valueOf(reviewCaseId),
                    "reason_code", BLOCK_REASON,
                    "status", "OPEN"));
        }
        return response;
    }

    private Map<String, Object> observationMap(StockObservation row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sku_id", String.valueOf(row.skuId()));
        value.put("goods_no", row.goodsNo());
        value.put("warehouse_code", row.warehouseCode());
        value.put("required_quantity", String.valueOf(row.requiredPieces()));
        value.put("quantity_unit", QUANTITY_UNIT);
        value.put("observation_status", row.observationStatus());
        if (row.observed()) {
            value.put("stock_quantity", decimal(row.stock()));
            value.put("usable_quantity", decimal(row.usable()));
        }
        return value;
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

    private static int integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? -1 : Integer.parseInt(value.toString());
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean fitsSnapshotQuantity(BigDecimal value) {
        try {
            BigDecimal persisted = value.setScale(3, RoundingMode.UNNECESSARY);
            return persisted.precision() <= 18;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
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
            BigDecimal stock,
            BigDecimal usable,
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
