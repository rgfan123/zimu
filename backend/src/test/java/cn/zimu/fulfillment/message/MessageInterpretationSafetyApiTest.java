package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMessageDispatchHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Public safety contract for historical interpretation data and order-reference routing. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageInterpretationSafetyApiTest {

    private static final String CONNECTION = "safety-relay";
    private static final String CORP_ID = "ww-safety-corp";
    private static final String BOT_ID = "AIBOT-SAFETY";
    private static final String ALLOWED_GROUP = "CHAT-SAFETY";
    private static final String RAW_SENTINEL = "raw provider exception secret=history-token";
    private static final String SAFE_PROVIDER_ALIAS = "test-provider";
    private static final String SAFE_MODEL_ALIAS = "test-model";
    private static final String SAFE_PROMPT_ALIAS = "test-prompt-v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.poll-ms", () -> "50");
        registry.add("app.message-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.lease-seconds", () -> "10");
        registry.add(
                "app.message-interpreter.public-metadata-aliases[0].provider",
                () -> SAFE_PROVIDER_ALIAS);
        registry.add(
                "app.message-interpreter.public-metadata-aliases[0].model",
                () -> SAFE_MODEL_ALIAS);
        registry.add(
                "app.message-interpreter.public-metadata-aliases[0].prompt-version",
                () -> SAFE_PROMPT_ALIAS);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter safetyInterpreter() {
            return input -> {
                InterpretationResult result = RESULTS.poll();
                if (result == null) {
                    throw new IllegalStateException("safety interpreter queue exhausted");
                }
                return result;
            };
        }
    }

    private static final ArrayBlockingQueue<InterpretationResult> RESULTS = new ArrayBlockingQueue<>(32);

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WecomMessageDispatchHandler wecomDispatchHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearInterpreter() {
        RESULTS.clear();
    }

    @Test
    void historicalRawErrorsAndUnknownReviewFieldsNeverLeavePublicApis() throws Exception {
        long messageId = jdbc.queryForObject(
                """
                INSERT INTO app.channel_messages
                    (corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                     sender_user_id, message_type, content, raw_payload)
                VALUES (?, ?, ?, ?, ?, 'group', 'USER-HISTORY', 'text', '历史消息', '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                CORP_ID,
                CONNECTION,
                BOT_ID,
                "MSG-SAFETY-HISTORY",
                ALLOWED_GROUP);
        long submissionId = jdbc.queryForObject(
                """
                INSERT INTO app.message_submissions (submission_no, source_message_id, status)
                VALUES ('SUB-SAFETY-HISTORY', ?, 'FAILED')
                RETURNING id
                """,
                Long.class,
                messageId);
        jdbc.update(
                """
                INSERT INTO app.message_interpretations
                    (submission_id, version, provider, model, prompt_version, intent, structured_output, error)
                VALUES (?, 1, 'provider-a', 'model-a', 'prompt-v1', 'NEED_REVIEW', '{}'::jsonb, ?)
                """,
                submissionId,
                RAW_SENTINEL);
        long taskId = jdbc.queryForObject(
                """
                INSERT INTO app.async_tasks
                    (task_type, payload_ref, status, attempts, max_attempts, last_error, idempotency_key)
                VALUES ('INTERPRET_MESSAGE', ?, 'FAILED', 3, 3, ?, ?)
                RETURNING id
                """,
                Long.class,
                "submission:" + submissionId,
                RAW_SENTINEL,
                "safety-history-task-" + submissionId);
        long caseId = jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, responsible_team, reason_code, message_submission_id, detail)
                VALUES (?, 'WECOM_INTAKE', 'ORDER_OPS', 'WECOM_NEED_REVIEW', ?, ?::jsonb)
                RETURNING id
                """,
                Long.class,
                "RC-SAFETY-HISTORY-" + submissionId,
                submissionId,
                objectMapper.writeValueAsString(Map.of(
                        "intent", "NEED_REVIEW",
                        "provider", "provider-a",
                        "model", "model-a",
                        "prompt_version", "prompt-v1",
                        "error_code", RAW_SENTINEL,
                        "error", RAW_SENTINEL,
                        "order_no", "请取消订单 ORD-2026-001",
                        "unknown_secret", "history-token")));

        Map<String, Object> submission = get("/api/v1/message-submissions/" + submissionId);
        Map<?, ?> interpretation = (Map<?, ?>) ((List<?>) submission.get("interpretations")).getFirst();
        Map<?, ?> latestTask = (Map<?, ?>) submission.get("latest_task");
        Map<String, Object> taskPage = get("/api/v1/message-submissions/tasks?size=200");
        Map<?, ?> listedTask = ((List<?>) taskPage.get("items")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> String.valueOf(taskId).equals(String.valueOf(item.get("id"))))
                .findFirst()
                .orElseThrow();
        Map<String, Object> reviewCase = get("/api/v1/review-cases/" + caseId);
        Map<?, ?> reviewDetail = (Map<?, ?>) reviewCase.get("detail");

        assertThat(submission.get("latest_error")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(interpretation.get("error")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(interpretation.get("provider")).isEqualTo("none");
        assertThat(interpretation.get("model")).isEqualTo("none");
        assertThat(interpretation.get("prompt_version")).isEqualTo("none");
        assertThat(latestTask.get("last_error")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(listedTask.get("last_error")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(reviewDetail.get("error_code")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(reviewDetail.get("provider")).isEqualTo("none");
        assertThat(reviewDetail.get("model")).isEqualTo("none");
        assertThat(reviewDetail.get("prompt_version")).isEqualTo("none");
        assertThat(reviewDetail.containsKey("error")).isFalse();
        assertThat(reviewDetail.containsKey("unknown_secret")).isFalse();
        assertThat(reviewDetail.containsKey("order_no")).isFalse();
        assertThat(objectMapper.writeValueAsString(Map.of(
                        "submission", submission, "tasks", taskPage, "review_case", reviewCase)))
                .doesNotContain(RAW_SENTINEL, "history-token", "请取消订单 ORD-2026-001");
    }

    @Test
    void orderChangeAndCancellationRequireAValueLevelOrderReference() throws Exception {
        List<String> invalidReferences = List.of(
                "请取消订单 ORD-2026-001",
                "13800138000",
                "ORD-2026-001\nsecret",
                "ORD 2026 001");
        int sequence = 1;
        for (String invalid : invalidReferences) {
            RESULTS.add(result(MessageIntent.ORDER_CHANGE, invalid));
            Map<String, Object> submission = submitAndAwait("MSG-SAFETY-INVALID-" + sequence, sequence);
            Map<String, Object> reviewCase = openSubmissionCase(String.valueOf(submission.get("id")));
            Map<?, ?> detail = (Map<?, ?>) reviewCase.get("detail");

            assertThat(reviewCase.get("reason_code")).isEqualTo("WECOM_NEED_REVIEW");
            assertThat(detail.containsKey("order_no")).isFalse();
            assertThat(objectMapper.writeValueAsString(detail)).doesNotContain(invalid);
            sequence++;
        }

        RESULTS.add(result(MessageIntent.ORDER_CANCEL, "JD-20260814/001"));
        Map<String, Object> validSubmission = submitAndAwait("MSG-SAFETY-VALID", sequence);
        Map<String, Object> validCase = openSubmissionCase(String.valueOf(validSubmission.get("id")));
        Map<?, ?> validDetail = (Map<?, ?>) validCase.get("detail");

        assertThat(validCase.get("reason_code")).isEqualTo("WECOM_ORDER_CANCEL");
        assertThat(validDetail.get("order_no")).isEqualTo("JD-20260814/001");
    }

    @Test
    void explicitlyConfiguredModelMetadataAliasRemainsAuditable() throws Exception {
        RESULTS.add(new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("kind", "chat"),
                SAFE_PROVIDER_ALIAS,
                SAFE_MODEL_ALIAS,
                SAFE_PROMPT_ALIAS,
                null));

        Map<String, Object> submission = submitAndAwait("MSG-SAFETY-METADATA-ALIAS", 39);
        Map<?, ?> interpretation = (Map<?, ?>) ((List<?>) submission.get("interpretations")).getFirst();
        Map<String, Object> persisted = jdbc.queryForMap(
                """
                SELECT provider, model, prompt_version
                FROM app.message_interpretations
                WHERE submission_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
                Long.parseLong(String.valueOf(submission.get("id"))));

        assertThat(interpretation.get("provider")).isEqualTo(SAFE_PROVIDER_ALIAS);
        assertThat(interpretation.get("model")).isEqualTo(SAFE_MODEL_ALIAS);
        assertThat(interpretation.get("prompt_version")).isEqualTo(SAFE_PROMPT_ALIAS);
        assertThat(persisted.get("provider")).isEqualTo(SAFE_PROVIDER_ALIAS);
        assertThat(persisted.get("model")).isEqualTo(SAFE_MODEL_ALIAS);
        assertThat(persisted.get("prompt_version")).isEqualTo(SAFE_PROMPT_ALIAS);
    }

    @Test
    void unsafeModelMetadataFailsClosedBeforePersistenceAndNeverLeavesPublicApis() throws Exception {
        String shortSecretLikeAlias = "sk-live123";
        RESULTS.add(new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "metadata-boundary-probe"),
                shortSecretLikeAlias,
                SAFE_MODEL_ALIAS,
                SAFE_PROMPT_ALIAS,
                null));

        Map<String, Object> submission = submitAndAwait("MSG-SAFETY-METADATA", 40);
        Map<?, ?> interpretation = (Map<?, ?>) ((List<?>) submission.get("interpretations")).getFirst();
        Map<String, Object> reviewCase = openSubmissionCase(String.valueOf(submission.get("id")));
        Map<?, ?> reviewDetail = (Map<?, ?>) reviewCase.get("detail");
        Map<String, Object> persisted = jdbc.queryForMap(
                """
                SELECT provider, model, prompt_version, intent, error
                FROM app.message_interpretations
                WHERE submission_id = ?
                ORDER BY version DESC
                LIMIT 1
                """,
                Long.parseLong(String.valueOf(submission.get("id"))));

        assertThat(submission)
                .containsEntry("status", "FAILED")
                .containsEntry("current_intent", "NEED_REVIEW")
                .containsEntry("latest_error", "MODEL_OUTPUT_INVALID");
        assertThat(interpretation.get("provider")).isEqualTo("none");
        assertThat(interpretation.get("model")).isEqualTo("none");
        assertThat(interpretation.get("prompt_version")).isEqualTo("none");
        assertThat(interpretation.get("intent")).isEqualTo("NEED_REVIEW");
        assertThat(interpretation.get("error")).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(reviewDetail.get("provider")).isEqualTo("none");
        assertThat(reviewDetail.get("model")).isEqualTo("none");
        assertThat(reviewDetail.get("prompt_version")).isEqualTo("none");
        assertThat(reviewDetail.get("error_code")).isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(persisted)
                .containsEntry("provider", "none")
                .containsEntry("model", "none")
                .containsEntry("prompt_version", "none")
                .containsEntry("intent", "NEED_REVIEW")
                .containsEntry("error", "MODEL_OUTPUT_INVALID");
        assertThat(objectMapper.writeValueAsString(Map.of(
                        "submission", submission,
                        "review_case", reviewCase,
                        "persisted", persisted)))
                .doesNotContain(shortSecretLikeAlias);
    }

    private Map<String, Object> submitAndAwait(String messageId, int sequence) throws Exception {
        String plaintext = objectMapper.writeValueAsString(Map.of(
                "msgid", messageId,
                "aibotid", BOT_ID,
                "chatid", ALLOWED_GROUP,
                "chattype", "group",
                "from", Map.of("userid", "USER-SAFETY-" + sequence),
                "msgtype", "text",
                "text", Map.of("content", "请处理这条改单或取消消息")));
        dispatch(plaintext);
        return awaitUntil(
                () -> submissionForMessage(messageId),
                value -> value != null && !"RECEIVED".equals(value.get("status")),
                Duration.ofSeconds(10));
    }

    private Map<String, Object> submissionForMessage(String messageId) {
        Map<String, Object> page = get("/api/v1/channel-messages?size=200");
        Map<?, ?> message = ((List<?>) page.get("items")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> messageId.equals(item.get("message_id")))
                .findFirst()
                .orElse(null);
        if (message == null) {
            return null;
        }
        Map<String, Object> detail = get("/api/v1/channel-messages/" + message.get("id"));
        Object submissionId = detail.get("submission_id");
        return submissionId == null ? null : get("/api/v1/message-submissions/" + submissionId);
    }

    private Map<String, Object> openSubmissionCase(String submissionId) {
        return awaitUntil(
                () -> {
                    Map<String, Object> page = get("/api/v1/review-cases?status=OPEN&size=200");
                    return ((List<?>) page.get("items")).stream()
                            .map(item -> (Map<String, Object>) item)
                            .filter(item -> "MESSAGE_SUBMISSION".equals(item.get("subject_type")))
                            .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                            .findFirst()
                            .orElse(null);
                },
                value -> value != null,
                Duration.ofSeconds(5));
    }

    private Map<String, Object> get(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "safety-reviewer");
        ResponseEntity<Map> response = http.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static InterpretationResult result(MessageIntent intent, String orderReference) {
        return new InterpretationResult(
                intent,
                Map.of("order_no", orderReference),
                SAFE_PROVIDER_ALIAS,
                SAFE_MODEL_ALIAS,
                SAFE_PROMPT_ALIAS,
                null);
    }

    /** 长连接接收接缝：把企微消息帧交给 {@link WecomMessageDispatchHandler}（原 HTTP 加密回调已被长连接替换）。 */
    private void dispatch(String bodyJson) {
        try {
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("cmd", "aibot_msg_callback");
            frame.set("body", objectMapper.readTree(bodyJson));
            wecomDispatchHandler.onFrame("aibot_msg_callback", frame);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static <T> T awaitUntil(Supplier<T> supplier, Predicate<T> predicate, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        T value;
        do {
            value = supplier.get();
            if (predicate.test(value)) {
                return value;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("condition not met before timeout; last value=" + value);
    }
}
