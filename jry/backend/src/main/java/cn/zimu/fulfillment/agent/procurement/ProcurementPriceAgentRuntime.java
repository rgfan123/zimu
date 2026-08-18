package cn.zimu.fulfillment.agent.procurement;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.output.OutputParsingException;
import java.time.Duration;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentTaskRequest;

/**
 * 采购比价 Agent 的 LangChain4j 运行时（agent-decision-layer 05）：OpenAI 兼容 Chat
 * Completions（langchain4j-open-ai）对接 DeepSeek 等现有供应商，结构化输出经 AiServices
 * 以 {@link ProcurementPriceRecommendation} 记录约束与校验。
 *
 * <p>与 01 票 {@code LangChain4jAgentRuntime} 同构：配置不完整（api-key/provider/model
 * 缺任一）时不构建模型客户端直接 fail-closed，不连接任何模型；模型输出经
 * {@link ProcurementPricePolicy#enforce} 确定性归一化（无候选/无价格/字段缺失/低置信度 →
 * requires_human=true）；错误分类一致——HTTP 失败/超时/网络错误 → AGENT_MODEL_CALL_FAILED，
 * 模型输出无法解析为 schema → AGENT_OUTPUT_INVALID。api-key 只经环境变量注入，
 * 绝不进入异常消息/结果/日志。
 */
public class ProcurementPriceAgentRuntime implements ProcurementPriceRuntime {

    /** 基础运行时固定提示词版本；业务提示词版本由 Agent 注册表定义（procurement-price-v1）。 */
    public static final String PROMPT_VERSION = "agent-foundation-v1";

    private final String provider;
    private final String model;
    private final boolean configured;
    private final AgentModelProperties properties;
    private final ProcurementPriceGateway gateway;

    public ProcurementPriceAgentRuntime(AgentModelProperties properties) {
        this.properties = properties;
        this.provider = properties.getProvider();
        this.model = properties.getModel();
        this.configured = properties.configured();
        this.gateway = configured
                ? AiServices.builder(ProcurementPriceGateway.class)
                        .chatModel(chatModel(properties))
                        .build()
                : null;
    }

    @Override
    public ProcurementPriceRunResult run(AgentTaskRequest request) {
        if (!configured) {
            return ProcurementPriceRunResult.failClosed(AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        if (request == null || request.userInput() == null || request.userInput().isBlank()) {
            throw new IllegalArgumentException("AgentTaskRequest.userInput 不能为空");
        }
        String systemPrompt = request.systemPrompt() == null ? "" : request.systemPrompt().strip();
        try {
            ProcurementPriceRecommendation recommendation = runWithTools(systemPrompt, request.userInput(), request.tools());
            if (recommendation == null) {
                return failure(AgentFailureCode.AGENT_OUTPUT_INVALID);
            }
            recommendation = ProcurementPricePolicy.enforce(recommendation);
            if (recommendation == null) {
                return failure(AgentFailureCode.AGENT_OUTPUT_INVALID);
            }
            return new ProcurementPriceRunResult(recommendation, provider, model, PROMPT_VERSION, null);
        } catch (OutputParsingException ex) {
            // 模型输出不满足 schema：稳定码收口，不重试，异常细节不对外
            return failure(AgentFailureCode.AGENT_OUTPUT_INVALID);
        } catch (RuntimeException ex) {
            // 请求组装/HTTP/网络等意外失败同样走稳定码，不把异常细节带进结果
            return failure(AgentFailureCode.AGENT_MODEL_CALL_FAILED);
        }
    }

    private ProcurementPriceRecommendation runWithTools(
            String systemPrompt, String userInput, cn.zimu.fulfillment.agent.AgentToolBinding binding) {
        if (binding == null || binding.isEmpty()) {
            return gateway.compare(systemPrompt, userInput);
        }
        ProcurementPriceGateway services = AiServices.builder(ProcurementPriceGateway.class)
                .chatModel(chatModel(properties))
                .tools(binding.tools())
                .build();
        return services.compare(systemPrompt, userInput);
    }

    private ProcurementPriceRunResult failure(AgentFailureCode code) {
        return new ProcurementPriceRunResult(null, provider, model, PROMPT_VERSION, code.name());
    }

    private static ChatModel chatModel(AgentModelProperties properties) {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .logRequests(false)
                .build();
    }
}
