package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 京东出库单取消（issue #213 首切片，2026-09-01 重复发货事故催生）。
 *
 * <p>只允许对本系统提交（sync_status=SUBMITTED）的出库单发起取消；京东确认取消后
 * **删除** shipment_jd_outbounds 行——该表的状态 CHECK（V9）没有 CANCELLED 终态且
 * 迁移编号冻结中，保留一行「已提交」是谎言，删除后 Shipment 回到未提交态可重新提交，
 * 完整过程由审计日志留痕。运单/发货单状态的回退不在本切片（无合法回退迁移），由
 * 运维按需人工处理。
 */
@Service
public class ShipmentJdOutboundCancelService {

    public static final String SCOPE = "shipment.jd_outbound.cancel";

    private final JdbcTemplate jdbc;
    private final JDWarehouseService jdWarehouse;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;
    private final Set<String> authorizedOperators;

    public ShipmentJdOutboundCancelService(
            JdbcTemplate jdbc,
            JDWarehouseService jdWarehouse,
            IdempotencyService idempotency,
            AuditLogService audits,
            @Value("${app.jd.outbound-authorized-operators:}") String authorizedOperators) {
        this.jdbc = jdbc;
        this.jdWarehouse = jdWarehouse;
        this.idempotency = idempotency;
        this.audits = audits;
        this.authorizedOperators = Arrays.stream(authorizedOperators.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    public IdempotentResult<Map<String, Object>> cancel(
            long shipmentId, String orderType, String idempotencyKey, CommandContext context) {
        requireAuthorized(context);
        OutboundRow row = requireSubmittedRow(shipmentId);
        // 京东 cancelOrder 要求 orderType；销售出库单默认 2，留可选入参兜住京东侧字典口径变化。
        String effectiveOrderType = orderType == null || orderType.isBlank() ? "2" : orderType.trim();
        Map<String, Object> payload = Map.of(
                "shipment_id", shipmentId,
                "erp_delivery_no", row.erpDeliveryNo(),
                "jd_delivery_no", row.jdDeliveryNo(),
                "order_type", effectiveOrderType);
        return idempotency.execute(SCOPE, idempotencyKey, payload, 200, () -> {
            Map<String, Object> command = new LinkedHashMap<>();
            command.put("erpOrderNo", row.erpDeliveryNo());
            command.put("orderNo", row.jdDeliveryNo());
            command.put("orderType", effectiveOrderType);
            JdResult result = jdWarehouse.cancelOutboundOrder(command);
            if (!result.success()) {
                audit(shipmentId, row, context, 502, "JD_OUTBOUND_CANCEL_REJECTED",
                        result.businessCode() + ":" + result.message());
                throw new BusinessException(
                        502,
                        "JD_OUTBOUND_CANCEL_REJECTED",
                        "京东取消出库单失败（" + result.businessCode() + "）：" + result.message(),
                        java.util.List.of(),
                        Map.of());
            }
            int deleted = jdbc.update(
                    "DELETE FROM app.shipment_jd_outbounds WHERE id=? AND shipment_id=?",
                    row.id(), shipmentId);
            audit(shipmentId, row, context, 200, "JD_OUTBOUND_CANCELLED",
                    "京东出库单已取消，提交记录删除 " + deleted + " 行");
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("shipment_id", String.valueOf(shipmentId));
            response.put("erp_delivery_no", row.erpDeliveryNo());
            response.put("jd_delivery_no", row.jdDeliveryNo());
            response.put("cancelled", true);
            response.put("submission_record_deleted", deleted == 1);
            return Map.copyOf(response);
        });
    }

    private void requireAuthorized(CommandContext context) {
        if (context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())
                && authorizedOperators.contains(context.authenticatedOperator())) {
            return;
        }
        throw new BusinessException(
                403, "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED", "当前操作人未获得京东出库取消授权");
    }

    private OutboundRow requireSubmittedRow(long shipmentId) {
        return jdbc.query(
                        """
                        SELECT id, erp_delivery_no, jd_delivery_no
                        FROM app.shipment_jd_outbounds
                        WHERE shipment_id=? AND sync_status='SUBMITTED'
                        """,
                        (rs, rowNum) -> new OutboundRow(
                                rs.getLong("id"),
                                rs.getString("erp_delivery_no"),
                                rs.getString("jd_delivery_no")),
                        shipmentId)
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.conflict(
                        "JD_OUTBOUND_NOT_SUBMITTED", "该发货批次没有已提交的京东出库单，无可取消"));
    }

    private void audit(
            long shipmentId, OutboundRow row, CommandContext context,
            int status, String code, String message) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(shipmentId),
                        "erp_delivery_no", row.erpDeliveryNo(),
                        "jd_delivery_no", row.jdDeliveryNo()))
                .responsePayload(Map.of("message", message))
                .httpStatus(status)
                .businessCode(code));
    }

    private record OutboundRow(long id, String erpDeliveryNo, String jdDeliveryNo) {}
}
