package cn.zimu.fulfillment.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpControlReadTools;
import cn.zimu.fulfillment.mcp.McpDomainReadTools;
import cn.zimu.fulfillment.mcp.McpReadTools;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 测试助手：由任意 {@link McpTool} 集合构造迷你注册表（唯一工具源语义与生产一致）。 */
public final class McpToolTestSupport {

    private McpToolTestSupport() {}

    public static McpToolRegistry registry(McpTool... tools) {
        McpReadTools reads = mock(McpReadTools.class);
        when(reads.tools()).thenReturn(List.of(tools));
        McpWriteTools writes = mock(McpWriteTools.class);
        when(writes.tools()).thenReturn(List.of());
        McpDomainReadTools domains = mock(McpDomainReadTools.class);
        when(domains.tools()).thenReturn(List.of());
        McpControlReadTools control = mock(McpControlReadTools.class);
        when(control.tools()).thenReturn(List.of());
        // 模块集按传入工具实际声明的模块推导，而不是写死一个名字：
        // 空值语义已改为「不暴露任何模块」（ADR 0015），而硬编码名字（曾经是 "default"）
        // 会撞上「未知模块名启动期 fail-fast」。夹具的语义是「把我给的工具都暴露出来」。
        String modules = java.util.Arrays.stream(tools)
                .map(McpTool::module)
                .distinct()
                .collect(java.util.stream.Collectors.joining(","));
        return new McpToolRegistry(reads, writes, domains, control, null, null, modules, false, false);
    }

    /** 空控制面工具组（仅测试：注册表构造占位，list_agent_tools 由真实类覆盖）。 */
    public static McpControlReadTools emptyControlTools() {
        McpControlReadTools tools = mock(McpControlReadTools.class);
        when(tools.tools()).thenReturn(List.of());
        return tools;
    }

    /**
     * 记录式绑定工厂：包装真实绑定执行器的每次工具调用（把 {@link ToolExecutionRequest} 交给
     * {@code onToolCall}）。仅覆写 3 参 bind（2 参便捷重载经虚分派落到本覆写），供评测/观测测试
     * 核对实际调用的工具序列，与生产路径（含 allowWrite 判定）保持一致。
     */
    public static AgentToolBindingFactory recordingBindingFactory(
            McpToolRegistry registry,
            McpAgentIdentity identity,
            ObjectMapper mapper,
            Consumer<ToolExecutionRequest> onToolCall) {
        AgentToolBindingFactory base = new AgentToolBindingFactory(registry, identity, mapper);
        return new AgentToolBindingFactory(registry, identity, mapper) {
            @Override
            public AgentToolBinding bind(String runId, List<String> toolNames, boolean allowWrite) {
                AgentToolBinding bound = base.bind(runId, toolNames, allowWrite);
                Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
                for (Map.Entry<ToolSpecification, ToolExecutor> entry : bound.tools().entrySet()) {
                    ToolSpecification spec = entry.getKey();
                    wrapped.put(spec, (request, memoryId) -> {
                        onToolCall.accept(request);
                        return entry.getValue().execute(request, memoryId);
                    });
                }
                return new AgentToolBinding(runId, wrapped, bound.rejectionExecutor());
            }
        };
    }

    public static McpTool tool(String name, String description) {
        return tool(name, description, Map.of(), List.of(), (context, args) -> ok(name));
    }

    /** 写工具（readOnly=false）：08 决策读写元数据，供权限判定测试构造迷你写工具。 */
    public static McpTool writeTool(String name, String description) {
        return new McpToolRegistry.SimpleTool(
                name,
                description,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> ok(name),
                false);
    }

    public static McpTool tool(
            String name,
            String description,
            Map<String, ObjectNode> properties,
            List<String> required,
            McpToolRegistry.SimpleTool.Handler handler) {
        return new McpToolRegistry.SimpleTool(
                name, description, McpToolRegistry.schema(properties, required), handler);
    }

    public static ObjectNode ok(String name) {
        return com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .objectNode()
                .put("tool", name)
                .put("ok", true);
    }
}
