package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.output.OutputParsingException;
import dev.langchain4j.service.tool.ToolExecutor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 数据查询 Agent（06 票）执行服务：自然语言问题（+可选 thread_id）→ 只读工具调用 →
 * 结构化答案（{@link DataQueryAgentOutput}）。
 *
 * <p>策略落地（三层）：
 * <ol>
 *   <li>确定性 PII 门：涉及客户/收货人 PII 的问题在模型调用前直接转人工（Agent 白名单
 *       无 PII 工具），不发起任何模型调用；</li>
 *   <li>确定性歧义门：占位/歧义问题（SKU-xxx、P-123、某履约方）直接进入澄清路径
 *       （clarification_needed），不猜参数、不发起模型调用；</li>
 *   <li>工具参数兜底：模型仍以占位值猜参数时，工具执行器拒绝该次调用并回传
 *       CLARIFICATION_REQUIRED，模型据此转入澄清路径。</li>
 * </ol>
 *
 * <p>执行模型路径复用 01 的传输配置（{@link AgentModelProperties}，未配置时 fail-closed，
 * 不连接任何模型）与 03 的 {@link AgentToolBindingFactory} 绑定（run_id 即工具调用上下文
 * 关联键）；输出 schema 由本 Agent 专属网关（{@link DataQueryAgentGateway}）约束，
 * 每次运行生成唯一 run_id 并落 AGENT 审计（operation=agent.data-query-agent.run），
 * 审计 responsePayload 携带工具调用序列（tool_call_sequence），满足「每次运行留下工具
 * 调用序列审计」。
 *
 * <p>与 {@link AgentRuntimeFacade} 的关系：门面的底层运行时 schema 固定为
 * {@link AgentStructuredOutput}（01 票最小 schema，不修改），本业务 Agent 按 02 票
 * 「业务 Agent 定义更丰富记录」的语义走自己的网关；两者共享注册表定义、模型配置与
 * 审计模型，审计 operation 命名一致。
 */
@Service
public class DataQueryAgentService {

    private static final String DEFAULT_OPERATOR = "agent";
    private static final String STATUS_SUCCESS = "SUCCESS";
    private static final String STATUS_CLARIFICATION = "CLARIFICATION";
    private static final String STATUS_PII_GUARDED = "PII_GUARDED";
    private static final String STATUS_FAILURE = "FAILURE";

    private final AgentRegistry registry;
    private final AgentModelProperties properties;
    private final AgentToolBindingFactory bindingFactory;
    private final AuditLogService audits;
    private final AgentModelMetadataRegistry metadata;
    private final ObjectMapper mapper;

    public DataQueryAgentService(
            AgentRegistry registry,
            AgentModelProperties properties,
            AgentToolBindingFactory bindingFactory,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            ObjectMapper mapper) {
        this.registry = registry;
        this.properties = properties;
        this.bindingFactory = bindingFactory;
        this.audits = audits;
        this.metadata = metadata;
        this.mapper = mapper;
    }

