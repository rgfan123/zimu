package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpRequestContext;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LangChain4j 工具调用 → 既有 {@link McpToolRegistry} 的桥接执行器（agent-decision-layer 03）。
 *
 * <p>由 {@link AgentToolBindingFactory} 按 Agent run 创建（每次运行一个实例，绑定 run_id）：
 * 调用一律经 {@link McpTool#invoke(McpRequestContext, args)} 执行，上下文由
 * {@link McpAgentIdentity#newContext(String)} 以 run_id 生成（requestId=traceId=run_id），
 * 操作人身份来自服务端注入的 Agent 身份——与 MCP stdio 进程走完全相同的身份/幂等/审计路径，
 * 工具参数不接受 operator，因此无法伪造。
 *
 * <p>错误契约与 {@link cn.zimu.fulfillment.mcp.McpServer} 对齐：业务失败返回
 * {@code {"code","http_status","message"}} 稳定业务码 JSON（模型可据此修正参数重试），
 * 意外失败返回 {@code MCP_INTERNAL_ERROR}，与 stdio 客户端收到的工具结果结构一致。
 *
 * <p>权限强制点（08 决策）：执行器携带绑定白名单（{@code AgentToolBindingFactory} 以
 * AgentDefinition.tool_names 注入），{@link #execute} 对每次工具调用按白名单复核——
 * 白名单外工具即使已在注册表也拒绝（{@code TOOL_NOT_AUTHORIZED}，防旁路真强制点）；
 * 白名单为空视为拒绝一切调用（fail-closed）。
 *
 * <p>可观测性（08 票）：每次工具调用以 run_id + 递增序号落 {@code agent_tool_call} 行
 * （参数/结果只存 {@link AgentPayloadRedactor} 脱敏摘要）；观测写入失败 try/catch 隔离，
 * 不影响工具调用结果。provider 经 {@link AgentObservability} 接缝注入，默认 no-op。
 */
public class AgentToolInvoker implements ToolExecutor {

    private final String runId;
    private final McpToolRegistry registry;
    private final McpAgentIdentity identity;
    private final ObjectMapper mapper;
    private final AgentObservability observability;
    private final Set<String> whitelist;
    private final Set<String> policyDeniedNames;
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentToolInvoker(
            String runId,
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            Set<String> whitelist) {
        this(runId, registry, identity, mapper, AgentObservability.disabled(), whitelist);
    }

    public AgentToolInvoker(
            String runId,
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            AgentObservability observability,
            Set<String> whitelist) {
        this(runId, registry, identity, mapper, observability, whitelist, Set.of());
    }

    public AgentToolInvoker(
            String runId,
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            AgentObservability observability,
            Set<String> whitelist,
            Set<String> policyDeniedNames) {
        this.runId = runId;
        this.registry = registry;
        this.identity = identity;
        this.mapper = mapper;
        this.observability = observability == null ? AgentObservability.disabled() : observability;
        this.whitelist = whitelist == null ? Set.of() : Set.copyOf(whitelist);
        this.policyDeniedNames = policyDeniedNames == null ? Set.of() : Set.copyOf(policyDeniedNames);
    }

    /** 当前绑定所属 Agent run 的 run_id（工具调用上下文关联键）。 */
    public String runId() {
        return runId;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return internalError();
        }
        String toolName = request.name().strip();
        long startedNanos = System.nanoTime();
        String outcome;
        boolean success;
        McpTool tool = registry.findAgentTool(toolName).orElse(null);
        if (tool == null && policyDeniedNames.contains(toolName)) {
            // Agent 工具面配置可能已隐藏该工具；会话策略仍知道它来自定义白名单且
            // 被模块/读写上限拒绝，因此保持权限错误语义并留观测，而不是伪装成未知工具。
            outcome = McpToolErrorEnvelope.notAuthorized();
            success = false;
        } else if (tool == null) {
            // 未注册名：注册表漂移/模型幻觉工具（工具根本不存在），非权限问题——稳定内部错误兜底；
            // 工具名随观测行（agent_tool_calls.tool_name）留痕，信封不透出细节
            outcome = internalError();
            success = false;
        } else if (!whitelist.contains(toolName)) {
            // 08 决策调用期复核（真强制点，防旁路）：白名单外工具即使注册存在也拒绝
            outcome = McpToolErrorEnvelope.notAuthorized();
            success = false;
        } else {
            try {
                Map<String, Object> argumentsMap = parseArguments(toolName, request.arguments());
                McpRequestContext context = identity.newContext(runId);
                outcome = tool.invoke(context, argumentsMap).toString();
                success = true;
            } catch (BusinessException ex) {
                outcome = businessError(ex);
                success = false;
            } catch (RuntimeException ex) {
                // 与 MCP stdio 对意外失败的兜底一致：稳定错误码，不泄漏内部细节
                outcome = internalError();
                success = false;
            }
        }
        recordToolCall(
                toolName,
                request.arguments(),
                outcome,
                success,
                (System.nanoTime() - startedNanos) / 1_000_000);
        return outcome;
    }

    /** 工具调用观测：序号递增、脱敏摘要落库；失败隔离——观测失败不影响工具调用结果。 */
    private void recordToolCall(
            String toolName, String arguments, String outcome, boolean success, long latencyMs) {
        try {
            observability.toolCallFinished(new AgentObservability.ToolCall(
                    runId,
                    sequence.incrementAndGet(),
                    toolName,
                    AgentPayloadRedactor.argsSummary(arguments),
                    AgentPayloadRedactor.resultSummary(outcome),
                    latencyMs,
                    success));
        } catch (RuntimeException ignored) {
            // 观测写入失败不影响工具调用结果（与 MCP 写路径审计失败容忍语义一致）
        }
    }

    private Map<String, Object> parseArguments(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("工具 " + toolName + " 的参数无法解析", ex);
        }
    }

    /** 业务失败结果与 McpServer 的 isError 载荷一致：code/http_status/message。 */
    private String businessError(BusinessException ex) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", ex.getBusinessCode());
        error.put("http_status", ex.getHttpStatus());
        error.put("message", ex.getMessage());
        return error.toString();
    }

    private String internalError() {
        // 信封统一经 McpToolErrorEnvelope；具体工具名随观测行（agent_tool_calls.tool_name）留痕
        return McpToolErrorEnvelope.internalError();
    }
}
