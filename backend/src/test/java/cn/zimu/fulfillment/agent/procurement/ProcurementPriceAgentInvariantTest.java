package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentRegistry;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.common.web.TestRequestAuthenticationConfiguration;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpTool;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import cn.zimu.fulfillment.mcp.McpWriteTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 05 — 采购比价 Agent 写操作不变式（agent-decision-layer 05，Testcontainers）：真实注册表下
 * 断言 AgentDefinition.tool_names 恰为 04 票只读工具面且与真实写工具清单（McpWriteTools）
 * 交集为空；Agent 工具绑定不暴露任何写工具；白名单引用的工具在真实注册表全部可解析。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.agent-identity=procurement-invariant-agent"
        })
@Import(TestRequestAuthenticationConfiguration.class)
class ProcurementPriceAgentInvariantTest {

    private static final String RUN_ID = "run_" + "0".repeat(32);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private AgentRegistry registry;

    @Autowired
    private McpToolRegistry toolRegistry;

    @Autowired
    private McpWriteTools writeTools;

    @Autowired
    private McpAgentIdentity identity;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void registryContainsEnabledProcurementAgentWithReadOnlyWhitelist() {
        AgentDefinition definition = registry.bySlug(ProcurementPriceAgent.AGENT_SLUG);
        assertThat(definition).as("注册表必须含采购比价 Agent 定义").isNotNull();
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.promptVersion()).isEqualTo(ProcurementPriceAgentConfiguration.PROMPT_VERSION);
        assertThat(definition.modelRef()).isEqualTo("app.agent");
        assertThat(definition.toolNames())
                .containsExactlyElementsOf(ProcurementPriceAgentConfiguration.READ_ONLY_TOOLS);
    }

    @Test
    void whitelistNeverReferencesAnyRealWriteTool() {
        List<String> writeToolNames = writeTools.tools().stream().map(McpTool::name).toList();
        assertThat(writeToolNames)
                .as("写工具清单必须非空（McpWriteTools 现网清单）")
                .isNotEmpty();

        AgentDefinition definition = registry.bySlug(ProcurementPriceAgent.AGENT_SLUG);
        assertThat(definition.toolNames()).doesNotContainAnyElementsOf(writeToolNames);
    }

    @Test
    void whitelistToolsAllResolveInRealRegistryAndBindWithoutWriteTools() {
        AgentDefinition definition = registry.bySlug(ProcurementPriceAgent.AGENT_SLUG);
        // 白名单引用的工具必须在唯一工具源全部可解析（配置漂移 fail-fast）
        for (String name : definition.toolNames()) {
            assertThat(toolRegistry.find(name)).as("白名单工具必须已注册: %s", name).isPresent();
        }

        AgentToolBinding binding = new AgentToolBindingFactory(toolRegistry, identity, mapper)
                .bind(RUN_ID, definition.toolNames());

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrderElementsOf(definition.toolNames());
        List<String> writeToolNames = writeTools.tools().stream().map(McpTool::name).toList();
        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .doesNotContainAnyElementsOf(writeToolNames);
    }
}
