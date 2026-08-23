package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 长连接业务帧分发钩子：连接层收到 {@code aibot_msg_callback} / {@code aibot_event_callback}
 * 时调用，由后续接收链路实现（消息进 {@code MessageSubmissionService}、事件留档）。默认空实现。
 *
 * <p>帧为原始 JSON（含 headers.req_id 等协议字段），业务分发负责幂等与过滤。</p>
 */
@FunctionalInterface
public interface WecomFrameHandler {

    /** 空实现：未接入接收链路时安全丢弃。 */
    WecomFrameHandler EMPTY = (cmd, frame) -> {};

    /**
     * 分发一条回调帧。
     *
     * @param cmd 帧类型：aibot_msg_callback / aibot_event_callback
     * @param frame 完整原始帧（顶层含 cmd / req_id / body）
     */
    void onFrame(String cmd, JsonNode frame);

    /**
     * 分发一条带 listener 到达时刻的回调帧。实现方可用单调时钟计算协议截止时间；默认保留
     * 两参数 SAM，已有 lambda 与非时限 handler 无需改动。
     */
    default void onFrame(String cmd, JsonNode frame, long receivedNanos) {
        onFrame(cmd, frame);
    }
}
