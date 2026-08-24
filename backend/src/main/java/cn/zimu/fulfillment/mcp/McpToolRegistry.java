package cn.zimu.fulfillment.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** MCP 工具注册表：聚合所有允许的工具，供 tools/list 发现与 tools/call 分发。 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> byName;

    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools) {
        Map<String, McpTool> index = new java.util.LinkedHashMap<>();
        List<McpTool> tools = new java.util.ArrayList<>();
        tools.addAll(readTools.tools());
        tools.addAll(writeTools.tools());
        tools.addAll(domainReadTools.tools());
        tools.addAll(controlReadTools.tools());
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

    /**
     * 全部写工具名（readOnly=false，08 决策的「默认禁写」元数据）。不变式测试以此查询
     * 替代手抄常量清单，写工具集合增长不会静默漏检。
     */
    public Set<String> writeToolNames() {
        return byName.values().stream()
                .filter(tool -> !tool.readOnly())
                .map(McpTool::name)
                .collect(Collectors.toUnmodifiableSet());
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
        // 恒带空 properties：与 McpToolSchemaConverter 的规范化输出一致（等价性测试逐字段比对）
        ObjectNode node = JsonNodeFactory.instance.objectNode()
                .put("type", "object")
                .put("description", description);
        node.putObject("properties");
        return node;
    }

    public static ObjectNode arrayProperty(String description, ObjectNode itemSchema) {
        return JsonNodeFactory.instance
                .objectNode()
                .put("type", "array")
                .put("description", description)
                .set("items", itemSchema);
    }

    /** 名称/描述/输入 Schema 的静态工具基类；invoke 委托给函数式处理器。默认只读。 */
    public static class SimpleTool implements McpTool {

        private final String name;
        private final String description;
        private final ObjectNode schema;
        private final Handler handler;
        private final boolean readOnly;

        public SimpleTool(String name, String description, ObjectNode schema, Handler handler) {
            this(name, description, schema, handler, true);
        }

        /** 写工具必须显式传 {@code readOnly=false}（08 决策：「默认禁写」为平台不变式）。 */
        public SimpleTool(
                String name, String description, ObjectNode schema, Handler handler, boolean readOnly) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.handler = handler;
            this.readOnly = readOnly;
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
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public JsonNode invoke(McpRequestContext context, Map<String, Object> arguments) {
            return handler.handle(context, arguments == null ? Map.of() : arguments);
        }
    }
}
