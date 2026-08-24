package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 03 — Agent ↔ MCP 工具绑定（agent-decision-layer 03）：注册表工具与 Agent 可见工具
 * 一一对应（同名/同描述/同 schema/invoke 结果等价）、白名单过滤、未知工具 fail-fast、
 * 去重，以及注册表集合变更时 Agent 描述自动同步（无手工维护漂移）。
 */
class AgentToolBindingFactoryTest {

    private static final String RUN_ID = "run_" + "0".repeat(32);

    private final McpToolRegistry registry = McpToolTestSupport.registry(
            McpToolTestSupport.tool("list_channel_messages", "分页查询企业微信渠道消息摘要。"),
            McpToolTestSupport.tool("get_message_submission", "查询消息提交详情。"),
            McpToolTestSupport.tool(
                    "list_order_drafts",
                    "分页查询订单草稿。",
                    Map.of(
                            "status", McpToolRegistry.stringProperty("草稿状态：OPEN/REJECTED/CONFIRMED"),
                            "page", McpToolRegistry.integerProperty("页码，从 0 开始")),
                    List.of(),
                    (context, args) -> McpToolTestSupport.ok("list_order_drafts")));

    private final AgentToolBindingFactory factory =
            new AgentToolBindingFactory(registry, new McpAgentIdentity("binding-agent"), new ObjectMapper());

    @Test
    void whitelistedToolsMapOneToOneToRegistry() {
        AgentToolBinding binding = factory.bind(RUN_ID, List.of("list_channel_messages", "list_order_drafts"));

        assertThat(binding.runId()).isEqualTo(RUN_ID);
        // 注册表 all() 的顺序不保证（Map.copyOf），等价性断言不依赖顺序
        assertThat(binding.specifications()).hasSize(2);
        assertThat(binding.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("list_channel_messages", "list_order_drafts");
        for (String name : new String[] {"list_channel_messages", "list_order_drafts"}) {
            ToolSpecification spec = specOf(binding, name);
            assertThat(spec.description()).isEqualTo(registry.find(name).orElseThrow().description());
            McpToolSchemaTestSupport.assertSchemaEquals(
                    registry.find(name).orElseThrow().inputSchema().deepCopy(), spec.parameters());
        }
    }

    @Test
    void whitelistedToolsAllRouteThroughSingleRunScopedInvoker() {
        AgentToolBinding binding = factory.bind(RUN_ID, List.of("list_channel_messages", "list_order_drafts"));

        assertThat(binding.tools().values()).hasSize(2);
        assertThat(binding.tools().values()).allSatisfy(executor -> assertThat(executor)
                .isInstanceOf(AgentToolInvoker.class));
        assertThat(binding.tools().values())
                .extracting(executor -> ((AgentToolInvoker) executor).runId())
                .containsOnly(RUN_ID);
    }

    @Test
    void toolsOutsideWhitelistAreNotExposed() {
        AgentToolBinding binding = factory.bind(RUN_ID, List.of("list_order_drafts"));

        assertThat(binding.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("list_order_drafts");
    }

    @Test
    void emptyWhitelistYieldsEmptyBinding() {
        AgentToolBinding binding = factory.bind(RUN_ID, List.of());

        assertThat(binding.isEmpty()).isTrue();
        assertThat(binding.specifications()).isEmpty();
        assertThat(binding.tools()).isEmpty();
    }

    @Test
    void nullWhitelistYieldsEmptyBinding() {
        AgentToolBinding binding = factory.bind(RUN_ID, null);

        assertThat(binding.isEmpty()).isTrue();
    }

    @Test
    void duplicateWhitelistEntriesAreDeduplicated() {
        AgentToolBinding binding =
                factory.bind(RUN_ID, List.of("list_channel_messages", "list_channel_messages", "list_order_drafts"));

        assertThat(binding.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("list_channel_messages", "list_order_drafts");
    }

    @Test
    void whitelistReferencingUnknownToolFailsFast() {
        assertThatThrownBy(() -> factory.bind(RUN_ID, List.of("list_channel_messages", "no_such_tool")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_tool")
                .hasMessageContaining("AgentDefinition.tool_names");
    }

    @Test
    void registrySetChangeAutoSyncsIntoAgentDescriptions() {
        // 注册表只有两个工具时，同一白名单只暴露两个
        AgentToolBinding before = factory.bind(RUN_ID, List.of("list_channel_messages", "list_order_drafts"));
        assertThat(before.specifications()).hasSize(2);

        // 注册表新增工具（同一白名单，Agent 侧零改动）→ 描述自动同步
        McpToolRegistry grown = McpToolTestSupport.registry(
                McpToolTestSupport.tool("list_channel_messages", "分页查询企业微信渠道消息摘要。"),
                McpToolTestSupport.tool("list_order_drafts", "分页查询订单草稿。"),
                McpToolTestSupport.tool("get_sku", "查询单个 SKU 详情。"));
        AgentToolBindingFactory grownFactory =
                new AgentToolBindingFactory(grown, new McpAgentIdentity("binding-agent"), new ObjectMapper());

        AgentToolBinding after =
                grownFactory.bind(RUN_ID, List.of("list_channel_messages", "list_order_drafts", "get_sku"));

        assertThat(after.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("list_channel_messages", "list_order_drafts", "get_sku");
        ToolSpecification sku = specOf(after, "get_sku");
        McpToolSchemaTestSupport.assertSchemaEquals(
                grown.find("get_sku").orElseThrow().inputSchema().deepCopy(), sku.parameters());
    }

    @Test
    void bindingAllRegistryToolsExposesExactlyRegistrySet() {
        List<String> allNames = registry.all().stream().map(McpTool::name).toList();

        AgentToolBinding binding = factory.bind(RUN_ID, allNames);

        assertThat(binding.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrderElementsOf(allNames);
    }

    @Test
    void whitelistWithWriteToolWithoutAllowWriteFailsFastAtBindTime() {
        // 08 决策：白名单含写工具（readOnly=false）且未声明 allow_write → 绑定期拒绝
        McpToolRegistry withWrite = McpToolTestSupport.registry(
                McpToolTestSupport.tool("list_channel_messages", "只读工具。"),
                McpToolTestSupport.writeTool("reinterpret_submission", "写工具。"));
        AgentToolBindingFactory strictFactory =
                new AgentToolBindingFactory(withWrite, new McpAgentIdentity("binding-agent"), new ObjectMapper());

        assertThatThrownBy(() -> strictFactory.bind(RUN_ID, List.of("reinterpret_submission")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allow_write")
                .hasMessageContaining("reinterpret_submission");

        // 显式 allowWrite=true 时写工具可绑定（meta-agent 等受约束放行）
        AgentToolBinding allowed =
                strictFactory.bind(RUN_ID, List.of("reinterpret_submission"), true);
        assertThat(allowed.specifications())
                .extracting(ToolSpecification::name)
                .containsExactly("reinterpret_submission");
    }

    private static ToolSpecification specOf(AgentToolBinding binding, String name) {
        return binding.specifications().stream()
                .filter(spec -> name.equals(spec.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("绑定中缺少工具: " + name));
    }
}
