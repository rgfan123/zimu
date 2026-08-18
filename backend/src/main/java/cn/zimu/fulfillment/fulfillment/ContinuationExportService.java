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
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把已部分发货的第三方 Fulfillment 转为一个新 Shipment 与独立履约文件。 */
@Service
public class ContinuationExportService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final ContinuationExportGenerator providerFiles;
    private final FulfillmentReadService reads;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;

    public ContinuationExportService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            ContinuationExportGenerator providerFiles,
            FulfillmentReadService reads,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.providerFiles = providerFiles;
        this.reads = reads;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> create(
            long fulfillmentId,
            ContinuationExportCommand command,
            String idempotencyKey,
            CommandContext context) {
        return idempotency.execute(
                "fulfillment.continuation_export",
                idempotencyKey,
                Map.of("fulfillment_id", fulfillmentId, "command", command),
                201,
                () -> doCreate(fulfillmentId, command, context));
    }

    private Map<String, Object> doCreate(
            long fulfillmentId, ContinuationExportCommand command, CommandContext context) {
        Context state = lockContext(fulfillmentId);
        if (state.version() != command.expectedVersion()) {
            throw BusinessException.conflict("VERSION_CONFLICT", "履约任务已更新，请刷新后重试");
        }
        if (!"PARTIALLY_SHIPPED".equals(state.shippingProgress())) {
            throw BusinessException.conflict("CONTINUATION_NOT_PARTIALLY_SHIPPED", "仅部分发货的履约任务可创建续发批次");
        }
        if (!"THIRD_PARTY".equals(state.providerType())) {
            throw BusinessException.unprocessable(
                    "CONTINUATION_PROVIDER_UNSUPPORTED", "当前仅支持第三方履约续发批次");
        }
        BigDecimal instructed = new BigDecimal(command.instructedQuantity());
        BigDecimal pending = pendingCreatedQuantity(fulfillmentId);
        BigDecimal remaining = state.requested().subtract(state.shipped()).subtract(state.cancelled()).subtract(pending);
        if (instructed.compareTo(remaining) > 0) {
            throw BusinessException.unprocessable(
                    "CONTINUATION_QUANTITY_EXCEEDS_REMAINING", "续发数量不得超过尚未发货、取消或已指令的剩余数量");
        }
        int updated = jdbc.update(
                "UPDATE app.fulfillments SET lock_version=lock_version+1 WHERE id=? AND lock_version=?",
                fulfillmentId,
                command.expectedVersion());
        if (updated != 1) {
            throw BusinessException.conflict("VERSION_CONFLICT", "履约任务已更新，请刷新后重试");
        }

        ContinuationExportGenerator.ContinuationExport generated = providerFiles.generateContinuation(
                fulfillmentId, instructed, command.remark(), context.operator());
        events.append(
                state.orderId(),
                "SHIPMENT_CREATED",
                state.orderLineId(),
                fulfillmentId,
                generated.shipmentId(),
                null,
                DataScope.BUSINESS,
                Map.of(
                        "shipment_sequence", generated.shipmentSequence(),
                        "fulfillment_export_id", String.valueOf(generated.fulfillmentExportId()),
                        "instructed_quantity", instructed.toPlainString(),
                        "remark", command.remark()),
                context.operator());
        versions.append(
                state.orderId(), null, "创建第三方履约续发批次", context.operator(), snapshot(state.orderId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("fulfillment_id", String.valueOf(fulfillmentId));
        response.put("shipment_id", String.valueOf(generated.shipmentId()));
        response.put("shipment_sequence", generated.shipmentSequence());
        response.put("fulfillment_export_id", String.valueOf(generated.fulfillmentExportId()));
        response.put("instructed_quantity", instructed.toPlainString());
        response.put("fulfillment_version", command.expectedVersion() + 1);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(state.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation("continuation_export.create")
                .requestPayload(command).responsePayload(response).httpStatus(201)
                .businessCode("CONTINUATION_EXPORT_CREATED"));
        return response;
    }

    private Context lockContext(long fulfillmentId) {
        Context value = jdbc.query(
                """
                SELECT f.lock_version, f.requested_quantity, f.cumulative_shipped_quantity,
                       f.cancelled_quantity, f.shipping_progress, ol.id order_line_id, o.id order_id,
                       fp.provider_type
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                WHERE f.id=? FOR UPDATE OF f
                """,
                rs -> rs.next() ? new Context(
                        rs.getLong("lock_version"), rs.getBigDecimal("requested_quantity"),
                        rs.getBigDecimal("cumulative_shipped_quantity"), rs.getBigDecimal("cancelled_quantity"),
                        rs.getString("shipping_progress"), rs.getLong("order_line_id"),
                        rs.getLong("order_id"), rs.getString("provider_type")) : null,
                fulfillmentId);
        if (value == null) {
            throw BusinessException.notFound("履约任务不存在");
        }
        return value;
    }

    private BigDecimal pendingCreatedQuantity(long fulfillmentId) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(sum(si.instructed_quantity),0)
                FROM app.shipment_items si JOIN app.shipments s ON s.id=si.shipment_id
                WHERE si.fulfillment_id=? AND si.shipped_quantity IS NULL AND s.shipment_status='CREATED'
                """,
                BigDecimal.class,
                fulfillmentId);
    }

    private Map<String, Object> snapshot(long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", jdbc.queryForMap("SELECT * FROM app.orders WHERE id=?", orderId));
        result.put("lines", jdbc.queryForList("SELECT * FROM app.order_lines WHERE order_id=? ORDER BY line_no", orderId));
        result.put("fulfillments", jdbc.queryForList(
                "SELECT f.* FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?",
                orderId));
        result.put("shipments", jdbc.queryForList(
                "SELECT * FROM app.shipments WHERE order_id=? ORDER BY shipment_sequence", orderId));
        return result;
    }

    private record Context(
            long version,
            BigDecimal requested,
            BigDecimal shipped,
            BigDecimal cancelled,
            String shippingProgress,
            long orderLineId,
            long orderId,
            String providerType) {
    }
}
