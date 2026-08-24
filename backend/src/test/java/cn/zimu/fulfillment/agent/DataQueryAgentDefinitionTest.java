package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.web.TestRequestAuthenticationConfiguration;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
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
 * 06 — 数据查询 Agent 定义与白名单不变式（agent-decision-layer 06）：注册表定义、
 * 只读白名单不变式（不含任何写工具 / 不含 PII 投影工具）。写工具不变式向真实注册表
 * 按读写元数据查询（08 决策）；守卫/策略纯逻辑判定见 {@link DataQueryAgentGuardTest}。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
@Import(TestRequestAuthenticationConfiguration.class)
class DataQueryAgentDefinitionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private McpToolRegistry toolRegistry;

    private final AgentDefinition definition = AgentSeedFixtures.dataQueryDefinition();

    // ------------------------------------------------------------------
    // 注册表定义
    // ------------------------------------------------------------------

    @Test
    void definitionIsRegisteredWithFixedIdentity() {
        assertThat(definition.agentSlug()).isEqualTo("data-query-agent");
        assertThat(definition.name()).isEqualTo("数据查询");
        assertThat(definition.promptVersion()).isEqualTo("data-query-v1");
        assertThat(definition.modelRef()).isEqualTo("app.agent");
        assertThat(definition.enabled()).isTrue();
        assertThat(definition.systemPrompt()).contains("只读").contains("clarification_needed");
    }

    // ------------------------------------------------------------------
    // 写操作不变式：白名单只含只读工具
    // ------------------------------------------------------------------

    /** McpReadTools/McpDomainReadTools 中含客户/收货人 PII 投影的工具名（草稿、候选、渠道消息、复核详情）。 */
    private static final List<String> PII_PROJECTION_TOOL_NAMES = List.of(
            "get_channel_message",
            "get_message_submission",
            "list_channel_messages",
            "list_order_drafts",
            "get_order_draft",
            "get_order_draft_candidates",
            "list_tracking_drafts",
            "get_tracking_draft",
            "get_tracking_draft_candidates",
            "list_review_cases",
            "get_review_case");

    @Test
    void whitelistContainsOnlyReadOnlyDomainAndNonPiiTools() {
        assertThat(definition.toolNames())
                .containsExactlyInAnyOrderElementsOf(AgentSeedFixtures.DATA_QUERY_TOOL_NAMES);
        // 04 票核心领域工具全覆盖
        assertThat(definition.toolNames())
                .contains(
                        "list_procurement_tickets",
                        "get_procurement_ticket",
                        "list_procurement_receipts",
                        "search_skus",
                        "get_sku",
                        "list_provider_skus",
                        "get_inventory_overview",
                        "get_inventory_detail",
                        "list_products",
                        "list_categories",
                        "list_fulfillment_providers");
    }

    @Test
    void whitelistNeverReferencesAnyWriteTool() {
        // 08 决策：写工具集合向真实注册表按读写元数据（readOnly）查询，不再手抄清单，
        // 写工具集合增长时不会静默漏检
        Set<String> writeToolNames = toolRegistry.writeToolNames();
        assertThat(writeToolNames)
                .as("注册表必须能判定写工具集合（默认禁写不变式）")
                .isNotEmpty();
        assertThat(definition.toolNames())
                .as("数据查询 Agent 白名单不得包含任何写工具")
                .doesNotContainAnyElementsOf(writeToolNames);
    }

    @Test
    void whitelistExcludesEveryPiiProjectionTool() {
        assertThat(definition.toolNames())
                .as("数据查询 Agent 白名单不得包含任何客户/收货人 PII 投影工具")
                .doesNotContainAnyElementsOf(PII_PROJECTION_TOOL_NAMES);
    }
}
