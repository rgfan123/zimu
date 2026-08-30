package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * MCP 分模块暴露（用户诉求：「有些 mcp 我不想提供给公共 agent」）的注册表行为验收。
 *
 * <p>不经 Spring/Testcontainers：{@link McpToolRegistry} 的模块过滤是纯构造期逻辑，
 * 用 Mockito 桩出 provider 的 {@code tools()} 即可覆盖——未配置 = 零模块（fail-safe）、
 * 显式模块只留列出的工具、被过滤的工具在 {@code find}/{@code tools/call} 上一致地
 * 「查不到」（不是列表里藏起来但还能调用的假隔离）、未知模块名启动期 fail-fast。
 */
class McpToolRegistryModuleFilterTest {

    /**
     * 语义反转（票 01）：本用例过去断言「空值 = 注册全部已知模块」，那是 fail-open——
     * 忘配 {@code MCP_MODULES} 的环境会连含客户姓名电话地址的 followup 模块一起暴露。
     * 现在空值 = 空集：漏配的失败模式是「MCP 全哑」（可诊断、可补配），而不是「PII 外泄」。
     */
    @Test
    void unconfiguredModulesRegisterNoToolAtAll() {
        McpToolRegistry registry = registry("",
                tool("read_a", "masterdata"),
                tool("read_b", "inventory"),
                writeTool("write_c", "write"));

        assertThat(registry.all()).isEmpty();
        assertThat(registry.find("read_a")).isEmpty();
        assertThat(registry.find("read_b")).isEmpty();
        assertThat(registry.find("write_c")).isEmpty();
        assertThat(registry.writeToolNames()).isEmpty();
    }

