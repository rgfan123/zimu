package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.InterpretationService;
import cn.zimu.fulfillment.message.InterpretationWorker;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageMediaStore;
import cn.zimu.fulfillment.message.MessageSubmissionRepository;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 第三条主验收接缝：MCP 协议（JSON-RPC 2.0 over stdio）。
 *
 * <p>覆盖工具发现、读写成功、认证失败、版本冲突、幂等重放、审计与禁止终局工具缺席；
 * 断言配置名与媒体下载凭据不出现在工具描述与响应中。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class McpProtocolAcceptanceTest {

    private static final String AGENT = "acceptance-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        cn.zimu.fulfillment.message.MessageInterpreter mcpProtocolInterpreter() {
            return ignored -> InterpreterControl.next();
        }
    }

    static final class InterpreterControl {

        private static final ConcurrentLinkedQueue<InterpretationResult> RESULTS = new ConcurrentLinkedQueue<>();

        static void queue(InterpretationResult result) {
            RESULTS.add(result);
        }

        static InterpretationResult next() {
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("mcp acceptance interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            RESULTS.clear();
        }
    }

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private McpToolRegistry registry;

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @Autowired
    private MessageMediaStore mediaStore;

    @Autowired
    private MessageSubmissionRepository submissions;

    @Autowired
    private AuditLogRepository audits;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void resetInterpreter() {
        InterpreterControl.reset();
        // 同类内多个测试方法共享同一个 PostgreSQL 容器，逐个清空消息链路表保证断言确定性。
        jdbc.execute("""
                TRUNCATE app.idempotency_registry, app.audit_logs, app.review_cases,
                         app.order_draft_lines, app.order_drafts, app.provider_tracking_drafts,
                         app.message_interpretations, app.async_tasks, app.message_media,
                         app.message_submissions, app.channel_messages
                RESTART IDENTITY CASCADE
                """);
    }

    // ------------------------------------------------------------------
    // 工具发现与协议握手
    // ------------------------------------------------------------------

    @Test
    void initializeReturnsProtocolCapabilities() throws Exception {
        JsonNode response = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        assertThat(response.get("id").asInt()).isEqualTo(1);
        JsonNode result = response.get("result");
        assertThat(result.get("protocolVersion").asText()).isNotBlank();
        assertThat(result.get("capabilities").get("tools").get("listChanged").asBoolean()).isFalse();
        assertThat(result.get("serverInfo").get("name").asText()).isEqualTo("fulfillment-hub-mcp");
    }

    @Test
    void toolDiscoveryListsOnlyAllowedToolsAndNoTerminalTools() throws Exception {
        JsonNode response = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        List<String> names = new ArrayList<>();
        response.get("result").get("tools").forEach(tool -> names.add(tool.get("name").asText()));
        assertThat(names).containsExactlyInAnyOrder(
                // 查询：消息提交/媒体元数据/解释历史
                "list_channel_messages",
                "get_channel_message",
                "get_message_submission",
                "list_interpretations",
                "list_message_media",
                // 查询：订单/运单草稿
                "list_order_drafts",
                "get_order_draft",
                "list_tracking_drafts",
                "get_tracking_draft",
                // 查询：候选与复核事项
                "get_order_draft_candidates",
                "get_tracking_draft_candidates",
                "list_review_cases",
                "get_review_case",
                // 查询：采购/库存/SKU 价格/主数据
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
                "list_fulfillment_providers",
                // 写：仅非终局 + 业务决策票放行的终局写（confirm_order_draft / submit_jd_outbound）
                "reinterpret_submission",
                "submit_order_draft_suggestion",
                "submit_supplementary_material",
                "submit_review_request",
                "confirm_order_draft",
                "submit_jd_outbound");

        Set<String> forbidden = Set.of(
                "confirm_order",
                "confirm_tracking_draft",
                "confirm_tracking_drafts",
                "batch_confirm_tracking_drafts",
                "batch_confirm_tracking",
                "create_customer",
                "bind_channel_identity",
                "close_review_case",
                "dismiss_review_case",
                "reject_order_draft",
                "resolve_review_case",
                "resolve_customer",
                "resolve_sku",
                "revise_order",
                "modify_order",
                "cancel_order",
                "shipment_jd_outbound_submit");
        assertThat(names).doesNotContainAnyElementsOf(forbidden);
    }

    @Test
    void toolDescriptionsAndDiscoveryDoNotLeakConfiguration() throws Exception {
        JsonNode response = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}");
        String fullDiscoveryText = response.toString();
        assertThat(fullDiscoveryText)
                .doesNotContain("MCP_AGENT_IDENTITY")
                .doesNotContain("MCP_ENABLED")
                .doesNotContain("APP_")
                .doesNotContain("SECRET")
                .doesNotContain("TOKEN")
                .doesNotContain("PASSWORD");
        // 工具描述与输入 Schema 同样不得携带配置/凭据
        for (McpTool tool : registry.all()) {
            assertThat(tool.description() + " " + tool.inputSchema())
                    .doesNotContain("MCP_AGENT_IDENTITY")
                    .doesNotContain("MCP_ENABLED")
                    .doesNotContain("SECRET")
                    .doesNotContain("TOKEN")
                    .doesNotContain("PASSWORD");
        }
    }

    // ------------------------------------------------------------------
    // 只读工具
    // ------------------------------------------------------------------

    @Test
    void readToolsReturnSeededMessagesSubmissionsAndReviewCases() throws Exception {
        long submissionId = submitAndInterpret("MCP-READ-001", nonBusiness());
        long needReviewId = submitAndInterpret("MCP-READ-002", needReview());

        JsonNode submission = callResult(AGENT, "get_message_submission",
                Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(submission.get("id").asText()).isEqualTo(String.valueOf(submissionId));
        assertThat(submission.get("status").asText()).isEqualTo("INTERPRETED");
        assertThat(submission.get("interpretations")).isNotEmpty();

        JsonNode interpretations = callResult(AGENT, "list_interpretations",
                Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(interpretations.get(0).get("intent").asText()).isEqualTo("NON_BUSINESS");

        JsonNode messages = callResult(AGENT, "list_channel_messages", Map.of());
        assertThat(messages.get("total_elements").asLong()).isEqualTo(2);

        JsonNode cases = callResult(AGENT, "list_review_cases",
                Map.of("status", "OPEN", "reason_code", "WECOM_NEED_REVIEW"));
        assertThat(cases.get("items")).hasSize(1);
        String caseId = cases.get("items").get(0).get("id").asText();

        JsonNode reviewCase = callResult(AGENT, "get_review_case", Map.of("case_id", caseId));
        assertThat(reviewCase.get("subject_type").asText()).isEqualTo("MESSAGE_SUBMISSION");
        assertThat(reviewCase.get("reason_code").asText()).isEqualTo("WECOM_NEED_REVIEW");
        assertThat(reviewCase.get("status").asText()).isEqualTo("OPEN");
        assertThat(reviewCase.get("case_no").asText()).startsWith("RC-WECOM-");
        assertThat(reviewCase.get("subject_id").asText()).isEqualTo(String.valueOf(needReviewId));
    }

    @Test
    void mediaMetadataExcludesCredentialsAndStorageRefs() throws Exception {
        long submissionId = submitAndInterpret("MCP-MEDIA-001", nonBusiness());
        long sourceMessageId = submissions.findById(submissionId).orElseThrow().getSourceMessageId();
        mediaStore.ensurePending(sourceMessageId, submissionId, "media-1", "image", "https://example.invalid/secret-url");

        JsonNode media = callResult(AGENT, "list_message_media",
                Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(media).hasSize(1);
        JsonNode item = media.get(0);
        assertThat(item.get("download_status").asText()).isEqualTo("PENDING");
        assertThat(item.get("channel_media_id").asText()).isEqualTo("media-1");
        assertThat(item.has("content_ref")).isFalse();
        assertThat(item.has("source_url")).isFalse();
        assertThat(item.has("decrypt_info")).isFalse();
        assertThat(item.toString()).doesNotContain("secret-url");
        assertThat(item.toString()).doesNotContain("aeskey");
    }

    @Test
    void draftAndCandidateReadToolsReturnSeededOrderDraft() throws Exception {
        long submissionId = submitAndInterpret("MCP-DRAFT-001", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(drafts.get("items")).hasSize(1);
        String draftId = drafts.get("items").get(0).get("id").asText();

        JsonNode draft = callResult(AGENT, "get_order_draft", Map.of("draft_id", draftId));
        assertThat(draft.get("status").asText()).isEqualTo("OPEN");
        assertThat(draft.get("review_case_id")).isNotNull();

        JsonNode candidates = callResult(AGENT, "get_order_draft_candidates", Map.of("draft_id", draftId));
        assertThat(candidates.get("customer_candidates")).isNotNull();
        assertThat(candidates.get("lines").get(0).get("sku_candidates")).isNotNull();
        assertThat(candidates.get("missing_fields")).isNotNull();
    }

    // ------------------------------------------------------------------
    // 写工具：成功 / 认证失败 / 版本冲突 / 幂等重放 / 审计
    // ------------------------------------------------------------------

    @Test
    void supplementSuggestionUpdatesDraftAndAuditsAgentIdentity() throws Exception {
        long submissionId = submitAndInterpret("MCP-WRITE-001", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        String draftId = drafts.get("items").get(0).get("id").asText();
        long revision = currentRevision(draftId);

        JsonNode result = callResult(AGENT, "submit_order_draft_suggestion",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", String.valueOf(revision),
                        "idempotency_key", "mcp-suggestion-key-001",
                        "items", List.of(Map.of("line_no", 1, "quantity", "2"))));
        assertThat(result.get("status").asText()).isEqualTo("OPEN");
        assertThat(result.get("lines").get(0).get("quantity").asText()).isEqualTo("2");

        // MCP 层 AGENT 审计
        AuditLog mcpAudit = onlyAudit("mcp.submit_order_draft_suggestion");
        assertThat(mcpAudit.getOperator()).isEqualTo(AGENT);
        assertThat(mcpAudit.getActorType().name()).isEqualTo("AGENT");
        assertThat(mcpAudit.getBusinessCode()).isEqualTo("ORDER_DRAFT_SUPPLEMENTED");
        // 业务层审计同样携带 Agent 身份
        AuditLog serviceAudit = onlyAudit("order_draft.supplement");
        assertThat(serviceAudit.getOperator()).isEqualTo(AGENT);
    }
    @Test
    void supplementaryMaterialUpdatesReceiverAndSettlement() throws Exception {
        long submissionId = submitAndInterpret("MCP-WRITE-002", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        String draftId = drafts.get("items").get(0).get("id").asText();
        long revision = currentRevision(draftId);

        JsonNode result = callResult(AGENT, "submit_supplementary_material",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", String.valueOf(revision),
                        "idempotency_key", "mcp-material-key-001",
                        "receiver", Map.of(
                                "name", "补充收货人",
                                "phone", "13900000000",
                                "address", "补充地址"),
                        "settlement_method", "COD"));
        assertThat(result.get("receiver_name").asText()).isEqualTo("补充收货人");
        assertThat(result.get("receiver_phone").asText()).isEqualTo("13900000000");
        assertThat(result.get("settlement_method").asText()).isEqualTo("COD");
    }

    @Test
    void versionConflictReturnsStableErrorAndAuditsFailure() throws Exception {
        long submissionId = submitAndInterpret("MCP-WRITE-003", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        String draftId = drafts.get("items").get(0).get("id").asText();
        long staleRevision = currentRevision(draftId) + 1;

        JsonNode response = call(AGENT, "submit_order_draft_suggestion",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", String.valueOf(staleRevision),
                        "idempotency_key", "mcp-conflict-key-001",
                        "items", List.of(Map.of("line_no", 1, "quantity", "2"))));
        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        JsonNode error = mapper.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertThat(error.get("code").asText()).isEqualTo("VERSION_CONFLICT");
        assertThat(error.get("http_status").asInt()).isEqualTo(409);
        assertThat(error.toString()).doesNotContain(AGENT);

        AuditLog failureAudit = onlyAudit("mcp.submit_order_draft_suggestion");
        assertThat(failureAudit.getOperator()).isEqualTo(AGENT);
        assertThat(failureAudit.getActorType().name()).isEqualTo("AGENT");
        assertThat(failureAudit.getBusinessCode()).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void idempotentReplayReturnsFirstResultSnapshot() throws Exception {
        long submissionId = submitAndInterpret("MCP-WRITE-004", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        String draftId = drafts.get("items").get(0).get("id").asText();

        Map<String, Object> args = Map.of(
                "draft_id", draftId,
                "expected_revision", String.valueOf(currentRevision(draftId)),
                "idempotency_key", "mcp-replay-key-001",
                "items", List.of(Map.of("line_no", 1, "quantity", "3")));
        JsonNode first = callResult(AGENT, "submit_order_draft_suggestion", args);
        JsonNode second = callResult(AGENT, "submit_order_draft_suggestion", args);
        assertThat(second).as("重放必须返回与首次执行语义相同的结果").isEqualTo(first);
        assertThat(second.get("lines").get(0).get("quantity").asText()).isEqualTo("3");

        List<AuditLog> auditsForOp = audits.findAll().stream()
                .filter(audit -> "mcp.submit_order_draft_suggestion".equals(audit.getOperation()))
                .toList();
        assertThat(auditsForOp)
                .anySatisfy(audit -> assertThat(audit.getBusinessCode()).isEqualTo("ORDER_DRAFT_SUPPLEMENTED"))
                .anySatisfy(audit -> assertThat(audit.getBusinessCode()).isEqualTo("IDEMPOTENT_REPLAY"));
    }

    @Test
    void writeWithoutAgentIdentityFailsWithAuthError() throws Exception {
        long submissionId = submitAndInterpret("MCP-AUTH-001", nonBusiness());
        JsonNode response = call("", "reinterpret_submission",
                Map.of("submission_id", String.valueOf(submissionId), "idempotency_key", "mcp-auth-key-001"));
        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        JsonNode error = mapper.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertThat(error.get("code").asText()).isEqualTo("MCP_AUTH_REQUIRED");
        assertThat(error.get("http_status").asInt()).isEqualTo(401);
        assertThat(error.toString()).doesNotContain("MCP_AGENT_IDENTITY");
        assertThat(error.toString()).doesNotContain("acceptance-agent");
    }

    @Test
    void reinterpretSubmissionEnqueuesNewTaskWithAgentIdentity() throws Exception {
        long submissionId = submitAndInterpret("MCP-RE-001", customerOrder());
        InterpreterControl.queue(nonBusiness());

        JsonNode result = callResult(AGENT, "reinterpret_submission",
                Map.of("submission_id", String.valueOf(submissionId), "idempotency_key", "mcp-reinterpret-key-001"));
        assertThat(result.get("id").asText()).isEqualTo(String.valueOf(submissionId));
        assertThat(result.get("status").asText()).isEqualTo("RECEIVED");

        JsonNode auditsForOp = mapper.valueToTree(audits.findAll().stream()
                .filter(audit -> "mcp.reinterpret_submission".equals(audit.getOperation()))
                .toList());
        assertThat(auditsForOp).hasSize(1);
        assertThat(auditsForOp.get(0).get("operator").asText()).isEqualTo(AGENT);
        assertThat(auditsForOp.get(0).get("actor_type").asText()).isEqualTo("AGENT");

        // 处理新解释后，旧的订单草稿被退役，提交转为非业务
        new InterpretationWorker(taskStore, interpretationService, true, 30, 0).poll();
        JsonNode after = callResult(AGENT, "get_message_submission", Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(after.get("status").asText()).isEqualTo("INTERPRETED");
        assertThat(after.get("current_intent").asText()).isEqualTo("NON_BUSINESS");
        JsonNode retired = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        assertThat(retired.get("items").get(0).get("status").asText()).isEqualTo("REJECTED");
    }

    // ------------------------------------------------------------------
    // 显式提交人工复核
    // ------------------------------------------------------------------

    @Test
    void submitReviewRequestCreatesAndReusesOpenNeedReviewCase() throws Exception {
        long submissionId = submitAndInterpret("MCP-REV-001", nonBusiness());

        JsonNode first = callResult(AGENT, "submit_review_request",
                Map.of("submission_id", String.valueOf(submissionId),
                        "idempotency_key", "mcp-review-key-001",
                        "note", "agent 要求人工复核"));
        assertThat(first.get("already_open").asBoolean()).isFalse();
        String caseId = first.get("review_case_id").asText();

        JsonNode reviewCase = callResult(AGENT, "get_review_case", Map.of("case_id", caseId));
        assertThat(reviewCase.get("status").asText()).isEqualTo("OPEN");
        assertThat(reviewCase.get("reason_code").asText()).isEqualTo("WECOM_NEED_REVIEW");
        assertThat(reviewCase.get("subject_type").asText()).isEqualTo("MESSAGE_SUBMISSION");
        assertThat(reviewCase.get("subject_id").asText()).isEqualTo(String.valueOf(submissionId));

        // 再次提交复用既有事项，不制造事项轮换
        JsonNode second = callResult(AGENT, "submit_review_request",
                Map.of("submission_id", String.valueOf(submissionId),
                        "idempotency_key", "mcp-review-key-002",
                        "note", "再次确认"));
        assertThat(second.get("already_open").asBoolean()).isTrue();
        assertThat(second.get("review_case_id").asText()).isEqualTo(caseId);

        // 两次成功调用各留一条 AGENT 审计（不同幂等键，均为新执行）
        List<AuditLog> reviewAudits = audits.findAll().stream()
                .filter(audit -> "mcp.submit_review_request".equals(audit.getOperation()))
                .toList();
        assertThat(reviewAudits).hasSize(2);
        assertThat(reviewAudits)
                .allSatisfy(audit -> {
                    assertThat(audit.getOperator()).isEqualTo(AGENT);
                    assertThat(audit.getActorType().name()).isEqualTo("AGENT");
                })
                .anySatisfy(audit -> assertThat(audit.getBusinessCode()).isEqualTo("REVIEW_REQUEST_OPENED"));
    }

    @Test
    void submitReviewRequestRejectsSubmissionsWithOpenDrafts() throws Exception {
        long submissionId = submitAndInterpret("MCP-REV-002", customerOrder());
        JsonNode response = call(AGENT, "submit_review_request",
                Map.of("submission_id", String.valueOf(submissionId), "idempotency_key", "mcp-review-key-003"));
        assertThat(response.get("result").get("isError").asBoolean()).isTrue();
        JsonNode error = mapper.readTree(response.get("result").get("content").get(0).get("text").asText());
        assertThat(error.get("code").asText()).isEqualTo("SUBMISSION_HAS_OPEN_DRAFTS");
    }

    // ------------------------------------------------------------------
    // 协议级 JSON-RPC 助手
    // ------------------------------------------------------------------

    private JsonNode rpc(String identity, String requestLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((requestLine + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity(identity),
                mapper);
        server.run();
        String output = out.toString(StandardCharsets.UTF_8);
        List<String> lines = output.lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).as("服务端必须且只能输出一条响应帧").hasSize(1);
        return mapper.readTree(lines.getFirst());
    }

    private JsonNode call(String identity, String toolName, Map<String, Object> args) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(args));
        JsonNode response = rpc(identity, request.toString());
        assertThat(response.has("error")).as("协议层不应报错: %s", response).isFalse();
        return response;
    }

    private JsonNode callResult(String identity, String toolName, Map<String, Object> args) throws Exception {
        JsonNode response = call(identity, toolName, args);
        JsonNode result = response.get("result");
        assertThat(result.get("isError").asBoolean())
                .as("工具应成功: %s -> %s", toolName, result)
                .isFalse();
        return mapper.readTree(result.get("content").get(0).get("text").asText());
    }

    private AuditLog onlyAudit(String operation) {
        List<AuditLog> matches = audits.findAll().stream()
                .filter(audit -> operation.equals(audit.getOperation()))
                .toList();
        assertThat(matches).as("审计记录: %s", operation).singleElement();
        return matches.getFirst();
    }

    private long currentRevision(String draftId) throws Exception {
        return callResult(AGENT, "get_order_draft", Map.of("draft_id", draftId))
                .get("revision")
                .asLong();
    }

    private long submitAndInterpret(String messageId, InterpretationResult result) {
        InterpreterControl.queue(result);
        long submissionId = submissionService.submit(new ChannelMessageCommand(
                "corp-mcp-acceptance",
                "connection-mcp-acceptance",
                "bot-mcp-acceptance",
                messageId,
                "chat-mcp-acceptance",
                "group",
                "sender-mcp-acceptance",
                "text",
                "mcp protocol acceptance message",
                null,
                null,
                mapper.createObjectNode().put("message_id", messageId)));
        new InterpretationWorker(taskStore, interpretationService, true, 30, 0).poll();
        return submissionId;
    }

    private static InterpretationResult nonBusiness() {
        return new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("kind", "chat"),
                "test-provider",
                "test-model",
                "prompt-v1",
                null);
    }

    private static InterpretationResult needReview() {
        return new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "AMBIGUOUS"),
                "test-provider",
                "test-model",
                "prompt-v1",
                null);
    }

    private static InterpretationResult customerOrder() {
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of(
                        "customer", "mcp acceptance customer",
                        "receiver", Map.of(
                                "name", "customer",
                                "phone", "13800000000",
                                "address", "mcp acceptance address"),
                        "settlement_method", "MONTHLY",
                        "items", List.of(Map.of(
                                "product", "test product",
                                "unit", "piece",
                                "quantity", "1"))),
                "test-provider",
                "test-model",
                "prompt-v1",
                null);
    }
}
