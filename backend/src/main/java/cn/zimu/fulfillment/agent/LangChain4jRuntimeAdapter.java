package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LangChain4j 运行时 Adapter（meta-agent-platform-impl 04，迁移批 A）：实现 {@link AgentRuntime}
 * 接缝，职责 = 定义 → ChatRequest → 结果；权限/守卫/审计/观测全部留在 Control Plane（Facade）。
 *
 * <p>替换 {@code LangChain4jAgentRuntime} 的 AiServices 静态网关路径，统一低层
 * {@link ChatRequest}：结构化输出经 {@code responseFormat} + 供应商能力自适应——OpenAI 原生
 * 走 {@code json_schema}（{@link JsonRawSchema} 动态 schema，定义携带的 output_schema），
 * DeepSeek 等兼容端点降级 {@code json_object}（{@link JsonSchemaCapability}）；客户端一律经
 * {@link JsonSchemaValidator} 校验兜底，失败映射 {@code AGENT_OUTPUT_INVALID}（不重试）。
 *
 * <p>工具调用：手写 tool-calling loop（AiServices 自带 loop 换成低层请求后需自建），每轮
 * 执行绑定内的 {@link ToolExecutor}（run_id 即 MCP 调用上下文），结果回传模型；白名单外/未
 * 绑定工具稳定拒绝回传（不暴露原始异常）。换低层 ChatRequest 可能改变工具调用序列——迁移
 * 前后用 {@code AgentEvalBaselineTest} 比对（本批只动 A 路径，B/C 路径不受影响）。
 *
 * <p>错误分类：HTTP 失败/超时/网络 → {@code AGENT_MODEL_CALL_FAILED}；缺配置 →
 * {@code AGENT_MODEL_NOT_CONFIGURED}；输出非 JSON / 不满足 output_schema / 工具循环超限 →
 * {@code AGENT_OUTPUT_INVALID}。api-key 只经环境变量注入，绝不进入异常消息/结果/日志。
 */
public class LangChain4jRuntimeAdapter implements AgentRuntime {

    /** 无定义（或定义无 prompt_version）时的基础提示词版本；业务 Agent 版本由定义携带。 */
    public static final String PROMPT_VERSION = "agent-foundation-v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentModelProperties properties;
    private final AgentObservability observability;
    private final boolean configured;
    private final boolean jsonSchemaSupported;
    private final String provider;
    private final String model;
    private volatile ChatModel chatModel;

    public LangChain4jRuntimeAdapter(AgentModelProperties properties) {
        this(properties, AgentObservability.disabled());
    }

    public LangChain4jRuntimeAdapter(AgentModelProperties properties, AgentObservability observability) {
        this.properties = properties;
        this.provider = properties.getProvider();
        this.model = properties.getModel();
        this.configured = properties.configured();
        this.jsonSchemaSupported = JsonSchemaCapability.supports(provider);
        this.observability = observability == null ? AgentObservability.disabled() : observability;
    }

