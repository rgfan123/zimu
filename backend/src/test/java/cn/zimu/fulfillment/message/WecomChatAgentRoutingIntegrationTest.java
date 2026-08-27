package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.wecom.WecomChatReplyPolicyService;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundGateway;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** #178：会话绑定先走只读 Agent，所有失败均回落既有消息解释/收单流水线。 */
@Testcontainers
@SpringBootTest
class WecomChatAgentRoutingIntegrationTest {

    private static final Set<String> READ_ONLY_MODULES = Set.of("masterdata", "inventory");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void disableBackgroundWorkers(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.wecom-chat-agent-worker.enabled", () -> "false");
        registry.add("app.wecom-tracking-file-worker.enabled", () -> "false");
        registry.add("app.wecom-export-worker.enabled", () -> "false");
        registry.add("app.wecom-reminder.enabled", () -> "false");
        registry.add("app.wecom-notification.enabled", () -> "false");
        registry.add("app.wecom-order-draft-card.enabled", () -> "false");
        registry.add("app.agent-worker.enabled", () -> "false");
    }

    @TestConfiguration
    static class InterpreterConfig {

        @Bean
        @Primary
        MessageInterpreter issue178Interpreter() {
            return input -> InterpreterResult.next.get();
        }
    }

    private static final class InterpreterResult {
        private static final AtomicReference<InterpretationResult> next = new AtomicReference<>();
    }

    @Autowired private MessageSubmissionService submissions;
    @Autowired private AsyncTaskStore tasks;
    @Autowired private InterpretationService interpretationService;
    @Autowired private WecomChatAgentRoutingService routingService;
    @Autowired private WecomChatReplyPolicyService replyPolicies;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuditLogService audits;

    @MockitoBean private AgentRuntimeFacade agents;
    @MockitoBean private WecomOutboundGateway outbound;

