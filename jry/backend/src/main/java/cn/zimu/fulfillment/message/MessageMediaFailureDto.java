package cn.zimu.fulfillment.message;

import java.time.Instant;

/**
 * 媒体状态的最小必要投影（wecom-message-intake 12）：不含下载凭据（source_url）、
 * 原始失败原因或受控文件引用，避免泄露一次性 URL / aeskey 与敏感失败文本。
 */
public record MessageMediaFailureDto(
        String id,
        String channelMediaId,
        String mediaType,
        String downloadStatus,
        int attempts,
        Instant createdAt) {}
