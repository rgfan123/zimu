package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundPreviewSnapshot.Blocker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 京东出库审计单元：集中承载出库流程的审计日志、对账查询留痕与预览阻断 ReviewCase 同步。
 *
 * <p>被拒绝的写意图、幂等重放/冲突与对账查询事实都必须在业务意图事务回滚/提交之前以独立
 * 事务（REQUIRES_NEW）保留可审计事实；随业务事务归档的审计（如意图落盘、预览阻断 case）则
 * 复用调用方事务。本单元不构造请求、不调用京东、不编排提交阶段。
 */
@Service
public class ShipmentJdOutboundAuditService {

    public static final String SCOPE = "shipment.jd_outbound.submit";
    public static final String PREVIEW_SCOPE = "shipment.jd_outbound.preview";
    public static final String PREVIEW_BLOCKED_REASON = "JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED";
    public static final String RECONCILE_SCOPE = "shipment.jd_outbound.reconcile";

    private final AuditLogService audits;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNew;

    public ShipmentJdOutboundAuditService(
            AuditLogService audits,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.audits = audits;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 拒绝发生在业务意图事务回滚之前，以独立事务保留可审计事实。 */
    public void auditRejectedSubmit(
            long shipmentId,
            Long orderId,
            CommandContext context,
            int httpStatus,
            String businessCode,
            String message,
            List<String> blockerCodes) {
        requiresNew.executeWithoutResult(status -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("business_code", businessCode);
            response.put("message", message);
            response.put("blocker_codes", blockerCodes);
            AuditLogService.AuditCommand audit = new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId()).traceId(context.traceId()).operator(auditOperator(context))
                    .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE)
                    .requestPayload(Map.of("shipment_id", String.valueOf(shipmentId)))
                    .responsePayload(response).httpStatus(httpStatus).businessCode(businessCode);
            if (orderId != null) {
                audit.orderId(orderId);
            }
            audits.record(audit);
        });
    }

    /** 幂等注册表在业务 callback 前返回时，也保留本次操作人的重放/冲突事实。 */
    public void auditIdempotencyOutcome(
            ShipmentJdOutboundPreviewSnapshot preview,
            CommandContext context,
            int httpStatus,
            String businessCode,
            String message,
            String clientMode) {
        requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(preview.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(auditOperator(context))
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(preview.shipmentId()),
                        "erp_delivery_no", preview.erpDeliveryNo(),
                        "request_hash", preview.requestHash(),
                        "client_mode", clientMode))
                .responsePayload(Map.of("business_code", businessCode, "message", message))
                .httpStatus(httpStatus)
                .businessCode(businessCode)));
    }

    /** 对账查询是独立可追溯事实；只留稳定参考和结果摘要，不留京东返回原文。 */
    public void recordReconciliationQuery(
            long shipmentId,
            long orderId,
            CommandContext context,
            Map<String, Object> request,
            JdResult result,
            boolean deliveryNoPresent,
            boolean erpDeliveryNoMatches) {
        requiresNew.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE app.shipment_jd_outbounds SET last_query_at=CURRENT_TIMESTAMP, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE shipment_id=?",
                    shipmentId);
            String businessCode = result == null || ShipmentJdOutboundPreparer.text(result.businessCode()) == null
                    ? "QUERY_FAILED" : ShipmentJdOutboundPreparer.text(result.businessCode());
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS).orderId(orderId)
                    .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                    .actorType(AuditActorType.SYSTEM).service("fulfillment").operation(RECONCILE_SCOPE)
                    .requestPayload(Map.of(
                            "shipment_id", String.valueOf(shipmentId),
                            "erp_delivery_no", request.get("erpDeliveryNo")))
                    .responsePayload(Map.of(
                            "success", result != null && result.success(),
                            "business_code", businessCode,
                            "delivery_no_present", deliveryNoPresent,
                            "erp_delivery_no_matches", erpDeliveryNoMatches))
                    .httpStatus(result != null && result.success() ? 200 : 502)
                    .businessCode(businessCode));
        });
    }

    /** 提交意图落盘（独立事务内）随同一事务记录意图审计；由编排单元在意图持久化时调用。 */
    public void recordSubmitIntent(
            ShipmentJdOutboundPreviewSnapshot current, CommandContext context, int attempt) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(current.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE + ".intent")
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(current.shipmentId()),
                        "erp_delivery_no", current.erpDeliveryNo(),
                        "request_hash", current.requestHash()))
                .responsePayload(Map.of(
                        "sync_status", ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTING, "retry_count", attempt))
                .httpStatus(202).businessCode("JD_SHIPMENT_OUTBOUND_INTENT_RECORDED"));
    }

    /** HTTP 预览把瞬时 blocker 同步为一个可处理且可复用的 Shipment 级 ReviewCase。 */
    public void reconcilePreviewReviewCase(ShipmentJdOutboundPreviewSnapshot preview, String operator) {
        // SKU 映射门禁由 Ticket 03 的 JD_SKU_MAPPING_BLOCKED case 独占维护；
        // 此 case 只承载地址、履约配置、数量换算等预览阻断，避免一个原因两张票。
        List<Blocker> reviewBlockers = preview.blockers().stream()
                .filter(blocker -> !"JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING".equals(blocker.code()))
                .toList();
        if (reviewBlockers.isEmpty()) {
            jdbc.update(
                    """
                    UPDATE app.review_cases
                    SET status='RESOLVED',
                        resolution=?::jsonb,
                        resolution_version=resolution_version+1,
                        resolved_by=?, resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                    WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                    """,
                    json(Map.of(
                            "reason", "JD outbound preview address/configuration/conversion blockers cleared",
                            "request_hash", preview.requestHash())),
                    operator, preview.shipmentId(), PREVIEW_BLOCKED_REASON);
            return;
        }

        String detail = json(Map.of(
                "message", "京东出库请求预览存在阻断项，请修正后重新预览",
                "request_hash", preview.requestHash(),
                "blockers", reviewBlockers.stream().map(ShipmentJdOutboundPreparer::blockerMap).toList()));
        List<Long> existing = jdbc.queryForList(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                FOR UPDATE
                """,
                Long.class, preview.shipmentId(), PREVIEW_BLOCKED_REASON);
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, shipment_id, detail)
                    VALUES (?, 'JD_OUTBOUND_PREVIEW', 'OPEN', 'FULFILLMENT_OPS', ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                    """,
                    "RC-JD-PREVIEW-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    PREVIEW_BLOCKED_REASON, preview.orderId(), preview.shipmentId(), detail);
        } else {
            jdbc.update(
                    "UPDATE app.review_cases SET detail=?::jsonb, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    detail, existing.getFirst());
        }
    }

    private static String auditOperator(CommandContext context) {
        return context.authenticatedOperator() == null
                ? "unauthenticated"
                : context.authenticatedOperator();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