    @Override
    public AgentRunResult run(AgentTaskRequest request) {
        if (!configured) {
            return AgentRunResult.failClosed(AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED);
        }
        if (request == null || request.userInput() == null || request.userInput().isBlank()) {
            throw new IllegalArgumentException("AgentTaskRequest.userInput 不能为空");
        }
        AgentDefinition definition = request.definition();
        String systemPrompt = definition != null && !definition.systemPrompt().isBlank()
                ? definition.systemPrompt()
                : request.systemPrompt() == null ? "" : request.systemPrompt().strip();
        String promptVersion = definition != null ? definition.promptVersion() : PROMPT_VERSION;
        JsonNode outputSchema = definition == null ? null : definition.outputSchema();
        AgentToolBinding binding = request.tools();
        AgentExecutionBudget budget = request.executionBudget() == null
                ? properties.executionBudget()
                : request.executionBudget();

        List<ChatMessage> messages = new ArrayList<>(List.of(
                SystemMessage.from(systemPrompt), UserMessage.from(request.userInput())));
        // 129：累加器活在循环之外。工具轮 continue 前先记账——工具轮携带完整上下文 +
        // 工具定义 + 累积的工具结果，往往是最贵的几轮，只记最后一轮是系统性少算。
        TokenTally tally = TokenTally.EMPTY;
        long startedNanos = System.nanoTime();
        int modelCalls = 0;
        int toolCalls = 0;
        String previousToolCall = null;
        int repeatedToolCalls = 0;
        try {
            while (true) {
                if (modelCalls >= budget.maxModelCalls() || budget.deadlineExceeded(startedNanos)) {
                    return AgentRunResult.failure(
                            provider,
                            model,
                            promptVersion,
                            AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED);
                }
                ChatResponse response = chatWithinBudget(
                        messages, binding, outputSchema, budget, startedNanos);
                modelCalls++;
                tally = tally.plus(response.tokenUsage());
                AiMessage aiMessage = response.aiMessage();
                if (aiMessage.hasToolExecutionRequests()) {
                    int requestedCalls = aiMessage.toolExecutionRequests().size();
                    if (toolCalls + requestedCalls > budget.maxToolCalls()
                            || budget.deadlineExceeded(startedNanos)) {
                        return AgentRunResult.failure(
                                provider,
                                model,
                                promptVersion,
                                AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED);
                    }
                    for (ToolExecutionRequest executionRequest : aiMessage.toolExecutionRequests()) {
                        if (budget.deadlineExceeded(startedNanos)) {
                            return AgentRunResult.failure(
                                    provider,
                                    model,
                                    promptVersion,
                                    AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED);
                        }
                        String fingerprint = executionRequest.name() + "\u0000" + executionRequest.arguments();
                        if (fingerprint.equals(previousToolCall)) {
                            repeatedToolCalls++;
                        } else {
                            previousToolCall = fingerprint;
                            repeatedToolCalls = 1;
                        }
                        if (repeatedToolCalls > budget.maxRepeatedToolCalls()) {
                            return AgentRunResult.failure(
                                    provider,
                                    model,
                                    promptVersion,
                                    AgentFailureCode.AGENT_NO_PROGRESS);
                        }
                        String toolResult = executeTool(binding, executionRequest);
                        toolCalls++;
                        if (budget.deadlineExceeded(startedNanos)) {
                            return AgentRunResult.failure(
                                    provider,
                                    model,
                                    promptVersion,
                                    AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED);
                        }
                        messages.add(ToolExecutionResultMessage.from(executionRequest, toolResult));
                    }
                    continue;
                }
                return parseOutput(aiMessage.text(), outputSchema, promptVersion);
            }
        } catch (AgentBudgetExceededException ex) {
            return AgentRunResult.failure(
                    provider, model, promptVersion, AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED);
        } catch (IllegalStateException ex) {
            // 配置漂移（output_schema 非法等）fail-fast 上抛——不允许伪装成模型调用失败被吞掉
            throw ex;
        } catch (RuntimeException ex) {
            // 请求组装/HTTP/网络等意外失败走稳定码，不把异常细节带进结果
            return AgentRunResult.failure(provider, model, promptVersion, AgentFailureCode.AGENT_MODEL_CALL_FAILED);
        } finally {
            // 四条出口（成功 / 输出无效 / 调用失败 / 循环超限）共用同一次写入：
            // 失败的调用照样花钱，不记就等于成本核算天然缺一块。
            recordTokens(binding, tally);
        }
    }

    private ChatResponse chat(List<ChatMessage> messages, AgentToolBinding binding, JsonNode outputSchema) {
        ChatRequest.Builder builder = ChatRequest.builder().messages(messages);
        if (binding != null && !binding.isEmpty()) {
            builder.toolSpecifications(binding.specifications());
        }
        builder.responseFormat(responseFormat(outputSchema));
        return model().chat(builder.build());
    }

    private ChatResponse chatWithinBudget(
            List<ChatMessage> messages,
            AgentToolBinding binding,
            JsonNode outputSchema,
            AgentExecutionBudget budget,
            long startedNanos) {
        long remainingNanos = budget.maxDuration().toNanos() - (System.nanoTime() - startedNanos);
        if (remainingNanos <= 0) {
            throw new AgentBudgetExceededException();
        }
        FutureTask<ChatResponse> call = new FutureTask<>(() -> chat(messages, binding, outputSchema));
        Thread worker = Thread.ofVirtual().name("agent-model-call").start(call);
        try {
            ChatResponse response = call.get(remainingNanos, TimeUnit.NANOSECONDS);
            if (budget.deadlineExceeded(startedNanos)) {
                throw new AgentBudgetExceededException();
            }
            return response;
        } catch (TimeoutException exception) {
            call.cancel(true);
            worker.interrupt();
            throw new AgentBudgetExceededException();
        } catch (InterruptedException exception) {
            call.cancel(true);
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new RuntimeException("Agent 模型调用被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IllegalStateException illegalState) {
                throw illegalState;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(cause);
        }
    }

    private static final class AgentBudgetExceededException extends RuntimeException {}

    /** 供应商能力自适应：支持 json_schema 时传定义携带的动态 schema；否则降级 json_object。 */
    private ResponseFormat responseFormat(JsonNode outputSchema) {
        if (outputSchema != null && jsonSchemaSupported) {
            return ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name("output")
                            .rootElement(JsonRawSchema.from(outputSchema.toString()))
                            .build())
                    .build();
        }
        return ResponseFormat.JSON;
    }