    /**
     * 用自然语言问题运行一次数据查询（可选会话上下文 thread_id 透传审计）。
     *
     * <p>PII / 歧义 / 未配置模型路径均不触碰模型，返回确定性结果并留审计；
     * 模型路径失败以 {@link AgentFailureCode} 稳定码返回，不抛异常。
     */
    public DataQueryRunResult answer(String question, AgentRunContext context) {
        AgentRunContext ctx = context == null ? AgentRunContext.empty() : context;
        String runId = AgentRuntimeFacade.newRunId();
        AgentDefinition definition = registry.bySlug(DataQueryAgentDefinitionConfiguration.SLUG);
        if (definition == null) {
            return rejected(ctx, runId, null, AgentFailureCode.AGENT_NOT_FOUND);
        }
        if (!definition.enabled()) {
            return rejected(ctx, runId, definition, AgentFailureCode.AGENT_DISABLED);
        }

        if (question == null || question.isBlank()) {
            return clarification(
                    ctx,
                    runId,
                    definition,
                    List.of("请说明要查询的内容（如：最近 7 天缺货的订单行数、SKU 进货价与零售价、采购工单缺口数量）"));
        }

        List<String> pii = DataQueryAgentGuard.piiProblems(question);
        if (!pii.isEmpty()) {
            DataQueryAgentOutput output = new DataQueryAgentOutput(
                    "该查询涉及客户/收货人 PII，数据查询 Agent 无 PII 工具，已转人工处理"
                            + "（requires_human=true），未发起任何工具调用。",
                    List.of(),
                    0.0,
                    true,
                    List.of());
            return finish(
                    ctx, runId, definition, output, STATUS_PII_GUARDED, List.of(), 0, "none", "none");
        }

        List<String> ambiguous = DataQueryAgentGuard.ambiguityProblems(question);
        if (!ambiguous.isEmpty()) {
            return clarification(ctx, runId, definition, ambiguous);
        }

        if (!properties.configured()) {
            return rejected(ctx, runId, definition, AgentFailureCode.AGENT_MODEL_NOT_CONFIGURED);
        }

        long startedNanos = System.nanoTime();
        List<DataQueryAgentToolCall> toolCalls = new ArrayList<>();
        AgentToolBinding binding = bindWithRecording(runId, definition.toolNames(), toolCalls);
        DataQueryAgentGateway gateway = AiServices.builder(DataQueryAgentGateway.class)
                .chatModel(chatModel())
                .tools(binding.tools())
                .build();
        try {
            DataQueryAgentOutput output = gateway.run(definition.systemPrompt(), question);
            long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
            String status =
                    output.clarification_needed().isEmpty() ? STATUS_SUCCESS : STATUS_CLARIFICATION;
            return finish(
                    ctx,
                    runId,
                    definition,
                    output,
                    status,
                    toolCalls,
                    latencyMs,
                    properties.getProvider(),
                    properties.getModel());
        } catch (OutputParsingException ex) {
            return failed(
                    ctx, runId, definition, AgentFailureCode.AGENT_OUTPUT_INVALID, toolCalls, startedNanos);
        } catch (RuntimeException ex) {
            // 请求组装/HTTP/网络等意外失败同样走稳定码，不把异常细节带进结果
            return failed(
                    ctx, runId, definition, AgentFailureCode.AGENT_MODEL_CALL_FAILED, toolCalls, startedNanos);
        }
    }

    // ------------------------------------------------------------------
    // 结果与审计
    // ------------------------------------------------------------------

    private DataQueryRunResult clarification(
            AgentRunContext ctx, String runId, AgentDefinition definition, List<String> reasons) {
        DataQueryAgentOutput output = new DataQueryAgentOutput(
                "问题缺少必要信息，按歧义澄清策略未发起任何工具调用；请补充下列信息后重试。",
                List.of(),
                0.0,
                true,
                reasons);
        return finish(ctx, runId, definition, output, STATUS_CLARIFICATION, List.of(), 0, "none", "none");
    }

    private DataQueryRunResult rejected(
            AgentRunContext ctx, String runId, AgentDefinition definition, AgentFailureCode code) {
        audit(ctx, runId, definition, code.name(), 0, "none", "none", List.of());
        return new DataQueryRunResult(null, code.name(), runId, code.name(), List.of(), 0);
    }

    private DataQueryRunResult failed(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            AgentFailureCode code,
            List<DataQueryAgentToolCall> toolCalls,
            long startedNanos) {
        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        audit(
                ctx,
                runId,
                definition,
                code.name(),
                latencyMs,
                properties.getProvider(),
                properties.getModel(),
                toolCalls);
        return new DataQueryRunResult(null, code.name(), runId, STATUS_FAILURE, toolCalls, latencyMs);
    }

    private DataQueryRunResult finish(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            DataQueryAgentOutput output,
            String status,
            List<DataQueryAgentToolCall> toolCalls,
            long latencyMs,
            String provider,
            String model) {
        audit(ctx, runId, definition, status, latencyMs, provider, model, toolCalls);
        return new DataQueryRunResult(output, null, runId, status, toolCalls, latencyMs);
    }

