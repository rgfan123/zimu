package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 12 票验收（/api 面，Basic Auth）：读端点契约测试——过滤条件生效、字段投影正确、
 * 无 PII/凭据外泄的负例断言、模型元数据 allowlist 投影三态区分、工具白名单读/写属性、
 * 列表一次拿全（防 N+1 聚合）。
 *
 * <p>测试数据按测试隔离（每用例独立 slug/run_id），共享同一真实 PostgreSQL（Flyway
 * V33 种子为基线）；profile production-auth-test 使生产鉴权策略（/api Basic Auth +
 * /internal Bearer 服务身份）经 HTTP 生效。
 */
@Testcontainers
@ActiveProfiles("production-auth-test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=business-admin",
            "app.gateway.basic-auth.password=business-admin-password",
            "app.internal-auth.service-name=trusted-agent-service",
            "app.internal-auth.bearer-token=internal-agent-token-for-tests",
            // 服务端 allowlist：仅此三元组可公开暴露真实 provider/model/prompt-version
            "app.agent.public-metadata-aliases[0].provider=test-provider",
            "app.agent.public-metadata-aliases[0].model=test-model",
            "app.agent.public-metadata-aliases[0].prompt-version=test-prompt-v1"
        })
class AgentReadEndpointsApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------
    // 鉴权：/api 需要 Basic Auth + 匹配的 X-Operator
    // ------------------------------------------------------------------

    @Test
    void businessAuthRequiredForAgentReads() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "business-admin");

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("business_code", "AUTHENTICATED_OPERATOR_REQUIRED");
    }

    @Test
    void matchingBasicCredentialsReachAgentReads() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsKey("items");
    }

    // ------------------------------------------------------------------
    // Agent 列表：一次拿全（聚合防 N+1）+ 工具读/写属性
    // ------------------------------------------------------------------

    @Test
    void agentsListCarriesDraftCountsAndSevenDayLiveRunStats() {
        insertDefinition("stats-e2e", "统计验收 Agent", 1, "active", true, "system", now(), false, List.of());
        insertRun("stats-e2e", "SUCCESS", null, "LIVE", now().minusDays(1), "none", "none", null);
        insertRun("stats-e2e", "SUCCESS", null, "LIVE", now().minusDays(2), "none", "none", null);
        insertRun("stats-e2e", "FAILED", "AGENT_OUTPUT_INVALID", "LIVE", now().minusDays(3), "none", "none", null);
        insertRun("stats-e2e", "SUCCESS", null, "PREVIEW", now().minusDays(1), "none", "none", null);
        insertDefinition("draft-count-e2e", "草稿计数验收 Agent", 1, "draft", true, null, null, false, List.of());
        // 10 天前的 LIVE 运行不计入近 7 日
        insertRun("stats-e2e", "FAILED", "AGENT_MODEL_CALL_FAILED", "LIVE", now().minusDays(10), "none", "none", null);

        List<Map<String, Object>> items = agentsList();

        Map<String, Object> stats = itemBySlug(items, "stats-e2e");
        assertThat(stats).containsEntry("state", "RUNNING").containsEntry("current_version", 1);
        assertThat(((Number) stats.get("seven_day_run_count")).longValue()).isEqualTo(3);
        assertThat(((Number) stats.get("seven_day_failure_count")).longValue()).isEqualTo(1);
        assertThat(((Number) stats.get("draft_count")).longValue()).isZero();

        Map<String, Object> draft = itemBySlug(items, "draft-count-e2e");
        assertThat(draft).containsEntry("state", "NO_ACTIVE_VERSION");
        assertThat(draft.get("current_version")).isNull();
        assertThat(((Number) draft.get("draft_count")).longValue()).isEqualTo(1);

        // V45 激活采购 Agent v2；列表必须返回当前 active 版本。
        Map<String, Object> procurement = itemBySlug(items, "procurement-price-agent");
        assertThat(procurement).containsEntry("state", "RUNNING").containsEntry("current_version", 2);
    }

    @Test
    void agentsListProjectsToolReadWriteAttributes() {
        List<Map<String, Object>> items = agentsList();

        // meta-agent：allow_write=true，白名单含写工具 → read_only=false（红线可视化）
        Map<String, Object> meta = itemBySlug(items, "meta-agent");
        assertThat(meta).containsEntry("allow_write", true);
        List<Map<String, Object>> metaTools = toolsOf(meta);
        assertThat(metaTools).hasSize(3);
        assertThat(metaTools).anySatisfy(tool -> {
            assertThat(tool).containsEntry("name", "create_agent_draft").containsEntry("read_only", false);
        });
        assertThat(metaTools).anySatisfy(tool -> {
            assertThat(tool).containsEntry("name", "update_agent_draft").containsEntry("read_only", false);
        });
        assertThat(metaTools).anySatisfy(tool -> {
            assertThat(tool).containsEntry("name", "list_agent_tools").containsEntry("read_only", true);
        });
        assertThat(metaTools).allSatisfy(tool -> assertThat(tool).containsEntry("registered", true));

        // 业务 Agent：白名单全只读（平台红线：业务 Agent 写工具零调用）
        Map<String, Object> procurement = itemBySlug(items, "procurement-price-agent");
        assertThat(procurement).containsEntry("allow_write", false);
        assertThat(toolsOf(procurement)).hasSize(11).allSatisfy(tool ->
                assertThat(tool).containsEntry("read_only", true).containsEntry("registered", true));

        // intent-recognition：无工具（单次分类接缝）→ 空数组而非空白
        Map<String, Object> intent = itemBySlug(items, "intent-recognition");
        assertThat(toolsOf(intent)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Agent 详情 / 版本链 / 评测用例
    // ------------------------------------------------------------------

    @Test
    void agentDetailReturnsFullDefinitionFacts() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents/procurement-price-agent",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("slug", "procurement-price-agent")
                .containsEntry("name", "采购比价 Agent")
                .containsEntry("prompt_version", "procurement-price-v2")
                .containsEntry("model_ref", "app.agent")
                .containsEntry("version", 2)
                .containsEntry("status", "ACTIVE")
                .containsEntry("enabled", true)
                .containsEntry("allow_write", false)
                .containsEntry("input_format", "STRUCTURED_JSON")
                .containsEntry("activated_by", "system:v45-seed");
        assertThat((String) body.get("system_prompt")).isNotBlank();
        assertThat(body.get("activated_at")).isNotNull();
        assertThat(body.get("output_schema")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) body.get("output_schema")).containsKey("properties")).isTrue();
        assertThat((List<?>) body.get("guard_exemptions")).isEmpty();
        assertThat(toolsOf(body)).hasSize(11);
    }

    @Test
    void agentDetailNotFoundForUnknownSlug() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents/no-such-agent",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("business_code", "NOT_FOUND");
    }

    @Test
    void versionsReturnsFullChainWithActivationInfo() {
        insertDefinition("chain-e2e", "版本链验收 Agent", 1, "active", true, "system", now(), false, List.of());
        insertDefinition("chain-e2e", "版本链验收 Agent", 2, "draft", true, null, null, false, List.of());

        ResponseEntity<List> response = http.exchange(
                "/api/v1/agents/chain-e2e/versions",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> versions = response.getBody();
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0)).containsEntry("version", 1).containsEntry("status", "ACTIVE");
        assertThat(versions.get(0).get("activated_by")).isEqualTo("system");
        assertThat(versions.get(0).get("activated_at")).isNotNull();
        assertThat(versions.get(1)).containsEntry("version", 2).containsEntry("status", "DRAFT");
        assertThat(versions.get(1).get("activated_by")).isNull();
        assertThat(versions.get(1).get("activated_at")).isNull();
    }

    @Test
    void versionsNotFoundForUnknownSlug() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents/no-such-agent/versions",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void evalCasesReturnFrozenCaseSetPerVersion() {
        ResponseEntity<List> response = http.exchange(
                "/api/v1/agents/procurement-price-agent/versions/1/eval-cases",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                List.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> cases = response.getBody();
        assertThat(cases).hasSize(7);
        assertThat(cases).allSatisfy(caseRow -> {
            assertThat(caseRow).containsEntry("metric_kind", "INVARIANT")
                    .containsEntry("status", "CONFIRMED")
                    .containsEntry("agent_version", 1);
            assertThat(caseRow.get("input")).isNotNull();
            assertThat(caseRow.get("expected")).isNotNull();
        });

        // metric_kind 过滤
        ResponseEntity<List> invariant = http.exchange(
                "/api/v1/agents/procurement-price-agent/versions/1/eval-cases?metric_kind=INVARIANT",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                List.class);
        assertThat(invariant.getBody()).hasSize(7);
        ResponseEntity<List> quality = http.exchange(
                "/api/v1/agents/procurement-price-agent/versions/1/eval-cases?metric_kind=QUALITY",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                List.class);
        assertThat(quality.getBody()).isEmpty();
    }

    @Test
    void evalCasesNotFoundForUnknownVersionAndRejectUnknownMetricKind() {
        ResponseEntity<Map> notFound = http.exchange(
                "/api/v1/agents/procurement-price-agent/versions/999/eval-cases",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<Map> invalid = http.exchange(
                "/api/v1/agents/procurement-price-agent/versions/1/eval-cases?metric_kind=FOO",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------
    // 运行记录列表：过滤条件生效（run_mode 默认排除 PREVIEW / outcome / 时间范围）
    // ------------------------------------------------------------------

    @Test
    void runListDefaultsToLiveAndExcludesPreview() {
        insertDefinition("runmode-e2e", "运行模式验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String liveId = insertRun("runmode-e2e", "SUCCESS", null, "LIVE", now().minusHours(1), "none", "none", null);
        String previewId = insertRun("runmode-e2e", "SUCCESS", null, "PREVIEW", now().minusHours(1), "none", "none", null);

        ResponseEntity<Map> defaultList = http.exchange(
                "/api/v1/agent-runs?slug=runmode-e2e",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        List<Map<String, Object>> defaultItems = itemsOf(defaultList.getBody());
        assertThat(runIds(defaultItems)).containsExactly(liveId);
        assertThat(((Number) defaultList.getBody().get("total")).longValue()).isEqualTo(1);

        ResponseEntity<Map> previewList = http.exchange(
                "/api/v1/agent-runs?slug=runmode-e2e&run_mode=PREVIEW",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(previewList.getBody()))).containsExactly(previewId);
    }

    @Test
    void runListFiltersByOutcomeDerivedFromStatusAndErrorType() {
        insertDefinition("outcome-e2e", "结果维度验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String successId = insertRun("outcome-e2e", "SUCCESS", null, "LIVE", now().minusHours(1), "none", "none", null);
        String failedId = insertRun("outcome-e2e", "FAILED", "AGENT_OUTPUT_INVALID", "LIVE", now().minusHours(1), "none", "none", null);
        String rejectedId = insertRun("outcome-e2e", "FAILED", "PII_GUARDED", "LIVE", now().minusHours(1), "none", "none", null);
        String runningId = insertRun("outcome-e2e", "RUNNING", null, "LIVE", now().minusHours(1), "none", "none", null);

        // 全部（含 RUNNING：outcome 为 null）
        ResponseEntity<Map> all = http.exchange(
                "/api/v1/agent-runs?slug=outcome-e2e",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(all.getBody()))).containsExactlyInAnyOrder(successId, failedId, rejectedId, runningId);

        assertThat(runIds(itemsOf(query("slug=outcome-e2e&outcome=SUCCESS").getBody()))).containsExactly(successId);
        assertThat(runIds(itemsOf(query("slug=outcome-e2e&outcome=FAILED").getBody()))).containsExactly(failedId);
        assertThat(runIds(itemsOf(query("slug=outcome-e2e&outcome=REJECTED").getBody()))).containsExactly(rejectedId);
        // RUNNING 行的 outcome 派生为 null，且其 status 不出现在任何 outcome 过滤中
        ResponseEntity<Map> running = query("slug=outcome-e2e&outcome=FAILED");
        List<Map<String, Object>> failedItems = itemsOf(running.getBody());
        assertThat(failedItems).allSatisfy(item -> assertThat(item).containsEntry("outcome", "FAILED"));
    }

    @Test
    void runListFiltersByRunIdTimeRangeAndBusinessEntity() {
        insertDefinition("filters-e2e", "过滤验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String inside = insertRun("filters-e2e", "SUCCESS", null, "LIVE", now().minusDays(2), "none", "none", null);
        String outside = insertRun("filters-e2e", "SUCCESS", null, "LIVE", now().minusDays(10), "none", "none", null);

        // run_id 精确过滤
        ResponseEntity<Map> byRunId = http.exchange(
                "/api/v1/agent-runs?run_id=" + inside,
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(byRunId.getBody()))).containsExactly(inside);

        // 时间范围（含上下界；UTC 格式避免 +08:00 的 + 在查询串中的编码歧义）
        String from = now().minusDays(7).withOffsetSameInstant(java.time.ZoneOffset.UTC)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
        ResponseEntity<Map> range = http.exchange(
                "/api/v1/agent-runs?slug=filters-e2e&started_from=" + from,
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(range.getStatusCode()).as("started_from 过滤应成功: %s", range.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(runIds(itemsOf(range.getBody()))).containsExactly(inside);
        assertThat(((Number) range.getBody().get("total")).longValue()).isEqualTo(1);
        assertThat(outside).isNotEqualTo(inside);

        // 业务实体过滤
        ResponseEntity<Map> entity = http.exchange(
                "/api/v1/agent-runs?slug=filters-e2e&business_entity_type=PROCUREMENT_TICKET&business_entity_id=42",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(runIds(itemsOf(entity.getBody()))).containsExactlyInAnyOrder(inside, outside);
    }

    @Test
    void invalidRunFiltersRejectedAsBadRequest() {
        for (String query : List.of(
                "outcome=FOO",
                "run_mode=STAGING",
                "slug=UPPER-CASE",
                "run_id=not-a-run-id",
                "limit=0",
                "limit=1000")) {
            ResponseEntity<Map> response = http.exchange(
                    "/api/v1/agent-runs?" + query,
                    HttpMethod.GET,
                    new HttpEntity<>(businessHeaders()),
                    Map.class);
            assertThat(response.getStatusCode())
                    .as("过滤参数应被拒绝: %s", query)
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
        }
    }

    // ------------------------------------------------------------------
    // 运行记录详情：工具调用序列 + outcome + 错误码 + 评测结果摘要（202 轮询面）
    // ------------------------------------------------------------------

    @Test
    void runDetailContainsToolCallSequenceOutcomeErrorCodeAndEvalResult() {
        insertDefinition("detail-e2e", "详情验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String runId = insertRun("detail-e2e", "FAILED", "AGENT_OUTPUT_INVALID", "LIVE", now().minusHours(1), "none", "none", null);
        jdbc.update(
                "INSERT INTO app.agent_tool_calls (run_id, sequence_no, tool_name, args_summary, result_summary, latency_ms, status)"
                        + " VALUES (?, 1, 'search_skus', '{\"sku\":\"1001\"}', '2 条结果', 120, 'SUCCESS'),"
                        + " (?, 2, 'get_sku', '{\"sku_id\":\"1001\"}', '价格信息', 80, 'FAILED')",
                runId, runId);
        jdbc.update(
                "INSERT INTO app.agent_eval_results"
                        + " (run_id, agent_slug, agent_version, metric_kind, case_count, passed_count, status)"
                        + " VALUES (?, 'detail-e2e', 1, 'QUALITY', 3, 2, 'SUCCEEDED')",
                runId);

        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agent-runs/" + runId,
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("run_id", runId)
                .containsEntry("agent_slug", "detail-e2e")
                .containsEntry("status", "FAILED")
                .containsEntry("outcome", "FAILED")
                .containsEntry("error_type", "AGENT_OUTPUT_INVALID")
                .containsEntry("run_mode", "LIVE")
                .containsEntry("intent", "purchase-inquiry");
        assertThat((String) body.get("input_digest")).matches("^[0-9a-f]{64}$");
        // 工具调用序列按序号升序
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) body.get("tool_calls");
        assertThat(toolCalls).hasSize(2);
        assertThat(toolCalls.get(0)).containsEntry("sequence_no", 1).containsEntry("tool_name", "search_skus")
                .containsEntry("status", "SUCCESS");
        assertThat(toolCalls.get(1)).containsEntry("sequence_no", 2).containsEntry("tool_name", "get_sku")
                .containsEntry("status", "FAILED");
        // 评测结果摘要
        Map<String, Object> evalResult = (Map<String, Object>) body.get("eval_result");
        assertThat(evalResult).containsEntry("status", "SUCCEEDED").containsEntry("case_count", 3).containsEntry("passed_count", 2);
        // token 用量为数字对象（无敏感内容）
        assertThat(body.get("token_usage")).isNotNull();
    }

    @Test
    void runDetailNotFoundForUnknownRunId() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agent-runs/run_00000000000000000000000000000000",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("business_code", "NOT_FOUND");
    }

    // ------------------------------------------------------------------
    // 模型元数据 allowlist 投影：区分「未配置」与「未命中 allowlist 故不公开」
    // ------------------------------------------------------------------

    @Test
    void modelMetadataProjectionDistinguishesExposedNotPublicAndNotConfigured() {
        insertDefinition("modelmeta-e2e", "模型元数据验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String exposed = insertRun("modelmeta-e2e", "SUCCESS", null, "LIVE", now().minusHours(1),
                "test-provider", "test-model", "test-prompt-v1");
        String notPublic = insertRun("modelmeta-e2e", "SUCCESS", null, "LIVE", now().minusHours(1),
                "deepseek", "deepseek-chat", "deepseek-v1");
        String notConfigured = insertRun("modelmeta-e2e", "SUCCESS", null, "LIVE", now().minusHours(1),
                null, "none", null);

        // 命中 allowlist → 真实值 + EXPOSED
        Map<String, Object> exposedItem = itemByRunId(query("run_id=" + exposed));
        Map<String, Object> exposedMeta = modelMetadataOf(exposedItem);
        assertThat(exposedMeta).containsEntry("visibility", "EXPOSED")
                .containsEntry("provider", "test-provider")
                .containsEntry("model", "test-model")
                .containsEntry("prompt_version", "test-prompt-v1");

        // 未命中 allowlist → 折叠 none + NOT_PUBLIC（与「未配置」可区分，界面文案不同）
        Map<String, Object> notPublicItem = itemByRunId(query("run_id=" + notPublic));
        Map<String, Object> notPublicMeta = modelMetadataOf(notPublicItem);
        assertThat(notPublicMeta).containsEntry("visibility", "NOT_PUBLIC")
                .containsEntry("provider", "none")
                .containsEntry("model", "none")
                .containsEntry("prompt_version", "none");

        // 存储三元组即 none → NOT_CONFIGURED
        Map<String, Object> notConfiguredItem = itemByRunId(query("run_id=" + notConfigured));
        Map<String, Object> notConfiguredMeta = modelMetadataOf(notConfiguredItem);
        assertThat(notConfiguredMeta).containsEntry("visibility", "NOT_CONFIGURED")
                .containsEntry("provider", "none")
                .containsEntry("model", "none")
                .containsEntry("prompt_version", "none");
    }

    // ------------------------------------------------------------------
    // 负例断言：PII / 凭据绝不出现在任何响应
    // ------------------------------------------------------------------

    @Test
    void runResponsesNeverExposeInputPlaintextOrCredentials() throws Exception {
        String piiPlaintext = "查一下客户张三的收货地址 13800138000";
        String digest = sha256(piiPlaintext);
        insertDefinition("pii-e2e", "PII 验收 Agent", 1, "active", true, "system", now(), false, List.of());
        String runId = insertRunWithDigest("pii-e2e", "SUCCESS", null, "LIVE", now().minusHours(1), digest);

        // 列表：不含输入原文（列表不暴露 digest，但绝不含原文与凭据）
        ResponseEntity<Map> list = http.exchange(
                "/api/v1/agent-runs?slug=pii-e2e",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        String listBody = String.valueOf(list.getBody()).toLowerCase();
        assertThat(listBody).doesNotContain(piiPlaintext)
                .doesNotContain("api_key", "api-key", "password", "secret", "bearer ");

        // 详情：只有 SHA-256 摘要，没有输入原文（08 票隐私设计），也没有凭据
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/agent-runs/" + runId,
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        String detailBody = String.valueOf(detail.getBody());
        assertThat(detailBody).contains(digest).doesNotContain(piiPlaintext);
        assertThat(detailBody.toLowerCase()).doesNotContain("api_key", "api-key", "password", "secret", "bearer ");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private List<Map<String, Object>> agentsList() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agents",
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return itemsOf(response.getBody());
    }

    private ResponseEntity<Map> query(String query) {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/agent-runs?" + query,
                HttpMethod.GET,
                new HttpEntity<>(businessHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private static Map<String, Object> itemBySlug(List<Map<String, Object>> items, String slug) {
        return items.stream()
                .filter(item -> slug.equals(item.get("slug")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表缺少 slug: " + slug));
    }

    private static Map<String, Object> itemByRunId(ResponseEntity<Map> response) {
        List<Map<String, Object>> items = itemsOf(response.getBody());
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toolsOf(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("tools");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> modelMetadataOf(Map<String, Object> item) {
        return (Map<String, Object>) item.get("model_metadata");
    }

    private static List<String> runIds(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("run_id")).toList();
    }

    private static HttpHeaders businessHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "business-admin");
        headers.setBasicAuth("business-admin", "business-admin-password");
        return headers;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    private String insertRun(
            String slug, String status, String errorType, String runMode,
            OffsetDateTime startedAt, String provider, String model, String promptVersion) {
        return insertRunWithDigest(slug, status, errorType, runMode, startedAt, "ab".repeat(32), provider, model, promptVersion);
    }

    private String insertRunWithDigest(
            String slug, String status, String errorType, String runMode, OffsetDateTime startedAt, String digest) {
        return insertRunWithDigest(slug, status, errorType, runMode, startedAt, digest, null, "none", null);
    }

    private String insertRunWithDigest(
            String slug, String status, String errorType, String runMode,
            OffsetDateTime startedAt, String digest, String provider, String model, String promptVersion) {
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "");
        boolean finished = !"RUNNING".equals(status);
        jdbc.update(
                """
                INSERT INTO app.agent_runs
                    (run_id, thread_id, agent_slug, agent_version, prompt_version, model, provider,
                     input_digest, status, error_type, latency_ms, token_usage, business_entity_type,
                     business_entity_id, run_mode, intent, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                """,
                runId,
                "thread-t12",
                slug,
                "1",
                promptVersion,
                model,
                provider,
                digest,
                status,
                errorType,
                finished ? 1234 : null,
                "{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30}",
                "PROCUREMENT_TICKET",
                "42",
                runMode,
                "purchase-inquiry",
                startedAt,
                finished ? startedAt.plusSeconds(30) : null);
        return runId;
    }

    private void insertDefinition(
            String slug, String name, int version, String status, boolean enabled,
            String activatedBy, OffsetDateTime activatedAt, boolean allowWrite, List<String> tools) {
        jdbc.update(
                """
                INSERT INTO app.agent_definitions
                    (agent_slug, name, description, system_prompt, prompt_version, model_ref,
                     enabled, version, status, activated_by, activated_at, allow_write,
                     guard_exemptions, output_schema, tool_whitelist, input_format)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]'::jsonb, NULL, ?::jsonb, 'NATURAL_LANGUAGE')
                """,
                slug,
                name,
                "测试定义",
                "你是测试 Agent。",
                "t12-v1",
                "app.agent",
                enabled,
                version,
                status,
                activatedBy,
                activatedAt,
                allowWrite,
                tools.isEmpty() ? "[]" : toolsJson(tools));
    }

    private static String toolsJson(List<String> tools) {
        return "[" + tools.stream().map(tool -> "\"" + tool + "\"").collect(java.util.stream.Collectors.joining(",")) + "]";
    }

    private static String sha256(String input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    }
}
