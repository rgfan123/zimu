package cn.zimu.fulfillment.agent.procurement;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 采购比价 Agent 的 AiServices 结构化输出网关（agent-decision-layer 05）：把任意
 * systemPrompt/userInput 约束为 {@link ProcurementPriceRecommendation} 记录返回。
 * 模型输出不满足记录 schema 时 AiServices 抛 {@code OutputParsingException}，
 * 由 {@link ProcurementPriceAgentRuntime} 映射为 AGENT_OUTPUT_INVALID。
 */
interface ProcurementPriceGateway {

    @SystemMessage("{{systemPrompt}}")
    ProcurementPriceRecommendation compare(
            @V("systemPrompt") String systemPrompt, @UserMessage String userInput);
}
