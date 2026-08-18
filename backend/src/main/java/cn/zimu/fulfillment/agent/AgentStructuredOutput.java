package cn.zimu.fulfillment.agent;

/**
 * 基础 Agent 结构化输出 schema（agent-decision-layer 01）。
 *
 * <p>AiServices 以 JSON Schema 约束模型输出并反序列化为本记录；任何不满足该结构的模型响应
 * 都会在运行时被拒绝（{@code AGENT_OUTPUT_INVALID}），从而保证 Agent 输出可 schema 校验。
 * 业务 Agent（02 票）在各自注册表/提示词中定义更丰富的记录，本记录只作为运行时基础的最小 schema。
 */
public record AgentStructuredOutput(String summary, String reasoning) {}
