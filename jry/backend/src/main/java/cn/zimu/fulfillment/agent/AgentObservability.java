package cn.zimu.fulfillment.agent;

/**
 * Agent 可观测性 provider 接缝（agent-decision-layer 08）：结构化 Agent 运行记录
 * （run/工具调用序列/token/latency/状态/业务实体关联）的只写通道。
 *
 * <p>业务代码只依赖本接口，不 import 任何第三方 SDK；一期默认实现
 * {@link JdbcAgentObservability} 落 PostgreSQL（app.agent_runs / app.agent_tool_calls），
 * 未来可加 Langfuse/OTLP 实现替换 Bean（配置见 {@link AgentObservabilityConfiguration}）。
 * 关闭开关时注入 {@link NoopAgentObservability}，业务与审计完全不受影响。
 *
 * <p>失败隔离契约：调用方（编排门面/工具桥接/运行时）一律以 try/catch 包裹回调，
 * 观测写入失败不得影响 Agent 运行结果与业务（与 {@code McpWriteTools.recordFailureAudit}
 * 的审计失败容忍语义一致）。
 *
 * <p>事件负载一律为脱敏后的摘要：输入只存 digest（{@link AgentPayloadRedactor#digest}），
 * 工具参数/结果只存经 {@link cn.zimu.fulfillment.common.audit.SecretRedactor} 投影的
 * 截断摘要，敏感原文不落库。
 */
public interface AgentObservability {

    /** 运行开始事件；实现方先落 RUNNING 行（finished_at 为空），进程中断时可检出。 */
    record Start(
            String runId,
            String threadId,
            String agentSlug,
            String agentVersion,
            String promptVersion,
            String model,
            String inputDigest,
            String businessEntityType,
            String businessEntityId) {}

    /**
     * 运行收口事件。{@code errorType} 为 null 表示成功（status=SUCCESS）；
     * 非 null 表示失败（status=FAILED，error_type=errorType，只取稳定枚举）。
     * {@code model} 为服务端 allowlist 投影后的模型名（null/空白则保留 Start 时值）。
     */
    record Finish(String runId, String errorType, long latencyMs, String model) {}

    /** 一次工具调用的脱敏摘要（args/result 均已脱敏与截断）。 */
    record ToolCall(
            String runId,
            int sequenceNo,
            String toolName,
            String argsSummary,
            String resultSummary,
            long latencyMs,
            boolean success) {}

    /** 模型 token 用量（任一字段可为 null，由 provider 决定）。 */
    record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}

    void runStarted(Start start);

    void runFinished(Finish finish);

    void recordModelTokens(String runId, TokenUsage tokens);

    void toolCallFinished(ToolCall call);

    /** 关闭开关/测试默认值：全部回调为 no-op。 */
    static AgentObservability disabled() {
        return NoopAgentObservability.INSTANCE;
    }
}
