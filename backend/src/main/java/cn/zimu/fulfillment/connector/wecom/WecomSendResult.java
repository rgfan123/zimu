package cn.zimu.fulfillment.connector.wecom;

import java.time.Instant;
import java.util.Objects;

/**
 * 企业微信主动消息的可判定结果；SUCCESS 的时间是服务端 ack 到达时间。
 *
 * <p>{@code retryable=true} 只表示帧尚未提交（例如连接未就绪或本地背压），可安全重试。ack
 * 超时、提交后断线和传输失败都可能已经送达，必须先对账，禁止盲目重发。
 */
public record WecomSendResult(
        WecomSendStatus status,
        String requestId,
        Instant acknowledgedAt,
        Integer errorCode,
        String errorMessage,
        boolean retryable) {

    public WecomSendResult {
        Objects.requireNonNull(status, "status");
        switch (status) {
            case SUCCESS -> {
                requireRequestId(requestId);
                Objects.requireNonNull(acknowledgedAt, "acknowledgedAt");
                if (errorCode != null || errorMessage != null || retryable) {
                    throw new IllegalArgumentException("SUCCESS cannot carry an error or be retryable");
                }
            }
            case TIMEOUT -> {
                requireRequestId(requestId);
                if (acknowledgedAt != null || errorCode != null || !"ACK_TIMEOUT".equals(errorMessage) || retryable) {
                    throw new IllegalArgumentException("TIMEOUT must represent an unknown, non-retryable delivery");
                }
            }
            case FAILED -> {
                if (acknowledgedAt != null || errorMessage == null || errorMessage.isBlank()) {
                    throw new IllegalArgumentException("FAILED must carry an error and no acknowledgement time");
                }
            }
        }
    }

    static WecomSendResult success(String requestId, Instant acknowledgedAt) {
        return new WecomSendResult(WecomSendStatus.SUCCESS, requestId, acknowledgedAt, null, null, false);
    }

    static WecomSendResult failed(String requestId, Integer errorCode, String errorMessage, boolean retryable) {
        return new WecomSendResult(
                WecomSendStatus.FAILED, requestId, null, errorCode, errorMessage, retryable);
    }

    static WecomSendResult timeout(String requestId) {
        return new WecomSendResult(
                WecomSendStatus.TIMEOUT, requestId, null, null, "ACK_TIMEOUT", false);
    }

    private static void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
    }
}
