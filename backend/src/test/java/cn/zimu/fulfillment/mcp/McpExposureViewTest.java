package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * MCP 开放面只读核对视图（票 05）的对外行为验收。
 *
 * <p>不经 Spring/Testcontainers：视图是注册表的纯投影，用 Mockito 桩出 provider 的
 * {@code tools()} 即可覆盖——与 {@link McpToolRegistryModuleFilterTest} 同一条构造路径，
 * 因此这里验的「已开放」与真实注册结果同源，不是另一份对配置字符串的解析。
 */
class McpExposureViewTest {

    /** 生产口径（masterdata,inventory,orders-read）：已开放模块按模块分组给出工具名与用途摘要。 */
    @Test
    void openModulesGroupRegisteredToolsWithTheirDeclaredDescription() {
        McpExposure exposure = exposure("masterdata,inventory,orders-read",
                tool("search_provider_skus", "masterdata", "按关键词检索履约方 SKU"),
                tool("list_inventory", "inventory", "查询库存总览"),
                tool("get_order", "orders-read", "按订单号查订单详情"),
                tool("search_orders", "orders-read", "按条件检索订单"),
                tool("search_customers", "followup", "检索客户档案"),
                writeTool("submit_jd_outbound", "write", "提交京东出库单"));

        assertThat(exposure.openModules()).extracting(McpExposureModule::module)
                .containsExactly("masterdata", "inventory", "orders-read");
        assertThat(exposure.openModules().get(0).tools())
                .containsExactly(new McpExposureTool("search_provider_skus", "按关键词检索履约方 SKU", true));
        // 模块内按工具名升序：同一份配置每次打开视图排布一致，逐条核对才有意义
        assertThat(exposure.openModules().get(2).tools()).extracting(McpExposureTool::name)
                .containsExactly("get_order", "search_orders");
        assertThat(exposure.openModules().get(2).tools()).extracting(McpExposureTool::description)
                .containsExactly("按订单号查订单详情", "按条件检索订单");
    }

    /**
     * 「已知但未开放」只给模块名，不给工具明细——未开放模块的工具根本没进注册表（票 01 的排除
     * 发生在注册期），凭空列出「开了会有什么」就得另建一份必然漂移的清单。
     */
    @Test
    void knownButUnopenedModulesAreNamedWithoutLeakingTheirToolDetail() {
        McpExposure exposure = exposure("masterdata",
                tool("search_provider_skus", "masterdata", "按关键词检索履约方 SKU"),
                tool("get_order_draft", "messages", "读订单草稿详情"),
                tool("search_customers", "followup", "检索客户档案"),
                writeTool("submit_jd_outbound", "write", "提交京东出库单"));

        assertThat(exposure.unopenedModules()).containsExactly("messages", "followup", "write");
        assertThat(exposure.openModules()).extracting(McpExposureModule::module).containsExactly("masterdata");
        List<String> exposedToolNames = exposure.openModules().stream()
                .flatMap(module -> module.tools().stream())
                .map(McpExposureTool::name)
                .toList();
        assertThat(exposedToolNames)
                .as("未开放模块的工具名不得出现在视图里")
                .containsExactly("search_provider_skus");
    }

    /** 写工具的读写属性如实呈现：开放面评审最需要看清的就是「这里面有没有写」。 */
    @Test
    void writeToolsAreMarkedAsNotReadOnly() {
        McpExposure exposure = exposure("write", writeTool("submit_jd_outbound", "write", "提交京东出库单"));

        assertThat(exposure.openModules()).singleElement()
                .extracting(McpExposureModule::tools)
                .isEqualTo(List.of(new McpExposureTool("submit_jd_outbound", "提交京东出库单", false)));
    }

    /**
     * 未开放任何模块（{@code MCP_MODULES} 未配置的 fail-safe 语义）是合法状态：
     * 已开放为空、已知模块原样落到「未开放」，视图照常给得出答案而不是报错。
     */
    @Test
    void nothingOpenIsAnHonestEmptyStateRatherThanAFailure() {
        McpExposure exposure = exposure("",
                tool("search_provider_skus", "masterdata", "按关键词检索履约方 SKU"),
                tool("list_inventory", "inventory", "查询库存总览"));

        assertThat(exposure.openModules()).isEmpty();
        assertThat(exposure.unopenedModules()).containsExactly("masterdata", "inventory");
    }

