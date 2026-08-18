package cn.zimu.fulfillment.agent;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.mcp.McpDomainReadTools;
import cn.zimu.fulfillment.mcp.McpReadTools;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

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
        return new McpToolRegistry(reads, writes, domains);
    }

    public static McpTool tool(String name, String description) {
        return tool(name, description, Map.of(), List.of(), (context, args) -> ok(name));
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
