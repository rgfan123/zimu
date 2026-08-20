package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * 上传本地文件为企微临时素材（#84 seam）。前置校验失败抛
     * {@link WecomUploadValidationException}（中文可读、不创建 upload_id、不写审计）；
     * 其余结局经 {@link WecomUploadResult} 返回。
     *
     * <p>审计纪律：只记录 media_type/文件大小/稳定错误码/步骤/req_id/upload_id 等安全元数据，
     * 不记录文件内容、base64 或 media_id；media_id 也绝不进入普通日志。
     */
    public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
        Instant startedAt = Instant.now();
        WecomUploadResult result = transport.upload(file, filename, type);
        audits.record(new AuditLogService.AuditCommand()
                .requestId(result.requestId())
                .operator("system")
                .actorType(AuditActorType.SYSTEM)
                .service("wecom-outbound")
                .operation("wecom.media.upload")
                .requestPayload(auditUploadRequest(file, type))
                .responsePayload(auditUploadResponse(result))
                .businessCode("WECOM_UPLOAD_" + result.status().name())
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

    /** 上传审计请求侧：只记录类型与文件大小，不记录文件名/内容。 */
    private static Map<String, Object> auditUploadRequest(Path file, WecomMediaType type) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("media_type", type.protocolValue());
        try {
            request.put("file_bytes", Files.size(file));
        } catch (IOException ignored) {
            // 文件在审计前已被移动/删除：只记录类型，不阻塞审计
        }
        return request;
    }

    /** 上传审计响应侧：稳定状态码/步骤/req_id/upload_id 等安全元数据；media_id 一律不落审计。 */
    private static Map<String, Object> auditUploadResponse(WecomUploadResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status().name());
        response.put("step", result.step());
        response.put("retryable", result.retryable());
        if (result.uploadId() != null) {
            response.put("upload_id", result.uploadId());
        }
        if (result.errorCode() != null) {
            response.put("error_code", result.errorCode());
        }
        if (result.errorMessage() != null) {
            response.put("error", result.errorMessage());
        }
        if (result.acknowledgedAt() != null) {
            response.put("acknowledged_at", result.acknowledgedAt().toString());
        }
        if (result.createdAt() != null) {
            response.put("media_created_at", result.createdAt().toString());
        }
        return response;
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
