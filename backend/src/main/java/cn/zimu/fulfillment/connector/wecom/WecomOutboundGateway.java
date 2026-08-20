package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 业务调用方使用的企业微信主动消息深模块。 */
@Component
public class WecomOutboundGateway {

    private final WecomOutboundTransport transport;
    private final AuditLogService audits;

    WecomOutboundGateway(WecomOutboundTransport transport, AuditLogService audits) {
        this.transport = transport;
        this.audits = audits;
    }

    public WecomSendResult send(WecomOutboundMessage message) {
        Instant startedAt = Instant.now();
        WecomSendResult result = transport.send(message);
        audits.record(new AuditLogService.AuditCommand()
                .requestId(result.requestId())
                .operator("system")
                .actorType(AuditActorType.SYSTEM)
                .service("wecom-outbound")
                .operation("wecom.message.send")
                .requestPayload(auditRequest(message))
                .responsePayload(auditResponse(result))
                .businessCode("WECOM_SEND_" + result.status().name())
                .latencyMs(toLatencyMillis(startedAt)));
        return result;
    }

    private static Map<String, Object> auditRequest(WecomOutboundMessage message) {
        byte[] content = message.content().getBytes(StandardCharsets.UTF_8);
        return Map.of(
                "chat_id", message.chatId(),
                "message_type", message.type().protocolValue(),
                "content_bytes", content.length,
                "content_sha256", sha256(content));
    }

    private static Map<String, Object> auditResponse(WecomSendResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("retryable", result.retryable());
        if (result.acknowledgedAt() != null) {
            response.put("acknowledged_at", result.acknowledgedAt().toString());
        }
        if (result.errorCode() != null) {
            response.put("error_code", result.errorCode());
        }
        if (result.errorMessage() != null) {
            response.put("error", result.errorMessage());
        }
        return response;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static int toLatencyMillis(Instant startedAt) {
        long millis = Duration.between(startedAt, Instant.now()).toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, millis));
    }
}
