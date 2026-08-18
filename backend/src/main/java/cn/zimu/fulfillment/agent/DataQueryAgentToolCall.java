package cn.zimu.fulfillment.agent;

import java.util.Map;

/**
 * 数据查询 Agent（06 票）一次工具调用的记录（审计 / 评测可观测）。
 *
 * <p>{@code guarded=true} 表示该次调用被 {@link DataQueryAgentGuard} 的占位/歧义
 * 参数兜底拦截（未执行），{@code guardReason} 为拦截原因。
 */
public record DataQueryAgentToolCall(
        String tool,
        Map<String, Object> arguments,
        boolean guarded,
        String guardReason) {

    public DataQueryAgentToolCall {
        tool = tool == null ? "" : tool.strip();
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
