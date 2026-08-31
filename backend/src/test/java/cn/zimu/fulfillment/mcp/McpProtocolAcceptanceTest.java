package cn.zimu.fulfillment.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentToolBinding;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.agent.AgentToolInvoker;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourcePlatformCheckResult;
import cn.zimu.fulfillment.connector.sync.SourceShipmentSyncService;
import cn.zimu.fulfillment.connector.sync.SourceSyncCheck;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.connector.sync.SourceSyncProjection;
import cn.zimu.fulfillment.connector.sync.SourceSyncStatus;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 第三条主验收接缝：MCP 协议（JSON-RPC 2.0 over stdio）。
 *
 * <p>08 决策：stdio 面一期收紧为只读——tools/list 只暴露只读工具、调用写工具被拒；
 * 写工具业务流（成功/认证失败/版本冲突/幂等重放/审计）经 Agent 面
 * {@link AgentToolInvoker}（同一 McpToolRegistry + 身份 + 审计路径）验证。
 * 覆盖工具发现、读写成功、认证失败、版本冲突、幂等重放、审计与禁止终局工具缺席；
 * 断言配置名与媒体下载凭据不出现在工具描述与响应中。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.mcp.protocol-modules=messages,orders,masterdata,inventory,procurement,orders-read,control,write"
        })
class McpProtocolAcceptanceTest {

    private static final String AGENT = "acceptance-agent";

    private static final String RUN_ID = "run_" + "0".repeat(32);

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

    @MockitoBean
    private SourceShipmentSyncService sourceSync;

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
    void toolDiscoveryExposesOnlyReadOnlyToolsAndNoWriteOrTerminalTools() throws Exception {
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
                "search_product_archive",
                "check_shipment_source_sync",
                "get_import_batch_progress",
                // 查询：真实订单（app.orders，非企微草稿）
                "search_orders",
                "get_order");

        JsonNode internalMetadata = rpc(
                AGENT,
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"list_agent_tools\",\"arguments\":{}}}");
        assertThat(internalMetadata.path("error").path("message").asText())
                .contains("Unknown tool");

        assertThat(registry.findProtocolTool("check_shipment_source_sync")).get()
                .extracting(McpTool::readOnly)
                .isEqualTo(true);

