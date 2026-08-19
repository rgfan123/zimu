package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 数据查询 Agent（06 票；meta-agent-platform-impl 05 收敛为门面薄包装）一次运行的结果。
 *
 * <p>{@code error} 非空表示失败（只取 {@link AgentFailureCode} 稳定枚举，此时
 * {@code output} 为 null）；{@code status} 为本次运行的业务状态（SUCCESS / NEEDS_INPUT /
 * REJECTED / 失败码——05 收敛后 CLARIFICATION/PII_GUARDED 自定状态消除，由
 * {@link AgentOutcome} 语义承载）；{@code toolCalls} 在收敛后恒为空——工具调用序列的
 * 规范化可观测记录在 {@code app.agent_tool_calls}（08 票），run_id 关联不变；
 * {@code runId}/{@code latencyMs} 取自门面控制面富化。
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
