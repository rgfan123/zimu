package cn.zimu.fulfillment.common.audit;

import java.time.Instant;
import java.util.Map;

/** 审计日志 DTO；列表查询不返回请求/响应体。 */
public record AuditLogDto(
        String id,
        String dataScope,
        String operator,
        String actorType,
        String service,
        String operation,
        String orderId,
        String requestId,
        String traceId,
        Map<String, Object> requestPayload,
        Map<String, Object> responsePayload,
        Integer httpStatus,
        String businessCode,
        Integer latencyMs,
        Instant createdAt) {

    static AuditLogDto from(AuditLog log, boolean withPayloads) {
        return new AuditLogDto(
                String.valueOf(log.getId()),
                log.getDataScope().name(),
                log.getOperator(),
                log.getActorType().name(),
                log.getService(),
                log.getOperation(),
                log.getOrderId() == null ? null : String.valueOf(log.getOrderId()),
                log.getRequestId(),
                log.getTraceId(),
                withPayloads ? redact(log.getRequestPayload()) : null,
                withPayloads ? redact(log.getResponsePayload()) : null,
                log.getHttpStatus(),
                log.getBusinessCode(),
                log.getLatencyMs(),
                log.getCreatedAt());
    }

    private static Map<String, Object> redact(Map<String, Object> payload) {
        return payload == null ? null : SecretRedactor.redact(payload);
    }
}
