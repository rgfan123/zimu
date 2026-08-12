package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
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
        BigDecimal requested = (BigDecimal) quantities.get("requested_quantity");
        BigDecimal cumulative = (BigDecimal) quantities.get("cumulative_shipped_quantity");
        BigDecimal cancelled = (BigDecimal) quantities.get("cancelled_quantity");
        if (cumulative.add(cancelled).compareTo(requested) == 0) {
            String outcome = cancelled.signum() == 0 ? "FULLY_FULFILLED" : "PARTIALLY_FULFILLED";
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

    private void createSourceFollowup(
            ShipmentTrackingCommand command, BigDecimal cumulative, BigDecimal requested) {
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
                cumulative.toPlainString(),
                requested.toPlainString());
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