    private AgentRunResult parseOutput(String content, JsonNode outputSchema, String promptVersion) {
        if (content == null || content.isBlank()) {
            return AgentRunResult.failure(provider, model, promptVersion, AgentFailureCode.AGENT_OUTPUT_INVALID);
        }
        final JsonNode output;
        try {
            output = MAPPER.readTree(content);
        } catch (Exception ex) {
            return AgentRunResult.failure(provider, model, promptVersion, AgentFailureCode.AGENT_OUTPUT_INVALID);
        }
        if (outputSchema != null) {
            String validationError = JsonSchemaValidator.validate(content, outputSchema.toString());
            if (validationError != null) {
                return AgentRunResult.failure(provider, model, promptVersion, AgentFailureCode.AGENT_OUTPUT_INVALID);
            }
        }
        return AgentRunResult.success(output, provider, model, promptVersion);
    }

    /** 执行一次工具调用：按白名单名称解析绑定内执行器；未绑定稳定拒绝回传模型。 */
    static String executeTool(AgentToolBinding binding, ToolExecutionRequest executionRequest) {
        ToolExecutor executor = binding == null ? null : binding.executorFor(executionRequest.name());
        if (executor == null) {
            // 模型请求未暴露工具时仍交给绑定期的拒绝执行器：已注册但白名单外（包括写工具）
            // 返回 TOOL_NOT_AUTHORIZED 并落 agent_tool_calls；完全未知名才收口为内部错误。
            executor = binding == null ? null : binding.rejectionExecutor();
            if (executor == null) {
                return McpToolErrorEnvelope.internalError();
            }
        }
        return executor.execute(executionRequest, null);
    }

    /**
     * 模型调用观测：以绑定携带的 run_id 落全轮累加的 token 用量；每次运行恰好写一次
     * （覆盖语义因此是幂等的，不需要把累加下推到 SQL）。失败隔离——观测失败不影响运行结果。
     *
     * <p>零次模型调用不写：写一行全 0 会被读成「跑了但没花钱」，与「根本没调用到模型」
     * 是两回事，宁可留空也不编数。
     */
    private void recordTokens(AgentToolBinding binding, TokenTally tally) {
        if (binding == null || tally == null || tally.isEmpty()) {
            return;
        }
        try {
            observability.recordModelTokens(
                    binding.runId(),
                    new AgentObservability.TokenUsage(
                            tally.promptTokens(),
                            tally.completionTokens(),
                            tally.totalTokens(),
                            tally.modelCalls()));
        } catch (RuntimeException ignored) {
            // 观测写入失败不影响运行结果（与审计失败容忍语义一致）
        }
    }

    /**
     * 全轮 token 累加器（129 票）：不可变，每轮返回新值。
     *
     * <p>provider 省略 {@code total_tokens} 时由 prompt+completion 推导——部分 OpenAI
     * 兼容端点只给分项，照抄 null 会让总量凭空少一截。
     */
    private record TokenTally(int promptTokens, int completionTokens, int totalTokens, int modelCalls) {

        static final TokenTally EMPTY = new TokenTally(0, 0, 0, 0);

        TokenTally plus(TokenUsage usage) {
            if (usage == null) {
                // 轮次照数：provider 没回 usage 不等于这一轮没发生，均值口径靠它才不失真
                return new TokenTally(promptTokens, completionTokens, totalTokens, modelCalls + 1);
            }
            int prompt = orZero(usage.inputTokenCount());
            int completion = orZero(usage.outputTokenCount());
            int total = usage.totalTokenCount() == null ? prompt + completion : usage.totalTokenCount();
            return new TokenTally(
                    promptTokens + prompt,
                    completionTokens + completion,
                    totalTokens + total,
                    modelCalls + 1);
        }

        boolean isEmpty() {
            return modelCalls == 0;
        }

        private static int orZero(Integer value) {
            return value == null ? 0 : value;
        }
    }

    private ChatModel model() {
        ChatModel existing = chatModel;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (chatModel == null) {
                OpenAiChatModel.OpenAiChatModelBuilder builder = OpenAiChatModel.builder()
                        .baseUrl(properties.getBaseUrl())
                        .apiKey(properties.getApiKey())
                        .modelName(properties.getModel())
                        .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()));
                if (jsonSchemaSupported) {
                    builder.supportedCapabilities(Set.of(Capability.RESPONSE_FORMAT_JSON_SCHEMA));
                }
                chatModel = builder.build();
            }
            return chatModel;
        }
    }
}
