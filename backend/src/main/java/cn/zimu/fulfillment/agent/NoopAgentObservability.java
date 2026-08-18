package cn.zimu.fulfillment.agent;

/**
 * 可观测性关闭时的空实现（agent-decision-layer 08）：所有回调为 no-op，
 * 业务与审计路径零影响；由 {@link AgentObservabilityConfiguration} 在
 * {@code app.agent.observability.enabled=false} 时注册。
 */
public final class NoopAgentObservability implements AgentObservability {

    public static final NoopAgentObservability INSTANCE = new NoopAgentObservability();

    private NoopAgentObservability() {}

    @Override
    public void runStarted(Start start) {}

    @Override
    public void runFinished(Finish finish) {}

    @Override
    public void recordModelTokens(String runId, TokenUsage tokens) {}

    @Override
    public void toolCallFinished(ToolCall call) {}
}
