package cn.zimu.fulfillment.agent;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 运行的工具绑定（agent-decision-layer 03）：白名单过滤后的 LangChain4j 工具
 * 描述 + 绑定 run_id 的执行器。
 *
 * <p>由 {@link AgentToolBindingFactory#bind} 从 {@code McpToolRegistry} 单一工具源生成，
 * Agent 侧不手工维护工具定义；{@code runId} 与门面审计的 run_id 一致，LangChain4j 工具调用
 * 经执行器以该 run_id 生成 {@code McpRequestContext}（requestId=traceId=run_id）。
 */
public record AgentToolBinding(String runId, Map<ToolSpecification, ToolExecutor> tools) {

    public AgentToolBinding {
        runId = runId == null ? "" : runId;
        tools = tools == null ? Map.of() : Map.copyOf(tools);
    }

    public static AgentToolBinding empty(String runId) {
        return new AgentToolBinding(runId, Map.of());
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    /** 工具描述清单，按注册表声明顺序。 */
    public List<ToolSpecification> specifications() {
        return List.copyOf(tools.keySet());
    }
}
