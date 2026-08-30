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
 * （env {@code MCP_MODULES}，逗号分隔）**未配置时一个工具都不注册**；非空时只把列出模块的
 * 工具收进 {@link #byName}——被排除的工具在 {@link #find} 上直接查不到，{@code tools/call}
 * 因此天然按「工具不存在」拒绝，不会出现「列表里藏起来但还能调用」的假隔离。未知模块名
 * （相对全部工具实际声明的模块集合）在构造期 fail-fast，防止拼错模块名静默少开模块。
 *
 * <p>空值语义已从「全部模块」反转为「零模块」：原先漏配 {@code MCP_MODULES} 的环境会把含客户
 * 姓名/电话/地址的 followup 模块连同写工具一并暴露，安全全靠运维「记得配置」。现在漏配的失败
 * 模式是「MCP 全哑」——启动即可见、补一行配置就恢复，而不是安静地把 PII 摆到公网。
 */
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> byName;

    /**
     * 便捷构造（不带 orders-read / kehuzx provider）：模块清单必须显式传入。
     * 这里刻意不提供「省略即全开」的重载——那正是本类要消灭的 fail-open 语义。
     */
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            String modulesProperty) {
        // 两个传输面开关恒传 false：本构造只服务测试/内嵌场景，不该触发「开着却零模块」
        // 的启动期自检——那道自检针对的是真实部署漏配 MCP_MODULES。
        this(readTools, writeTools, domainReadTools, controlReadTools, null, null, modulesProperty, false, false);
    }

    @Autowired
    public McpToolRegistry(
            McpReadTools readTools,
            McpWriteTools writeTools,
            McpDomainReadTools domainReadTools,
            McpControlReadTools controlReadTools,
            McpOrdersReadTools ordersReadTools,
            KehuzxRemoteReadTools kehuzxReadTools,
            @Value("${app.mcp.modules:}") String modulesProperty,
            @Value("${app.mcp.enabled:false}") boolean mcpEnabled,
            @Value("${app.mcp.http.enabled:false}") boolean mcpHttpEnabled) {
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
        //
        // 影响面比「对外 MCP」更宽：本注册表同时是内部 Agent 平台的工具源
        // （AgentToolInvoker 从 find(name) 取工具），所以 MCP_MODULES 丢失会让
        // 对外 HTTP 面与企微机器人一起变哑。这正是必须在启动期炸掉的理由。
        if ((mcpEnabled || mcpHttpEnabled) && enabledModules.isEmpty()) {
            throw new IllegalStateException(
                    "app.mcp.modules（env MCP_MODULES）解析后为空，但 MCP 传输面是开的"
                            + "（app.mcp.enabled=" + mcpEnabled
                            + ", app.mcp.http.enabled=" + mcpHttpEnabled + "）："
                            + "空值语义是「不暴露任何模块」，这会让 MCP 面与内部 Agent 平台都拿不到工具。"
                            + "要暴露请显式列出模块（已知：" + knownModules + "）；"
                            + "确实要整体关闭请把两个传输面开关都设为 false。");
        }

        Map<String, McpTool> index = new java.util.LinkedHashMap<>();
        for (McpTool tool : tools) {
            if (enabledModules.contains(tool.module())) {
                index.put(tool.name(), tool);
            }
        }
        this.byName = Map.copyOf(index);
    }

    /**
     * 解析 {@code app.mcp.modules}。**空值（未配置、纯空白、只有逗号）= 零模块**：想用 MCP 的
     * 环境必须显式列出模块名，忘配的代价是「什么都不开放」而不是「什么都开放」。非空则只启用
     * 列出的模块，列出的模块名必须都在 {@code knownModules} 中出现过，否则 fail-fast——拼错
     * 模块名要么整段部署起不来，绝不能静默按「少开一个模块」了事。
     */
    private static Set<String> parseModules(String modulesProperty, Set<String> knownModules) {
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
