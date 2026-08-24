package cn.zimu.fulfillment.agent.dto;

/**
 * 工具调用序列节点（12 票；agent-console 设计 P4 Timeline，支撑 13 票 202 轮询展示）。
 *
 * <p>每步：序号、工具名、耗时、状态（SUCCESS/FAILED）与参数/结果摘要——
 * 摘要为落库时经 SecretRedactor 脱敏与截断后的投影（{@code app.agent_tool_calls}），
 * 敏感原文不落库故不可能出现在本投影中。
 */
public record RunToolCallItem(
        int sequenceNo,
        String toolName,
        String argsSummary,
        String resultSummary,
        Long latencyMs,
        String status) {}
