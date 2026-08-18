package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent ↔ MCP 工具绑定工厂（agent-decision-layer 03）：从唯一工具源
 * {@link McpToolRegistry#all()} 按 {@code AgentDefinition.tool_names} 白名单生成
 * LangChain4j {@link ToolSpecification} 与执行器。
 *
 * <p>每个 Agent run 绑定一次（run_id 即 {@link AgentToolInvoker} 的上下文关联键）；
 * 白名单引用不存在的工具时 fail-fast（注册表是唯一工具源，引用漂移必须立刻暴露）。
 * 注册表工具集合变更后重新 bind 即自动同步，Agent 侧无任何手工维护的工具定义。
 *
 * <p>可观测性（08 票）：执行器携带 {@link AgentObservability} provider（经可选的
 * 4 参构造器注入，05/06 业务 Agent 与既有 3 参构造器零改动即自动获得工具调用观测）；
 * 关闭开关时注入 no-op，工具绑定与执行行为不变。
 */
@Component
public class AgentToolBindingFactory {

    private final McpToolRegistry registry;
    private final McpAgentIdentity identity;
    private final ObjectMapper mapper;
    private final AgentObservability observability;

    public AgentToolBindingFactory(
            McpToolRegistry registry, McpAgentIdentity identity, ObjectMapper mapper) {
        this(registry, identity, mapper, AgentObservability.disabled());
    }

    @Autowired
    public AgentToolBindingFactory(
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            AgentObservability observability) {
        this.registry = registry;
        this.identity = identity;
        this.mapper = mapper;
        this.observability = observability == null ? AgentObservability.disabled() : observability;
    }

    /**
     * 为一次 Agent run 生成工具绑定。
     *
     * @param runId    Agent run 的 run_id（工具调用 requestId/traceId 来源）
     * @param toolNames 白名单（可为空）；引用未注册工具时抛 {@link IllegalArgumentException}
     * @return 白名单过滤后的工具描述与执行器（空白名单返回空绑定，LangChain4j 侧不暴露任何工具）
     */
    public AgentToolBinding bind(String runId, List<String> toolNames) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        AgentToolInvoker invoker = null;
        if (toolNames != null) {
            for (String name : toolNames) {
                McpTool tool = registry.find(name).orElseThrow(() -> new IllegalArgumentException(
                        "Agent 工具白名单引用未知 MCP 工具: " + name
                                + "；请同步 AgentDefinition.tool_names 或注册该工具"));
                if (invoker == null) {
                    invoker = new AgentToolInvoker(runId, registry, identity, mapper, observability);
                }
                tools.put(toSpecification(tool), invoker);
            }
        }
        return new AgentToolBinding(runId, tools);
    }

    private static ToolSpecification toSpecification(McpTool tool) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(McpToolSchemaConverter.toObjectSchema(tool.inputSchema()))
                .build();
    }
}
