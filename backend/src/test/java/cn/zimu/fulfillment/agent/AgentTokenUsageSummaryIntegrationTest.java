package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.agent.dto.AgentTokenUsageFilter;
import cn.zimu.fulfillment.agent.dto.TokenUsageSummaryItem;
import cn.zimu.fulfillment.agent.dto.TokenUsageSummaryResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 129 — 消耗汇总验收（真实 PostgreSQL）：SQL 语义必须真跑才算数，
 * 尤其 {@code jsonb_exists}（不能写成 {@code ? 'key'}，会被 JdbcTemplate 当占位符）
 * 与 {@code AT TIME ZONE 'Asia/Shanghai'} 的业务日口径。
 *
 * <p>核心断言是两条容易被做错的口径：
 * <ul>
 *   <li>没有 token_usage 的运行**计入 runs 但不计入求和**，并单独暴露 runsWithoutTokenUsage
 *       ——汇总是下界不是全量，界面必须能说出这件事；</li>
 *   <li>PREVIEW 运行默认不进成本视图——草稿试跑混进来，「线上花了多少」就不成立。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.message-worker.enabled=false", "app.mcp.enabled=false"})
class AgentTokenUsageSummaryIntegrationTest {

    private static final String SLUG_A = "cost-agent-a";
    private static final String SLUG_B = "cost-agent-b";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentTokenUsageReadService service;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE app.agent_runs, app.agent_tool_calls RESTART IDENTITY CASCADE");
    }

    @Test
    void groupsByAgentAndSumsOnlyMeasuredRuns() {
        insertRun(SLUG_A, "SUCCESS", 400, 50, 450, 2, 1_200, "LIVE", null, false);
        insertRun(SLUG_A, "SUCCESS", 100, 20, 120, 1, 300, "LIVE", null, false);
        // 未配置模型的 fail-closed 运行：有 run 行但一分钱计量都没有
        insertRun(SLUG_A, "FAILED", null, null, null, null, 5, "LIVE", null, false);
        insertRun(SLUG_B, "SUCCESS", 10, 2, 12, 1, 90, "LIVE", null, false);

        TokenUsageSummaryResponse response = service.summarize(filter(null, null, "AGENT"));

        assertThat(response.groupBy()).isEqualTo("AGENT");
        assertThat(response.runMode()).isEqualTo("LIVE");
        // 最贵的排最前
        assertThat(response.items()).extracting(TokenUsageSummaryItem::groupKey)
                .containsExactly(SLUG_A, SLUG_B);

        TokenUsageSummaryItem a = response.items().get(0);
        assertThat(a.runs()).isEqualTo(3);
        assertThat(a.failedRuns()).isEqualTo(1);
        // 求和只覆盖有计量的两条；第三条既不被丢弃也不被当成 0 混进均值
        assertThat(a.promptTokens()).isEqualTo(500);
        assertThat(a.completionTokens()).isEqualTo(70);
        assertThat(a.totalTokens()).isEqualTo(570);
        assertThat(a.runsWithoutTokenUsage()).isEqualTo(1);
        assertThat(a.measuredRuns()).isEqualTo(2);
        assertThat(a.maxRunTotalTokens()).isEqualTo(450);
        assertThat(a.modelCalls()).isEqualTo(3);
        // 耗时同样入账：卡在工具上空转的运行 token 不高，只看 token 会漏掉
        assertThat(a.totalLatencyMs()).isEqualTo(1_505);
        assertThat(a.maxRunLatencyMs()).isEqualTo(1_200);

        TokenUsageSummaryItem totals = response.totals();
        assertThat(totals.groupKey()).isEmpty();
        assertThat(totals.runs()).isEqualTo(4);
        assertThat(totals.totalTokens()).isEqualTo(582);
        // 峰值是各组峰值的最大值，不是求和
        assertThat(totals.maxRunTotalTokens()).isEqualTo(450);
    }

    @Test
    void previewRunsAreExcludedFromCostViewByDefault() {
        insertRun(SLUG_A, "SUCCESS", 100, 10, 110, 1, 100, "LIVE", null, false);
        insertRun(SLUG_A, "SUCCESS", 900, 90, 990, 5, 900, "PREVIEW", null, false);

        assertThat(service.summarize(filter(null, null, "AGENT")).totals().totalTokens())
                .isEqualTo(110);
        assertThat(service.summarize(filter(null, "PREVIEW", "AGENT")).totals().totalTokens())
                .isEqualTo(990);
    }

    @Test
    void filtersByOutcomeUsingTheRunListSemantics() {
        insertRun(SLUG_A, "SUCCESS", 100, 10, 110, 1, 100, "LIVE", null, false);
        insertRun(SLUG_A, "FAILED", 200, 20, 220, 1, 100, "LIVE", null, false);

        TokenUsageSummaryItem item = service.summarize(
                filter(null, "FAILED", null, null, "AGENT")).items().get(0);

        assertThat(item.runs()).isEqualTo(1);
        assertThat(item.failedRuns()).isEqualTo(1);
        assertThat(item.totalTokens()).isEqualTo(220);
    }

    @Test
    void filtersByBusinessEntityId() {
        insertRun(SLUG_A, "SUCCESS", 100, 10, 110, 1, 100, "LIVE", "ORDER", "ORDER-1", false);
        insertRun(SLUG_A, "SUCCESS", 200, 20, 220, 1, 100, "LIVE", "ORDER", "ORDER-2", false);

        TokenUsageSummaryItem item = service.summarize(
                filter(null, null, null, "ORDER-2", "AGENT")).items().get(0);

        assertThat(item.runs()).isEqualTo(1);
        assertThat(item.totalTokens()).isEqualTo(220);
    }

    @Test
    void omittingOutcomeAndBusinessEntityIdKeepsTheExistingAggregateScope() {
        insertRun(SLUG_A, "SUCCESS", 100, 10, 110, 1, 100, "LIVE", "ORDER", "ORDER-1", false);
        insertRun(SLUG_A, "FAILED", 200, 20, 220, 1, 100, "LIVE", "ORDER", "ORDER-2", false);

        TokenUsageSummaryItem totals = service.summarize(filter(null, null, "AGENT")).totals();

        assertThat(totals.runs()).isEqualTo(2);
        assertThat(totals.totalTokens()).isEqualTo(330);
    }

    @Test
    void overThresholdRunsAreCountedViaJsonbExists() {
        insertRun(SLUG_A, "SUCCESS", 100, 10, 110, 1, 100, "LIVE", null, false);
        insertRun(SLUG_A, "SUCCESS", 90_000, 5_000, 95_000, 7, 60_000, "LIVE", null, true);

        TokenUsageSummaryItem item = service.summarize(filter(null, null, "AGENT")).items().get(0);
        assertThat(item.overThresholdRuns()).isEqualTo(1);
        assertThat(item.runs()).isEqualTo(2);
    }

    @Test
    void groupsByBusinessDayInShanghaiTime() {
        // 2026-08-24T23:30+08:00 与次日 00:30+08:00 必须落在不同业务日
        insertRunAt(SLUG_A, 110, "2026-08-24 23:30:00+08");
        insertRunAt(SLUG_A, 220, "2026-08-25 00:30:00+08");

        TokenUsageSummaryResponse response = service.summarize(filter(null, null, "DAY"));
        assertThat(response.items()).extracting(TokenUsageSummaryItem::groupKey)
                .containsExactlyInAnyOrder("2026-08-24", "2026-08-25");
    }

    @Test
    void groupsByBusinessEntityTypeKeepingEntitylessRunsVisible() {
        insertRun(SLUG_A, "SUCCESS", 10, 1, 11, 1, 10, "LIVE", "PROCUREMENT_TICKET", false);
        insertRun(SLUG_A, "SUCCESS", 20, 2, 22, 1, 10, "LIVE", null, false);

        TokenUsageSummaryResponse response =
                service.summarize(filter(null, null, "BUSINESS_ENTITY_TYPE"));
        // 无业务实体的运行归入空串而不是被 GROUP BY 丢掉——0 不等于不存在
        assertThat(response.items()).extracting(TokenUsageSummaryItem::groupKey)
                .containsExactlyInAnyOrder("PROCUREMENT_TICKET", "");
        assertThat(response.totals().runs()).isEqualTo(2);
    }

    @Test
    void unknownGroupByIsRejectedInsteadOfInterpolatedIntoSql() {
        assertThatThrownBy(() -> filter(null, null, "agent_slug; DROP TABLE app.agent_runs"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("group_by");
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private AgentTokenUsageFilter filter(String slug, String runMode, String groupBy) {
        return filter(slug, null, runMode, null, groupBy);
    }

    private AgentTokenUsageFilter filter(
            String slug, String outcome, String runMode, String businessEntityId, String groupBy) {
        return AgentTokenUsageFilter.of(
                slug, outcome, runMode, null, businessEntityId, null, null, groupBy, 100);
    }

    private void insertRun(
            String slug,
            String status,
            Integer prompt,
            Integer completion,
            Integer total,
            Integer modelCalls,
            int latencyMs,
            String runMode,
            String businessEntityType,
            boolean overThreshold) {
        insertRun(
                slug, status, prompt, completion, total, modelCalls, latencyMs,
                runMode, businessEntityType, null, overThreshold);
    }

    private void insertRun(
            String slug,
            String status,
            Integer prompt,
            Integer completion,
            Integer total,
            Integer modelCalls,
            int latencyMs,
            String runMode,
            String businessEntityType,
            String businessEntityId,
            boolean overThreshold) {
        String tokenUsage = total == null
                ? null
                : "{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion
                        + ",\"total_tokens\":" + total
                        + (modelCalls == null ? "" : ",\"model_calls\":" + modelCalls)
                        + (overThreshold ? ",\"over_threshold\":true,\"threshold\":50000" : "")
                        + "}";
        jdbc.update(
                """
                INSERT INTO app.agent_runs
                    (run_id, agent_slug, model, input_digest, status, error_type, latency_ms,
                     token_usage, business_entity_type, business_entity_id, run_mode, finished_at)
                VALUES (?, ?, 'none', ?, ?, ?, ?, ?::jsonb, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                newRunId(),
                slug,
                "0".repeat(64),
                status,
                "FAILED".equals(status) ? "AGENT_MODEL_NOT_CONFIGURED" : null,
                latencyMs,
                tokenUsage,
                businessEntityType,
                businessEntityId,
                runMode);
    }

    private void insertRunAt(String slug, int total, String startedAt) {
        jdbc.update(
                """
                INSERT INTO app.agent_runs
                    (run_id, agent_slug, model, input_digest, status, latency_ms,
                     token_usage, run_mode, started_at, finished_at)
                VALUES (?, ?, 'none', ?, 'SUCCESS', 10, ?::jsonb, 'LIVE', ?::timestamptz,
                        CURRENT_TIMESTAMP)
                """,
                newRunId(),
                slug,
                "0".repeat(64),
                "{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":" + total + "}",
                startedAt);
    }

    private static String newRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "");
    }
}
