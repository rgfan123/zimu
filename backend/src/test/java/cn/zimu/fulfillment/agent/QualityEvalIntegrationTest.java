package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.web.TestRequestAuthenticationConfiguration;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 09 — QUALITY 评测链路验收（meta-agent-platform-impl 09，Testcontainers）：真实 DB 下
 * 提交 → 异步任务领取 → 执行（假执行器）→ 结果回写 {@code agent_eval_results}（按 run_id
 * 关联）+ {@code agent_runs} 以 run_mode=PREVIEW 落行（不污染 LIVE）；按 task_type 领取
 * 隔离（解释 Worker 不抢 QUALITY 任务）；失败路径落 FAILED 结果与观测行。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.quality-eval.enabled=true"
        })
@Import(TestRequestAuthenticationConfiguration.class)
class QualityEvalIntegrationTest {

    private static final String SLUG = "quality-eval-agent";
    private static final String CANNED_OUTPUT = """
            {"results":{"results":[
              {"gradingResult":{"pass":true,"score":1}},
              {"gradingResult":{"pass":true,"score":1}}
            ]}}
            """;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        QualityEvalRunner cannedRunner() {
            return (configFile, outputFile) ->
                    new QualityEvalRunner.RunResult(0, CANNED_OUTPUT, "");
        }
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private QualityEvalService service;

    @Autowired
    private QualityEvalWorker worker;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private AgentObservability observability;

    @BeforeEach
    void seedDefinitionAndQualityCases() {
        jdbc.execute("""
                TRUNCATE app.agent_eval_results, app.agent_runs, app.agent_tool_calls,
                         app.audit_logs, app.async_tasks, app.agent_eval_cases,
                         app.agent_definitions
                RESTART IDENTITY CASCADE
                """);
        // 定义（T02 后 DB 真源）+ QUALITY 用例（CONFIRMED 冻结集）
        AgentSeedFixtures.upsertActiveDefinition(
                jdbc,
                AgentDefinition.ofActiveV1(
                        SLUG, "QUALITY 评测 Agent", "d", "你是采购比价助手，只读分析。",
                        "quality-v1", "app.agent", true, List.of("search_skus")));
        holder.reload();
        insertQualityCase(1L, "9005 的进货价", List.of("9005", "元"));
        insertQualityCase(2L, "缺货行数", List.of("缺货"));
    }

    private void insertQualityCase(long id, String input, List<String> fragments) {
        jdbc.update(
                """
                INSERT INTO app.agent_eval_cases
                    (id, agent_slug, agent_version, metric_kind, input, expected, status, created_by, confirmed_by, confirmed_at)
                VALUES (?, ?, 1, 'QUALITY', ?::jsonb, ?::jsonb, 'CONFIRMED', 'test', 'test', CURRENT_TIMESTAMP)
                """,
                id,
                SLUG,
                mapper.createObjectNode().put("input", input).toString(),
                expectedJson(fragments));
    }

    private String expectedJson(List<String> fragments) {
        com.fasterxml.jackson.databind.node.ArrayNode array =
                mapper.createObjectNode().putArray("answer_contains");
        fragments.forEach(array::add);
        // expected 根必须是对象（repository 按 path("answer_contains") 解析）
        com.fasterxml.jackson.databind.node.ObjectNode expected = mapper.createObjectNode();
        expected.set("answer_contains", array);
        return expected.toString();
    }

