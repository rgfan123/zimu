package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 运营人员把已确认的企业微信订单显式、可审计地接回既有京东 Shipment pipeline。 */
@Service
class OrderFulfillmentRoutingService {

    private static final String SCOPE = "order.fulfillment.route";

    private final ProviderFileService providerFiles;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final JdbcTemplate jdbc;

    OrderFulfillmentRoutingService(
            ProviderFileService providerFiles,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            JdbcTemplate jdbc) {
        this.providerFiles = providerFiles;
        this.idempotency = idempotency;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
        this.jdbc = jdbc;
    }

    @Transactional
    IdempotentResult<Map<String, Object>> route(
            long orderId,
            OrderFulfillmentRoutingCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of(
                "order_id", String.valueOf(orderId),
                "expected_order_version", command.expectedOrderVersion());
        return idempotency.execute(SCOPE, idempotencyKey, payload, 201, () -> {
            ProviderFileService.ReadyOrderRoute routed = providerFiles.routeReadyWecomOrder(
                    orderId, command.expectedOrderVersion());
            for (Long shipmentId : routed.shipmentIds()) {
                events.append(
                        orderId,
                        "SHIPMENT_CREATED",
                        null,
                        null,
                        shipmentId,
                        null,
                        DataScope.BUSINESS,
                        Map.of("shipment_id", String.valueOf(shipmentId), "route", "WECOM_MANUAL"),
                        context.operator());
            }
            versions.append(
                    orderId,
                    null,
                    "企业微信订单生成京东发货批次",
                    context.operator(),
                    snapshot(orderId));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_id", String.valueOf(orderId));
            result.put("order_version", routed.orderVersion());
            result.put(
                    "jd_sdk_shipment_ids",
                    routed.shipmentIds().stream().map(String::valueOf).toList());
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .orderId(orderId)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("fulfillment")
                    .operation("order.fulfillment.route")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(201)
                    .businessCode("ORDER_FULFILLMENT_ROUTED"));
            return result;
        });
    }

    private Map<String, Object> snapshot(long orderId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("order", jdbc.queryForMap("SELECT * FROM app.orders WHERE id=?", orderId));
        snapshot.put(
                "lines",
                jdbc.queryForList("SELECT * FROM app.order_lines WHERE order_id=? ORDER BY line_no", orderId));
        snapshot.put(
                "fulfillments",
                jdbc.queryForList(
                        "SELECT f.* FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id "
                                + "WHERE ol.order_id=? ORDER BY f.id",
                        orderId));
        snapshot.put(
                "shipments",
                jdbc.queryForList("SELECT * FROM app.shipments WHERE order_id=? ORDER BY shipment_sequence", orderId));
        return snapshot;
    }
}
