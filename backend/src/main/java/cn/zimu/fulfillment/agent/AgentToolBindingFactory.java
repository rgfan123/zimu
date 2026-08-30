package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Agent ↔ MCP 工具绑定工厂（agent-decision-layer 03）：从唯一工具源
 * {@link McpToolRegistry#agentTools()} 按 {@code AgentDefinition.tool_names} 白名单生成
 * LangChain4j {@link ToolSpecification} 与执行器。
 *
 * <p>每个 Agent run 绑定一次（run_id 即 {@link AgentToolInvoker} 的上下文关联键）；
 * 白名单引用不存在的工具时 fail-fast（注册表是唯一工具源，引用漂移必须立刻暴露）。
 * 注册表工具集合变更后重新 bind 即自动同步，Agent 侧无任何手工维护的工具定义。
 *
 * <p>权限强制点（08 决策）：绑定期按 {@code allowWrite} 判定——白名单含写工具
 * （readOnly=false）且未声明 allow_write=true 时绑定期拒绝（fail-fast）；
 * 执行器以白名单注入 {@link AgentToolInvoker}，调用期再按白名单复核（防旁路）。
 * 生产路径一律走 3 参 {@link #bind(String, List, boolean)}；2 参便捷重载按
 * allowWrite=false（fail-closed）委托，仅供白名单全只读的调用方/测试使用。
 *
 * <p>可观测性（08 票）：执行器携带 {@link AgentObservability} provider（经可选的
 * 4 参构造器注入，05/06 业务 Agent 与既有 3 参构造器零改动即自动获得工具调用观测）；
 * 关闭开关时注入 no-op，工具绑定与执行行为不变。
 */
@Component
public class AgentToolBindingFactory {

    /** 一次受限绑定的有效工具与被策略拒绝工具，供运行审计如实记录。 */
    public record RestrictedBinding(
            AgentToolBinding binding,
            List<String> exposedToolNames,
            List<String> deniedToolNames) {}

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
     * 便捷重载：按 allowWrite=false（fail-closed，白名单含写工具即绑定期拒绝）委托。
     * 仅供白名单全只读的调用方/测试使用；生产路径（门面）一律走 3 参重载显式传
     * {@code AgentDefinition.allowWrite()}。
     */
    public AgentToolBinding bind(String runId, List<String> toolNames) {
        return bind(runId, toolNames, false);
    }

    /**
     * 为一次 Agent run 生成工具绑定。
     *
     * @param runId     Agent run 的 run_id（工具调用 requestId/traceId 来源）
     * @param toolNames 白名单（可为空）；引用未注册工具时抛 {@link IllegalArgumentException}
     * @param allowWrite 是否允许写工具（AgentDefinition.allow_write）；白名单含写工具且
     *                   非 true 时绑定期拒绝（08 决策，fail-fast）
     * @return 白名单过滤后的工具描述与执行器（空白名单返回空绑定，LangChain4j 侧不暴露任何工具）
     */
    public AgentToolBinding bind(String runId, List<String> toolNames, boolean allowWrite) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        Set<String> whitelist = toolNames == null ? Set.of() : Set.copyOf(toolNames);
        AgentToolInvoker invoker = new AgentToolInvoker(
                runId, registry, identity, mapper, observability, whitelist);
        if (toolNames != null) {
            for (String name : toolNames) {
                McpTool tool = registry.findAgentTool(name).orElseThrow(() -> new IllegalArgumentException(
                        "Agent 工具白名单引用未知 MCP 工具: " + name
                                + "；请同步 AgentDefinition.tool_names 或注册该工具"));
                if (!tool.readOnly() && !allowWrite) {
                    throw new IllegalArgumentException(
                            "Agent 白名单含写工具但未声明 allow_write=true: " + name
                                    + "；写工具必须显式放行（AgentDefinition.allow_write）");
                }
                tools.put(toSpecification(tool), invoker);
            }
        }
        return new AgentToolBinding(runId, tools, invoker);
    }

    /**
     * 会话 Agent 的最小权限绑定：只暴露指定模块内且 {@code readOnly=true} 的工具。
     *
     * <p>与普通定义绑定不同，定义中属于其他模块、写工具或被 Agent 工具面配置隐藏的名称
     * 都按策略拒绝而不是配置漂移抛错。运行时绑定白名单只含有效工具；即使底层运行时尝试
     * 旁路调用已注册写工具，{@link AgentToolInvoker} 仍会返回 TOOL_NOT_AUTHORIZED 并留观测。
     */
    public RestrictedBinding bindReadOnlyModules(
            String runId, List<String> toolNames, Set<String> allowedModules) {
        Set<String> modules = allowedModules == null ? Set.of() : Set.copyOf(allowedModules);
        Set<String> requested = toolNames == null
                ? Set.of()
                : new LinkedHashSet<>(toolNames);
        List<String> exposed = new java.util.ArrayList<>();
        List<String> denied = new java.util.ArrayList<>();
        for (String name : requested) {
            McpTool tool = registry.findAgentTool(name).orElse(null);
            if (tool != null && tool.readOnly() && modules.contains(tool.module())) {
                exposed.add(name);
            } else {
                denied.add(name);
            }
        }
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        AgentToolInvoker rejectionAwareInvoker = new AgentToolInvoker(
                runId,
                registry,
                identity,
                mapper,
                observability,
                Set.copyOf(exposed),
                Set.copyOf(denied));
        for (String name : exposed) {
            McpTool tool = registry.findAgentTool(name).orElseThrow();
            tools.put(toSpecification(tool), rejectionAwareInvoker);
        }
        return new RestrictedBinding(
                new AgentToolBinding(runId, tools, rejectionAwareInvoker),
                List.copyOf(exposed),
                List.copyOf(denied));
    }

    private static ToolSpecification toSpecification(McpTool tool) {
        return ToolSpecification.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(McpToolSchemaConverter.toObjectSchema(tool.inputSchema()))
                .build();
    }
}
