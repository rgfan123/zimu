package cn.zimu.fulfillment.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 数据查询 Agent（06 票）的 AiServices 结构化输出网关：把任意 systemPrompt/userInput 约束为
 * {@link DataQueryAgentOutput} 记录返回。
 *
 * <p>模式与 {@link AgentGateway} 一致（每次运行按工具绑定动态构建），仅输出 schema 更丰富：
 * 业务 Agent 在 02 票 AgentStructuredOutput 的最小 schema 之上定义自己的结构化记录。
 * 模型输出不满足记录 schema 时 AiServices 抛 {@code OutputParsingException}，
 * 由 {@link DataQueryAgentService} 映射为 AGENT_OUTPUT_INVALID。
 */
interface DataQueryAgentGateway {

    @SystemMessage("{{systemPrompt}}")
    DataQueryAgentOutput run(@V("systemPrompt") String systemPrompt, @UserMessage String userInput);
}
