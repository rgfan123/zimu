package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 06 — 数据查询 Agent 定义与策略门（agent-decision-layer 06）：注册表定义、
 * 只读白名单不变式（不含任何写工具 / 不含 PII 投影工具）、PII 拒绝与歧义澄清的
 * 确定性判定。纯单元测试，不依赖模型与数据库。
 */
class DataQueryAgentDefinitionTest {

    private final AgentDefinition definition =
            new DataQueryAgentDefinitionConfiguration().dataQueryAgentDefinition();

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

    /** McpWriteTools 的全部写工具名（与 mcp/McpWriteTools.java 一致）；白名单命中任一即违反不变式。 */
    private static final List<String> WRITE_TOOL_NAMES = List.of(
            "reinterpret_submission",
            "submit_order_draft_suggestion",
            "submit_supplementary_material",
            "submit_review_request");

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
                .containsExactlyInAnyOrderElementsOf(DataQueryAgentDefinitionConfiguration.TOOL_NAMES);
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
        assertThat(definition.toolNames())
                .as("数据查询 Agent 白名单不得包含任何写工具")
                .doesNotContainAnyElementsOf(WRITE_TOOL_NAMES);
    }

    @Test
    void whitelistExcludesEveryPiiProjectionTool() {
        assertThat(definition.toolNames())
                .as("数据查询 Agent 白名单不得包含任何客户/收货人 PII 投影工具")
                .doesNotContainAnyElementsOf(PII_PROJECTION_TOOL_NAMES);
    }

    // ------------------------------------------------------------------
    // PII 拒绝路径
    // ------------------------------------------------------------------

    @Test
    void piiQueriesAreFlaggedForHumanTransfer() {
        assertThat(DataQueryAgentGuard.piiProblems("查一下客户张三的收货地址")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("某客户下了什么订单")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("最近发货单的收货人是谁")).isNotEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("查一下订单上的手机号是多少")).isNotEmpty();
    }

    @Test
    void nonPiiQueriesAreNotFlagged() {
        assertThat(DataQueryAgentGuard.piiProblems("最近 7 天有多少缺货的订单行")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("SKU-EVAL-000001 的进货价和零售价是多少")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("采购工单 9005 还差多少数量")).isEmpty();
        assertThat(DataQueryAgentGuard.piiProblems("某履约方本月共接收多少运单回执")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 歧义澄清路径
    // ------------------------------------------------------------------

    @Test
    void placeholderQueriesAreFlaggedForClarification() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-xxx 的进货价和零售价是多少"))
                .as("SKU 占位符必须进入澄清路径")
                .isNotEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("采购工单 P-123 还差多少数量"))
                .as("工单号（非数字 ticket_id）必须进入澄清路径")
                .isNotEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("某履约方本月共接收多少运单回执"))
                .as("未指明履约方必须进入澄清路径")
                .isNotEmpty();
    }

    @Test
    void concreteQueriesAreNotFlaggedAsAmbiguous() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-EVAL-000001 的进货价和零售价是多少"))
                .as("真实 SKU 编号不得误判为占位")
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("采购工单 9005 还差多少数量"))
                .as("数字 ticket_id 不得误判为工单号占位")
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("最近 7 天有多少缺货的订单行")).isEmpty();
    }

    @Test
    void skuPlaceholderDetectionDoesNotMatchRealSkuCodes() {
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-EVAL-000001 的进货价和零售价是多少"))
                .isEmpty();
        assertThat(DataQueryAgentGuard.ambiguityProblems("SKU-PROD-LAMBLEG-000001 价格"))
                .isEmpty();
    }

    @Test
    void toolArgumentGuardRejectsPlaceholderArguments() {
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("query", "xxx")))
                .as("占位查询词必须被参数级兜底拦截")
                .isNotNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("ticket_id", "5")))
                .as("合法数字 ID 不得被拦截")
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("query", "SKU-EVAL-000001")))
                .as("真实 SKU 编号不得被拦截")
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of("status", "PENDING")))
                .isNull();
        assertThat(DataQueryAgentGuard.toolArgumentProblem(Map.of())).isNull();
    }
}
