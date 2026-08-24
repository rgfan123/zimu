package cn.zimu.fulfillment.agent.eval;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentTestcontainersBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 09 — 评测跑分器（agent-decision-layer 09；meta-agent-platform-impl 03 数据驱动化）：
 * 用例真源为 DB（{@code agent_eval_cases}，Testcontainers 加载），运行 {@link AgentEvalScorer}
 * 计算全部指标、按版本归档（不覆盖旧结果）、验证结果确定性（正确性指标两次运行完全一致；
 * latency 为信息性指标只做合理区间检查），并输出人类可读指标摘要。
 */
class AgentEvalScorerTest extends AgentTestcontainersBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<AgentEvalScorer.AgentEvalCase> cases() {
        return AgentEvalScorer.loadInvariantCases(jdbc);
    }

    @Test
    void scorerArchivesDeterministicMetricsPerRunWithoutOverwrite() throws IOException {
        AgentEvalScorer.Metrics first = AgentEvalScorer.compute(cases());
        Path firstArchive = AgentEvalScorer.archive(first);

        assertThat(firstArchive).exists();
        JsonNode json = MAPPER.readTree(firstArchive.toFile());
        assertThat(json.path("baseline").asText()).isEqualTo("agent-eval-baseline");
        assertThat(json.path("environment").path("eval_case_source").asText())
                .contains("agent_eval_cases");
        assertThat(json.path("procurement").path("eval_set_version").asText())
                .isEqualTo("procurement-eval-v2");
        assertThat(json.path("data_query").path("eval_set_version").asText())
                .isEqualTo("data-query-eval-v1");

        // 确定性：第二次运行的正确性指标与第一次完全一致
        AgentEvalScorer.Metrics second = AgentEvalScorer.compute(cases());
        assertDeterministic(first.procurement(), second.procurement());
        assertDeterministic(first.dataQuery(), second.dataQuery());

        // 归档不覆盖旧结果：第二次归档是独立文件
        Path secondArchive = AgentEvalScorer.archive(second);
        assertThat(secondArchive).isNotEqualTo(firstArchive);
        assertThat(secondArchive).exists();
    }

    @Test
    void metricsAreSaneAndRendered() {
        AgentEvalScorer.Metrics metrics = AgentEvalScorer.compute(cases());
        System.out.println(AgentEvalScorer.render(metrics));

        assertThat(metrics.procurement().totalCases()).isEqualTo(12);
        assertThat(metrics.procurement().avgLatencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.dataQuery().totalQueries()).isEqualTo(7);
        assertThat(metrics.dataQuery().avgModelLatencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void archivedResultsAccumulateWithoutOverwritingPreviousRuns() throws IOException {
        Path dir = Path.of(AgentEvalScorer.ARCHIVE_DIR);
        if (!Files.isDirectory(dir)) {
            return;
        }
        long before = Files.list(dir).filter(Files::isRegularFile).count();
        AgentEvalScorer.archive(AgentEvalScorer.compute(cases()));
        long after = Files.list(dir).filter(Files::isRegularFile).count();
        assertThat(after).isEqualTo(before + 1);
    }

    private static void assertDeterministic(
            AgentEvalScorer.ProcurementMetrics a, AgentEvalScorer.ProcurementMetrics b) {
        assertThat(b.evalSetVersion()).isEqualTo(a.evalSetVersion());
        assertThat(b.totalCases()).isEqualTo(a.totalCases());
        assertThat(b.schemaValid()).isEqualTo(a.schemaValid());
        assertThat(b.schemaRejected()).isEqualTo(a.schemaRejected());
        assertThat(b.requiresHumanExpected()).isEqualTo(a.requiresHumanExpected());
        assertThat(b.requiresHumanCaught()).isEqualTo(a.requiresHumanCaught());
        assertThat(b.happyPathWronglyRequiresHuman()).isEqualTo(a.happyPathWronglyRequiresHuman());
        assertThat(b.writeToolCalls()).isEqualTo(a.writeToolCalls());
        assertThat(b.totalTokens()).isEqualTo(a.totalTokens());
    }

    private static void assertDeterministic(
            AgentEvalScorer.DataQueryMetrics a, AgentEvalScorer.DataQueryMetrics b) {
        assertThat(b.evalSetVersion()).isEqualTo(a.evalSetVersion());
        assertThat(b.totalQueries()).isEqualTo(a.totalQueries());
        assertThat(b.gatePaths()).isEqualTo(a.gatePaths());
        assertThat(b.gateRequiresHumanCaught()).isEqualTo(a.gateRequiresHumanCaught());
        assertThat(b.answerableQueries()).isEqualTo(a.answerableQueries());
        assertThat(b.toolSelectionCorrect()).isEqualTo(a.toolSelectionCorrect());
        assertThat(b.answerNumbersCorrect()).isEqualTo(a.answerNumbersCorrect());
        assertThat(b.writeToolCalls()).isEqualTo(a.writeToolCalls());
        assertThat(b.totalTokens()).isEqualTo(a.totalTokens());
    }
}
