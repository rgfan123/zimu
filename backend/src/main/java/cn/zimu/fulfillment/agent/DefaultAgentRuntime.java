package cn.zimu.fulfillment.agent;

/**
 * 默认 Agent 运行时：模型未配置时的诚实兜底（fail-closed）。
 *
 * <p>没有真实模型配置时，本实现不猜测任何 Agent 输出，一律返回
 * {@code AGENT_MODEL_NOT_CONFIGURED}（三元组 none/none/none），不连接任何模型。真实模型接入
 * 通过实现 {@link AgentRuntime} 替换本 Bean（配置 {@code app.agent.base-url} 时
 * {@link LangChain4jAgentRuntime} 注册，两者互斥），本类不包含任何业务规则。
 */
public class DefaultAgentRuntime implements AgentRuntime {

    private final AgentModelProperties properties;

    public DefaultAgentRuntime(AgentModelProperties properties) {
        this.properties = properties;
    }

    @Override
    public AgentRunResult run(AgentTaskRequest request) {
        if (!properties.configured()) {
            return AgentRunResult.failClosed(AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        throw new IllegalStateException(
                "app.agent 已配置但未实现模型客户端：请实现 AgentRuntime 替换默认 Bean");
    }
}
