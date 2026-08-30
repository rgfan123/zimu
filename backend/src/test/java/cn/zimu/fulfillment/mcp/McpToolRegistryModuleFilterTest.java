package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

/** Agent 进程内工具面与外部 MCP 协议工具面的独立模块配置验收。 */
class McpToolRegistryModuleFilterTest {

    @Test
    void agentAndProtocolModulesAreIndependent() {
        McpToolRegistry registry = registry(
                "control,write",
                "masterdata,inventory",
                tool("list_agent_tools", "control"),
                writeTool("create_agent_draft", "write"),
                tool("search_skus", "masterdata"),
                tool("get_inventory_overview", "inventory"));

        assertThat(registry.agentTools()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("list_agent_tools", "create_agent_draft");
        assertThat(registry.protocolTools()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("search_skus", "get_inventory_overview");
        assertThat(registry.findAgentTool("create_agent_draft")).isPresent();
        assertThat(registry.findProtocolTool("create_agent_draft")).isEmpty();
        assertThat(registry.findAgentTool("search_skus")).isEmpty();
        assertThat(registry.findProtocolTool("search_skus")).isPresent();
    }

    @Test
    void changingOneSurfaceDoesNotChangeTheOther() {
        McpTool[] tools = {
            tool("list_agent_tools", "control"),
            writeTool("create_agent_draft", "write"),
            tool("search_skus", "masterdata")
        };
        McpToolRegistry before = registry("control", "masterdata", tools);
        McpToolRegistry agentChanged = registry("control,write", "masterdata", tools);
        McpToolRegistry protocolChanged = registry("control", "masterdata,write", tools);

        assertThat(agentChanged.protocolTools()).extracting(McpTool::name)
                .containsExactlyElementsOf(before.protocolTools().stream().map(McpTool::name).toList());
        assertThat(protocolChanged.agentTools()).extracting(McpTool::name)
                .containsExactlyElementsOf(before.agentTools().stream().map(McpTool::name).toList());
        assertThat(agentChanged.findAgentTool("create_agent_draft")).isPresent();
        assertThat(protocolChanged.findProtocolTool("create_agent_draft")).isPresent();
    }

    @Test
    void emptyOrDelimiterOnlyConfigurationDisablesEachSurface() {
        McpToolRegistry empty = registry("", "   ", tool("read_a", "masterdata"));
        McpToolRegistry delimiters = registry(" , ", ",", tool("read_a", "masterdata"));

        assertThat(empty.agentTools()).isEmpty();
        assertThat(empty.protocolTools()).isEmpty();
        assertThat(delimiters.agentTools()).isEmpty();
        assertThat(delimiters.protocolTools()).isEmpty();
    }

    @Test
    void unknownAgentOrProtocolModuleFailsFastWithConfigurationName() {
        assertThatThrownBy(() -> registry("mastrdata", "masterdata", tool("read_a", "masterdata")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.agent.tool-modules")
                .hasMessageContaining("mastrdata");

        assertThatThrownBy(() -> registry("masterdata", "inventry", tool("read_a", "masterdata")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.protocol-modules")
                .hasMessageContaining("inventry");
    }

    @Test
    void protocolListAndCallUseOnlyProtocolModules() {
        McpToolRegistry registry = registry(
                "control,write",
                "masterdata",
                tool("list_agent_tools", "control"),
                writeTool("create_agent_draft", "write"),
                tool("search_skus", "masterdata"));
        McpServer server = new McpServer(registry, new McpAgentIdentity("protocol-test"), new ObjectMapper());

        JsonNode list = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        list.path("result").path("tools").forEach(node -> names.add(node.path("name").asText()));
        assertThat(names).containsExactly("search_skus");

        JsonNode hiddenAgentTool = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_agent_tools\",\"arguments\":{}}}");
        assertThat(hiddenAgentTool.path("error").path("message").asText()).contains("Unknown tool");
    }

    @Test
    void metaAgentDraftWhitelistBindsWhenProtocolSurfaceExcludesControlAndWrite() {
        McpToolRegistry registry = registry(
                "masterdata,inventory,orders-read,control,write",
                "masterdata,inventory,orders-read",
                tool("list_agent_tools", "control"),
                writeTool("create_agent_draft", "write"),
                writeTool("update_agent_draft", "write"),
                tool("search_skus", "masterdata"),
                tool("get_inventory_overview", "inventory"),
                tool("search_orders", "orders-read"));

        AgentToolBinding binding = new AgentToolBindingFactory(
                        registry, new McpAgentIdentity("meta-agent-test"), new ObjectMapper())
                .bind(
                        "run_" + "0".repeat(32),
                        List.of("list_agent_tools", "create_agent_draft", "update_agent_draft"),
                        true);

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("list_agent_tools", "create_agent_draft", "update_agent_draft");
        assertThat(registry.findProtocolTool("list_agent_tools")).isEmpty();
        assertThat(registry.findProtocolTool("create_agent_draft")).isEmpty();
        assertThat(registry.findProtocolTool("update_agent_draft")).isEmpty();
    }

    @Test
    void protocolRejectsWritesEvenWhenItsModuleConfigurationIncludesWrite() {
        McpToolRegistry registry = registry(
                "write",
                "masterdata,write",
                tool("search_skus", "masterdata"),
                writeTool("submit_jd_outbound", "write"));
        McpServer server = new McpServer(registry, new McpAgentIdentity("protocol-test"), new ObjectMapper());

        JsonNode list = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(list.path("result").path("tools").toString())
                .contains("search_skus")
                .doesNotContain("submit_jd_outbound");

        JsonNode call = server.handleRequest(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"submit_jd_outbound\",\"arguments\":{}}}");
        assertThat(call.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(call.path("error").path("message").asText()).contains("read-only");
    }

    @Test
    void enabledTransportWithNoProtocolModuleFailsFastButDisabledTransportMayStayEmpty() {
        McpTool tool = tool("search_skus", "masterdata");

        assertThatThrownBy(() -> registry("masterdata", "", true, false, tool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.protocol-modules")
                .hasMessageContaining("app.mcp.enabled=true");

        assertThat(registry("masterdata", "", false, false, tool).protocolTools()).isEmpty();

        assertThatThrownBy(() -> registry("masterdata", "", false, true, tool))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.mcp.http.enabled=true");
    }

    @Test
    void productionProtocolModulesExposeExactlyTheThreeReviewedReadModules() {
        McpToolRegistry registry = registry(
                "control,write",
                "masterdata,inventory,orders-read",
                tool("search_skus", "masterdata"),
                tool("get_inventory_overview", "inventory"),
                tool("search_orders", "orders-read"),
                tool("list_agent_tools", "control"),
                writeTool("submit_jd_outbound", "write"));

        assertThat(registry.protocolTools()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("search_skus", "get_inventory_overview", "search_orders");
    }

    @Test
    void listAgentToolsIsAgentInternalEvenIfControlIsAddedToProtocolConfiguration() {
        @SuppressWarnings("unchecked")
        ObjectProvider<McpToolRegistry> provider = mock(ObjectProvider.class);
        McpTool listAgentTools = new McpControlReadTools(provider, new ObjectMapper()).tools().getFirst();

        assertThat(listAgentTools.name()).isEqualTo("list_agent_tools");
        assertThat(listAgentTools.externallyDiscoverable()).isFalse();
    }

    private static McpToolRegistry registry(
            String agentModulesProperty, String protocolModulesProperty, McpTool... tools) {
        return registry(agentModulesProperty, protocolModulesProperty, false, false, tools);
    }

    private static McpToolRegistry registry(
            String agentModulesProperty,
            String protocolModulesProperty,
            boolean mcpEnabled,
            boolean mcpHttpEnabled,
            McpTool... tools) {
        List<McpTool> readTools = new ArrayList<>();
        List<McpTool> writeTools = new ArrayList<>();
        for (McpTool tool : tools) {
            (tool.readOnly() ? readTools : writeTools).add(tool);
        }
        McpReadTools reads = mock(McpReadTools.class);
        when(reads.tools()).thenReturn(readTools);
        McpWriteTools writes = mock(McpWriteTools.class);
        when(writes.tools()).thenReturn(writeTools);
        McpDomainReadTools domains = mock(McpDomainReadTools.class);
        when(domains.tools()).thenReturn(List.of());
        McpControlReadTools control = mock(McpControlReadTools.class);
        when(control.tools()).thenReturn(List.of());
        return new McpToolRegistry(
                reads,
                writes,
                domains,
                control,
                null,
                null,
                agentModulesProperty,
                protocolModulesProperty,
                mcpEnabled,
                mcpHttpEnabled);
    }

    private static McpTool tool(String name, String module) {
        return new McpToolRegistry.SimpleTool(
                name,
                "测试工具 " + name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                module);
    }

    private static McpTool writeTool(String name, String module) {
        return new McpToolRegistry.SimpleTool(
                name,
                "测试写工具 " + name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                false,
                module);
    }
}
