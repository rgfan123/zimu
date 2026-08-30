package cn.zimu.fulfillment.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * MCP 控制面只读工具（06 决策；meta-agent-platform-impl 10）：{@code list_agent_tools}
 * 返回 Agent 工具面全部工具的名称/描述/参数 schema/读写属性（07 票读写元数据），供元 Agent
 * 规划工具白名单——工具面增长无需改提示词（「注册一次自动获得」延续）。
 *
 * <p>经 {@link ObjectProvider} 懒解析 {@link McpToolRegistry}（构造期互依赖：
 * 注册表聚合本类的工具定义，本类的执行期才需要注册表全量列表）。
 */
@Component
public class McpControlReadTools {

    private final ObjectProvider<McpToolRegistry> registryProvider;
    private final ObjectMapper mapper;

    public McpControlReadTools(ObjectProvider<McpToolRegistry> registryProvider, ObjectMapper mapper) {
        this.registryProvider = registryProvider;
        this.mapper = mapper;
    }

    public List<McpTool> tools() {
        return List.of(new McpToolRegistry.SimpleTool(
                "list_agent_tools",
                "列出平台注册的全部 Agent 工具（名称/描述/参数 JSON Schema/读写属性 readOnly），供规划 Agent 工具白名单。",
                McpToolRegistry.schema(Map.of(), List.of()),
                this::listAgentTools,
                "control") {
            @Override
            public boolean externallyDiscoverable() {
                // 结果包含 Agent-only 写工具的名称和 schema，只允许进程内 Agent 绑定调用。
                return false;
            }
        });
    }

    private JsonNode listAgentTools(McpRequestContext context, Map<String, Object> arguments) {
        ArrayNode tools = mapper.createArrayNode();
        for (McpTool tool : registryProvider.getObject().agentTools()) {
            ObjectNode item = tools.addObject();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.set("inputSchema", tool.inputSchema());
            item.put("readOnly", tool.readOnly());
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }
}
