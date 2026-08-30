package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
 * 08 — Agent 可观测性验收（agent-decision-layer 08，Testcontainers）：真实 PostgreSQL
 * （Flyway V29 自动迁移）下——完整 run 落 agent_run 行（status/error/latency/digest/
 * business_entity）且与 AuditLog 以 run_id 双向关联；工具调用按 run_id+序号落
 * agent_tool_call 行且敏感参数脱敏（负向断言）；同一 run_id 下 run 行 + 工具序列 + 审计
 * 可完整重放。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.agent-identity=acceptance-agent"
        })
class AgentObservabilityIntegrationTest {

    private static final String INPUT = "汇总一下进货价";
    private static final String SLUG = "obs-test-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private AgentRuntimeFacade facade;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private AgentToolBindingFactory bindingFactory;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private McpAgentIdentity identity;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.agent_runs, app.agent_tool_calls, app.audit_logs,
                         app.idempotency_registry
                RESTART IDENTITY CASCADE
                """);
        // T02 后定义真源为 DB：测试 Agent 幂等注册（先删同 slug 再插），holder 换实例即被运行路径感知；
        // 白名单只含只读工具（08 决策：写工具需 allow_write=true 才能在绑定期放行）
        AgentSeedFixtures.upsertActiveDefinition(
                jdbc,
                AgentDefinition.ofActiveV1(
                        SLUG, "可观测性验收 Agent", "d", "你是只读助手。", "obs-v1", "app.agent", true,
                        List.of("search_skus")));
        holder.reload();
    }

    @Test
    void fullRunWritesRunRowLinkedToAuditAndBusinessEntity() {
        // 上下文携带业务实体（PROCUREMENT_TICKET/42），随 run 行落库供双向追溯
        AgentRunResult result = facade.invoke(
                SLUG,
                INPUT,
                AgentRunContext.of("thread-obs").withBusinessEntity("PROCUREMENT_TICKET", "42"));

        // 测试上下文未配置模型：fail-closed 稳定码（业务正常）
        assertThat(result.error()).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");

        // run_id 双向关联锚点：审计 trace_id/request_id = run_id
        AuditLog audit = audits.findAll().stream()
                .filter(log -> ("agent." + SLUG + ".run").equals(log.getOperation()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少 agent." + SLUG + ".run 审计"));
        String runId = audit.getTraceId();
        assertThat(audit.getRequestId()).isEqualTo(runId);

        Map<String, Object> run = jdbc.queryForMap(
                "SELECT run_id, thread_id, agent_slug, prompt_version, model, input_digest,"
                        + " status, error_type, latency_ms, token_usage, business_entity_type,"
                        + " business_entity_id, started_at, finished_at"
                        + " FROM app.agent_runs WHERE run_id = ?",
                runId);
        assertThat(run.get("run_id")).isEqualTo(runId);
        assertThat(run.get("thread_id")).isEqualTo("thread-obs");
        assertThat(run.get("agent_slug")).isEqualTo(SLUG);
        assertThat(run.get("prompt_version")).isEqualTo("obs-v1");
        // 未白名单的三元组投影为 none（allowlist 生效）
        assertThat(run.get("model")).isEqualTo("none");
        // input 只存 digest 不存原文
        assertThat(run.get("input_digest")).isEqualTo(sha256(INPUT));
        assertThat(run.values()).doesNotContain(INPUT);
        // 失败 run：status=FAILED + 稳定 error_type，finished_at 已收口
        assertThat(run.get("status")).isEqualTo("FAILED");
        assertThat(run.get("error_type")).isEqualTo("AGENT_MODEL_NOT_CONFIGURED");
        assertThat(((Number) run.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);
        assertThat(run.get("token_usage")).isNull();
        assertThat(run.get("business_entity_type")).isEqualTo("PROCUREMENT_TICKET");
        assertThat(run.get("business_entity_id")).isEqualTo("42");
        assertThat(run.get("started_at")).isNotNull();
        assertThat(run.get("finished_at")).isNotNull();

        // 双向：从业务实体可回溯到 run_id
        List<String> byEntity = jdbc.queryForList(
                "SELECT run_id FROM app.agent_runs"
                        + " WHERE business_entity_type = 'PROCUREMENT_TICKET'"
                        + "   AND business_entity_id = '42'",
                String.class);
        assertThat(byEntity).containsExactly(runId);
    }

    @Test
    void toolCallsAreRecordedAsOrderedRedactedSequenceUnderRunId() throws Exception {
        String runId = AgentRuntimeFacade.newRunId();
        AgentToolBinding binding = bindingFactory.bind(runId, List.of("search_skus"));
        AgentToolInvoker invoker = (AgentToolInvoker) binding.tools().values().iterator().next();

        String first = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("search_skus")
                        .arguments("{\"query\":\"羊\",\"page\":0,\"size\":3}")
                        .build(),
                null);
        assertThat(mapper.readTree(first).has("total_elements")).isTrue();

        // 敏感参数必须脱敏：负向断言原文不落库
        String second = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("search_skus")
                        .arguments(
                                "{\"query\":\"羊\",\"api_key\":\"sk-leak\",\"password\":\"hunter2\","
                                        + "\"phone\":\"13800138000\",\"receiver_name\":\"张三\"}")
                        .build(),
                null);
        assertThat(mapper.readTree(second).get("code").asText()).isEqualTo("INVALID_PARAMETERS");

        List<Map<String, Object>> calls = jdbc.queryForList(
                "SELECT run_id, sequence_no, tool_name, args_summary, result_summary,"
                        + " latency_ms, status FROM app.agent_tool_calls"
                        + " WHERE run_id = ? ORDER BY sequence_no",
                runId);
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).get("sequence_no")).isEqualTo(1);
        assertThat(calls.get(1).get("sequence_no")).isEqualTo(2);
        assertThat(calls.get(0).get("status")).isEqualTo("SUCCESS");
        assertThat(calls.get(1).get("status")).isEqualTo("FAILED");
        for (Map<String, Object> call : calls) {
            assertThat(call.get("run_id")).isEqualTo(runId);
            assertThat(call.get("tool_name")).isEqualTo("search_skus");
            assertThat(((Number) call.get("latency_ms")).intValue()).isGreaterThanOrEqualTo(0);
            assertThat(call.get("result_summary")).isNotNull();
        }
        assertThat((String) calls.get(1).get("result_summary")).contains("INVALID_PARAMETERS");
        String secondArgs = (String) calls.get(1).get("args_summary");
        assertThat(secondArgs).contains("***");
        assertThat(secondArgs)
                .doesNotContain("sk-leak")
                .doesNotContain("hunter2")
                .doesNotContain("13800138000")
                .doesNotContain("张三");
        // 第一行参数无敏感键，保留查询词（脱敏不破坏结构）
        assertThat((String) calls.get(0).get("args_summary")).contains("羊");
    }

    @Test
    void failedToolCallIsRecordedWithFailedStatus() throws Exception {
        String runId = AgentRuntimeFacade.newRunId();
        // 写工具需显式 allow_write=true 才能在 Agent 面绑定（08 决策）
        AgentToolBinding binding = bindingFactory.bind(runId, List.of("reinterpret_submission"), true);
        AgentToolInvoker invoker = (AgentToolInvoker) binding.tools().values().iterator().next();

        // 写工具缺 idempotency_key → 业务失败（INVALID_PARAMETERS），观测行必须 FAILED
        String outcome = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("reinterpret_submission")
                        .arguments("{\"submission_id\":\"1\"}")
                        .build(),
                null);

        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(outcome);
        assertThat(node.get("code").asText()).isEqualTo("INVALID_PARAMETERS");

        Map<String, Object> call = jdbc.queryForMap(
                "SELECT tool_name, status, result_summary FROM app.agent_tool_calls"
                        + " WHERE run_id = ? AND sequence_no = 1",
                runId);
        assertThat(call.get("tool_name")).isEqualTo("reinterpret_submission");
        assertThat(call.get("status")).isEqualTo("FAILED");
        assertThat((String) call.get("result_summary")).contains("INVALID_PARAMETERS");
    }

    @Test
    void bindingWithObservabilityRecordsToolCallsThroughSpringWiring() {
        // 工具绑定工厂经 Spring 注入真实 provider（JdbcAgentObservability）
        String runId = AgentRuntimeFacade.newRunId();
        AgentToolBinding binding = bindingFactory.bind(runId, List.of("search_skus"));
        ((AgentToolInvoker) binding.tools().values().iterator().next()).execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name("search_skus")
                        .arguments("{\"query\":\"羊\",\"page\":0,\"size\":3}")
                        .build(),
                null);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.agent_tool_calls WHERE run_id = ?", Integer.class, runId);
        assertThat(count).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
