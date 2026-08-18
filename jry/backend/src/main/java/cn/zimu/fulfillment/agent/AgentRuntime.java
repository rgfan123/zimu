package cn.zimu.fulfillment.agent;

/**
 * Agent 运行时唯一接缝。真实模型集成以实现本接口替换默认 Bean（配置 {@code app.agent.base-url}
 * 时注册 {@link LangChain4jAgentRuntime}，与 {@link DefaultAgentRuntime} 互斥，见
 * {@link AgentRuntimeConfiguration}）；更换供应商/模型只改配置，不修改 Agent 业务代码。
 */
public interface AgentRuntime {

    AgentRunResult run(AgentTaskRequest request);
}