    /**
     * 生产回归：显式三模块（{@code masterdata,inventory,orders-read}）的行为不因空值语义反转而改变，
     * 列出的照常注册、未列出的（含 followup 这类带客户个人信息的模块与全部写工具）照常查不到。
     */
    @Test
    void productionModuleListKeepsExactlyThoseThreeModules() {
        McpToolRegistry registry = registry("masterdata,inventory,orders-read",
                tool("search_provider_skus", "masterdata"),
                tool("list_inventory", "inventory"),
                tool("search_orders", "orders-read"),
                tool("search_customers", "followup"),
                tool("search_messages", "messages"),
                writeTool("submit_jd_outbound", "write"));

        assertThat(registry.all()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("search_provider_skus", "list_inventory", "search_orders");
        assertThat(registry.find("search_customers")).isEmpty();
        assertThat(registry.find("search_messages")).isEmpty();
        assertThat(registry.find("submit_jd_outbound")).isEmpty();
        assertThat(registry.writeToolNames()).isEmpty();
    }

    @Test
    void explicitModulesOnlyRegisterListedModulesAndOthersAreUnfindable() {
        McpToolRegistry registry = registry("masterdata,inventory",
                tool("read_a", "masterdata"),
                tool("read_b", "inventory"),
                tool("read_c", "procurement"),
                writeTool("write_d", "write"));

        assertThat(registry.all()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("read_a", "read_b");
        assertThat(registry.find("read_a")).isPresent();
        assertThat(registry.find("read_b")).isPresent();
        // 被过滤的模块在 find 上直接查不到——不是「列表里藏起来但还能调用」的假隔离
        assertThat(registry.find("read_c")).isEmpty();
        assertThat(registry.find("write_d")).isEmpty();
        assertThat(registry.writeToolNames()).isEmpty();
    }

    @Test
    void toolsListAndToolsCallAgreeOnFilteredModules() throws Exception {
        McpToolRegistry registry = registry("masterdata",
                tool("read_a", "masterdata"),
                tool("read_c", "procurement"));
        ObjectMapper mapper = new ObjectMapper();

        JsonNode listResult = rpc(registry, mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        listResult.get("result").get("tools").forEach(node -> names.add(node.get("name").asText()));
        assertThat(names).containsExactly("read_a");

        JsonNode callFiltered = rpc(registry, mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"read_c\",\"arguments\":{}}}");
        assertThat(callFiltered.has("error"))
                .as("被过滤模块的工具必须按「工具不存在」拒绝，不能仍可调用: %s", callFiltered)
                .isTrue();
        assertThat(callFiltered.get("error").get("message").asText()).contains("Unknown tool");

        JsonNode callEnabled = rpc(registry, mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"read_a\",\"arguments\":{}}}");
        assertThat(callEnabled.has("error")).isFalse();
    }

    @Test
    void ordersReadModuleCanBeEnabledOrFilteredLikeAnyOtherModule() {
        McpToolRegistry registry = registry("orders-read",
                tool("search_orders", "orders-read"),
                tool("get_order", "orders-read"),
                tool("read_a", "masterdata"));

        assertThat(registry.all()).extracting(McpTool::name)
                .containsExactlyInAnyOrder("search_orders", "get_order");
        assertThat(registry.find("search_orders")).isPresent();
        assertThat(registry.find("get_order")).isPresent();
        // masterdata 未列出，orders-read 工具不会被顺带放行别的模块
        assertThat(registry.find("read_a")).isEmpty();
    }

    /** 未知模块名保持既有 fail-fast，且错误信息要同时给出拼错的名字与已知模块全集，运维照着就能改。 */
    @Test
    void unknownModuleNameFailsFastAtConstruction() {
        assertThatThrownBy(() -> registry("mastrdata", tool("read_a", "masterdata"), tool("read_b", "inventory")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mastrdata")
                .hasMessageContaining("masterdata")
                .hasMessageContaining("inventory");
    }

    /** 空白与「只有逗号」同样落到零模块：任何解析不出模块名的配置都不得退化成放行。 */
    @Test
    void blankAndCommaOnlyModulesPropertyRegisterNoTool() {
        McpToolRegistry viaBlank = registry("   ", tool("read_a", "masterdata"));
        assertThat(viaBlank.all()).isEmpty();
        assertThat(viaBlank.find("read_a")).isEmpty();

        McpToolRegistry viaCommaOnly = registry(" , ", tool("read_a", "masterdata"));
        assertThat(viaCommaOnly.all()).isEmpty();
        assertThat(viaCommaOnly.find("read_a")).isEmpty();
    }

    /**
     * 未配置时协议面同样一致：{@code tools/list} 为空列表，{@code tools/call} 按「工具不存在」拒绝。
     * 排除发生在注册期，所以不可能出现「列表藏起来但还能调用」。
     */
    @Test
    void unconfiguredModulesRejectToolsCallExactlyLikeToolsList() throws Exception {
        McpToolRegistry registry = registry("", tool("read_a", "masterdata"));
        ObjectMapper mapper = new ObjectMapper();

        JsonNode listResult = rpc(registry, mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        assertThat(listResult.get("result").get("tools")).isEmpty();

        JsonNode callResult = rpc(registry, mapper,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"read_a\",\"arguments\":{}}}");
        assertThat(callResult.has("error"))
                .as("未配置模块时工具调用必须一律拒绝: %s", callResult)
                .isTrue();
        assertThat(callResult.get("error").get("message").asText()).contains("Unknown tool");
    }

    private static McpToolRegistry registry(String modulesProperty, McpTool... tools) {
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
        return new McpToolRegistry(reads, writes, domains, control, null, null, modulesProperty);
    }

    private static McpTool tool(String name, String module) {
        return new McpToolRegistry.SimpleTool(
                name,
                "测试工具 " + name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> null,
                module);
    }

    private static McpTool writeTool(String name, String module) {
        return new McpToolRegistry.SimpleTool(
                name,
                "测试写工具 " + name,
                McpToolRegistry.schema(Map.of(), List.of()),
                (context, args) -> null,
                false,
                module);
    }

    private static JsonNode rpc(McpToolRegistry registry, ObjectMapper mapper, String requestLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((requestLine + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity("module-filter-test"),
                mapper);
        server.run();
        String output = out.toString(StandardCharsets.UTF_8);
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(1);
        return mapper.readTree(lines.getFirst());
    }
}
