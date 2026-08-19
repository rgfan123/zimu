package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.AgentToolInvoker;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.web.TestRequestAuthenticationConfiguration;
import cn.zimu.fulfillment.mcp.McpAgentIdentity;
import cn.zimu.fulfillment.mcp.McpServer;
import cn.zimu.fulfillment.mcp.McpToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 10 — Meta-Agent 工具面验收（meta-agent-platform-impl 10，Testcontainers）：{@code
 * list_agent_tools} 只读工具返回读写元数据（与 07 一致）；{@code create_agent_draft} /
 * {@code update_agent_draft} 写工具创建/更新草稿（全量快照 + PENDING 建议用例）并留
 * AGENT 审计与幂等重放；meta-agent 禁改（target 拒绝）、08 静态门禁拒绝脏草稿、
 * meta-agent 白名单（allow_write=true）可实际绑定。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.agent-identity=meta-agent-test"
        })
@Import(TestRequestAuthenticationConfiguration.class)
class MetaAgentDraftIntegrationTest {

    private static final String RUN_ID = "run_" + "0".repeat(32);
    private static final String IDENTITY = "meta-agent-test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private McpAgentIdentity identity;

    @Autowired
    private AgentRegistryHolder holder;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private AgentDraftService agentDraftService;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE app.idempotency_registry, app.audit_logs, app.agent_eval_cases,
                         app.agent_definitions, app.async_tasks, app.review_cases
                RESTART IDENTITY CASCADE
                """);
        // 恢复 meta-agent 定义（V33 种子语义：allow_write=true + 三工具白名单）
        AgentSeedFixtures.upsertActiveDefinition(jdbc, AgentDefinition.of(
                "meta-agent",
                "元 Agent",
                "用自然语言创建/修改受管 Agent 的定义草稿与建议评测输入",
                "你是元 Agent：只产出草稿 JSON，绝不直接启用任何 Agent，禁止修改自身定义。",
                "meta-agent-v1",
                "app.agent",
                true,
                List.of("list_agent_tools", "create_agent_draft", "update_agent_draft"),
                1,
                AgentStatus.ACTIVE,
                "system",
                java.time.OffsetDateTime.now(),
                true,
                List.of(),
                null,
                AgentInputFormat.NATURAL_LANGUAGE));
        holder.reload();
    }

    // ------------------------------------------------------------------
    // ① list_agent_tools：读写元数据（07 一致）
    // ------------------------------------------------------------------

    @Test
    void listAgentToolsExposesReadWriteMetadataConsistentWithRegistry() throws Exception {
        JsonNode tools = listAgentToolsViaStdio();

        Set<String> writeNames = registry.writeToolNames();
        assertThat(writeNames).contains("create_agent_draft", "update_agent_draft");
        for (JsonNode tool : tools) {
            assertThat(tool.has("name")).isTrue();
            assertThat(tool.has("description")).isTrue();
            assertThat(tool.has("inputSchema")).isTrue();
            assertThat(tool.has("readOnly")).isTrue();
            String name = tool.get("name").asText();
            boolean readOnly = tool.get("readOnly").asBoolean();
            if (writeNames.contains(name)) {
                assertThat(readOnly).as("写工具 readOnly 必须为 false: %s", name).isFalse();
            } else {
                assertThat(readOnly).as("只读工具 readOnly 必须为 true: %s", name).isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // ② create_agent_draft / update_agent_draft
    // ------------------------------------------------------------------

    @Test
    void createAgentDraftPersistsDraftRowAndPendingCasesWithAuditAndReplay() throws Exception {
        ObjectNode draft = baseDraft("t10-new-agent", false, List.of("search_skus"));
        draft.putArray("suggested_eval_cases").add("采购工单 9005 还差多少数量").add("SKU 进货价是多少");

        JsonNode first = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-create-key-001"));
        if (!first.has("agent_slug")) {
            throw new AssertionError("create 返回错误信封: " + first);
        }

        assertThat(first.get("agent_slug").asText()).isEqualTo("t10-new-agent");
        assertThat(first.get("version").asInt()).isEqualTo(1);
        assertThat(first.get("status").asText()).isEqualTo("DRAFT");

        // 全量快照落库：draft 行
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT version, status, allow_write, tool_whitelist::text"
                        + " FROM app.agent_definitions WHERE agent_slug = 't10-new-agent'");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(row.get("status")).isEqualTo("draft");
        assertThat(row.get("allow_write")).isEqualTo(false);
        assertThat(row.get("tool_whitelist").toString()).contains("search_skus");

        // 建议评测输入落 PENDING（QUALITY，expected 占位待人工确认）
        List<Map<String, Object>> cases = jdbc.queryForList(
                "SELECT metric_kind, status, input::text, expected::text FROM app.agent_eval_cases"
                        + " WHERE agent_slug = 't10-new-agent' ORDER BY id");
        assertThat(cases).hasSize(2);
        assertThat(cases.get(0).get("metric_kind")).isEqualTo("QUALITY");
        assertThat(cases.get(0).get("status")).isEqualTo("PENDING");
        assertThat(cases.get(0).get("input").toString()).contains("9005");
        assertThat(cases.get(0).get("expected").toString()).contains("answer_contains");

        // AGENT 审计 + 幂等重放
        AuditLog audit = onlyAudit("mcp.create_agent_draft");
        assertThat(audit.getBusinessCode()).isEqualTo("AGENT_DRAFT_CREATED");
        assertThat(audit.getOperator()).isEqualTo(IDENTITY);
        JsonNode replay = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-create-key-001"));
        assertThat(replay).isEqualTo(first);
        assertThat(audits.findAll().stream()
                        .filter(a -> "mcp.create_agent_draft".equals(a.getOperation())))
                .anySatisfy(a -> assertThat(a.getBusinessCode()).isEqualTo("IDEMPOTENT_REPLAY"));
    }

    @Test
    void updateAgentDraftOverwritesDraftOrOpensNewVersionOverActiveOrRetired() throws Exception {
        // 新 slug 创建（v1 draft）→ update 原地覆盖（仍 v1），PENDING 建议用例被替换（不累积）
        ObjectNode draftV1 = baseDraft("t10-evolve-agent", false, List.of("search_skus"));
        draftV1.putArray("suggested_eval_cases").add("缺货行数");
        agentWriteCall("create_agent_draft", Map.of("draft", draftV1, "idempotency_key", "t10-up-key-001"));

        ObjectNode draftV1b = baseDraft("t10-evolve-agent", false, List.of("search_skus", "get_sku"));
        draftV1b.putArray("suggested_eval_cases").add("工单缺口明细");
        JsonNode updated = agentWriteCall("update_agent_draft",
                Map.of("draft", draftV1b, "idempotency_key", "t10-up-key-002"));

        assertThat(updated.get("version").asInt()).isEqualTo(1);
        assertThat(updated.get("status").asText()).isEqualTo("DRAFT");
        // 旧 PENDING 建议（缺货行数）被替换为新建议（工单缺口明细）：v1 只有 1 条且为新内容
        List<Map<String, Object>> overwrittenCases = jdbc.queryForList(
                "SELECT input::text FROM app.agent_eval_cases"
                        + " WHERE agent_slug='t10-evolve-agent' AND agent_version=1 AND status='PENDING'");
        assertThat(overwrittenCases).hasSize(1);
        assertThat(overwrittenCases.get(0).get("input").toString()).contains("工单缺口明细");

        // active 版本之上 update → 开新版本 draft（v2）
        AgentSeedFixtures.upsertActiveDefinition(jdbc, AgentDefinition.ofActiveV1(
                "t10-active-agent", "存量 Agent", "d", "你是只读助手。", "v1", "app.agent", true,
                List.of("search_skus")));
        holder.reload();
        ObjectNode draftV2 = baseDraft("t10-active-agent", false, List.of("search_skus", "get_sku"));
        draftV2.putArray("suggested_eval_cases").add("进货价");
        JsonNode v2 = agentWriteCall("update_agent_draft",
                Map.of("draft", draftV2, "idempotency_key", "t10-up-key-003"));

        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.agent_definitions WHERE agent_slug='t10-active-agent' AND version=1",
                        String.class))
                .isEqualTo("active");

        // retired 版本之上 update → 开新版本 draft（最新版非 draft 即开新版本）
        AgentSeedFixtures.upsertActiveDefinition(jdbc, AgentDefinition.ofActiveV1(
                "t10-retired-agent", "退役 Agent", "d", "你是只读助手。", "v1", "app.agent", true,
                List.of("search_skus")));
        holder.reload();
        jdbc.update("UPDATE app.agent_definitions SET status='retired'"
                + " WHERE agent_slug='t10-retired-agent' AND version=1");
        ObjectNode draftRetired = baseDraft("t10-retired-agent", false, List.of("search_skus"));
        draftRetired.putArray("suggested_eval_cases").add("SKU 价格");
        JsonNode vRetired = agentWriteCall("update_agent_draft",
                Map.of("draft", draftRetired, "idempotency_key", "t10-up-key-004"));

        assertThat(vRetired.get("version").asInt()).isEqualTo(2);
        assertThat(vRetired.get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void createDuplicateSlugIsRefused() throws Exception {
        ObjectNode draft = baseDraft("t10-dup-agent", false, List.of("search_skus"));
        agentWriteCall("create_agent_draft", Map.of("draft", draft, "idempotency_key", "t10-dup-key-001"));

        JsonNode error = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-dup-key-002"));

        assertThat(error.get("code").asText()).isEqualTo("AGENT_SLUG_EXISTS");
        assertThat(error.get("http_status").asInt()).isEqualTo(409);
    }

    @Test
    void nonBooleanAllowWriteIsRejected() throws Exception {
        ObjectNode draft = baseDraft("t10-bool-agent", false, List.of("search_skus"));
        draft.put("allow_write", "yes");

        JsonNode error = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-bool-key-001"));

        assertThat(error.get("code").asText()).isEqualTo("INVALID_PARAMETERS");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE agent_slug='t10-bool-agent'", Long.class))
                .isZero();
    }

    @Test
    void metaAgentTargetIsRefusedWithoutSideEffects() throws Exception {
        ObjectNode draft = baseDraft("meta-agent", false, List.of("search_skus"));
        JsonNode error = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-meta-key-001"));

        assertThat(error.get("code").asText()).isEqualTo("AGENT_TARGET_FORBIDDEN");
        assertThat(error.get("http_status").asInt()).isEqualTo(403);
        // 无副作用：无新定义行、无 PENDING 用例
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE agent_slug='meta-agent' AND version > 1",
                        Long.class))
                .isZero();
    }

    @Test
    void gateBlockedDraftIsRefusedWithoutDirtyRows() throws Exception {
        // 白名单含写工具但 allow_write=false → 08 只读不变式阻断（脏草稿不落库）
        ObjectNode draft = baseDraft("t10-gate-agent", false, List.of("reinterpret_submission"));
        JsonNode error = agentWriteCall("create_agent_draft",
                Map.of("draft", draft, "idempotency_key", "t10-gate-key-001"));

        assertThat(error.get("code").asText()).isEqualTo("AGENT_GATE_BLOCKED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE agent_slug='t10-gate-agent'", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_eval_cases WHERE agent_slug='t10-gate-agent'", Long.class))
                .isZero();
    }

    // ------------------------------------------------------------------
    // ③ meta-agent 白名单可实际绑定（T07 bind 校验 allow_write=true）
    // ------------------------------------------------------------------

    @Test
    void metaAgentWhitelistBindsWithAllowWrite() {
        AgentDefinition meta = holder.current().bySlug("meta-agent");
        assertThat(meta).isNotNull();
        assertThat(meta.allowWrite()).isTrue();
        assertThat(meta.toolNames()).containsExactlyInAnyOrder(
                "list_agent_tools", "create_agent_draft", "update_agent_draft");

        AgentToolBinding binding = new AgentToolBindingFactory(registry, identity, mapper)
                .bind(RUN_ID, meta.toolNames(), meta.allowWrite());

        assertThat(binding.specifications())
                .extracting(spec -> spec.name())
                .containsExactlyInAnyOrder("list_agent_tools", "create_agent_draft", "update_agent_draft");
    }

    @Test
    void debugDirectCreate() {
        ObjectNode draft = baseDraft("t10-debug", false, List.of("search_skus"));
        try {
            agentDraftService.createDraft(IDENTITY, "t10-debug-key", draft);
        } catch (RuntimeException ex) {
            throw new AssertionError("createDraft 异常", ex);
        }
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private ObjectNode baseDraft(String slug, boolean allowWrite, List<String> toolWhitelist) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("agent_slug", slug);
        draft.put("name", "测试 Agent");
        draft.put("description", "集成测试创建的草稿");
        draft.put("system_prompt", "你是只读助手。");
        draft.put("prompt_version", "t10-v1");
        draft.put("model_ref", "app.agent");
        draft.put("enabled", true);
        com.fasterxml.jackson.databind.node.ArrayNode whitelist = draft.putArray("tool_whitelist");
        toolWhitelist.forEach(whitelist::add);
        draft.put("allow_write", allowWrite);
        draft.putArray("guard_exemptions");
        return draft;
    }

    /** 经 Agent 面（绑定工厂 allowWrite=true）调用定义写工具，返回工具结果载荷。 */
    private JsonNode agentWriteCall(String toolName, Map<String, Object> args) throws Exception {
        AgentToolBinding binding = new AgentToolBindingFactory(registry, identity, mapper)
                .bind(RUN_ID, List.of(toolName), true);
        AgentToolInvoker invoker = (AgentToolInvoker) binding.tools().values().iterator().next();
        String text = invoker.execute(
                dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                        .name(toolName)
                        .arguments(mapper.writeValueAsString(args))
                        .build(),
                null);
        return mapper.readTree(text);
    }

    /** list_agent_tools 是只读工具，stdio 直接可调。 */
    private JsonNode listAgentToolsViaStdio() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"list_agent_tools\",\"arguments\":{}}}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((request + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity(IDENTITY),
                mapper);
        server.run();
        JsonNode response = mapper.readTree(out.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElseThrow());
        assertThat(response.has("error")).as("协议层不应报错: %s", response).isFalse();
        String text = response.get("result").get("content").get(0).get("text").asText();
        return mapper.readTree(text).path("tools");
    }

    private AuditLog onlyAudit(String operation) {
        List<AuditLog> matches = audits.findAll().stream()
                .filter(audit -> operation.equals(audit.getOperation()))
                .toList();
        assertThat(matches).as("审计记录: %s", operation).singleElement();
        return matches.getFirst();
    }
}
