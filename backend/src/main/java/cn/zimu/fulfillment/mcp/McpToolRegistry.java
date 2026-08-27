package cn.zimu.fulfillment.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import cn.zimu.fulfillment.followup.KehuzxRemoteReadTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 工具注册表：聚合所有允许的工具，供 tools/list 发现与 tools/call 分发。
 *
 * <p>分模块暴露（用户诉求：「有些 mcp 我不想提供给公共 agent」）：{@code app.mcp.modules}
 * （env {@code MCP_MODULES}，逗号分隔）为空时注册全部模块（向后兼容）；非空时只把列出模块的
 * 工具收进 {@link #byName}——被排除的工具在 {@link #find} 上直接查不到，{@code tools/call}
 * 因此天然按「工具不存在」拒绝，不会出现「列表里藏起来但还能调用」的假隔离。未知模块名
 * （相对全部工具实际声明的模块集合）在构造期 fail-fast，防止拼错模块名静默放行全部工具。
 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> byName;

    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools) {
        this(readTools, writeTools, domainReadTools, controlReadTools, null, null, "");
    }

    @Autowired
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            McpOrdersReadTools ordersReadTools,
            KehuzxRemoteReadTools kehuzxReadTools,
            @Value("${app.mcp.modules:}") String modulesProperty) {
        List<McpTool> tools = new java.util.ArrayList<>();
        tools.addAll(readTools.tools());
        tools.addAll(writeTools.tools());
        tools.addAll(domainReadTools.tools());
        tools.addAll(controlReadTools.tools());
        if (ordersReadTools != null) {
            tools.addAll(ordersReadTools.tools());
        }
        if (kehuzxReadTools != null) {
            tools.addAll(kehuzxReadTools.tools());
        }

        // 重名检测在过滤模块之前进行：注册冲突是与「当前启用哪些模块」无关的不变式，
        // 不能等运维改了 MCP_MODULES 才炸出来。
        Set<String> seenNames = new java.util.HashSet<>();
        for (McpTool tool : tools) {
            if (!seenNames.add(tool.name())) {
                throw new IllegalStateException("重复的 MCP 工具名: " + tool.name());
            }
        }

        Set<String> knownModules = tools.stream()
                .map(McpTool::module)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> enabledModules = parseModules(modulesProperty, knownModules);

        Map<String, McpTool> index = new java.util.LinkedHashMap<>();
        for (McpTool tool : tools) {
            if (enabledModules.contains(tool.module())) {
                index.put(tool.name(), tool);
            }
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * 解析 {@code app.mcp.modules}。空值（未配置）= 全部已知模块，向后兼容一期「注册即暴露」；
     * 非空则只启用列出的模块，列出的模块名必须都在 {@code knownModules} 中出现过，否则
     * fail-fast——拼错模块名要么整段部署起不来，绝不能静默放行全部工具当无事发生。
     */
    private static Set<String> parseModules(String modulesProperty, Set<String> knownModules) {
        if (modulesProperty == null || modulesProperty.isBlank()) {
            return knownModules;
        }
        Set<String> requested = Arrays.stream(modulesProperty.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            return knownModules;
        }
        Set<String> unknown = requested.stream()
                .filter(module -> !knownModules.contains(module))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    "app.mcp.modules 配置了未知模块名: " + unknown + "；已知模块: " + knownModules);
        }
        return requested;
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
        private final String module;

        public SimpleTool(String name, String description, ObjectNode schema, Handler handler) {
            this(name, description, schema, handler, true, "default");
        }

        /** 写工具必须显式传 {@code readOnly=false}（08 决策：「默认禁写」为平台不变式）。 */
        public SimpleTool(
                String name, String description, ObjectNode schema, Handler handler, boolean readOnly) {
            this(name, description, schema, handler, readOnly, "default");
        }

        /** 只读工具的模块化构造：显式声明所属模块（分模块暴露，见 {@link McpTool#module()}）。 */
        public SimpleTool(
                String name, String description, ObjectNode schema, Handler handler, String module) {
            this(name, description, schema, handler, true, module);
        }

        /** 全参构造：读写属性 + 所属模块都显式声明，五个内建 provider 与写工具一律走这个重载。 */
        public SimpleTool(
                String name,
                String description,
                ObjectNode schema,
                Handler handler,
                boolean readOnly,
                String module) {
            this.name = name;
            this.description = description;
            this.schema = schema;
            this.handler = handler;
            this.readOnly = readOnly;
            this.module = module;
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
        public String module() {
            return module;
        }

        @Override
        public JsonNode invoke(McpRequestContext context, Map<String, Object> arguments) {
            return handler.handle(context, arguments == null ? Map.of() : arguments);
        }
    }
}
