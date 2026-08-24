package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 11 — 定义域写端点契约测试（meta-agent-platform-impl 11，Testcontainers + 真实 HTTP）：
 * 五个写动作 202 异步闭环（轮询 agent_runs PREVIEW 行 + agent_tool_calls 门禁明细）、
 * confirm 前全量门禁复跑与 PENDING 用例原子联动、幂等/并发契约（重复 confirm 200、
 * 并发确认不同版本败者 AGENT_CONFLICT、set-enabled 目标值幂等、rollback 复制为新草稿）、
 * operator 一律取自 Basic Auth 身份（请求体禁止携带 operator 字段）。
 *
 * <p>轮询面 = {@code app.agent_runs}（T12 的 GET /api/agent-runs 是本分支外的并行读面，
 * 测试直接断言 DB 真源）：每个任务落 run_mode=PREVIEW 行，RUNNING → SUCCESS/FAILED
 * （error_type 稳定码），门禁明细经 agent_tool_calls 合成行可读。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AgentDefinitionWriteApiTest {

    private static final String ADMIN_USER = "t11-admin";
    private static final String ADMIN_PASSWORD = "t11-admin-password";
    private static final String SLUG = "t11-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void ticketConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.scheduling.enabled", () -> "true");
        registry.add("app.agent-worker.enabled", () -> "true");
        registry.add("app.gateway.basic-auth.username", () -> ADMIN_USER);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
        registry.add("app.agent-worker.poll-ms", () -> "50");
        registry.add("app.agent-worker.lease-seconds", () -> "10");
        registry.add("app.agent-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.quality-eval.enabled", () -> "false");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AgentRegistryHolder holder;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.idempotency_registry, app.audit_logs, app.agent_eval_cases,
                         app.agent_definitions, app.async_tasks, app.agent_runs,
                         app.agent_tool_calls, app.agent_eval_results
                RESTART IDENTITY CASCADE
                """);
        AgentSeedFixtures.upsertActiveDefinition(jdbc, AgentDefinition.ofActiveV1(
                SLUG, "测试 Agent", "写端点契约测试基座", "你是只读助手。", "t11-v1", "app.agent", true,
                List.of("search_skus")));
        holder.reload();
    }

    // ==================================================================
    // ① 建草稿：202 → 轮询闭环（任务含门禁结果）
    // ==================================================================

    @Test
    void createDraftReturns202AndTaskPersistsDraftWithPendingCases() {
        ObjectNode draft = baseDraft("t11-new-agent");
        draft.putArray("suggested_eval_cases").add("SKU 进货价是多少").add("工单缺口明细");

        ResponseEntity<String> response = httpPost("/api/agents/drafts", draft.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String runId = runIdOf(response);
        assertThat(awaitRun(runId).get("status")).isEqualTo("SUCCESS");

        // 全量快照落库：draft 行 + PENDING 建议用例
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, status, tool_whitelist::text FROM app.agent_definitions"
                        + " WHERE agent_slug = 't11-new-agent'");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(row.get("tool_whitelist").toString()).contains("search_skus");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_eval_cases"
                                + " WHERE agent_slug='t11-new-agent' AND status='PENDING'", Long.class))
                .isEqualTo(2L);

        // 运行行：run_mode=PREVIEW（12 决策 3，轮询面复用 agent_runs）
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT run_mode, agent_slug, status FROM app.agent_runs WHERE run_id = ?", runId);
        assertThat(run.get("run_mode")).isEqualTo("PREVIEW");
        assertThat(run.get("agent_slug")).isEqualTo("t11-new-agent");
        assertThat(run.get("status")).isEqualTo("SUCCESS");

        // 任务含门禁结果：agent_gate / agent_invariant_eval / agent_draft_persist 合成行
        List<Map<String, Object>> calls = toolCalls(runId);
        assertThat(calls).extracting(c -> c.get("tool_name"))
                .containsExactly("agent_gate", "agent_invariant_eval", "agent_draft_persist");
        assertThat(calls.get(0).get("result_summary").toString()).contains("\"passed\":true");
        assertThat(calls.get(2).get("result_summary").toString()).contains("\"status\":\"draft\"");

        // 人工动作留 HUMAN 审计（operator 来自 Basic Auth 身份）
        List<Map<String, Object>> audits = audits("agent.definition.draft-created");
        assertThat(audits).singleElement();
        assertThat(audits.getFirst().get("operator")).isEqualTo(ADMIN_USER);
    }

    @Test
    void gateBlockedDraftFailsTaskWithoutDirtyRows() {
        ObjectNode draft = baseDraft("t11-gate-blocked");
        // 白名单含写工具但未声明 allow_write=true → 08 只读不变式阻断（任务 FAILED，草稿不落库）
        ((ArrayNode) draft.get("tool_whitelist")).removeAll().add("reinterpret_submission");

        ResponseEntity<String> response = httpPost("/api/agents/drafts", draft.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String runId = runIdOf(response);
        Map<String, Object> run = awaitRun(runId);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("AGENT_GATE_BLOCKED");

        // 门禁明细可轮询（blockers 在 agent_tool_calls）
        List<Map<String, Object>> calls = toolCalls(runId);
        assertThat(calls.get(0).get("result_summary").toString()).contains("写工具但未声明 allow_write");

        // 无脏数据：无定义行、无用例行
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE agent_slug='t11-gate-blocked'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_eval_cases WHERE agent_slug='t11-gate-blocked'", Long.class))
                .isZero();
    }

    @Test
    void invalidDraftPayloadRejectedSynchronouslyWithoutTask() {
        ObjectNode draft = baseDraft("t11-invalid");
        draft.remove("name");

        ResponseEntity<String> response = httpPost("/api/agents/drafts", draft.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_DRAFT_CREATE'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.agent_runs", Long.class)).isZero();
    }

    // ==================================================================
    // ② confirm：确认前全量门禁复跑 + PENDING 用例原子联动
    // ==================================================================

    @Test
    void confirmActivatesDraftAndConfirmsPendingCasesAtomically() {
        // 基座 v1 active；v2 草稿 + 2 条 PENDING 建议用例（模拟已提交草稿）
        draftRow(SLUG, 2);
        jdbc.update(
                "INSERT INTO app.agent_eval_cases"
                        + " (agent_slug, agent_version, metric_kind, input, expected, status, created_by)"
                        + " VALUES (?, 2, 'QUALITY', ?::jsonb, ?::jsonb, 'PENDING', 'test')",
                SLUG, "{\"input\":\"SKU-1001 价格\"}", "{\"answer_contains\":[\"12.34\"]}");
        jdbc.update(
                "INSERT INTO app.agent_eval_cases"
                        + " (agent_slug, agent_version, metric_kind, input, expected, status, created_by)"
                        + " VALUES (?, 2, 'QUALITY', ?::jsonb, ?::jsonb, 'PENDING', 'test')",
                SLUG, "{\"input\":\"工单 9005 缺口\"}", "{\"answer_contains\":[\"23.500\"]}");

        ResponseEntity<String> response = httpPost("/api/agents/" + SLUG + "/drafts/2/confirm", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String runId = runIdOf(response);
        assertThat(awaitRun(runId).get("status")).isEqualTo("SUCCESS");

        // 状态机：v2 draft→active（activated_by/at 同事务上行），v1 被替换 retired
        Map<String, Object> v2 = jdbc.queryForMap(
                "SELECT status, activated_by FROM app.agent_definitions"
                        + " WHERE agent_slug='" + SLUG + "' AND version=2");
        assertThat(v2.get("status")).isEqualTo("active");
        assertThat(v2.get("activated_by")).isEqualTo(ADMIN_USER);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1",
                        String.class))
                .isEqualTo("retired");

        // 07 联动：该版本 PENDING 用例一并 CONFIRMED（confirmed_by = Basic Auth 身份）
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT status, confirmed_by FROM app.agent_eval_cases"
                        + " WHERE agent_slug='" + SLUG + "' AND agent_version=2");
        assertThat(cases).hasSize(2);
        assertThat(cases).allSatisfy(c -> {
            assertThat(c.get("status")).isEqualTo("CONFIRMED");
            assertThat(c.get("confirmed_by")).isEqualTo(ADMIN_USER);
        });

        // 影响范围随任务结果可轮询：agent_confirm 行含 confirmed_pending_cases
        List<Map<String, Object>> calls = toolCalls(runId);
        assertThat(calls.get(2).get("tool_name")).isEqualTo("agent_confirm");
        assertThat(calls.get(2).get("result_summary").toString()).contains("\"confirmed_pending_cases\":2");

        // 注册表换实例：运行路径感知新 active 版本
        assertThat(holder.current().bySlug(SLUG).version()).isEqualTo(2);
        // 人工确认留 HUMAN 审计
        List<Map<String, Object>> audits = audits("agent.definition.activated");
        assertThat(audits).singleElement();
        assertThat(audits.getFirst().get("operator")).isEqualTo(ADMIN_USER);
        assertThat(audits.getFirst().get("actor_type")).isEqualTo("HUMAN");
    }

    @Test
    void repeatConfirmReturns200WithCurrentStateWithoutNewTask() {
        draftRow(SLUG, 2);
        confirmAndAwait(SLUG, 2);
        long tasksBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_CONFIRM'", Long.class);

        ResponseEntity<String> repeat = httpPost("/api/agents/" + SLUG + "/drafts/2/confirm", "{}");

        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = parseBody(repeat);
        assertThat(body.get("agent_slug").asText()).isEqualTo(SLUG);
        assertThat(body.get("version").asInt()).isEqualTo(2);
        assertThat(body.get("status").asText()).isEqualTo("active");
        // 目标状态幂等：不产生新任务/新运行
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_CONFIRM'", Long.class))
                .isEqualTo(tasksBefore);
    }

    @Test
    void confirmRetiredOrMissingReturns409Or404() {
        draftRow(SLUG, 2);
        confirmAndAwait(SLUG, 2); // v2 active，v1 retired

        ResponseEntity<String> retired = httpPost("/api/agents/" + SLUG + "/drafts/1/confirm", "{}");
        assertThat(retired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parseBody(retired).get("business_code").asText()).isEqualTo("AGENT_VERSION_RETIRED");

        ResponseEntity<String> missing = httpPost("/api/agents/" + SLUG + "/drafts/9/confirm", "{}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void gateBlockedConfirmFailsTaskWithoutStateChange() {
        // 绕过建草稿门禁直接插脏草稿（模拟提交后被人工编辑）：白名单含写工具但无 allow_write
        insertDraftRow(SLUG, 2, "[\"reinterpret_submission\"]");

        ResponseEntity<String> response = httpPost("/api/agents/" + SLUG + "/drafts/2/confirm", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String runId = runIdOf(response);
        Map<String, Object> run = awaitRun(runId);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("AGENT_GATE_BLOCKED");

        // 全量复跑不过 → 零状态变化：v2 仍是 draft，v1 仍 active，无用例被确认
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=2",
                        String.class))
                .isEqualTo("draft");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1",
                        String.class))
                .isEqualTo("active");
        assertThat(toolCalls(runId).get(0).get("result_summary").toString()).contains("写工具但未声明 allow_write");
    }

    @Test
    void invariantBlockedConfirmFailsTaskWithoutStateChange() {
        insertDraftRow(SLUG, 2, "[\"search_skus\"]");
        // 该版本挂一条 PENDING INVARIANT 用例：tool_sequence 引用白名单外工具 → stub 评测阻断
        jdbc.update(
                "INSERT INTO app.agent_eval_cases"
                        + " (agent_slug, agent_version, metric_kind, input, expected, status, created_by)"
                        + " VALUES (?, 2, 'INVARIANT', ?::jsonb, ?::jsonb, 'PENDING', 'test')",
                SLUG, "\"查最近缺货订单行\"", "{\"requires_human\":false,\"tool_sequence\":[\"list_procurement_tickets\"]}");

        ResponseEntity<String> response = httpPost("/api/agents/" + SLUG + "/drafts/2/confirm", "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String runId = runIdOf(response);
        Map<String, Object> run = awaitRun(runId);
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("AGENT_INVARIANT_BLOCKED");
        assertThat(toolCalls(runId).get(1).get("result_summary").toString()).contains("白名单外工具");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=2",
                        String.class))
                .isEqualTo("draft");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_eval_cases"
                                + " WHERE agent_slug='" + SLUG + "' AND agent_version=2", String.class))
                .isEqualTo("PENDING");
    }

    // ==================================================================
    // ③ reject：硬删 + 幂等
    // ==================================================================

    @Test
    void rejectHardDeletesDraftWithAuditAndIsIdempotent() {
        draftRow(SLUG, 2);
        long tasksBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_REJECT'", Long.class);

        ResponseEntity<String> first = httpPost("/api/agents/" + SLUG + "/drafts/2/reject", "{}");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitRun(runIdOf(first)).get("status")).isEqualTo("SUCCESS");

        // 03 决策：拒绝 = 硬删行（含该版本用例，版本消亡不留孤儿）
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=2",
                        Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_eval_cases WHERE agent_slug='" + SLUG + "' AND agent_version=2",
                        Long.class))
                .isZero();
        List<Map<String, Object>> audits = audits("agent.definition.rejected");
        assertThat(audits).singleElement();
        assertThat(audits.getFirst().get("operator")).isEqualTo(ADMIN_USER);

        // 对已拒绝幂等 200（不产生新任务）
        ResponseEntity<String> repeat = httpPost("/api/agents/" + SLUG + "/drafts/2/reject", "{}");
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseBody(repeat).get("status").asText()).isEqualTo("rejected");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_REJECT'", Long.class))
                .isEqualTo(tasksBefore + 1);
    }

    @Test
    void rejectActiveOrMissingVersionReturns409Or404() {
        ResponseEntity<String> active = httpPost("/api/agents/" + SLUG + "/drafts/1/reject", "{}");
        assertThat(active.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parseBody(active).get("business_code").asText()).isEqualTo("AGENT_VERSION_NOT_DRAFT");

        ResponseEntity<String> missing = httpPost("/api/agents/" + SLUG + "/drafts/9/reject", "{}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    // ④ set-enabled：显式目标值幂等，与 status 正交
    // ==================================================================

    @Test
    void setEnabledIsTargetValueIdempotentAndDoesNotTouchStatus() {
        // 目标值已是 true → 200 重放（不产生任务）
        ResponseEntity<String> noop = httpPost("/api/agents/" + SLUG + "/set-enabled", "{\"enabled\":true}");
        assertThat(noop.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseBody(noop).get("enabled").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_SET_ENABLED'", Long.class))
                .isZero();

        // 停用：202 → SUCCESS，只改 enabled 不碰 status
        ResponseEntity<String> disable = httpPost("/api/agents/" + SLUG + "/set-enabled", "{\"enabled\":false}");
        assertThat(disable.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitRun(runIdOf(disable)).get("status")).isEqualTo("SUCCESS");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, enabled FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1");
        assertThat(row.get("status")).isEqualTo("active");
        assertThat(row.get("enabled")).isEqualTo(false);
        // 注册表换实例：运行路径感知停用
        assertThat(holder.current().bySlug(SLUG).enabled()).isFalse();

        // 已处于目标值 → 200 重放
        ResponseEntity<String> repeat = httpPost("/api/agents/" + SLUG + "/set-enabled", "{\"enabled\":false}");
        assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.OK);
        long tasksAfter = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE task_type='AGENT_SET_ENABLED'", Long.class);
        assertThat(tasksAfter).isEqualTo(1);

        // 重新启用：显式目标值可翻转（非 toggle，语义无歧义）
        ResponseEntity<String> enable = httpPost("/api/agents/" + SLUG + "/set-enabled", "{\"enabled\":true}");
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitRun(runIdOf(enable)).get("status")).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT enabled FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1",
                        Boolean.class))
                .isTrue();

        // 无生效版本 → 404
        ResponseEntity<String> missing = httpPost("/api/agents/no-such-agent/set-enabled", "{\"enabled\":true}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    // ⑤ rollback：复制为 v{n+1} 新草稿（版本链无回边）
    // ==================================================================

    @Test
    void rollbackCopiesTargetAsNewDraftWithoutTouchingOldRows() {
        // 给 v1 冻结一份 CONFIRMED 用例集（07：每版本冻结）
        jdbc.update(
                "INSERT INTO app.agent_eval_cases"
                        + " (agent_slug, agent_version, metric_kind, input, expected, status, created_by, confirmed_by, confirmed_at)"
                        + " VALUES (?, 1, 'QUALITY', ?::jsonb, ?::jsonb, 'CONFIRMED', 'system', 'system', CURRENT_TIMESTAMP)",
                SLUG, "{\"input\":\"SKU-1001 价格\"}", "{\"answer_contains\":[\"12.34\"]}");
        OffsetDateTime activatedAt = jdbc.queryForObject(
                "SELECT activated_at FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1",
                OffsetDateTime.class);

        ResponseEntity<String> response = httpPost("/api/agents/" + SLUG + "/rollback", "{\"target_version\":1}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitRun(runIdOf(response)).get("status")).isEqualTo("SUCCESS");

        // 新行 = v2 draft，内容为 v1 全量复制（激活事实为空——新草稿未确认）
        Map<String, Object> v2 = jdbc.queryForMap(
                "SELECT name, system_prompt, prompt_version, model_ref, allow_write, enabled, status,"
                        + " activated_by, tool_whitelist::text FROM app.agent_definitions"
                        + " WHERE agent_slug='" + SLUG + "' AND version=2");
        assertThat(v2.get("status")).isEqualTo("draft");
        assertThat(v2.get("name")).isEqualTo("测试 Agent");
        assertThat(v2.get("system_prompt")).isEqualTo("你是只读助手。");
        assertThat(v2.get("prompt_version")).isEqualTo("t11-v1");
        assertThat(v2.get("model_ref")).isEqualTo("app.agent");
        assertThat(v2.get("enabled")).isEqualTo(true);
        assertThat(v2.get("activated_by")).isNull();
        assertThat(v2.get("tool_whitelist").toString()).contains("search_skus");

        // 旧行零改动（append-only：状态、激活事实不变）
        Map<String, Object> v1 = jdbc.queryForMap(
                "SELECT status, activated_at FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1");
        assertThat(v1.get("status")).isEqualTo("active");
        assertThat(((java.sql.Timestamp) v1.get("activated_at")).toInstant())
                .isEqualTo(activatedAt.toInstant());

        // 冻结用例集复制为新版本 PENDING（换版本 = 换用例集，评测可复现可回滚）
        Map<String, Object> copied = jdbc.queryForMap(
                "SELECT status, created_by, input::text FROM app.agent_eval_cases"
                        + " WHERE agent_slug='" + SLUG + "' AND agent_version=2");
        assertThat(copied.get("status")).isEqualTo("PENDING");
        assertThat(copied.get("created_by")).isEqualTo(ADMIN_USER);
        assertThat(copied.get("input").toString()).contains("SKU-1001 价格");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_eval_cases WHERE agent_slug='" + SLUG + "' AND agent_version=1",
                        Long.class))
                .isEqualTo(1L);

        // 回滚产物可走正常确认流：confirm v2 → v2 active、v1 retired
        confirmAndAwait(SLUG, 2);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=2",
                        String.class))
                .isEqualTo("active");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=1",
                        String.class))
                .isEqualTo("retired");

        // retired（曾 active）版本仍可回滚 → v3 draft；草稿目标 409、不存在 404
        ResponseEntity<String> retiredRollback = httpPost("/api/agents/" + SLUG + "/rollback", "{\"target_version\":1}");
        assertThat(retiredRollback.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(awaitRun(runIdOf(retiredRollback)).get("status")).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='" + SLUG + "' AND version=3",
                        String.class))
                .isEqualTo("draft");

        ResponseEntity<String> draftTarget = httpPost("/api/agents/" + SLUG + "/rollback", "{\"target_version\":3}");
        assertThat(draftTarget.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parseBody(draftTarget).get("business_code").asText()).isEqualTo("AGENT_ROLLBACK_TARGET_NOT_ACTIVE");

        ResponseEntity<String> missing = httpPost("/api/agents/" + SLUG + "/rollback", "{\"target_version\":9}");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ==================================================================
    // ⑥ 并发：并发确认不同版本，DB 部分唯一索引兜底，败者 AGENT_CONFLICT
    // ==================================================================

    @Test
    void concurrentConfirmOfDifferentVersionsLoserGetsConflict() throws Exception {
        insertDraftRow(SLUG, 2, "[\"search_skus\"]");
        insertDraftRow(SLUG, 3, "[\"search_skus\"]");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> f2 = pool.submit(() -> {
                start.await();
                return httpPost("/api/agents/" + SLUG + "/drafts/2/confirm", "{}");
            });
            Future<ResponseEntity<String>> f3 = pool.submit(() -> {
                start.await();
                return httpPost("/api/agents/" + SLUG + "/drafts/3/confirm", "{}");
            });
            start.countDown();

            ResponseEntity<String> r2 = f2.get(10, TimeUnit.SECONDS);
            ResponseEntity<String> r3 = f3.get(10, TimeUnit.SECONDS);
            assertThat(r2.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(r3.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

            Map<String, Object> out2 = awaitRun(runIdOf(r2));
            Map<String, Object> out3 = awaitRun(runIdOf(r3));
            long successes = List.of(out2, out3).stream()
                    .filter(run -> "SUCCESS".equals(run.get("status"))).count();
            long conflicts = List.of(out2, out3).stream()
                    .filter(run -> "AGENT_CONFLICT".equals(run.get("error_type"))).count();
            // 并发确认不同版本：恰一个生效，败者由部分唯一索引兜底为 409 语义
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM app.agent_definitions"
                                    + " WHERE agent_slug='" + SLUG + "' AND status='active'", Long.class))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    // ==================================================================
    // ⑦ 身份红线：operator 来自 Basic Auth，请求体禁止携带
    // ==================================================================

    @Test
    void operatorFieldInBodyIsRejectedForEveryWriteAction() {
        ObjectNode draft = baseDraft("t11-op-draft");
        draft.put("operator", "hacker");

        ResponseEntity<String> drafts = httpPost("/api/agents/drafts", draft.toString());
        assertThat(drafts.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseBody(drafts).get("business_code").asText()).isEqualTo("OPERATOR_FIELD_FORBIDDEN");

        ResponseEntity<String> confirm = httpPost("/api/agents/" + SLUG + "/drafts/1/confirm", "{\"operator\":\"hacker\"}");
        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parseBody(confirm).get("business_code").asText()).isEqualTo("OPERATOR_FIELD_FORBIDDEN");

        ResponseEntity<String> reject = httpPost("/api/agents/" + SLUG + "/drafts/1/reject", "{\"operator\":\"hacker\"}");
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> setEnabled =
                httpPost("/api/agents/" + SLUG + "/set-enabled", "{\"enabled\":true,\"operator\":\"hacker\"}");
        assertThat(setEnabled.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<String> rollback =
                httpPost("/api/agents/" + SLUG + "/rollback", "{\"target_version\":1,\"operator\":\"hacker\"}");
        assertThat(rollback.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 全部被拒：无任何任务/运行产生
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.async_tasks", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.agent_runs", Long.class)).isZero();
    }

    @Test
    void confirmTaskAuditUsesBasicAuthIdentityNotRequestClaims() {
        draftRow(SLUG, 2);
        confirmAndAwait(SLUG, 2);
        // 审计 operator 与 activated_by 都是 Basic Auth 用户名（不是任何请求体字段）
        assertThat(jdbc.queryForObject(
                        "SELECT activated_by FROM app.agent_definitions"
                                + " WHERE agent_slug='" + SLUG + "' AND version=2", String.class))
                .isEqualTo(ADMIN_USER);
        assertThat(audits("agent.definition.activated").getFirst().get("operator")).isEqualTo(ADMIN_USER);
    }

    // ==================================================================
    // 助手
    // ==================================================================

    private ObjectNode baseDraft(String slug) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("agent_slug", slug);
        draft.put("name", "测试 Agent");
        draft.put("description", "写端点契约测试草稿");
        draft.put("system_prompt", "你是只读助手。");
        draft.put("prompt_version", "t11-v1");
        draft.put("model_ref", "app.agent");
        draft.put("enabled", true);
        draft.putArray("tool_whitelist").add("search_skus");
        draft.put("allow_write", false);
        draft.putArray("guard_exemptions");
        return draft;
    }

    private ResponseEntity<String> httpPost(String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
        headers.set("X-Operator", ADMIN_USER);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private static String runIdOf(ResponseEntity<String> response) {
        JsonNode body = parseBody(response);
        return body.path("run_id").asText();
    }

    private static JsonNode parseBody(ResponseEntity<String> response) {
        try {
            return new ObjectMapper().readTree(response.getBody());
        } catch (Exception ex) {
            throw new IllegalStateException("响应不是合法 JSON: " + response.getBody(), ex);
        }
    }

    /** 轮询 agent_runs 直到终态（真实 202 → 轮询闭环，worker 开启 poll-ms=50）。 */
    private Map<String, Object> awaitRun(String runId) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status, error_type FROM app.agent_runs WHERE run_id = ?", runId);
            if (!rows.isEmpty() && !"RUNNING".equals(rows.getFirst().get("status"))) {
                return rows.getFirst();
            }
            sleep(50);
        }
        throw new AssertionError("运行未在超时内收口: " + runId);
    }

    private void awaitDraft(String slug, int version) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM app.agent_definitions WHERE agent_slug=? AND version=?",
                    Integer.class, slug, version);
            if (count != null && count > 0) {
                return;
            }
            sleep(50);
        }
        throw new AssertionError("草稿未在超时内落库: " + slug + " v" + version);
    }

    /** 直接落库一条合法草稿行（drafts API 只建新 slug；同 slug 新版本草稿在此直接插入）。 */
    private void draftRow(String slug, int version) {
        insertDraftRow(slug, version, "[\"search_skus\"]");
    }

    private void confirmAndAwait(String slug, int version) {
        ResponseEntity<String> response =
                httpPost("/api/agents/" + slug + "/drafts/" + version + "/confirm", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        awaitRun(runIdOf(response));
    }

    /** 直接插入 draft 行（模拟绕过建草稿门禁的脏数据/人工编辑），version 自选。 */
    private void insertDraftRow(String slug, int version, String toolWhitelistJson) {
        jdbc.update(
                """
                INSERT INTO app.agent_definitions
                    (agent_slug, name, description, system_prompt, prompt_version, model_ref,
                     enabled, version, status, activated_by, activated_at, allow_write,
                     guard_exemptions, output_schema, tool_whitelist, input_format)
                VALUES (?, '测试 Agent', '直接插入的草稿', '你是只读助手。', ?, 'app.agent',
                        true, ?, 'draft', NULL, NULL, false, '[]'::jsonb, NULL, ?::jsonb,
                        'NATURAL_LANGUAGE')
                """,
                slug, "t11-v" + version, version, toolWhitelistJson);
    }

    private List<Map<String, Object>> toolCalls(String runId) {
        return jdbc.queryForList(
                "SELECT tool_name, result_summary FROM app.agent_tool_calls"
                        + " WHERE run_id = ? ORDER BY sequence_no",
                runId);
    }

    private List<Map<String, Object>> audits(String operation) {
        return jdbc.queryForList(
                "SELECT operator, actor_type FROM app.audit_logs WHERE operation = ?", operation);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试轮询被中断", ex);
        }
    }
}