    /** 与 AgentRuntimeFacade 同构的 AGENT 审计；responsePayload 额外携带工具调用序列。 */
    private void audit(
            AgentRunContext ctx,
            String runId,
            AgentDefinition definition,
            String status,
            long latencyMs,
            String provider,
            String model,
            List<DataQueryAgentToolCall> toolCalls) {
        String slug = definition == null ? "unknown" : definition.agentSlug();
        String promptVersion = definition == null ? "none" : definition.promptVersion();
        String modelRef = definition == null ? "none" : definition.modelRef();
        List<String> toolNames = definition == null ? List.of() : definition.toolNames();
        AgentModelMetadataRegistry.PublicMetadata meta = metadata.publicProjection(provider, model, promptVersion);
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", status);
            response.put("provider", meta.provider());
            response.put("model", meta.model());
            response.put("prompt_version", meta.promptVersion());
            response.put("tool_call_sequence", toolCallSequence(toolCalls));
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(runId)
                    .traceId(runId)
                    .operator(ctx.operator() == null || ctx.operator().isBlank()
                            ? DEFAULT_OPERATOR
                            : ctx.operator())
                    .actorType(AuditActorType.AGENT)
                    .service("agent")
                    .operation("agent." + slug + ".run")
                    .requestPayload(Map.of(
                            "agent_slug", slug,
                            "run_id", runId,
                            "thread_id", ctx.threadId(),
                            "prompt_version", promptVersion,
                            "model_ref", modelRef,
                            "tool_names", toolNames))
                    .responsePayload(response)
                    .businessCode(status)
                    .latencyMs((int) latencyMs));
        } catch (RuntimeException ignored) {
            // 审计失败不掩盖 Agent 运行结果（与既有 MCP/门面路径语义一致）
        }
    }

    private static List<Map<String, Object>> toolCallSequence(List<DataQueryAgentToolCall> calls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DataQueryAgentToolCall call : calls) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", call.tool());
            item.put("arguments", call.arguments());
            item.put("guarded", call.guarded());
            if (call.guardReason() != null) {
                item.put("guard_reason", call.guardReason());
            }
            result.add(item);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 工具绑定与模型
    // ------------------------------------------------------------------

    /** 白名单绑定 + 记录/兜底包装：每次工具调用写入调用序列，占位参数被拦截。 */
    private AgentToolBinding bindWithRecording(
            String runId, List<String> toolNames, List<DataQueryAgentToolCall> log) {
        AgentToolBinding base = bindingFactory.bind(runId, toolNames);
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        for (Map.Entry<ToolSpecification, ToolExecutor> entry : base.tools().entrySet()) {
            wrapped.put(entry.getKey(), new RecordingToolExecutor(entry.getValue(), log, mapper));
        }
        return new AgentToolBinding(runId, wrapped);
    }

    private ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(properties.getBaseUrl())
                .apiKey(properties.getApiKey())
                .modelName(properties.getModel())
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .logRequests(false)
                .build();
    }

    /** 记录 + 占位参数兜底的工具执行器包装（委托给 03 的 AgentToolInvoker）。 */
    private static final class RecordingToolExecutor implements ToolExecutor {

        private final ToolExecutor delegate;
        private final List<DataQueryAgentToolCall> log;
        private final ObjectMapper mapper;

        RecordingToolExecutor(ToolExecutor delegate, List<DataQueryAgentToolCall> log, ObjectMapper mapper) {
            this.delegate = delegate;
            this.log = log;
            this.mapper = mapper;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            Map<String, Object> arguments = parse(request.arguments());
            String problem = DataQueryAgentGuard.toolArgumentProblem(arguments);
            if (problem != null) {
                log.add(new DataQueryAgentToolCall(request.name(), arguments, true, problem));
                return clarificationError();
            }
            String result = delegate.execute(request, memoryId);
            log.add(new DataQueryAgentToolCall(request.name(), arguments, false, null));
            return result;
        }

        private Map<String, Object> parse(String arguments) {
            if (arguments == null || arguments.isBlank()) {
                return Map.of();
            }
            try {
                return mapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                // 参数解析失败交给委托执行器按既有错误契约处理；此处仅记录空参数
                return Map.of();
            }
        }

        /** 占位拦截结果与 McpServer 的 isError 载荷同构：code/message，模型据此转入澄清。 */
        private String clarificationError() {
            ObjectNode error = mapper.createObjectNode();
            error.put("code", "CLARIFICATION_REQUIRED");
            error.put("message", "工具参数为占位/歧义值，禁止猜测参数；请向用户要求具体值后重新回答");
            return error.toString();
        }
    }
}
