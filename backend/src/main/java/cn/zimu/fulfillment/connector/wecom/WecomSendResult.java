package cn.zimu.fulfillment.connector.wecom;

import java.time.Instant;

/** 企业微信主动消息的可判定结果；SUCCESS 的时间是服务端 ack 到达时间。 */
public record WecomSendResult(
        WecomSendStatus status,
        String requestId,
        Instant acknowledgedAt,
        Integer errorCode,
        String errorMessage,
        boolean retryable) {

    static WecomSendResult success(String requestId, Instant acknowledgedAt) {
        return new WecomSendResult(WecomSendStatus.SUCCESS, requestId, acknowledgedAt, null, null, false);
    }

    static WecomSendResult failed(String requestId, Integer errorCode, String errorMessage, boolean retryable) {
        return new WecomSendResult(
                WecomSendStatus.FAILED, requestId, null, errorCode, errorMessage, retryable);
    }

    static WecomSendResult timeout(String requestId) {
        return new WecomSendResult(
                WecomSendStatus.TIMEOUT, requestId, null, null, "ACK_TIMEOUT", true);
    }
}
