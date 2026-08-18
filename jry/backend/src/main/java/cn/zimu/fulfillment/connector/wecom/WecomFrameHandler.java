package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 长连接业务帧分发钩子：连接层收到 {@code aibot_msg_callback} / {@code aibot_event_callback}
 * 时调用，由后续接收链路实现（消息进 {@code MessageSubmissionService}、事件留档）。默认空实现。
 *
 * <p>帧为原始 JSON（含 headers.req_id 等协议字段），业务分发负责幂等与过滤。</p>
 */
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
}