    /** 全部模块开放时「未开放」为空——空态在两端都要成立，不能只处理一头。 */
    @Test
    void everythingOpenLeavesNoUnopenedModule() {
        McpExposure exposure = exposure("masterdata,inventory",
                tool("search_provider_skus", "masterdata", "按关键词检索履约方 SKU"),
                tool("list_inventory", "inventory", "查询库存总览"));

        assertThat(exposure.openModules()).extracting(McpExposureModule::module)
                .containsExactly("masterdata", "inventory");
        assertThat(exposure.unopenedModules()).isEmpty();
    }

    /**
     * 视图与注册表同源：凡是视图说「已开放」的工具，注册表都必须真的能 {@code find} 到；
     * 凡是它说「未开放」的模块，注册表里不得有任何该模块的工具。
     *
     * <p>这条挡的是把配置字符串再解析一遍的实现——那样界面会显示「开着」而调用方拿不到工具。
     */
    @Test
    void theViewNeverDisagreesWithWhatTheRegistryActuallyRegistered() {
        McpToolRegistry registry = registry("masterdata,orders-read",
                tool("search_provider_skus", "masterdata", "按关键词检索履约方 SKU"),
                tool("search_orders", "orders-read", "按条件检索订单"),
                tool("get_order_draft", "messages", "读订单草稿详情"));
        McpExposure exposure = new McpExposureReadService(registry).exposure();

        for (McpExposureModule module : exposure.openModules()) {
            for (McpExposureTool viewTool : module.tools()) {
                assertThat(registry.findProtocolTool(viewTool.name()))
                        .as("视图声称已开放的工具 %s 必须真的在注册表里", viewTool.name())
                        .isPresent();
                assertThat(registry.findProtocolTool(viewTool.name()).orElseThrow().module()).isEqualTo(module.module());
            }
        }
        for (String unopened : exposure.unopenedModules()) {
            assertThat(registry.protocolTools()).extracting(McpTool::module)
                    .as("视图声称未开放的模块 %s 不得有任何已注册工具", unopened)
                    .doesNotContain(unopened);
        }
        assertThat(exposure.openModules()).extracting(McpExposureModule::module)
                .containsExactlyInAnyOrderElementsOf(
                        registry.protocolTools().stream().map(McpTool::module).distinct().toList());
    }

    /**
     * 端点纯只读（票 05 验收项）：开放面由 {@code MCP_MODULES} 在部署期决定、启动期一次性生效，
     * 界面上能改就等于绕过部署评审，而且注册表运行期不可变，改了也不会生效。把「不提供修改能力」
     * 钉在 HTTP 面上——将来谁加一个写方法，这条测试红。
     */
    @Test
    void theExposureEndpointExposesNoWriteMethodAtAll() {
        List<String> writeMappings = new ArrayList<>();
        for (Method method : McpExposureController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)
                    || method.isAnnotationPresent(RequestMapping.class)) {
                writeMappings.add(method.getName());
            }
        }
        assertThat(writeMappings)
                .as("MCP 开放面核对视图必须是纯只读端点，不得提供任何改开放面的写方法")
                .isEmpty();
    }

    private static McpExposure exposure(String modulesProperty, McpTool... tools) {
        return new McpExposureReadService(registry(modulesProperty, tools)).exposure();
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
        // 兼容便捷构造：同一份清单进 Agent / 协议两个工具面；本视图只读协议面，语义与拆分前一致。
        return new McpToolRegistry(reads, writes, domains, control, modulesProperty);
    }

    private static McpTool tool(String name, String module, String description) {
        return new McpToolRegistry.SimpleTool(
                name, description, McpToolRegistry.schema(Map.of(), List.of()), (context, args) -> null, module);
    }

    private static McpTool writeTool(String name, String module, String description) {
        return new McpToolRegistry.SimpleTool(
                name, description, McpToolRegistry.schema(Map.of(), List.of()), (context, args) -> null, false, module);
    }
}