    @Test
    void qualityEvalWritesPreviewRunAndResultsWithoutPollutingLive() throws Exception {
        String runId = service.submit(SLUG, "test-user");

        worker.poll();

        // 结果回写：按 run_id 关联，QUALITY 指标
        Map<String, Object> result = jdbc.queryForMap(
                "SELECT agent_slug, agent_version, metric_kind, case_count, passed_count, status, details"
                        + " FROM app.agent_eval_results WHERE run_id = ?",
                runId);
        assertThat(result.get("agent_slug")).isEqualTo(SLUG);
        assertThat(result.get("metric_kind")).isEqualTo("QUALITY");
        assertThat(result.get("case_count")).isEqualTo(2);
        assertThat(result.get("passed_count")).isEqualTo(2);
        assertThat(result.get("status")).isEqualTo("SUCCEEDED");
        // details 按用例 id 关联逐条得分（JSON 语义等价断言，容忍键序/空白差异）
        com.fasterxml.jackson.databind.JsonNode details = mapper.readTree(result.get("details").toString());
        assertThat(details.path("cases")).hasSize(2);
        assertThat(details.path("cases").get(0).path("case_id").asLong()).isEqualTo(1L);
        assertThat(details.path("cases").get(0).path("score").asDouble()).isEqualTo(1.0);
        assertThat(details.path("cases").get(1).path("case_id").asLong()).isEqualTo(2L);
        assertThat(details.path("cases").get(1).path("score").asDouble()).isEqualTo(1.0);

        // agent_runs 以 PREVIEW 落行：不污染 LIVE 统计与基线
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT agent_slug, prompt_version, run_mode, status, error_type,"
                        + " business_entity_type, business_entity_id"
                        + " FROM app.agent_runs WHERE run_id = ?",
                runId);
        assertThat(run.get("agent_slug")).isEqualTo(SLUG);
        assertThat(run.get("prompt_version")).isEqualTo("quality-v1");
        assertThat(run.get("run_mode")).isEqualTo("PREVIEW");
        assertThat(run.get("status")).isEqualTo("SUCCESS");
        assertThat(run.get("error_type")).isNull();
        assertThat(run.get("business_entity_type")).isEqualTo("AGENT_EVAL");
        assertThat(run.get("business_entity_id")).isEqualTo(SLUG);

        // 任务收口 SUCCEEDED
        assertThat(taskStatus(runId)).isEqualTo("SUCCEEDED");

        // 全库无 LIVE 运行行（PREVIEW 隔离）
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_runs WHERE run_mode = 'LIVE'", Long.class))
                .isZero();
    }

    @Test
    void typedClaimIsolatesQualityTasksFromInterpretationWorker() {
        String runId = service.submit(SLUG, "test-user");

        // 解释 Worker 按类型领取：领不到 QUALITY 任务
        assertThat(taskStore.claim("INTERPRET_MESSAGE", "probe", Duration.ofSeconds(30)))
                .isEmpty();
        // QUALITY Worker 按类型领取：领到
        Optional<AsyncTaskStore.AsyncTask> claimed =
                taskStore.claim(QualityEvalService.TASK_TYPE, "probe", Duration.ofSeconds(30));
        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().payloadRef()).contains(SLUG).contains(runId);
        taskStore.succeed(claimed.orElseThrow().id(), "probe");
    }

    @Test
    void failedExecutionWritesFailedResultAndFailedObservabilityRow() {
        // 假执行器退出码非 0 → FAILED
        String runId = service.submit(SLUG, "test-user");
        QualityEvalRunner failing = (configFile, outputFile) ->
                new QualityEvalRunner.RunResult(1, "", "boom");

        // 直接以失败执行器驱动（绕过 @Primary 假执行器）
        executeWith(failing, runId);

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_eval_results WHERE run_id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_runs WHERE run_id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT error_type FROM app.agent_runs WHERE run_id = ?", String.class, runId))
                .isEqualTo("QUALITY_EVAL_FAILED");
    }

    /** 用给定执行器驱动一次执行（绕过 @Primary bean，验证失败路径）。 */
    private void executeWith(QualityEvalRunner runner, String runId) {
        Optional<AsyncTaskStore.AsyncTask> claimed =
                taskStore.claim(QualityEvalService.TASK_TYPE, "probe", Duration.ofSeconds(30));
        AsyncTaskStore.AsyncTask task = claimed.orElseThrow();
        // 以独立服务实例执行（同一 holder/repos/jdbc/observability，替换 runner）
        QualityEvalService direct = new QualityEvalService(
                holder,
                new AgentDefinitionRepository(jdbc, mapper),
                new AgentEvalCaseRepository(jdbc),
                runner,
                observability,
                taskStore,
                jdbc,
                mapper);
        try {
            direct.execute(task);
        } catch (RuntimeException ignored) {
            // 失败路径预期抛出；任务由测试收口
        }
        taskStore.succeed(task.id(), "probe");
    }

    private String taskStatus(String runId) {
        return jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE payload_ref LIKE ?",
                String.class,
                "%" + runId);
    }
}
