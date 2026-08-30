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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MCP 工具注册表：聚合一次工具定义，分别建立 Agent 进程内工具面与外部 MCP 协议工具面。
 *
 * <p>{@code app.agent.tool-modules} 只决定 {@link #agentTools()} 与
 * {@link #findAgentTool(String)}；{@code app.mcp.protocol-modules} 只决定
 * {@link #protocolTools()} 与 {@link #findProtocolTool(String)}。任一配置解析为空都表示该工具面
 * 不提供工具，未知模块名在构造期 fail-fast。外部协议面即使误配写模块，仍由
 * {@link McpServer} 的只读门禁拒绝发现与调用。
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final Map<String, McpTool> agentByName;
    private final Map<String, McpTool> protocolByName;

    /**
     * 兼容既有测试的便捷构造：显式清单同时用于两个工具面，不带可选 provider，且不触发真实
     * 传输面启动门禁。生产装配始终走双清单构造。
     */
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            String modulesProperty) {
        this(
                readTools,
                writeTools,
                domainReadTools,
                controlReadTools,
                null,
                null,
                modulesProperty,
                modulesProperty,
                false,
                false,
                false);
    }

    /** 测试便捷构造：两个工具面都启用传入 provider 实际声明的全部模块。 */
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools) {
        this(readTools, writeTools, domainReadTools, controlReadTools, null, null, null, null, false, false, true);
    }

    @Autowired
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            McpOrdersReadTools ordersReadTools,
            KehuzxRemoteReadTools kehuzxReadTools,
            @Value("${app.agent.tool-modules:}") String agentModulesProperty,
            @Value("${app.mcp.protocol-modules:}") String protocolModulesProperty,
            @Value("${app.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${app.mcp.http.enabled:false}") boolean mcpHttpEnabled) {
        this(
                readTools,
                writeTools,
                domainReadTools,
                controlReadTools,
                ordersReadTools,
                kehuzxReadTools,
                agentModulesProperty,
                protocolModulesProperty,
                mcpEnabled,
                mcpHttpEnabled,
                false);
    }

    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            McpOrdersReadTools ordersReadTools,
            KehuzxRemoteReadTools kehuzxReadTools,
            String agentModulesProperty,
            String protocolModulesProperty) {
        this(
                readTools,
                writeTools,
                domainReadTools,
                controlReadTools,
                ordersReadTools,
                kehuzxReadTools,
                agentModulesProperty,
                protocolModulesProperty,
                false,
                false,
                false);
    }

    private McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            McpOrdersReadTools ordersReadTools,
            KehuzxRemoteReadTools kehuzxReadTools,
            String agentModulesProperty,
            String protocolModulesProperty,
            boolean mcpEnabled,
            boolean mcpHttpEnabled,
            boolean enableAllModules) {
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

        // 重名检测在两个工具面过滤之前进行：注册冲突与当前启用哪些模块无关，不能因配置隐藏。
        Set<String> seenNames = new java.util.HashSet<>();
        for (McpTool tool : tools) {
            if (!seenNames.add(tool.name())) {
                throw new IllegalStateException("重复的 MCP 工具名: " + tool.name());
            }
        }

        Set<String> knownModules = tools.stream()
                .map(McpTool::module)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> agentModules = enableAllModules
                ? knownModules
                : parseModules("app.agent.tool-modules", agentModulesProperty, knownModules);
        Set<String> protocolModules = enableAllModules
                ? knownModules
                : parseModules("app.mcp.protocol-modules", protocolModulesProperty, knownModules);

        // 启动期自检（同事 2026-08-28 提议，采纳）：MCP 开着却一个模块都没启用，
        // 是配置事故而非合法状态——运维不会有意「开启 MCP 且不暴露任何工具」。
        //
        // 为什么是 fail-fast 而不是 WARN：本条纪律此前只活在部署脚本的 grep 里，
        // 换个部署路径或手改 override 就守不住。空值语义翻成「不开」之后，
        // 失败模式从「PII 外泄」变成「机器人全哑」——哑是静默的，没人会立刻发现，
        // 等运营察觉时已过去很久。让它在部署那一刻炸，由部署者当场看见，
        // 远好过让业务同事第二天问「机器人怎么不说话了」。
        // 与本类既有的「未知模块名启动期 fail-fast」同源，不是新范式。
        // 注意条件是「任一传输面开着」而不是只看 stdio：生产实测 app.mcp.enabled 未设（=false）、
        // 只开了 app.mcp.http.enabled，若只看前者，这道自检在生产永远不触发，等于没有。
        // 双工具面拆分后本门禁只保护公共协议面；Agent 清单独立，不会再被 MCP 配置连带清空。
        if ((mcpEnabled || mcpHttpEnabled) && protocolModules.isEmpty()) {
            throw new IllegalStateException(
                    "app.mcp.protocol-modules（env MCP_PROTOCOL_MODULES / MCP_MODULES）解析后为空，"
                            + "但 MCP 传输面是开的"
                            + "（app.mcp.enabled=" + mcpEnabled
                            + ", app.mcp.http.enabled=" + mcpHttpEnabled + "）："
                            + "空值语义是「公共协议面不暴露任何模块」。"
                            + "要暴露请显式列出模块（已知：" + knownModules + "）；"
                            + "确实要整体关闭请把两个传输面开关都设为 false。");
        }

        this.agentByName = index(tools, agentModules);
        this.protocolByName = index(tools, protocolModules);
        logSurface("Agent", "app.agent.tool-modules", agentModules, agentByName.size());
        logSurface("MCP 协议", "app.mcp.protocol-modules", protocolModules, protocolByName.size());
    }

    private static Map<String, McpTool> index(List<McpTool> tools, Set<String> enabledModules) {
        Map<String, McpTool> index = new java.util.LinkedHashMap<>();
        for (McpTool tool : tools) {
            if (enabledModules.contains(tool.module())) {
                index.put(tool.name(), tool);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 解析单个工具面配置。空值或仅含分隔符都表示零模块；非空模块名必须存在，否则
     * fail-fast。配置名进入错误信息，使启动失败可以直接定位到具体工具面。
     */
    private static Set<String> parseModules(
            String propertyName, String modulesProperty, Set<String> knownModules) {
        if (modulesProperty == null || modulesProperty.isBlank()) {
            return Set.of();
        }
        Set<String> requested = Arrays.stream(modulesProperty.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requested.isEmpty()) {
            return Set.of();
        }
        Set<String> unknown = requested.stream()
                .filter(module -> !knownModules.contains(module))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                    propertyName + " 配置了未知模块名: " + unknown + "；已知模块: " + knownModules);
        }
        return Set.copyOf(requested);
    }

    private static void logSurface(
            String surfaceName, String propertyName, Set<String> modules, int toolCount) {
        if (modules.isEmpty()) {
            log.warn("{}工具面未启用任何模块（{} 为空），该工具面将不提供工具", surfaceName, propertyName);
            return;
        }
        log.info("{}工具面启用模块 {}，共 {} 个工具", surfaceName, modules, toolCount);
    }

    public List<McpTool> agentTools() {
        return List.copyOf(agentByName.values());
    }

    public Optional<McpTool> findAgentTool(String name) {
        return Optional.ofNullable(agentByName.get(name));
    }

    public Set<String> agentWriteToolNames() {
        return agentByName.values().stream()
                .filter(tool -> !tool.readOnly())
                .map(McpTool::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<McpTool> protocolTools() {
        return List.copyOf(protocolByName.values());
    }

    public Optional<McpTool> findProtocolTool(String name) {
        return Optional.ofNullable(protocolByName.get(name));
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

    public static ObjectNode booleanProperty(String description) {
        return JsonNodeFactory.instance.objectNode().put("type", "boolean").put("description", description);
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