        // 08 决策：stdio 面一期只读——写工具集合按 readOnly 元数据向注册表查询（不手抄清单）
        Set<String> writeTools = registry.protocolTools().stream()
                .filter(tool -> !tool.readOnly())
                .map(McpTool::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(writeTools)
                .as("注册表必须能判定写工具集合（默认禁写不变式）")
                .isNotEmpty();
        assertThat(names).doesNotContainAnyElementsOf(writeTools);

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
    void writeToolCallIsRejectedOnReadOnlyStdioWithoutSideEffects() throws Exception {
        JsonNode response = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"reinterpret_submission\",\"arguments\":{}}}");
        JsonNode unknown = rpc(AGENT, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"not_a_real_tool\",\"arguments\":{}}}");
        assertThat(response.has("error")).as("写工具调用必须以 JSON-RPC 错误拒绝: %s", response).isTrue();
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(response.get("error").get("message").asText())
                .isEqualTo("Unknown tool: reinterpret_submission")
                .doesNotContain("read-only", "write", "restricted");
        assertThat(unknown.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(unknown.get("error").get("message").asText())
                .isEqualTo("Unknown tool: not_a_real_tool");
        // 拒绝发生在工具执行之前：不得留下任何写审计/副作用
        assertThat(audits.findAll().stream()
                        .filter(audit -> "mcp.reinterpret_submission".equals(audit.getOperation()))
                        .count())
                .isZero();
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
        for (McpTool tool : registry.protocolTools()) {
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
    void sourceSyncProtocolResponseIsAdvisoryAndDoesNotExposeReceiverOrTrackingPii() throws Exception {
        String receiverName = "协议测试收货人-小沈";
        String receiverPhone = "13700002222";
        String receiverAddress = "上海市浦东新区测试街 66 号 502";
        String trackingNumber = "TRACK-SECRET-13700002222";
        SourceSyncFacts internal = new SourceSyncFacts(
                501L,
                601L,
                SourceChannel.JUFUBAO,
                "SOURCE-ORDER-PII",
                "SOURCE-LINE-PII",
                receiverName,
                receiverPhone,
                receiverAddress,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.TEN,
                "FULLY_FULFILLED",
                "SF",
                "顺丰速运",
                "PLATFORM-CARRIER-PII",
                trackingNumber);
        SourcePlatformCheckResult platform = new SourcePlatformCheckResult(
                true,
                "RAW-" + receiverPhone,
                "平台消息含 " + receiverAddress,
                "STATE-" + receiverName,
                false,
                SourcePlatformCheckResult.AddressStatus.CLEAR,
                receiverName,
                receiverPhone,
                receiverAddress,
                BigDecimal.ONE,
                true);
        when(sourceSync.check(eq(501L), any(), eq(AuditActorType.AGENT)))
                .thenReturn(new SourceSyncCheck(
                        501L,
                        true,
                        "safe-check-hash",
                        "safe-artifact-hash",
                        internal,
                        platform,
                        List.of(),
                        new SourceSyncProjection(
                                SourceSyncStatus.PENDING,
                                1,
                                2L,
                                "RAW-" + receiverPhone,
                                "历史错误含 " + receiverAddress,
                                OffsetDateTime.parse("2026-08-24T12:34:56+08:00"))));

        JsonNode result = callResult(
                AGENT,
                "check_shipment_source_sync",
                Map.of("shipment_id", "501"));

        assertThat(result.get("advisory").asBoolean()).isTrue();
        assertThat(result.get("write_allowed").asBoolean()).isFalse();
        assertThat(result.get("receiver_comparison").get("all_match").asBoolean()).isTrue();
        assertThat(result.get("quantity_comparison").get("matches").asBoolean()).isTrue();
        assertThat(result.get("shipment_summary").get("tracking_present").asBoolean()).isTrue();
        assertThat(result.get("sync_projection").get("status").asText()).isEqualTo("PENDING");
        assertThat(result.toString())
                .doesNotContain(receiverName)
                .doesNotContain(receiverPhone)
                .doesNotContain(receiverAddress)
                .doesNotContain(trackingNumber)
                .doesNotContain("SOURCE-ORDER-PII")
                .doesNotContain("SOURCE-LINE-PII")
                .doesNotContain("PLATFORM-CARRIER-PII")
                .doesNotContain("receiver_name")
                .doesNotContain("receiver_phone")
                .doesNotContain("receiver_address")
                .doesNotContain("tracking_number")
                .doesNotContain("platform_state")
                .doesNotContain("business_code")
                .doesNotContain("last_error_message");
    }

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

        // 08 决策：写工具业务流走 Agent 面（stdio 只读）；同一注册表/身份/审计路径
        JsonNode result = agentWriteCall(AGENT, "submit_order_draft_suggestion",
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

        JsonNode result = agentWriteCall(AGENT, "submit_supplementary_material",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", String.valueOf(revision),
                        "idempotency_key", "mcp-material-key-001",
                        "receiver", Map.of(
                                "name", "补充收货人",
                                "phone", "13900000000",
                                "address", "补充地址"),
                        "settlement_method", "COD",
                        "settlement_time", "2026-08-31T16:00:00Z"));
        assertThat(result.get("receiver_name").asText()).isEqualTo("补充收货人");
        assertThat(result.get("receiver_phone").asText()).isEqualTo("13900000000");
        assertThat(result.get("settlement_method").asText()).isEqualTo("COD");
        assertThat(result.get("settlement_time").asText()).isEqualTo("2026-08-31T16:00:00Z");
    }

    @Test
    void versionConflictReturnsStableErrorAndAuditsFailure() throws Exception {
        long submissionId = submitAndInterpret("MCP-WRITE-003", customerOrder());
        JsonNode drafts = callResult(AGENT, "list_order_drafts",
                Map.of("submission_id", String.valueOf(submissionId)));
        String draftId = drafts.get("items").get(0).get("id").asText();
        long staleRevision = currentRevision(draftId) + 1;

        JsonNode error = agentWriteCall(AGENT, "submit_order_draft_suggestion",
                Map.of(
                        "draft_id", draftId,
                        "expected_revision", String.valueOf(staleRevision),
                        "idempotency_key", "mcp-conflict-key-001",
                        "items", List.of(Map.of("line_no", 1, "quantity", "2"))));
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
        JsonNode first = agentWriteCall(AGENT, "submit_order_draft_suggestion", args);
        JsonNode second = agentWriteCall(AGENT, "submit_order_draft_suggestion", args);
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
        JsonNode error = agentWriteCall("", "reinterpret_submission",
                Map.of("submission_id", String.valueOf(submissionId), "idempotency_key", "mcp-auth-key-001"));
        assertThat(error.get("code").asText()).isEqualTo("MCP_AUTH_REQUIRED");
        assertThat(error.get("http_status").asInt()).isEqualTo(401);
        assertThat(error.toString()).doesNotContain("MCP_AGENT_IDENTITY");
        assertThat(error.toString()).doesNotContain("acceptance-agent");
    }

    @Test
    void reinterpretSubmissionEnqueuesNewTaskWithAgentIdentity() throws Exception {
        long submissionId = submitAndInterpret("MCP-RE-001", customerOrder());
        InterpreterControl.queue(nonBusiness());

        JsonNode result = agentWriteCall(AGENT, "reinterpret_submission",
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

        JsonNode first = agentWriteCall(AGENT, "submit_review_request",
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
        JsonNode second = agentWriteCall(AGENT, "submit_review_request",
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
        JsonNode error = agentWriteCall(AGENT, "submit_review_request",
                Map.of("submission_id", String.valueOf(submissionId), "idempotency_key", "mcp-review-key-003"));
        assertThat(error.get("code").asText()).isEqualTo("SUBMISSION_HAS_OPEN_DRAFTS");
    }

    // ------------------------------------------------------------------
    // 特权 stdio 面（issue-181 收口）：内部工具（hermes 等）经显式双门拿 Agent 全工具面
    // ------------------------------------------------------------------

    /** 特权面 tools/list = Agent 全工具面（含写与内部专用），数量与注册表逐一对得上。 */
    @Test
    void privilegedStdioListsTheFullAgentSurfaceIncludingWriteAndInternalTools() throws Exception {
        JsonNode response = privilegedRpc(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        List<String> names = new java.util.ArrayList<>();
        response.get("result").get("tools").forEach(tool -> names.add(tool.get("name").asText()));

        assertThat(names).hasSize(registry.agentTools().size());
        assertThat(names).contains("submit_jd_outbound");
        assertThat(names).contains("kehuzx_search_customers");
    }

    /** 特权面写工具可被调用（错误来自工具校验层，而不是「Unknown tool」投影）。 */
    @Test
    void privilegedStdioResolvesWriteToolsInsteadOfProjectingUnknown() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 7);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", "submit_jd_outbound");
        params.putObject("arguments");
        JsonNode response = privilegedRpc(mapper.writeValueAsString(request));

        String rendered = response.toString();
        assertThat(rendered).doesNotContain("Unknown tool");
    }

    /** 非特权构造保持既有边界：写工具在 stdio 面仍按「不存在」投影（回归钉）。 */
    @Test
    void nonPrivilegedStdioStillProjectsWriteToolsAsUnknown() throws Exception {
        JsonNode response = rpc(AGENT,
                "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"submit_jd_outbound\",\"arguments\":{}}}");
        assertThat(response.get("error").get("message").asText()).contains("Unknown tool");
    }

    private JsonNode privilegedRpc(String requestLine) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpServer server = new McpServer(
                new ByteArrayInputStream((requestLine + "\n").getBytes(StandardCharsets.UTF_8)),
                out,
                registry,
                new McpAgentIdentity("hermes-privileged-test"),
                mapper,
                true);
        server.run();
        List<String> lines = out.toString(StandardCharsets.UTF_8)
                .lines().filter(line -> !line.isBlank()).toList();
        assertThat(lines).hasSize(1);
        return mapper.readTree(lines.getFirst());
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

    /**
     * 08 决策后写工具业务流经 Agent 面执行（stdio 只读）：按真实生产路径
     * （绑定工厂 allowWrite=true → 注入白名单的执行器）调用一次写工具，
     * 返回工具结果载荷（成功为业务 JSON，失败为 code/http_status/message 信封）。
     */
    private JsonNode agentWriteCall(String identity, String toolName, Map<String, Object> args) throws Exception {
        AgentToolBinding binding = new AgentToolBindingFactory(registry, new McpAgentIdentity(identity), mapper)
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
