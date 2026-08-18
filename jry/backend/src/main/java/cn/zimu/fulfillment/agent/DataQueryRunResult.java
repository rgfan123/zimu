package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 数据查询 Agent（06 票）一次运行的结果：结构化输出 + 稳定失败码 + 工具调用序列。
 *
 * <p>{@code error} 非空表示运行失败（只取 {@link AgentFailureCode} 稳定枚举，此时
 * {@code output} 为 null）；{@code status} 为本次运行的审计状态
 * （SUCCESS / CLARIFICATION / PII_GUARDED / 失败码）；{@code toolCalls} 为实际发生的
 * 工具调用序列（含参数与占位拦截标记），随 AGENT 审计落盘。
 */
public record DataQueryRunResult(
        DataQueryAgentOutput output,
        String error,
        String runId,
        String status,
        List<DataQueryAgentToolCall> toolCalls,
        long latencyMs) {

    public DataQueryRunResult {
        runId = runId == null ? "" : runId;
        status = status == null ? "" : status;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
