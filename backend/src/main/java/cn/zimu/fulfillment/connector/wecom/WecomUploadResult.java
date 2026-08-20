package cn.zimu.fulfillment.connector.wecom;

import java.time.Instant;
import java.util.Objects;

/**
 * 企业微信素材上传的可判定结果（不可伪造）。
 *
 * <p>只有 {@code status=SUCCESS} 时携带 {@code mediaId}，且必带 finish ack 证据
 * （{@code acknowledgedAt} + {@code requestId}）与服务端声明的 {@code createdAt}/{@code mediaType}；
 * 任何缺失字段或证据矛盾（type 与请求不一致、created_at 非正的 Unix 秒）的 finish 应答
 * 都不会被当作成功。
 *
 * <p>{@code retryable=true} 仅表示「可安全从头重试」（重新 init，原 upload_id 最多成为 30 分钟后
 * 被清理的孤儿会话），绝不包括 finish 已提交但结局未知的情况：此时 status 为
 * {@link WecomUploadStatus#UNKNOWN}，必须人工对账。
 */
public record WecomUploadResult(
        WecomUploadStatus status,
        String mediaId,
        String mediaType,
        Instant createdAt,
        Instant acknowledgedAt,
        String uploadId,
        String requestId,
        String step,
        Integer errorCode,
        String errorMessage,
        boolean retryable) {

    public WecomUploadResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(step, "step");
        switch (status) {
            case SUCCESS -> {
                requireText(mediaId, "mediaId");
                requireText(mediaType, "mediaType");
                Objects.requireNonNull(createdAt, "createdAt");
                Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
                requireText(uploadId, "uploadId");
                requireText(requestId, "requestId");
                if (errorCode != null || errorMessage != null || retryable) {
                    throw new IllegalArgumentException("SUCCESS cannot carry an error or be retryable");
                }
            }
            case UNKNOWN -> {
                requireText(uploadId, "uploadId");
                requireText(requestId, "requestId");
                if (acknowledgedAt != null || errorCode != null || retryable) {
                    throw new IllegalArgumentException("UNKNOWN must carry no ack evidence and never be retryable");
                }
                requireText(errorMessage, "errorMessage");
            }
            case FAILED -> {
                if (acknowledgedAt != null || mediaId != null || mediaType != null || createdAt != null) {
                    throw new IllegalArgumentException("FAILED must not carry success evidence");
                }
                requireText(errorMessage, "errorMessage");
            }
        }
    }

    static WecomUploadResult success(
            String mediaId, String mediaType, Instant createdAt, String uploadId, String requestId, Instant acknowledgedAt) {
        return new WecomUploadResult(
                WecomUploadStatus.SUCCESS,
                mediaId,
                mediaType,
                createdAt,
                acknowledgedAt,
                uploadId,
                requestId,
                "FINISH",
                null,
                null,
                false);
    }

    static WecomUploadResult failed(
            Integer errorCode,
            String step,
            String errorMessage,
            boolean retryable,
            String uploadId,
            String requestId) {
        return new WecomUploadResult(
                WecomUploadStatus.FAILED,
                null,
                null,
                null,
                null,
                uploadId,
                requestId,
                step,
                errorCode,
                errorMessage,
                retryable);
    }

    /** finish 结局未知（未获 ack，或应答 errcode=0 但字段缺失）：服务端可能已生成 media_id，必须人工对账。 */
    static WecomUploadResult unknown(String uploadId, String requestId, String reason) {
        return new WecomUploadResult(
                WecomUploadStatus.UNKNOWN, null, null, null, null, uploadId, requestId, "FINISH", null, reason, false);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
