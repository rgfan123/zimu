package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * wecom-message-intake 12 主验收：后台运行可见性（积压 / 重试中 / 最终失败 / 媒体失败）与
 * NON_BUSINESS 保留清理（到期清理、保留门禁、重复清理、审计摘要、秘密不泄露）。
 *
 * <p>通过真实 PostgreSQL + 管理 API 验证；模型只在 {@link MessageInterpreter} 边界替换，媒体下载
 * 使用本地 HttpServer 替身。最终失败与清理路径绝不向原企微群补发消息（无任何出站连接依赖）。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessagePipelineOperationsApiTest {

    private static final String AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final String SECRET_SENTINEL = "OPS_SECRET_SENTINEL_MUST_NOT_LEAK";
    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void operationsConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
        registry.add("app.message-retention.non-business-days", () -> "30");
    }

    @MockitoBean
    private MessageInterpreter interpreter;

    @Autowired private TestRestTemplate http;
    @Autowired private MessageSubmissionService submissionService;
    @Autowired private InterpretationService interpretationService;
    @Autowired private AsyncTaskStore taskStore;
    @Autowired private MessageRetentionProperties retentionProperties;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private HttpServer mediaServer;
    private String mediaBaseUrl;
    private final AtomicInteger downloadHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM app.review_cases");
        jdbc.update("DELETE FROM app.message_interpretations");
        jdbc.update("DELETE FROM app.message_media");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        jdbc.update("DELETE FROM app.async_tasks");
        // audit_logs 是 append-only 表，不做清理；相关断言按增量比较
        cleanMediaDir();
        retentionProperties.setNonBusinessDays(30);
        mediaServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mediaServer.createContext(
                "/media/ok",
                exchange -> {
                    downloadHits.incrementAndGet();
                    respond(exchange, 200, "image/png", encrypt("重启后仍可读的原图内容".getBytes(StandardCharsets.UTF_8)));
                });
        mediaServer.createContext(
                "/media/missing",
                exchange -> {
                    downloadHits.incrementAndGet();
                    respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                });
        mediaServer.start();
        mediaBaseUrl = "http://127.0.0.1:" + mediaServer.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (mediaServer != null) {
            mediaServer.stop(0);
        }
    }

    // ------------------------------------------------------------------
    // 运行可见性：积压 / 重试中 / 最终失败 / 媒体失败
    // ------------------------------------------------------------------

    @Test
    void summaryAndTaskFiltersTrackBacklogRetryingAndFinalFailures() throws Exception {
        when(interpreter.interpret(any()))
                .thenReturn(nonBusiness("成功闲聊"))
                .thenThrow(new RuntimeException(SECRET_SENTINEL));

        long successId = submitText("OPS-001", "闲聊成功");
        makeTasksDue();
        pollWorker();
        assertThat(submissionDetail(successId).get("current_intent")).isEqualTo("NON_BUSINESS");

        long failureId = submitText("OPS-002", "这条消息解释会失败");
        makeTasksDue();
        pollWorker();
        Map<String, Object> afterFirstTry = summary();
        assertThat(number(afterFirstTry.get("retrying"))).isEqualTo(1);
        assertThat(number(afterFirstTry.get("backlog"))).isGreaterThanOrEqualTo(1);

        makeTasksDue();
        pollWorker();
        assertThat(number(summary().get("retrying"))).isEqualTo(1);

        makeTasksDue();
        pollWorker();
        Map<String, Object> afterExhaustion = summary();
        assertThat(number(afterExhaustion.get("retrying"))).isZero();
        assertThat(number(afterExhaustion.get("final_failures"))).isGreaterThanOrEqualTo(1);

        expireFinalizingLeases();
        pollWorker();
        Map<String, Object> terminal = summary();
        assertThat(number(terminal.get("final_failures"))).isGreaterThanOrEqualTo(1);
        assertThat(terminal.get("retention_days")).isEqualTo(30);
        assertThat(terminal.get("retention_enabled")).isEqualTo(Boolean.TRUE);

        Map<String, Object> finalFailures = tasks("scope=FINAL_FAILURES");
        assertThat(number(finalFailures.get("total_elements"))).isGreaterThanOrEqualTo(1);
        assertThat(tasks("status=FAILED")).isNotEmpty();
        Map<String, Object> failed = (Map<String, Object>) ((List<?>) finalFailures.get("items")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> "FAILED".equals(item.get("status")))
                .findFirst()
                .orElseThrow();
        // 最终失败只收敛为稳定错误码，绝不携带模型原始异常文本
        assertThat(failed.get("last_error")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(objectMapper.writeValueAsString(finalFailures)).doesNotContain(SECRET_SENTINEL);

        // 最终失败在后台可见（提交 FAILED + 唯一开放复核事项），不向原群补发任何消息
        Map<String, Object> detail = submissionDetail(failureId);
        assertThat(detail.get("status")).isEqualTo("FAILED");
        assertThat(detail.get("current_intent")).isEqualTo("NEED_REVIEW");
        Long openCases = jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE message_submission_id = ? AND status = 'OPEN'",
                Long.class,
                failureId);
        assertThat(openCases).isEqualTo(1);
    }

    @Test
    void mediaFailuresAppearInSummaryAndFilterWithoutCredentials() throws Exception {
        when(interpreter.interpret(any())).thenReturn(nonBusiness("媒体失败"));

        submitImage("OPS-MEDIA-001", mediaBaseUrl + "/media/missing", AES_KEY);
        makeTasksDue();
        pollWorker();
        makeTasksDue();
        pollWorker();
        makeTasksDue();
        pollWorker();
        expireFinalizingLeases();
        pollWorker();

        // 证据缺失时模型绝不被调用
        verify(interpreter, never()).interpret(any());

        Map<String, Object> snapshot = summary();
        assertThat(number(snapshot.get("media_failures"))).isEqualTo(1);
        Map<String, Object> failures = mediaFailures(null);
        assertThat(number(failures.get("total_elements"))).isEqualTo(1);
        Map<String, Object> item = (Map<String, Object>) ((List<?>) failures.get("items")).getFirst();
        assertThat(item.get("download_status")).isEqualTo("FAILED");
        assertThat(item.get("attempts")).isEqualTo(3);

        String json = objectMapper.writeValueAsString(Map.of("summary", snapshot, "media", failures));
        assertThat(json)
                .doesNotContain(AES_KEY, "aeskey", "source_url", "failure_reason", "content_ref", SECRET_SENTINEL);
        assertThat(number(mediaFailures("PENDING").get("total_elements"))).isZero();
    }

    // ------------------------------------------------------------------
    // 保留清理：到期清理 / 保留门禁 / 重复清理 / 审计摘要
    // ------------------------------------------------------------------

    @Test
    void cleanupRemovesExpiredNonBusinessMessagesAndTheirMediaWithAuditSummary() throws Exception {
        when(interpreter.interpret(any())).thenReturn(nonBusiness("过期闲聊"));

        long textSubmission = submitText("OPS-CLEAN-001", "三十天前的闲聊");
        makeTasksDue();
        pollWorker();
        long imageSubmission = submitImage("OPS-CLEAN-002", mediaBaseUrl + "/media/ok", AES_KEY);
        makeTasksDue();
        pollWorker();

        String contentRef = jdbc.queryForObject(
                "SELECT content_ref FROM app.message_media WHERE submission_id = ?",
                String.class,
                imageSubmission);
        assertThat(contentRef).isNotBlank();
        assertThat(Files.exists(Path.of(contentRef))).as("媒体文件必须已持久化").isTrue();

        backdate(textSubmission);
        backdate(imageSubmission);
        long textMessageId = messageIdOf(textSubmission);
        long imageMessageId = messageIdOf(imageSubmission);
        long auditsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE business_code = 'RETENTION_CLEANUP_DONE'",
                Long.class);

        Map<String, Object> report = postCleanup();
        assertThat(report.get("enabled")).isEqualTo(Boolean.TRUE);
        assertThat(report.get("retention_days")).isEqualTo(30);
        assertThat(report.get("expired_candidates")).isEqualTo(2);
        assertThat(report.get("protected_count")).isEqualTo(0);
        assertThat(report.get("submissions_removed")).isEqualTo(2);
        assertThat(report.get("messages_removed")).isEqualTo(2);
        assertThat(report.get("media_rows_removed")).isEqualTo(1);
        assertThat(report.get("files_removed")).isEqualTo(1);

        assertThat(countSubmissions(textSubmission, imageSubmission)).isZero();
        assertThat(count("channel_messages", "id", textMessageId, imageMessageId)).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.message_media WHERE submission_id IN (?, ?)",
                        Long.class,
                        textSubmission,
                        imageSubmission))
                .isZero();
        assertThat(Files.exists(Path.of(contentRef))).as("孤儿媒体文件必须被清理").isFalse();

        Map<String, Object> audit = latestRetentionAudit();
        assertThat(audit.get("actor_type")).isEqualTo("HUMAN");
        assertThat(audit.get("operator")).isEqualTo("tester");
        assertThat(((Map<?, ?>) audit.get("request_payload")).get("retention_days")).isEqualTo(30);
        assertThat(((Map<?, ?>) audit.get("response_payload")).get("submissions_removed")).isEqualTo(2);

        // 重复清理：幂等，第二次零删除但仍记录审计摘要
        Map<String, Object> second = postCleanup();
        assertThat(second.get("expired_candidates")).isEqualTo(0);
        assertThat(second.get("submissions_removed")).isEqualTo(0);
        assertThat(second.get("messages_removed")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.audit_logs WHERE business_code = 'RETENTION_CLEANUP_DONE'",
                        Long.class))
                .isEqualTo(auditsBefore + 2);
    }

    @Test
    void cleanupKeepsFreshMessagesAndProtectsOrderAndTrackingEvidence() throws Exception {
        when(interpreter.interpret(any()))
                .thenReturn(customerOrder())
                .thenReturn(nonBusiness("改判为非业务"));

        long submissionId = submitText("OPS-DRAFT-001", "客户订单后被改判非业务");
        makeTasksDue();
        pollWorker();
        Map<String, Object> draft = onlyRow("order_drafts", submissionId);
        assertThat(draft.get("status")).isEqualTo("OPEN");
        Long openCaseBefore = jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE order_draft_id = ? AND status = 'OPEN'",
                Long.class,
                draft.get("id"));
        assertThat(openCaseBefore).isEqualTo(1);

        submissionService.reinterpret(
                submissionId, new CommandContext("req-retention-draft", "trace-retention-draft", "tester"));
        makeTasksDue();
        pollWorker();
        assertThat(submissionDetail(submissionId).get("current_intent")).isEqualTo("NON_BUSINESS");
        assertThat(onlyRow("order_drafts", submissionId).get("status")).isEqualTo("REJECTED");
        Long dismissedCases = jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases WHERE order_draft_id = ? AND status = 'DISMISSED'",
                Long.class,
                draft.get("id"));
        assertThat(dismissedCases).isEqualTo(1);

        backdate(submissionId);
        Map<String, Object> report = postCleanup();
        // 订单草稿、ReviewCase（含终态）与重新解释审计引用全部触发保留门禁
        assertThat(report.get("expired_candidates")).isEqualTo(1);
        assertThat(report.get("protected_count")).isEqualTo(1);
        assertThat(report.get("submissions_removed")).isEqualTo(0);
        assertThat(report.get("messages_removed")).isEqualTo(0);

        assertThat(countMessages(submissionId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.order_drafts WHERE submission_id = ?",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
        Long auditRefs = jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_payload ->> 'submission_id' = ?",
                Long.class,
                String.valueOf(submissionId));
        assertThat(auditRefs).isEqualTo(1);
    }

    @Test
    void cleanupProtectsOpenReviewCasesAndBusinessAuditReferences() throws Exception {
        when(interpreter.interpret(any())).thenReturn(nonBusiness("被引用证据"));

        long caseSubmission = submitText("OPS-GATE-CASE-001", "存在遗留开放事项");
        makeTasksDue();
        pollWorker();
        backdate(caseSubmission);
        jdbc.update(
                """
                INSERT INTO app.review_cases (
                    case_no, case_type, status, responsible_team, reason_code, message_submission_id, detail
                ) VALUES (?, 'WECOM_INTAKE', 'OPEN', 'ORDER_OPS', 'WECOM_NEED_REVIEW', ?, '{}'::jsonb)
                """,
                "RC-GATE-" + caseSubmission,
                caseSubmission);

        long auditSubmission = submitText("OPS-GATE-AUDIT-001", "存在业务审计引用");
        makeTasksDue();
        pollWorker();
        backdate(auditSubmission);
        jdbc.update(
                """
                INSERT INTO app.audit_logs (
                    data_scope, operator, actor_type, service, operation, request_payload, http_status, business_code
                ) VALUES ('BUSINESS', 'tester', 'SYSTEM', 'message-submission',
                          'message_submission.reinterpret', CAST(? AS jsonb), 200,
                          'MESSAGE_REINTERPRETATION_QUEUED')
                """,
                objectMapper.writeValueAsString(Map.of("submission_id", String.valueOf(auditSubmission))));

        Map<String, Object> report = postCleanup();
        assertThat(report.get("expired_candidates")).isEqualTo(2);
        assertThat(report.get("protected_count")).isEqualTo(2);
        assertThat(report.get("submissions_removed")).isEqualTo(0);
        assertThat(countMessages(caseSubmission, auditSubmission)).isEqualTo(2);
    }

    @Test
    void retentionPeriodIsConfigurableAndDisabledPeriodSkipsCleanup() throws Exception {
        when(interpreter.interpret(any())).thenReturn(nonBusiness("期限配置测试"));

        long shortSubmission = submitText("OPS-RETENTION-001", "可缩短期限");
        makeTasksDue();
        pollWorker();
        backdate(shortSubmission, 25);

        retentionProperties.setNonBusinessDays(20);
        Map<String, Object> shortened = postCleanup();
        assertThat(shortened.get("retention_days")).isEqualTo(20);
        assertThat(shortened.get("submissions_removed")).isEqualTo(1);

        long disabledSubmission = submitText("OPS-RETENTION-002", "禁用期不清理");
        makeTasksDue();
        pollWorker();
        backdate(disabledSubmission, 25);

        retentionProperties.setNonBusinessDays(0);
        Map<String, Object> disabled = postCleanup();
        assertThat(disabled.get("enabled")).isEqualTo(Boolean.FALSE);
        assertThat(disabled.get("submissions_removed")).isEqualTo(0);
        assertThat(countMessages(disabledSubmission)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // 权限与投影：X-Operator 强制、列表最小必要、秘密不泄露
    // ------------------------------------------------------------------

    @Test
    void operationsEndpointsRequireOperatorAndExposeMinimalProjections() throws Exception {
        ResponseEntity<Map> anonymous = http.exchange(
                "/api/v1/admin/message-pipeline/summary",
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Map.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<Map> badScope = exchangeGet("/api/v1/admin/message-pipeline/tasks?scope=BOGUS");
        assertThat(badScope.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> badMedia = exchangeGet("/api/v1/admin/message-pipeline/media-failures?status=BOGUS");
        assertThat(badMedia.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        when(interpreter.interpret(any())).thenReturn(nonBusiness("投影最小化"));
        submitText("OPS-SECRET-001", "带秘密哨兵的闲聊");
        makeTasksDue();
        pollWorker();

        Map<String, Object> summaryBody = exchangeGet("/api/v1/admin/message-pipeline/summary").getBody();
        Map<String, Object> tasksBody = exchangeGet("/api/v1/admin/message-pipeline/tasks").getBody();
        Map<String, Object> mediaBody = exchangeGet("/api/v1/admin/message-pipeline/media-failures").getBody();
        String json = objectMapper.writeValueAsString(
                Map.of("summary", summaryBody, "tasks", tasksBody, "media", mediaBody));
        assertThat(json)
                .doesNotContain(
                        AES_KEY,
                        "aeskey",
                        "raw_payload",
                        "lease_owner",
                        "payload_ref",
                        "source_url",
                        "content_ref",
                        SECRET_SENTINEL);
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private Map<String, Object> summary() {
        return exchangeGet("/api/v1/admin/message-pipeline/summary").getBody();
    }

    private Map<String, Object> tasks(String query) {
        return exchangeGet("/api/v1/admin/message-pipeline/tasks?" + query + "&size=200").getBody();
    }

    private Map<String, Object> mediaFailures(String status) {
        String url = status == null
                ? "/api/v1/admin/message-pipeline/media-failures?size=200"
                : "/api/v1/admin/message-pipeline/media-failures?status=" + status + "&size=200";
        return exchangeGet(url).getBody();
    }

    private Map<String, Object> postCleanup() {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/admin/message-pipeline/cleanup",
                HttpMethod.POST,
                new HttpEntity<>(adminHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> exchangeGet(String url) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "tester");
        return headers;
    }

    private long submitText(String messageId, String content) {
        String frame = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"req-ops\"},\"body\":{"
                + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"user-ops\"},\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"}}}";
        return submissionService.submit(new ChannelMessageCommand(
                "bot-1",
                "wecom-long-connection",
                "bot-1",
                messageId,
                "single:user-ops",
                "single",
                "user-ops",
                "text",
                content,
                null,
                null,
                json(frame)));
    }

    private long submitImage(String messageId, String url, String aesKey) {
        String frame = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"req-media\"},\"body\":{"
                + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"user-ops\"},\"msgtype\":\"image\","
                + "\"image\":{\"url\":\"" + url + "\",\"aeskey\":\"" + aesKey + "\"}}}";
        return submissionService.submit(new ChannelMessageCommand(
                "bot-1",
                "wecom-long-connection",
                "bot-1",
                messageId,
                "single:user-ops",
                "single",
                "user-ops",
                "image",
                "",
                null,
                null,
                json(frame)));
    }

    private Map<String, Object> submissionDetail(long submissionId) {
        ResponseEntity<Map> response = exchangeGet("/api/v1/message-submissions/" + submissionId);
        return response.getBody();
    }

    private Map<String, Object> latestRetentionAudit() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT actor_type, operator, request_payload::text AS request_payload, "
                        + "response_payload::text AS response_payload "
                        + "FROM app.audit_logs WHERE business_code = 'RETENTION_CLEANUP_DONE' "
                        + "ORDER BY id DESC LIMIT 1");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = new LinkedHashMap<>(rows.getFirst());
        row.put("request_payload", parseJsonObject((String) row.get("request_payload")));
        row.put("response_payload", parseJsonObject((String) row.get("response_payload")));
        return row;
    }

    private Map<?, ?> parseJsonObject(String raw) {
        try {
            return objectMapper.readValue(raw, Map.class);
        } catch (IOException ex) {
            throw new IllegalStateException("invalid audit payload", ex);
        }
    }

    private Map<String, Object> onlyRow(String table, long submissionId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, status FROM app." + table + " WHERE submission_id = ?", submissionId);
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private long countSubmissions(long... submissionIds) {
        return count("message_submissions", "id", submissionIds);
    }

    private long countMessages(long... submissionIds) {
        long[] messageIds = new long[submissionIds.length];
        for (int index = 0; index < submissionIds.length; index++) {
            messageIds[index] = messageIdOf(submissionIds[index]);
        }
        return count("channel_messages", "id", messageIds);
    }

    private long messageIdOf(long submissionId) {
        Long messageId = jdbc.queryForObject(
                "SELECT source_message_id FROM app.message_submissions WHERE id = ?",
                Long.class,
                submissionId);
        assertThat(messageId).as("submission %s 必须存在", submissionId).isNotNull();
        return messageId;
    }

    private long count(String table, String column, long... ids) {
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < ids.length; index++) {
            if (index > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }
        Long[] boxed = new Long[ids.length];
        for (int index = 0; index < ids.length; index++) {
            boxed[index] = ids[index];
        }
        return jdbc.queryForObject(
                "SELECT count(*) FROM app." + table + " WHERE " + column + " IN (" + placeholders + ")",
                Long.class,
                (Object[]) boxed);
    }

    private void backdate(long submissionId) {
        backdate(submissionId, 45);
    }

    private void backdate(long submissionId, int days) {
        jdbc.update(
                "UPDATE app.channel_messages SET received_at = CURRENT_TIMESTAMP - (? || ' days')::interval "
                        + "WHERE id = (SELECT source_message_id FROM app.message_submissions WHERE id = ?)",
                days,
                submissionId);
        jdbc.update(
                "UPDATE app.message_submissions SET created_at = CURRENT_TIMESTAMP - (? || ' days')::interval, "
                        + "updated_at = CURRENT_TIMESTAMP - (? || ' days')::interval WHERE id = ?",
                days,
                days,
                submissionId);
        jdbc.update(
                "UPDATE app.message_interpretations SET created_at = CURRENT_TIMESTAMP - (? || ' days')::interval "
                        + "WHERE submission_id = ?",
                days,
                submissionId);
    }

    /** Worker 手动领取一轮；退避 60 秒避免单轮自动排空，便于断言中间状态。 */
    private void pollWorker() {
        new InterpretationWorker(taskStore, interpretationService, true, 30, 60).poll();
    }

    private void makeTasksDue() {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at = CURRENT_TIMESTAMP - interval '1 second' "
                        + "WHERE status = 'PENDING'");
    }

    private void expireFinalizingLeases() {
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' "
                        + "WHERE status = 'FINALIZING'");
    }

    private static InterpretationResult nonBusiness(String note) {
        return new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("note", note),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private static InterpretationResult customerOrder() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("customer", "保留门禁测试客户");
        output.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800138000",
                "address", "保留门禁测试地址"));
        output.put("settlement_method", "MONTHLY");
        output.put("items", List.of(Map.of(
                "product", "保留门禁测试商品",
                "quantity", "1")));
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                output,
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private static JsonNode json(String raw) {
        try {
            return new ObjectMapper().readTree(raw);
        } catch (IOException ex) {
            throw new IllegalStateException("invalid test frame", ex);
        }
    }

    /** 按长连接规范构造媒体密文：AES-256-CBC、IV=aeskey 前 16 字节、PKCS#7 填充至 32 字节倍数。 */
    private static byte[] encrypt(byte[] plain) {
        try {
            byte[] key = Base64.getDecoder().decode(AES_KEY);
            byte[] padded = pkcs7Pad(plain);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            return cipher.doFinal(padded);
        } catch (Exception ex) {
            throw new IllegalStateException("媒体密文样本构造失败", ex);
        }
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int block = 32;
        int padding = block - (data.length % block);
        byte[] padded = new byte[data.length + padding];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int index = data.length; index < padded.length; index++) {
            padded[index] = (byte) padding;
        }
        return padded;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-ops-media-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void cleanMediaDir() throws IOException {
        try (var stream = Files.list(MEDIA_DIR)) {
            for (Path file : stream.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
