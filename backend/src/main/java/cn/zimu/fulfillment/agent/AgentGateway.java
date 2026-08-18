package cn.zimu.fulfillment.agent;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * AiServices 结构化输出网关：把任意 systemPrompt/userInput 约束为 {@link AgentStructuredOutput}
 * 记录返回，并随 {@link Result} 带回 token 用量（08 票，经
 * {@link LangChain4jAgentRuntime} 落可观测记录）。模型输出不满足记录 schema 时
 * AiServices 抛 {@code OutputParsingException}，由 {@link LangChain4jAgentRuntime}
 * 映射为 AGENT_OUTPUT_INVALID。
 */
interface AgentGateway {

    @SystemMessage("{{systemPrompt}}")
    Result<AgentStructuredOutput> run(
            @V("systemPrompt") String systemPrompt, @UserMessage String userInput);
}