    @BeforeEach
    void setUp() {
        InterpreterResult.next.set(new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "ambiguous"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null));
    }

    @Test
    void unboundConversationKeepsOriginalTaskAndIntakeBytesUnchanged() {
        String chatId = unique("unbound-chat");
        long submissionId = submissions.submit(command(chatId, "这个怎么处理", "text"));

        String queued = jdbc.queryForObject(
                """
                SELECT concat_ws('|', task_type, payload_ref, status, attempts, max_attempts, idempotency_key)
                FROM app.async_tasks WHERE payload_ref = ?
                """,
                String.class,
                "submission:" + submissionId);
        assertThat(queued).isEqualTo(
                "INTERPRET_MESSAGE|submission:" + submissionId + "|PENDING|0|3|interpret:" + submissionId);

        interpretationWorker().poll();

        String intake = jdbc.queryForObject(
                """
                SELECT concat_ws('|', case_type, status, responsible_team, reason_code, detail::text)
                FROM app.review_cases WHERE message_submission_id = ?
                """,
                String.class,
                submissionId);
        assertThat(intake).isEqualTo(
                "WECOM_INTAKE|OPEN|ORDER_OPS|WECOM_NEED_REVIEW|"
                        + "{\"model\": \"test-model\", \"intent\": \"NEED_REVIEW\", "
                        + "\"provider\": \"test-provider\", \"prompt_version\": \"test-prompt-v1\"}");
        verify(agents, never()).invokeReadOnlyModules(any(), any(), any(), any());
    }

    @Test
    void boundQuestionRunsReadOnlyAgentAndDeliversAnswerWithoutIntakeCase() {
        String chatId = bind(unique("bound-query"));
        AgentRunResult answer = AgentRunResult.success(
                        mapper.createObjectNode()
                                .put("answer", "M5霜降肥牛卷：SKU-M5，当前可用库存 18 件")
                                .put("requires_human", false),
                        "test-provider",
                        "test-model",
                        "test-v1")
                .withRunMetadata("run_" + "1".repeat(32), 12);
        when(agents.invokeReadOnlyModules(
                        eq("data-query-agent"),
                        eq("帮我查商品 M5霜降肥牛卷"),
                        any(AgentRunContext.class),
                        eq(READ_ONLY_MODULES)))
                .thenReturn(answer);
        when(outbound.send(any())).thenReturn(success("answer-req"));

        long submissionId = submissions.submit(command(chatId, "帮我查商品 M5霜降肥牛卷", "text"));
        agentWorker().poll();

        ArgumentCaptor<WecomOutboundMessage> sent = ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(outbound).send(sent.capture());
        assertThat(sent.getValue().chatId()).isEqualTo(chatId);
        assertThat(sent.getValue().type()).isEqualTo(WecomOutboundMessage.Type.MARKDOWN);
        assertThat(sent.getValue().content()).isEqualTo("M5霜降肥牛卷：SKU-M5，当前可用库存 18 件");
        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isZero();
        assertThat(count("app.message_interpretations", "submission_id", submissionId)).isZero();
        assertThat(taskStatus(submissionId, MessageSubmissionService.WECOM_CHAT_AGENT_TASK_TYPE))
                .isEqualTo("SUCCEEDED");
        assertThat(lastRouteCode(submissionId)).isEqualTo("WECOM_CHAT_AGENT_ANSWER_SENT");
    }

    @Test
    void boundSingleChatUsesDirectoryUserIdForPolicyAndOutboundTarget() {
        String userId = unique("single-user");
        bind(userId);
        when(agents.invokeReadOnlyModules(any(), any(), any(), eq(READ_ONLY_MODULES)))
                .thenReturn(AgentRunResult.success(
                                mapper.createObjectNode().put("answer", "单聊查询结果").put("requires_human", false),
                                "test-provider",
                                "test-model",
                                "test-v1")
                        .withRunMetadata("run_" + "5".repeat(32), 4));
        when(outbound.send(any())).thenReturn(success("single-answer-req"));

        long submissionId = submissions.submit(command(
                "single:" + userId, "帮我查 M5", "text", "single"));
        agentWorker().poll();

        ArgumentCaptor<WecomOutboundMessage> sent = ArgumentCaptor.forClass(WecomOutboundMessage.class);
        verify(outbound).send(sent.capture());
        assertThat(sent.getValue().chatId()).isEqualTo(userId);
        assertThat(taskStatus(submissionId, MessageSubmissionService.WECOM_CHAT_AGENT_TASK_TYPE))
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void boundConversationFileKeepsOriginalDedicatedFileTask() {
        String chatId = bind(unique("bound-file"));

        long submissionId = submissions.submit(command(chatId, "", "file"));

        assertThat(taskStatus(submissionId, MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE))
                .isEqualTo("PENDING");
        assertThat(countTasks(submissionId, MessageSubmissionService.WECOM_CHAT_AGENT_TASK_TYPE))
                .isZero();
        verify(agents, never()).invokeReadOnlyModules(any(), any(), any(), any());
    }

    @Test
    void agentBusinessDocumentDecisionFallsBackToOriginalIntakePipeline() {
        String chatId = bind(unique("bound-order"));
        when(agents.invokeReadOnlyModules(any(), any(), any(), eq(READ_ONLY_MODULES)))
                .thenReturn(AgentRunResult.success(
                                mapper.createObjectNode()
                                        .put("answer", "")
                                        .put("requires_human", true)
                                        .put("business_document", true),
                                "test-provider",
                                "test-model",
                                "test-v1")
                        .withRunMetadata("run_" + "2".repeat(32), 8));

        long submissionId = submissions.submit(command(chatId, "下单 M5霜降肥牛卷 10箱", "text"));
        agentWorker().poll();
        interpretationWorker().poll();

        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isEqualTo(1);
        assertThat(taskStatus(submissionId, MessageSubmissionService.INTERPRET_TASK_TYPE))
                .isEqualTo("SUCCEEDED");
        assertThat(lastRouteCode(submissionId)).isEqualTo("WECOM_CHAT_AGENT_FALLBACK_INTAKE");
        verify(outbound, never()).send(any());
    }

    @Test
    void clearOrderIntentIsCaughtByExistingIntentContractBeforeQuestionAgent() {
        String chatId = bind(unique("bound-clear-order"));
        InterpreterResult.next.set(new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of("customer", "测试客户"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null));

        long submissionId = submissions.submit(command(chatId, "下单 M5霜降肥牛卷 10箱", "text"));
        agentWorker().poll();
        interpretationWorker().poll();

        verify(agents, never()).invokeReadOnlyModules(any(), any(), any(), any());
        assertThat(count("app.order_drafts", "submission_id", submissionId)).isEqualTo(1);
        assertThat(count("app.review_cases", "message_submission_id", submissionId)
                        + countReviewCasesForDraft(submissionId))
                .isGreaterThanOrEqualTo(1);
        assertThat(lastRouteReason(submissionId)).isEqualTo("BUSINESS_INTENT_CUSTOMER_ORDER");
    }

    @Test
    void agentTimeoutFallsBackAndLeavesStableAuditReason() {
        String chatId = bind(unique("bound-timeout"));
        when(agents.invokeReadOnlyModules(any(), any(), any(), eq(READ_ONLY_MODULES)))
                .thenReturn(AgentRunResult.failClosed(AgentFailureCode.AGENT_EXECUTION_BUDGET_EXHAUSTED)
                        .withRunMetadata("run_" + "3".repeat(32), 30_000));

        long submissionId = submissions.submit(command(chatId, "帮我查 M5", "text"));
        agentWorker().poll();
        interpretationWorker().poll();

        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isEqualTo(1);
        assertThat(lastRouteReason(submissionId)).isEqualTo("AGENT_EXECUTION_BUDGET_EXHAUSTED");
    }

    @Test
    void agentExceptionFallsBackWithoutSwallowingMessage() {
        String chatId = bind(unique("bound-exception"));
        when(agents.invokeReadOnlyModules(any(), any(), any(), eq(READ_ONLY_MODULES)))
                .thenThrow(new IllegalStateException("provider secret must not escape"));

        long submissionId = submissions.submit(command(chatId, "帮我查 M5", "text"));
        agentWorker().poll();
        interpretationWorker().poll();

        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isEqualTo(1);
        assertThat(lastRouteReason(submissionId)).isEqualTo("AGENT_RUNTIME_EXCEPTION");
        String response = jdbc.queryForObject(
                """
                SELECT response_payload::text FROM app.audit_logs
                WHERE operation = 'wecom.chat-agent.route'
                  AND request_payload ->> 'submission_id' = ?
                ORDER BY id DESC LIMIT 1
                """,
                String.class,
                String.valueOf(submissionId));
        assertThat(response).doesNotContain("provider secret");
    }

    @Test
    void outboundFailureAlsoFallsBackSoSuccessfulAgentCannotSilenceMessage() {
        String chatId = bind(unique("bound-outbound-failure"));
        when(agents.invokeReadOnlyModules(any(), any(), any(), eq(READ_ONLY_MODULES)))
                .thenReturn(AgentRunResult.success(
                                mapper.createObjectNode().put("answer", "查询结果").put("requires_human", false),
                                "test-provider",
                                "test-model",
                                "test-v1")
                        .withRunMetadata("run_" + "4".repeat(32), 5));
        when(outbound.send(any())).thenReturn(new WecomSendResult(
                WecomSendStatus.FAILED, "answer-failed", null, 93000, "rejected", false));

        long submissionId = submissions.submit(command(chatId, "帮我查 M5", "text"));
        agentWorker().poll();
        interpretationWorker().poll();

        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isEqualTo(1);
        assertThat(lastRouteReason(submissionId)).isEqualTo("WECOM_REPLY_FAILED");
    }

    @Test
    void unfinishedSendingFenceBecomesUnknownAndIsNeverBlindlyResent() {
        String chatId = bind(unique("bound-unknown"));
        long submissionId = submissions.submit(command(chatId, "帮我查 M5", "text"));
        Long taskId = jdbc.queryForObject(
                "SELECT id FROM app.async_tasks WHERE payload_ref = ? AND task_type = ?",
                Long.class,
                "submission:" + submissionId,
                MessageSubmissionService.WECOM_CHAT_AGENT_TASK_TYPE);
        audits.record(new AuditLogService.AuditCommand()
                .requestId("run_" + "6".repeat(32))
                .traceId("run_" + "6".repeat(32))
                .operator("wecom-chat-agent")
                .actorType(AuditActorType.SYSTEM)
                .service("wecom-chat-agent")
                .operation("wecom.chat-agent.route")
                .requestPayload(Map.of(
                        "task_id", String.valueOf(taskId),
                        "submission_id", String.valueOf(submissionId),
                        "chat_id", chatId,
                        "agent_slug", "data-query-agent"))
                .responsePayload(Map.of("status", "SENDING"))
                .businessCode("WECOM_CHAT_AGENT_REPLY_SENDING"));

        agentWorker().poll();
        interpretationWorker().poll();

        verify(agents, never()).invokeReadOnlyModules(any(), any(), any(), any());
        verify(outbound, never()).send(any());
        assertThat(count("app.review_cases", "message_submission_id", submissionId)).isEqualTo(1);
        assertThat(lastRouteCode(submissionId)).isEqualTo("WECOM_CHAT_AGENT_REPLY_UNKNOWN");
        assertThat(lastRouteReason(submissionId)).isEqualTo("WECOM_REPLY_UNKNOWN");
    }

    private WecomChatAgentWorker agentWorker() {
        return new WecomChatAgentWorker(tasks, routingService, true, 330, 0);
    }

    private InterpretationWorker interpretationWorker() {
        return new InterpretationWorker(tasks, interpretationService, true, 30, 0);
    }

    private String bind(String chatId) {
        replyPolicies.upsertProfile(chatId, null, null, "data-query-agent", null, "test");
        return chatId;
    }

    private ChannelMessageCommand command(String chatId, String content, String messageType) {
        return command(chatId, content, messageType, "group");
    }

    private ChannelMessageCommand command(
            String chatId, String content, String messageType, String chatType) {
        return new ChannelMessageCommand(
                "corp-test",
                "connection-test",
                "bot-test",
                unique("msg"),
                chatId,
                chatType,
                "user-test",
                messageType,
                content,
                null,
                null,
                mapper.createObjectNode());
    }

    private String taskStatus(long submissionId, String taskType) {
        return jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE payload_ref = ? AND task_type = ?",
                String.class,
                "submission:" + submissionId,
                taskType);
    }

    private long countTasks(long submissionId, String taskType) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE payload_ref = ? AND task_type = ?",
                Long.class,
                "submission:" + submissionId,
                taskType);
        return value == null ? 0 : value;
    }

    private long count(String table, String column, long id) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Long.class, id);
        return value == null ? 0 : value;
    }

    private long countReviewCasesForDraft(long submissionId) {
        Long value = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases rc
                JOIN app.order_drafts od ON od.id = rc.order_draft_id
                WHERE od.submission_id = ?
                """,
                Long.class,
                submissionId);
        return value == null ? 0 : value;
    }

    private String lastRouteCode(long submissionId) {
        return jdbc.queryForObject(
                """
                SELECT business_code FROM app.audit_logs
                WHERE operation = 'wecom.chat-agent.route'
                  AND request_payload ->> 'submission_id' = ?
                ORDER BY id DESC LIMIT 1
                """,
                String.class,
                String.valueOf(submissionId));
    }

    private String lastRouteReason(long submissionId) {
        return jdbc.queryForObject(
                """
                SELECT response_payload ->> 'reason' FROM app.audit_logs
                WHERE operation = 'wecom.chat-agent.route'
                  AND request_payload ->> 'submission_id' = ?
                ORDER BY id DESC LIMIT 1
                """,
                String.class,
                String.valueOf(submissionId));
    }

    private static WecomSendResult success(String requestId) {
        return new WecomSendResult(
                WecomSendStatus.SUCCESS, requestId, Instant.parse("2026-08-27T00:00:00Z"), null, null, false);
    }

    private static String unique(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
