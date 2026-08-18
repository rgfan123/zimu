package cn.zimu.fulfillment.agent;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.output.OutputParsingException;
import java.time.Duration;

/**
 * LangChain4j Agent 运行时：OpenAI 兼容 Chat Completions（langchain4j-open-ai）对接 DeepSeek
 * 等现有供应商，结构化输出经 AiServices 以 {@link AgentStructuredOutput} 记录约束与校验。
 *
 * <p>由 {@link AgentRuntimeConfiguration} 按 {@code app.agent.base-url} 条件注册（与
 * {@link DefaultAgentRuntime} 互斥：无 base-url 时 Default 以 fail-closed 兜底）。配置不完整
 * （api-key/provider/model 缺任一）时不构建模型客户端，直接 fail-closed，不连接任何模型。
 *
 * <p>工具绑定（03 票）：请求携带非空 {@link AgentToolBinding} 时按绑定构建带工具的
 * AiServices（白名单外的工具不暴露给模型，调用经 {@link AgentToolInvoker} 走 MCP 既有路径）；
 * 无绑定或空绑定走无工具缓存网关，行为与 01 一致。
 *
 * <p>可观测性（08 票）：模型调用成功后以 run_id（取自绑定）落 token 用量
 * （{@link AgentObservability#recordModelTokens}）；经可选的 2 参构造器注入 provider，
 * 1 参构造器保持 no-op 行为不变。观测写入失败 try/catch 隔离，不影响运行结果。
 *
 * <p>错误分类（与既有 message 解释器语义对齐）：HTTP 失败/超时/网络错误 → AGENT_MODEL_CALL_FAILED；
 * 缺配置 → AGENT_MODEL_NOT_CONFIGURED；模型输出无法解析为 {@link AgentStructuredOutput} →
 * AGENT_OUTPUT_INVALID。api-key 只经环境变量注入，绝不进入异常消息/结果/日志。
 */
public class LangChain4jAgentRuntime implements AgentRuntime {

    /** 基础运行时固定提示词版本；业务 Agent 的提示词版本由 02 票 Agent 注册表定义。 */
    public static final String PROMPT_VERSION = "agent-foundation-v1";

    private final String provider;
    private final String model;
    private final boolean configured;
    private final AgentModelProperties properties;
    private final AgentGateway gateway;
    private final AgentObservability observability;

    public LangChain4jAgentRuntime(AgentModelProperties properties) {
        this(properties, AgentObservability.disabled());
    }

    public LangChain4jAgentRuntime(AgentModelProperties properties, AgentObservability observability) {
        this.properties = properties;
        this.provider = properties.getProvider();
        this.model = properties.getModel();
        this.configured = properties.configured();
        this.observability = observability == null ? AgentObservability.disabled() : observability;
        this.gateway = configured
                ? AiServices.builder(AgentGateway.class)
                        .chatModel(chatModel(properties))
                        .build()
                : null;
    }

    @Override
    public AgentRunResult run(AgentTaskRequest request) {
        if (!configured) {
            return AgentRunResult.failClosed(AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        if (request == null || request.userInput() == null || request.userInput().isBlank()) {
            throw new IllegalArgumentException("AgentTaskRequest.userInput 不能为空");
        }
        String systemPrompt = request.systemPrompt() == null ? "" : request.systemPrompt().strip();
        try {
            Result<AgentStructuredOutput> raw =
                    runWithTools(systemPrompt, request.userInput(), request.tools());
            if (raw == null || raw.content() == null) {
                return failure(AgentFailureCode.AGENT_OUTPUT_INVALID);
            }
            recordTokens(request, raw.tokenUsage());
            return new AgentRunResult(raw.content(), provider, model, PROMPT_VERSION, null);
        } catch (OutputParsingException ex) {
            // 模型输出不满足 schema：稳定码收口，不重试，异常细节不对外
            return failure(AgentFailureCode.AGENT_OUTPUT_INVALID);
        } catch (RuntimeException ex) {
            // 请求组装/HTTP/网络等意外失败同样走稳定码，不把异常细节带进结果
            return failure(AgentFailureCode.AGENT_MODEL_CALL_FAILED);
        }
    }

    private Result<AgentStructuredOutput> runWithTools(
            String systemPrompt, String userInput, AgentToolBinding binding) {
        if (binding == null || binding.isEmpty()) {
            return gateway.run(systemPrompt, userInput);
        }
        AgentGateway services = AiServices.builder(AgentGateway.class)
                .chatModel(chatModel(properties))
                .tools(binding.tools())
                .build();
        return services.run(systemPrompt, userInput);
    }

    /** 模型调用观测：以绑定携带的 run_id 落 token 用量；失败隔离——观测失败不影响运行结果。 */
    private void recordTokens(AgentTaskRequest request, TokenUsage usage) {
        if (request == null || request.tools() == null || usage == null) {
            return;
        }
        try {
            observability.recordModelTokens(
                    request.tools().runId(),
                    new AgentObservability.TokenUsage(
                            usage.inputTokenCount(),
                            usage.outputTokenCount(),
                            usage.totalTokenCount()));
        } catch (RuntimeException ignored) {
            // 观测写入失败不影响运行结果（与审计失败容忍语义一致）
        }
    }

    private AgentRunResult failure(AgentFailureCode code) {
        return new AgentRunResult(null, provider, model, PROMPT_VERSION, code.name());
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
