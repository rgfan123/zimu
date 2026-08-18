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
    private final AtomicInteger sequence = new AtomicInteger();

    public AgentToolInvoker(
            String runId, McpToolRegistry registry, McpAgentIdentity identity, ObjectMapper mapper) {
        this(runId, registry, identity, mapper, AgentObservability.disabled());
    }

    public AgentToolInvoker(
            String runId,
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            AgentObservability observability) {
        this.runId = runId;
        this.registry = registry;
        this.identity = identity;
        this.mapper = mapper;
        this.observability = observability == null ? AgentObservability.disabled() : observability;
    }

    /** 当前绑定所属 Agent run 的 run_id（工具调用上下文关联键）。 */
    public String runId() {
        return runId;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return internalError("工具请求缺少名称");
        }
        String toolName = request.name().strip();
        long startedNanos = System.nanoTime();
        String outcome;
        boolean success;
        McpTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            // 白名单之外的名称不应到达执行器（LangChain4j 只暴露绑定工具）；到达即注册表漂移
            outcome = internalError("未知工具: " + toolName);
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
                outcome = internalError(toolName);
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

    private String internalError(String detail) {
        ObjectNode error = mapper.createObjectNode();
        error.put("code", "MCP_INTERNAL_ERROR");
        error.put("message", "内部错误，请联系运维");
        return error.toString();
    }
}
