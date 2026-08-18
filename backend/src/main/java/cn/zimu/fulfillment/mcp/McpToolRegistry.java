package cn.zimu.fulfillment.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** MCP 工具注册表：聚合所有允许的工具，供 tools/list 发现与 tools/call 分发。 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> byName;

    public McpToolRegistry(McpReadTools readTools, McpWriteTools writeTools, McpDomainReadTools domainReadTools) {
        Map<String, McpTool> index = new java.util.LinkedHashMap<>();
        List<McpTool> tools = new java.util.ArrayList<>();
        tools.addAll(readTools.tools());
        tools.addAll(writeTools.tools());
        tools.addAll(domainReadTools.tools());
        for (McpTool tool : tools) {
            McpTool previous = index.putIfAbsent(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("重复的 MCP 工具名: " + tool.name());
            }
        }
        this.byName = Map.copyOf(index);
    }

    public List<McpTool> all() {
        return List.copyOf(byName.values());
    }

    public Optional<McpTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** 工具输入 JSON Schema 构建助手。 */
    public static ObjectNode schema(Map<String, ObjectNode> properties, List<String> required) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        ArrayNode requiredArray = schema.putArray("required");
        required.forEach(requiredArray::add);
        ObjectNode props = schema.putObject("properties");
        properties.forEach(props::set);
        return schema;
    }

    public static ObjectNode stringProperty(String description) {
        return JsonNodeFactory.instance.objectNode().put("type", "string").put("description", description);
    }

    public static ObjectNode integerProperty(String description) {
        return JsonNodeFactory.instance.objectNode().put("type", "integer").put("description", description);
    }

    public static ObjectNode objectProperty(String description) {
        return JsonNodeFactory.instance.objectNode().put("type", "object").put("description", description);
    }

    public static ObjectNode arrayProperty(String description, ObjectNode itemSchema) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("type", "array")
                .put("description", description)
                .set("items", itemSchema);
    }

    /** 名称/描述/输入 Schema 的静态工具基类；invoke 委托给函数式处理器。 */
    public static class SimpleTool implements McpTool {

        private final String name;
        private final String description;
        private final ObjectNode schema;
        private final Handler handler;

        public SimpleTool(String name, String description, ObjectNode schema, Handler handler) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.handler = handler;
        }

        public interface Handler {
            JsonNode handle(McpRequestContext context, Map<String, Object> arguments);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public JsonNode inputSchema() {
            return schema;
        }

        @Override
        public JsonNode invoke(McpRequestContext context, Map<String, Object> arguments) {
            return handler.handle(context, arguments == null ? Map.of() : arguments);
        }
    }
}
