package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentRegistryHolder;
import cn.zimu.fulfillment.agent.AgentSeedFixtures;
import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.common.web.TestRequestAuthenticationConfiguration;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
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
 * 断言 AgentDefinition.tool_names 恰为 04 票只读工具面且与真实写工具集合（按 readOnly
 * 元数据向注册表查询，08 决策）交集为空；Agent 工具绑定不暴露任何写工具；白名单引用的
 * 工具在真实注册表全部可解析。
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
    private AgentRegistryHolder holder;

    @Autowired
    private McpToolRegistry toolRegistry;

    @Autowired
    private McpAgentIdentity identity;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void registryContainsEnabledProcurementAgentWithReadOnlyWhitelist() {
        AgentDefinition definition = holder.current().bySlug(ProcurementPriceAgent.AGENT_SLUG);
        assertThat(definition).as("注册表必须含采购比价 Agent 定义").isNotNull();
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.promptVersion()).isEqualTo("procurement-price-v1");
        assertThat(definition.modelRef()).isEqualTo("app.agent");
        assertThat(definition.toolNames())
                .containsExactlyElementsOf(AgentSeedFixtures.PROCUREMENT_TOOL_NAMES);
    }

    @Test
    void whitelistNeverReferencesAnyRealWriteTool() {
        // 08 决策：写工具集合按 readOnly 元数据向注册表查询，不再手抄清单
        Set<String> writeToolNames = toolRegistry.writeToolNames();
        assertThat(writeToolNames)
                .as("写工具集合必须非空（默认禁写不变式可判定）")
                .isNotEmpty();

        AgentDefinition definition = holder.current().bySlug(ProcurementPriceAgent.AGENT_SLUG);
        assertThat(definition.toolNames()).doesNotContainAnyElementsOf(writeToolNames);
    }

    @Test
    void whitelistToolsAllResolveInRealRegistryAndBindWithoutWriteTools() {
        AgentDefinition definition = holder.current().bySlug(ProcurementPriceAgent.AGENT_SLUG);
        // 白名单引用的工具必须在唯一工具源全部可解析（配置漂移 fail-fast）
        for (String name : definition.toolNames()) {
            assertThat(toolRegistry.find(name)).as("白名单工具必须已注册: %s", name).isPresent();
        }

        AgentToolBinding binding = new AgentToolBindingFactory(toolRegistry, identity, mapper)
                .bind(RUN_ID, definition.toolNames());

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrderElementsOf(definition.toolNames());
        Set<String> writeToolNames = toolRegistry.writeToolNames();
        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .doesNotContainAnyElementsOf(writeToolNames);
    }
}
