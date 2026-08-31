package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Shipment/Tracking 的共享事务边界。Excel Adapter 只校验文件并调用此用例，
 * 状态、事件、版本和审计不在 Adapter 内重复编排。
 */
@Service
public class ShipmentTrackingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OrderEventService eventService;
    private final OrderVersionService versionService;
    private final AuditLogService auditLogService;
    private final SourceFollowupProgressService sourceFollowups;

    public ShipmentTrackingService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            OrderEventService eventService,
            OrderVersionService versionService,
            AuditLogService auditLogService,
            SourceFollowupProgressService sourceFollowups) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.eventService = eventService;
        this.versionService = versionService;
        this.auditLogService = auditLogService;
        this.sourceFollowups = sourceFollowups;
    }

    @Transactional
    public void accept(ShipmentTrackingCommand command, CommandContext context) {
        // 全部 Shipment 变更统一先锁 Shipment，再写 ShipmentItem/OrderLine。
        // JD SKU 门禁与 JD 出库预览遵循相同顺序，避免反向等待。
        lockShipment(command.shipmentId(), command.orderId());
        if ("FAILED".equals(command.result())) {
            jdbc.update(
                    """
                    UPDATE app.shipments SET shipment_status='FAILED', failure_reason=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """, command.failureReason(), command.shipmentId());
            jdbc.update(
                    """
                    UPDATE app.fulfillments SET exception_code='PROVIDER_FAILED', exception_reason=? WHERE id=?
                    """, command.failureReason(), command.fulfillmentId());
            jdbc.update("UPDATE app.order_lines SET processing_stage='EXCEPTION' WHERE id=?", command.orderLineId());
            appendFacts(command, context, "MANUAL_INTERVENTION_REQUIRED", "PROVIDER_FAILED");
            return;
        }

        jdbc.update(
                "UPDATE app.shipment_items SET shipped_quantity=? WHERE shipment_id=? AND fulfillment_id=?",
                command.shippedQuantity(), command.shipmentId(), command.fulfillmentId());
        jdbc.update(
                """
                UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                command.shippedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(command.shippedAt(), ZoneOffset.UTC),
                command.shipmentId());
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number,
                     provider_tracking_batch_id, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                command.shipmentId(), command.logisticsCompanyCode(), command.logisticsCompanyName(),
                command.trackingNumber(), command.providerTrackingBatchId(), json(command.rawPayload()));
        Map<String, Object> quantities = jdbc.queryForMap(
                "SELECT requested_quantity, cumulative_shipped_quantity, cancelled_quantity FROM app.fulfillments WHERE id=?",
                command.fulfillmentId());
        int requested = ((Number) quantities.get("requested_quantity")).intValue();
        int cumulative = ((Number) quantities.get("cumulative_shipped_quantity")).intValue();
        int cancelled = ((Number) quantities.get("cancelled_quantity")).intValue();
        if (cumulative + cancelled == requested) {
            String outcome = cancelled == 0 ? "FULLY_FULFILLED" : "PARTIALLY_FULFILLED";
            jdbc.update("UPDATE app.fulfillments SET outcome=? WHERE id=?", outcome, command.fulfillmentId());
        }
        boolean requiresSourceFollowup = "PARTIAL".equals(command.result())
                || jdbc.queryForObject(
                        "SELECT COUNT(*)>1 FROM app.shipment_items WHERE fulfillment_id=?",
                        Boolean.class,
                        command.fulfillmentId());
        if (requiresSourceFollowup) {
            createSourceFollowup(command, cumulative, requested);
            sourceFollowups.refresh(command.fulfillmentId());
        } else {
            jdbc.update("UPDATE app.order_lines SET processing_stage='TRACKING_RECEIVED' WHERE id=?", command.orderLineId());
        }
        jdbc.update(
                """
                UPDATE app.orders SET order_status=CASE WHEN NOT EXISTS (
                    SELECT 1 FROM app.order_lines ol JOIN app.fulfillments f ON f.order_line_id=ol.id
                    WHERE ol.order_id=app.orders.id AND f.outcome<>'FULLY_FULFILLED'
                ) AND NOT EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    WHERE rc.order_id=app.orders.id AND rc.status='OPEN'
                      AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                ) THEN 'SHIPPED' ELSE order_status END WHERE id=?
                """, command.orderId());
        appendFacts(command, context, "TRACKING_RECEIVED", "TRACKING_RECEIVED");
    }

    /**
     * 在一个事务中接受整个 Shipment 的完整实发明细和唯一 Tracking。
     *
     * <p>同 Shipment + 同承运商 + 同运单号是业务重放，不追加任何事实；已存在不同
     * Tracking 则返回冲突结果，由调用端在同一事务中进入人工 ReviewCase。应用层始终先锁 Shipment，
     * 再更新其 ShipmentItem/Fulfillment，最后通过订单 advisory lock 追加 Event/Version。
     */
    @Transactional
    public ShipmentTrackingAcceptance acceptShipment(
            ShipmentTrackingBatchCommand command, CommandContext context) {
        lockShipment(command.shipmentId(), command.orderId());
        Map<String, Object> existing = jdbc.query(
                """
                SELECT logistics_company_code, logistics_company_name, tracking_number
                FROM app.trackings WHERE shipment_id=?
                """,
                rs -> rs.next() ? Map.of(
                        "code", rs.getString("logistics_company_code"),
                        "name", rs.getString("logistics_company_name"),
                        "number", rs.getString("tracking_number")) : null,
                command.shipmentId());
        if (existing != null) {
            if (existing.get("code").equals(command.logisticsCompanyCode())
                    && existing.get("number").equals(command.trackingNumber())) {
                return ShipmentTrackingAcceptance.replayed(command.trackingNumber());
            }
            return ShipmentTrackingAcceptance.conflict(command.trackingNumber());
        }
        if (command.items().isEmpty()) {
            throw BusinessException.unprocessable(
                    "SHIPMENT_TRACKING_ITEMS_REQUIRED", "整批物流回填必须包含全部 ShipmentItem");
        }

        // Shipment 锁之后再按承运商+运单号取细粒度 advisory lock，使不同
        // Shipment 并发收到同一外部运单时也能稳定收敛为业务冲突，而不是唯一键异常。
        // PostgreSQL text rejects NUL bytes. Length-prefix the first component so the
        // advisory-lock identity stays unambiguous without relying on a forbidden separator.
        String trackingIdentity = command.logisticsCompanyCode().length()
                + ":" + command.logisticsCompanyCode() + command.trackingNumber();
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 7419206))",
                rs -> {
                    rs.next();
                    return null;
                },
                trackingIdentity);
        Boolean usedByAnotherShipment = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.trackings "
                        + "WHERE logistics_company_code=? AND tracking_number=? AND shipment_id<>?)",
                Boolean.class,
                command.logisticsCompanyCode(), command.trackingNumber(), command.shipmentId());
        if (Boolean.TRUE.equals(usedByAnotherShipment)) {
            return ShipmentTrackingAcceptance.conflict(command.trackingNumber());
        }

        List<Map<String, Object>> persisted = jdbc.queryForList(
                """
                SELECT si.fulfillment_id, si.instructed_quantity, si.shipped_quantity,
                       f.order_line_id
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                WHERE si.shipment_id=? ORDER BY si.id
                FOR UPDATE OF si, f
                """,
                command.shipmentId());
        Map<Long, ShipmentTrackingBatchCommand.Item> requested = new LinkedHashMap<>();
        for (ShipmentTrackingBatchCommand.Item item : command.items()) {
            if (requested.putIfAbsent(item.fulfillmentId(), item) != null) {
                throw BusinessException.unprocessable(
                        "SHIPMENT_TRACKING_ITEM_DUPLICATE", "整批物流回填不能重复提交 Fulfillment");
            }
        }
        if (requested.size() != persisted.size()) {
            throw BusinessException.unprocessable(
                    "SHIPMENT_TRACKING_ITEMS_MISMATCH", "整批物流回填必须精确覆盖全部 ShipmentItem");
        }
        for (Map<String, Object> row : persisted) {
            long fulfillmentId = ((Number) row.get("fulfillment_id")).longValue();
            long orderLineId = ((Number) row.get("order_line_id")).longValue();
            int instructed = ((Number) row.get("instructed_quantity")).intValue();
            ShipmentTrackingBatchCommand.Item item = requested.get(fulfillmentId);
            if (item == null
                    || item.orderLineId() != orderLineId
                    || item.shippedQuantity() != instructed
                    || row.get("shipped_quantity") != null) {
                throw BusinessException.unprocessable(
                        "SHIPMENT_TRACKING_ITEMS_MISMATCH", "物流回填明细与发货批次指令量不完全一致");
            }
        }

        for (ShipmentTrackingBatchCommand.Item item : command.items()) {
            jdbc.update(
                    "UPDATE app.shipment_items SET shipped_quantity=?, updated_at=CURRENT_TIMESTAMP "
                            + "WHERE shipment_id=? AND fulfillment_id=?",
                    item.shippedQuantity(), command.shipmentId(), item.fulfillmentId());
        }
        jdbc.update(
                """
                UPDATE app.shipments SET shipment_status='SHIPPED', shipped_at=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                command.shippedAt() == null
                        ? null
                        : OffsetDateTime.ofInstant(command.shippedAt(), ZoneOffset.UTC),
                command.shipmentId());
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number,
                     provider_tracking_batch_id, raw_payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                command.shipmentId(), command.logisticsCompanyCode(), command.logisticsCompanyName(),
                command.trackingNumber(), command.providerTrackingBatchId(), json(command.rawPayload()));

        for (ShipmentTrackingBatchCommand.Item item : command.items()) {
            Map<String, Object> quantities = jdbc.queryForMap(
                    "SELECT requested_quantity, cumulative_shipped_quantity, cancelled_quantity "
                            + "FROM app.fulfillments WHERE id=?",
                    item.fulfillmentId());
            int requestedQuantity = ((Number) quantities.get("requested_quantity")).intValue();
            int cumulative = ((Number) quantities.get("cumulative_shipped_quantity")).intValue();
            int cancelled = ((Number) quantities.get("cancelled_quantity")).intValue();
            if (cumulative + cancelled == requestedQuantity) {
                jdbc.update(
                        "UPDATE app.fulfillments SET outcome=? WHERE id=?",
                        cancelled == 0 ? "FULLY_FULFILLED" : "PARTIALLY_FULFILLED",
                        item.fulfillmentId());
            }
            boolean requiresSourceFollowup = jdbc.queryForObject(
                    "SELECT COUNT(*)>1 FROM app.shipment_items WHERE fulfillment_id=?",
                    Boolean.class,
                    item.fulfillmentId());
            if (requiresSourceFollowup) {
                createSourceFollowup(command, item, cumulative, requestedQuantity);
                sourceFollowups.refresh(item.fulfillmentId());
            } else {
                jdbc.update(
                        "UPDATE app.order_lines SET processing_stage='TRACKING_RECEIVED', "
                                + "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        item.orderLineId());
            }
        }
        jdbc.update(
                """
                UPDATE app.orders SET order_status=CASE WHEN NOT EXISTS (
                    SELECT 1 FROM app.order_lines ol JOIN app.fulfillments f ON f.order_line_id=ol.id
                    WHERE ol.order_id=app.orders.id AND f.outcome<>'FULLY_FULFILLED'
                ) AND NOT EXISTS (
                    SELECT 1 FROM app.review_cases rc
                    WHERE rc.order_id=app.orders.id AND rc.status='OPEN'
                      AND rc.reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                ) THEN 'SHIPPED' ELSE order_status END, updated_at=CURRENT_TIMESTAMP WHERE id=?
                """,
                command.orderId());
        appendShipmentFacts(command, context);
        return ShipmentTrackingAcceptance.accepted(command.trackingNumber());
    }

    private void lockShipment(long shipmentId, long orderId) {
        jdbc.queryForObject(
                "SELECT id FROM app.shipments WHERE id=? AND order_id=? FOR UPDATE",
                Long.class,
                shipmentId,
                orderId);
    }

    private void createSourceFollowup(
            ShipmentTrackingCommand command, int cumulative, int requested) {
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, order_line_id, fulfillment_id, detail)
                VALUES (?, 'SOURCE_FOLLOWUP', 'OPEN', 'FULFILLMENT_OPS',
                        'MULTI_SHIPMENT_SOURCE_FOLLOWUP', ?, ?, ?, jsonb_build_object(
                            'message', '来源商品行存在多个发货批次，需人工完成后续回传',
                            'first_tracking_shipment_id', ?::text,
                            'cumulative_shipped_quantity', ?::text,
                            'requested_quantity', ?::text))
                ON CONFLICT DO NOTHING
                """,
                "RC-MULTI-SHIPMENT-" + command.fulfillmentId(),
                command.orderId(),
                command.orderLineId(),
                command.fulfillmentId(),
                command.shipmentId(),
                String.valueOf(cumulative),
                String.valueOf(requested));
    }

    private void createSourceFollowup(
            ShipmentTrackingBatchCommand command,
            ShipmentTrackingBatchCommand.Item item,
            int cumulative,
            int requested) {
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, order_line_id, fulfillment_id, detail)
                VALUES (?, 'SOURCE_FOLLOWUP', 'OPEN', 'FULFILLMENT_OPS',
                        'MULTI_SHIPMENT_SOURCE_FOLLOWUP', ?, ?, ?, jsonb_build_object(
                            'message', '来源商品行存在多个发货批次，需人工完成后续回传',
                            'first_tracking_shipment_id', ?::text,
                            'cumulative_shipped_quantity', ?::text,
                            'requested_quantity', ?::text))
                ON CONFLICT DO NOTHING
                """,
                "RC-MULTI-SHIPMENT-" + item.fulfillmentId(),
                command.orderId(), item.orderLineId(), item.fulfillmentId(), command.shipmentId(),
                String.valueOf(cumulative), String.valueOf(requested));
    }

    private void appendShipmentFacts(ShipmentTrackingBatchCommand command, CommandContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", "SHIPPED");
        payload.put("tracking_number", command.trackingNumber());
        payload.put("shipment_item_count", command.items().size());
        eventService.append(
                command.orderId(), "TRACKING_RECEIVED", null, null, command.shipmentId(),
                null, DataScope.BUSINESS, payload, context.operator());
        versionService.append(
                command.orderId(), null,
                command.changeReason() == null || command.changeReason().isBlank()
                        ? "履约方整批回传发货结果" : command.changeReason(),
                context.operator(), snapshot(command.orderId()));
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(command.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.EXTERNAL).service("fulfillment").operation("tracking.accept")
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(command.shipmentId()),
                        "logistics_company_code", command.logisticsCompanyCode(),
                        "tracking_number", command.trackingNumber(),
                        "shipment_item_count", command.items().size()))
                .responsePayload(Map.of("result", "SHIPPED"))
                .httpStatus(200).businessCode("TRACKING_RECEIVED"));
    }

    private void appendFacts(
            ShipmentTrackingCommand command, CommandContext context, String eventType, String businessCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("result", command.result());
        payload.put("tracking_number", command.trackingNumber());
        payload.put("shipped_quantity", command.shippedQuantity());
        payload.put("failure_reason", command.failureReason());
        eventService.append(
                command.orderId(), eventType, command.orderLineId(), command.fulfillmentId(), command.shipmentId(),
                null, DataScope.BUSINESS, payload, context.operator());
        versionService.append(
                command.orderId(), null, "履约方回传发货结果", context.operator(), snapshot(command.orderId()));
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(command.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.EXTERNAL).service("fulfillment").operation("tracking.accept")
                .requestPayload(command).responsePayload(Map.of("result", command.result()))
                .httpStatus(200).businessCode(businessCode));
    }

    private Map<String, Object> snapshot(long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", jdbc.queryForMap("SELECT * FROM app.orders WHERE id=?", orderId));
        result.put("lines", jdbc.queryForList("SELECT * FROM app.order_lines WHERE order_id=? ORDER BY line_no", orderId));
        result.put("fulfillments", jdbc.queryForList(
                """
                SELECT f.* FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=? ORDER BY f.id
                """, orderId));
        result.put("shipments", jdbc.queryForList("SELECT * FROM app.shipments WHERE order_id=? ORDER BY shipment_sequence", orderId));
        result.put("trackings", jdbc.queryForList(
                """
                SELECT t.* FROM app.trackings t JOIN app.shipments s ON s.id=t.shipment_id
                WHERE s.order_id=? ORDER BY t.id
                """, orderId));
        return result;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
