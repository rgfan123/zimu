package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMessageDispatchHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * 票 03 主验收接缝：消息提交、解释历史与四种基础分流。
 *
 * <p>通过真实 PostgreSQL + 长连接接收接缝（{@link WecomMessageDispatchHandler}）/管理 API 验证
 * Worker 成功、三次失败、并发幂等、重新解释追加版本和分流规则；模型只在 {@link MessageInterpreter}
 * 边界替换。
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageInterpretationApiTest {

    private static final String BOT_ID = "AIBOT-ORDER-OPS";
    private static final String ALLOWED_GROUP = "CHAT-ORDER-OPS";
    private static final String TOKEN = "fixed-callback-token-for-tests";
    private static final String ENCODING_AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void messagePipelineConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.poll-ms", () -> "100");
        registry.add("app.message-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.lease-seconds", () -> "10");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter testInterpreter() {
            return InterpreterControl::next;
        }
    }

    /** 测试替身解释器：先注入结果队列，Worker 依次消费；也可以注入持续抛出的失败。 */
    static final class InterpreterControl {

        private static final java.util.concurrent.ArrayBlockingQueue<InterpretationResult> QUEUE =
                new java.util.concurrent.ArrayBlockingQueue<>(64);
        private static volatile RuntimeException failure;

        static void queue(InterpretationResult result) {
            QUEUE.offer(result);
        }

        static void failWith(RuntimeException ex) {
            failure = ex;
        }

        static InterpretationResult next(InterpretationInput input) {
            RuntimeException ex = failure;
            if (ex != null) {
                throw ex;
            }
            InterpretationResult result = QUEUE.poll();
            if (result == null) {
                throw new IllegalStateException("test interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            QUEUE.clear();
            failure = null;
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WecomMessageDispatchHandler wecomDispatchHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetInterpreter() {
        InterpreterControl.reset();
    }

    // ------------------------------------------------------------------
    // 分流验收
    // ------------------------------------------------------------------

    @Test
    void nonBusinessMessagesAreArchivedWithoutCreatingAReviewCase() throws Exception {
        long casesBefore = countSubmissionCases(null);
        InterpreterControl.queue(result(MessageIntent.NON_BUSINESS, Map.of("kind", "chat")));

        String messageId = "MSG-NB-01";
        postAndReceipt(messageId, "大家早上好，今天天气不错", 1);
        Map<String, Object> detail = awaitSubmission(messageId);

        assertThat(detail.get("status")).isEqualTo("INTERPRETED");
        assertThat(detail.get("current_intent")).isEqualTo("NON_BUSINESS");
        assertThat(interpretationVersions(detail)).containsExactly("1");

        awaitUntil(
                () -> countSubmissionCases(null),
                count -> count == casesBefore,
                Duration.ofSeconds(5));
    }

    @Test
    void needReviewMessagesCreateExactlyOneVisibleOpenCase() throws Exception {
        long casesBefore = countSubmissionCases("WECOM_NEED_REVIEW");
        InterpreterControl.queue(result(MessageIntent.NEED_REVIEW, Map.of("reason", "ambiguous")));

        String messageId = "MSG-NR-01";
        postAndReceipt(messageId, "这个有点复杂，我不确定怎么处理", 1);
        Map<String, Object> detail = awaitSubmission(messageId);

        assertThat(detail.get("status")).isEqualTo("INTERPRETED");
        assertThat(detail.get("current_intent")).isEqualTo("NEED_REVIEW");

        List<Map<String, Object>> cases = awaitUntil(
                () -> listReviewCases(),
                list -> list.stream()
                                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                                .filter(c -> "WECOM_NEED_REVIEW".equals(c.get("reason_code")))
                                .count()
                        == casesBefore + 1,
                Duration.ofSeconds(5));
        List<Map<String, Object>> created = cases.stream()
                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                .filter(c -> "WECOM_NEED_REVIEW".equals(c.get("reason_code")))
                .toList();
        assertThat(created.getLast().get("status")).isEqualTo("OPEN");
        assertThat(created.getLast().get("responsible_team")).isEqualTo("ORDER_OPS");

        Map<String, Object> caseDetail = getReviewCase(String.valueOf(created.getLast().get("id")));
        assertThat(((List<?>) caseDetail.get("allowed_actions")).stream()
                        .map(String::valueOf)
                        .toList())
                .containsExactly("REINTERPRET", "REJECT", "RESOLVE_MANUALLY");
        assertThat(objectMapper.writeValueAsString(caseDetail))
                .doesNotContain(TOKEN, ENCODING_AES_KEY, "unknown_secret");
    }

    @Test
    void repeatedNeedReviewReinterpretationSupersedesBeforeCreatingReplacementCase() throws Exception {
        InterpreterControl.queue(result(MessageIntent.NEED_REVIEW, Map.of("reason", "first-review")));
        String messageId = "MSG-NR-REINTERPRET-01";
        postAndReceipt(messageId, "请人工先复核这条消息", 1);
        Map<String, Object> submission = awaitSubmission(messageId);
        String submissionId = String.valueOf(submission.get("id"));

        List<Map<String, Object>> originalCases = awaitUntil(
                this::listReviewCases,
                cases -> cases.stream()
                        .filter(item -> "MESSAGE_SUBMISSION".equals(item.get("subject_type")))
                        .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                        .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                        .filter(item -> "OPEN".equals(item.get("status")))
                        .findAny()
                        .isPresent(),
                Duration.ofSeconds(5));
        Map<String, Object> originalCase = originalCases.stream()
                .filter(item -> "MESSAGE_SUBMISSION".equals(item.get("subject_type")))
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .filter(item -> "OPEN".equals(item.get("status")))
                .findFirst()
                .orElseThrow();

        InterpreterControl.queue(result(MessageIntent.NEED_REVIEW, Map.of("reason", "second-review")));
        HttpHeaders headers = adminHeaders();
        headers.set("Idempotency-Key", "test-reinterpret-need-review-0001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> queued = http.postForEntity(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> refreshed = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && interpretationVersions(detail).size() == 2,
                Duration.ofSeconds(10));
        assertThat(refreshed.get("current_intent")).isEqualTo("NEED_REVIEW");

        List<Map<String, Object>> matching = listReviewCases().stream()
                .filter(item -> "MESSAGE_SUBMISSION".equals(item.get("subject_type")))
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .toList();
        assertThat(matching).hasSize(2);
        assertThat(matching.stream().filter(item -> "OPEN".equals(item.get("status"))))
                .singleElement();
        assertThat(getReviewCase(String.valueOf(originalCase.get("id"))))
                .containsEntry("status", "DISMISSED")
                .satisfies(detail -> assertThat(((Map<?, ?>) detail.get("resolution")).get("note"))
                        .isEqualTo("SUPERSEDED_BY_NEW_INTERPRETATION"));
    }

    @Test
    void orderChangeAndCancelRouteByOrderReference() throws Exception {
        long changeBefore = countSubmissionCases("WECOM_ORDER_CHANGE");
        long cancelBefore = countSubmissionCases("WECOM_ORDER_CANCEL");
        long needReviewBefore = countSubmissionCases("WECOM_NEED_REVIEW");

        InterpreterControl.queue(result(MessageIntent.ORDER_CHANGE, Map.of("order_no", "SO-10086")));
        postAndReceipt("MSG-CHG-01", "订单 SO-10086 改一下数量", 1);
        awaitUntil(
                () -> countSubmissionCases("WECOM_ORDER_CHANGE"),
                count -> count == changeBefore + 1,
                Duration.ofSeconds(5));

        // 没有订单号/引用时，取消请求归入 NEED_REVIEW 而不是 ORDER_CANCEL
        InterpreterControl.queue(result(MessageIntent.ORDER_CANCEL, Map.of()));
        postAndReceipt("MSG-CXL-01", "把这个取消了吧", 2);
        awaitUntil(
                () -> countSubmissionCases("WECOM_NEED_REVIEW"),
                count -> count == needReviewBefore + 1,
                Duration.ofSeconds(5));
        assertThat(countSubmissionCases("WECOM_ORDER_CANCEL")).isEqualTo(cancelBefore);
    }

    @Test
    void quotedEvidenceAloneDoesNotBecomeADeterministicOrderReference() throws Exception {
        long cancelBefore = countSubmissionCases("WECOM_ORDER_CANCEL");
        long needReviewBefore = countSubmissionCases("WECOM_NEED_REVIEW");

        InterpreterControl.queue(result(MessageIntent.ORDER_CANCEL, Map.of("quoted", true)));
        postAndReceipt("MSG-CXL-QUOTED-01", "把引用的这个取消了吧", 3);

        awaitUntil(
                () -> countSubmissionCases("WECOM_NEED_REVIEW"),
                count -> count == needReviewBefore + 1,
                Duration.ofSeconds(5));
        assertThat(countSubmissionCases("WECOM_ORDER_CANCEL")).isEqualTo(cancelBefore);
    }

    @Test
    void customerOrderIntentsCreateOneDraftReviewCase() throws Exception {
        long casesBefore = countOrderDraftCases();
        InterpreterControl.queue(result(MessageIntent.CUSTOMER_ORDER, Map.of("customer", "张三")));

        String messageId = "MSG-CO-01";
        postAndReceipt(messageId, "@OrderBot 张三 13800000000 浦东新区 猪肉礼盒 2份", 1);
        Map<String, Object> detail = awaitSubmission(messageId);

        assertThat(detail.get("status")).isEqualTo("DRAFTED");
        assertThat(detail.get("current_intent")).isEqualTo("CUSTOMER_ORDER");
        assertThat(interpretationVersions(detail)).containsExactly("1");
        List<Map<String, Object>> cases = awaitUntil(
                this::listReviewCases,
                list -> countOrderDraftCases(list) == casesBefore + 1,
                Duration.ofSeconds(5));
        assertThat(cases.stream()
                        .filter(c -> "ORDER_DRAFT".equals(c.get("subject_type")))
                        .filter(c -> "WECOM_ORDER_DRAFT".equals(c.get("reason_code")))
                        .filter(c -> "OPEN".equals(c.get("status")))
                        .toList())
                .hasSizeGreaterThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Worker 幂等与重试
    // ------------------------------------------------------------------

    @Test
    void duplicateCallbackCreatesOneSubmissionOneVersion() throws Exception {
        InterpreterControl.queue(result(MessageIntent.CUSTOMER_ORDER, Map.of("customer", "张三")));

        String messageId = "MSG-DUP-01";
        String plaintext = textMessage(messageId, BOT_ID, ALLOWED_GROUP, "USER-FWD-01", "@OrderBot 张三 订猪肉", true);
        dispatch(plaintext);
        dispatch(plaintext);

        Map<String, Object> detail = awaitSubmission(messageId);
        assertThat(detail.get("status")).isEqualTo("DRAFTED");
        assertThat(interpretationVersions(detail)).containsExactly("1");
        assertThat(detail.get("latest_task")).isNotNull();
    }

    @Test
    void concurrentDuplicateCallbacksStayIdempotent() throws Exception {
        InterpreterControl.queue(result(MessageIntent.CUSTOMER_ORDER, Map.of("customer", "李四")));

        String messageId = "MSG-CONC-01";
        String plaintext = textMessage(messageId, BOT_ID, ALLOWED_GROUP, "USER-FWD-02", "@OrderBot 李四 订羊肉", true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> dispatched = List.of(
                    pool.submit(() -> dispatch(plaintext)),
                    pool.submit(() -> dispatch(plaintext)));
            for (Future<?> future : dispatched) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> detail = awaitSubmission(messageId);
        assertThat(interpretationVersions(detail)).containsExactly("1");
    }

    @Test
    void finalFailureAfterThreeAttemptsCreatesUniqueNeedReviewCase() throws Exception {
        long needReviewBefore = countSubmissionCases("WECOM_NEED_REVIEW");
        long openBefore = countOpenSubmissionCases();
        InterpreterControl.failWith(new RuntimeException("model endpoint unavailable"));

        String messageId = "MSG-FAIL-01";
        postAndReceipt(messageId, "@OrderBot 帮我下一单", 1);

        Map<String, Object> detail = awaitUntil(
                () -> submissionDetail(messageId),
                d -> d != null && "FAILED".equals(d.get("status")),
                Duration.ofSeconds(15));
        assertThat(detail.get("latest_task")).isNotNull();
        assertThat(((Map<?, ?>) detail.get("latest_task")).get("status")).isEqualTo("FAILED");
        assertThat(((Map<?, ?>) detail.get("latest_task")).get("attempts")).isEqualTo(3);

        awaitUntil(
                () -> countSubmissionCases("WECOM_NEED_REVIEW"),
                count -> count == needReviewBefore + 1,
                Duration.ofSeconds(5));

        // 重新解释成功后旧待办被取代、版本追加
        InterpreterControl.reset();
        InterpreterControl.queue(result(MessageIntent.CUSTOMER_ORDER, Map.of("customer", "王五")));
        Map<String, Object> submission = submissionDetail(messageId);
        String submissionId = String.valueOf(submission.get("id"));
        HttpHeaders headers = adminHeaders();
        headers.set("Idempotency-Key", "test-reinterpret-key-0001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> reinterpret = http.postForEntity(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(reinterpret.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> refreshed = awaitUntil(
                () -> submissionDetail(messageId),
                d -> d != null && "DRAFTED".equals(d.get("status")),
                Duration.ofSeconds(10));
        // 终败版本 1 保留，首次成功重新解释追加版本 2
        assertThat(interpretationVersions(refreshed)).containsExactly("2", "1");
        awaitUntil(
                () -> countOpenSubmissionCases(),
                count -> count == openBefore,
                Duration.ofSeconds(5));

        // 再次重新解释：追加版本 3，历史保留（必须使用新的幂等键，否则被幂等重放）
        InterpreterControl.queue(result(MessageIntent.CUSTOMER_ORDER, Map.of("customer", "赵六")));
        HttpHeaders againHeaders = adminHeaders();
        againHeaders.set("Idempotency-Key", "test-reinterpret-key-0002");
        againHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> again = http.postForEntity(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                new HttpEntity<>(Map.of(), againHeaders),
                Map.class);
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> twice = awaitUntil(
                () -> submissionDetail(messageId),
                d -> d != null && interpretationVersions(d).size() == 3,
                Duration.ofSeconds(10));
        assertThat(interpretationVersions(twice)).containsExactly("3", "2", "1");
    }

    @Test
    void successfulInterpretationFollowedByFinalFailureAppendsSafeNeedReviewVersion()
            throws Exception {
        String sentinel = "REINTERPRET_FAILURE_SENTINEL_MUST_NOT_BE_PUBLIC";
        InterpreterControl.queue(result(MessageIntent.NON_BUSINESS, Map.of("kind", "chat")));

        String messageId = "MSG-REINTERPRET-FAIL-01";
        postAndReceipt(messageId, "先成功归档，随后重新解释失败", 7);
        Map<String, Object> succeeded = awaitSubmission(messageId);
        assertThat(succeeded)
                .containsEntry("status", "INTERPRETED")
                .containsEntry("current_intent", "NON_BUSINESS");
        assertThat(interpretationVersions(succeeded)).containsExactly("1");

        InterpreterControl.failWith(new IllegalStateException(sentinel));
        HttpHeaders headers = adminHeaders();
        headers.set("Idempotency-Key", "test-reinterpret-final-failure-key-0001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> reinterpret = http.postForEntity(
                "/api/v1/message-submissions/" + succeeded.get("id") + "/reinterpret",
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(reinterpret.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> failed = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && "FAILED".equals(detail.get("status")),
                Duration.ofSeconds(15));
        assertThat(failed)
                .containsEntry("status", "FAILED")
                .containsEntry("current_intent", "NEED_REVIEW")
                .containsEntry("latest_error", "MODEL_CALL_FAILED");
        assertThat(interpretationVersions(failed)).containsExactly("2", "1");

        Map<?, ?> terminalVersion = (Map<?, ?>) ((List<?>) failed.get("interpretations")).getFirst();
        assertThat(String.valueOf(terminalVersion.get("version"))).isEqualTo("2");
        assertThat(terminalVersion.get("intent")).isEqualTo("NEED_REVIEW");
        assertThat(terminalVersion.get("error")).isEqualTo("MODEL_CALL_FAILED");
        Map<?, ?> latestTask = (Map<?, ?>) failed.get("latest_task");
        assertThat(latestTask.get("status")).isEqualTo("FAILED");
        assertThat(latestTask.get("attempts")).isEqualTo(3);
        assertThat(objectMapper.writeValueAsString(failed)).doesNotContain(sentinel);
    }

    // ------------------------------------------------------------------
    // 管理查询与秘密防护
    // ------------------------------------------------------------------

    @Test
    void reviewCasePublicDetailUsesOnlyAllowlistedModelEvidence() throws Exception {
        String sentinel = "MODEL_OUTPUT_SENTINEL_MUST_NOT_LEAVE_DERIVED_EVIDENCE";
        InterpreterControl.queue(result(
                MessageIntent.NEED_REVIEW,
                Map.of(
                        "reason", "ambiguous",
                        "debug_context", Map.of("opaque", sentinel))));

        String messageId = "MSG-MODEL-EVIDENCE-01";
        postAndReceipt(messageId, "这条消息需要人工复核", 4);
        Map<String, Object> submission = awaitSubmission(messageId);
        String submissionId = String.valueOf(submission.get("id"));

        List<Map<String, Object>> cases = awaitUntil(
                this::listReviewCases,
                items -> items.stream()
                        .anyMatch(item -> submissionId.equals(String.valueOf(item.get("subject_id")))
                                && "WECOM_NEED_REVIEW".equals(item.get("reason_code"))),
                Duration.ofSeconds(5));
        Map<String, Object> reviewCase = cases.stream()
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> detail = (Map<String, Object>) getReviewCase(
                        String.valueOf(reviewCase.get("id")))
                .get("detail");

        assertThat(detail.keySet())
                .containsExactlyInAnyOrder("intent", "provider", "model", "prompt_version");
        assertThat(objectMapper.writeValueAsString(detail))
                .doesNotContain(sentinel, "debug_context", "opaque", "model_output");
    }

    @Test
    void modelExceptionMessageNeverLeavesStablePublicFailureCode(CapturedOutput output)
            throws Exception {
        String sentinel = "EXCEPTION_MESSAGE_SENTINEL_MUST_NOT_BE_PERSISTED_OR_LOGGED";
        InterpreterControl.failWith(new IllegalStateException(sentinel));

        String messageId = "MSG-MODEL-EXCEPTION-01";
        postAndReceipt(messageId, "这条消息会触发模型异常", 5);
        Map<String, Object> submission = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && "FAILED".equals(detail.get("status")),
                Duration.ofSeconds(15));
        String submissionId = String.valueOf(submission.get("id"));

        List<Map<String, Object>> cases = awaitUntil(
                this::listReviewCases,
                items -> items.stream()
                        .anyMatch(item -> submissionId.equals(String.valueOf(item.get("subject_id")))
                                && "WECOM_NEED_REVIEW".equals(item.get("reason_code"))),
                Duration.ofSeconds(5));
        Map<String, Object> reviewCase = cases.stream()
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> caseDetail = getReviewCase(String.valueOf(reviewCase.get("id")));

        Map<?, ?> latestTask = (Map<?, ?>) submission.get("latest_task");
        assertThat(latestTask.get("last_error")).isEqualTo("MODEL_CALL_FAILED");
        Object errorCode = ((Map<?, ?>) caseDetail.get("detail")).get("error_code");
        assertThat(errorCode).isEqualTo("MODEL_CALL_FAILED");
        assertThat(objectMapper.writeValueAsString(Map.of(
                        "submission", submission,
                        "tasks", listTasks(null),
                        "review_case", caseDetail)))
                .doesNotContain(sentinel);
        assertThat(output.getAll()).doesNotContain(sentinel);
    }

    @Test
    void returnedTransientModelErrorRetriesThreeTimesAndOnlyExposesStableFailureCode() throws Exception {
        String sentinel = "RETURNED_MODEL_ERROR_SENTINEL_MUST_NOT_BE_PUBLIC";
        InterpretationResult transientFailure = new InterpretationResult(
                MessageIntent.NEED_REVIEW,
                Map.of("reason", "provider_failure"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                sentinel);
        InterpreterControl.queue(transientFailure);
        InterpreterControl.queue(transientFailure);
        InterpreterControl.queue(transientFailure);

        String messageId = "MSG-MODEL-RETURNED-ERROR-01";
        postAndReceipt(messageId, "这条消息会返回模型错误结果", 5);
        Map<String, Object> submission = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && "FAILED".equals(detail.get("status")),
                Duration.ofSeconds(15));
        String submissionId = String.valueOf(submission.get("id"));

        Map<String, Object> reviewCase = awaitUntil(
                        this::listReviewCases,
                        items -> items.stream().anyMatch(item -> submissionId.equals(
                                        String.valueOf(item.get("subject_id")))
                                && "WECOM_NEED_REVIEW".equals(item.get("reason_code"))),
                        Duration.ofSeconds(5))
                .stream()
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> caseDetail = getReviewCase(String.valueOf(reviewCase.get("id")));

        Map<?, ?> latestTask = (Map<?, ?>) submission.get("latest_task");
        assertThat(latestTask.get("status")).isEqualTo("FAILED");
        assertThat(latestTask.get("attempts")).isEqualTo(3);
        assertThat(latestTask.get("last_error")).isEqualTo("MODEL_CALL_FAILED");
        Object errorCode = ((Map<?, ?>) caseDetail.get("detail")).get("error_code");
        assertThat(errorCode).isEqualTo("MODEL_CALL_FAILED");
        assertThat(objectMapper.writeValueAsString(Map.of(
                        "submission", submission,
                        "review_case", caseDetail)))
                .doesNotContain(sentinel);
    }

    @Test
    void errorResultIsForcedToNeedReviewAndCannotCreateBusinessDrafts() throws Exception {
        long orderDraftCasesBefore = countOrderDraftCases();
        long needReviewCasesBefore = countSubmissionCases("WECOM_NEED_REVIEW");
        InterpreterControl.queue(new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of("customer", "MODEL_OUTPUT_MUST_NOT_ROUTE_A_FAILED_RESULT"),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                "MODEL_NOT_CONFIGURED"));

        String messageId = "MSG-MODEL-ERROR-INTENT-01";
        postAndReceipt(messageId, "失败的模型结果不能生成业务草稿", 6);
        Map<String, Object> submission = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && !"RECEIVED".equals(detail.get("status")),
                Duration.ofSeconds(15));

        assertThat(submission)
                .containsEntry("status", "FAILED")
                .containsEntry("current_intent", "NEED_REVIEW")
                .containsEntry("latest_error", "MODEL_NOT_CONFIGURED");
        assertThat(countOrderDraftCases()).isEqualTo(orderDraftCasesBefore);
        awaitUntil(
                () -> countSubmissionCases("WECOM_NEED_REVIEW"),
                count -> count == needReviewCasesBefore + 1,
                Duration.ofSeconds(5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuredOutputBoundaryViolations")
    void structuredOutputBoundaryViolationsFailClosedBeforeBusinessDraftRouting(
            String boundary,
            int nonceSequence,
            MessageIntent returnedIntent,
            Map<String, Object> structuredOutput,
            String sentinel) throws Exception {
        long businessDraftCasesBefore = countBusinessDraftCases();
        InterpreterControl.queue(result(returnedIntent, structuredOutput));

        String messageId = "MSG-MODEL-OUTPUT-" + boundary;
        postAndReceipt(messageId, "这条消息的模型输出必须先通过结构边界", nonceSequence);
        Map<String, Object> submission = awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && "FAILED".equals(detail.get("status")),
                Duration.ofSeconds(15));
        String submissionId = String.valueOf(submission.get("id"));

        assertThat(submission)
                .containsEntry("status", "FAILED")
                .containsEntry("current_intent", "NEED_REVIEW")
                .containsEntry("latest_error", "MODEL_OUTPUT_INVALID");
        Map<?, ?> latestInterpretation =
                (Map<?, ?>) ((List<?>) submission.get("interpretations")).getFirst();
        assertThat(latestInterpretation.get("intent")).isEqualTo("NEED_REVIEW");
        assertThat(latestInterpretation.get("error")).isEqualTo("MODEL_OUTPUT_INVALID");

        List<Map<String, Object>> submissionCases = awaitUntil(
                this::listReviewCases,
                cases -> cases.stream()
                                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                                .filter(item -> "OPEN".equals(item.get("status")))
                                .count()
                        == 1,
                Duration.ofSeconds(5));
        List<Map<String, Object>> matchingCases = submissionCases.stream()
                .filter(item -> submissionId.equals(String.valueOf(item.get("subject_id"))))
                .filter(item -> "WECOM_NEED_REVIEW".equals(item.get("reason_code")))
                .filter(item -> "OPEN".equals(item.get("status")))
                .toList();

        assertThat(matchingCases).hasSize(1);
        assertThat(countBusinessDraftCases()).isEqualTo(businessDraftCasesBefore);
        Map<String, Object> reviewCase = getReviewCase(String.valueOf(matchingCases.getFirst().get("id")));
        assertThat(((Map<?, ?>) reviewCase.get("detail")).get("error_code"))
                .isEqualTo("MODEL_OUTPUT_INVALID");
        assertThat(objectMapper.writeValueAsString(Map.of(
                        "submission", submission,
                        "review_case", reviewCase)))
                .doesNotContain(sentinel);
    }

    @Test
    void submissionDetailExposesNoSecretsAndTaskListIsFilterable() throws Exception {
        InterpreterControl.queue(result(MessageIntent.NEED_REVIEW, Map.of("reason", "ambiguous")));
        String messageId = "MSG-SEC-01";
        postAndReceipt(messageId, "需要人工看的内容", 1);
        Map<String, Object> detail = awaitSubmission(messageId);

        String json = objectMapper.writeValueAsString(detail);
        assertThat(json).doesNotContain(TOKEN, ENCODING_AES_KEY, "raw_payload", "unknown_secret");

        Map<String, Object> failed = listTasks("FAILED");
        Map<String, Object> all = listTasks(null);
        assertThat(failed.get("items")).isNotNull();
        assertThat(all.get("total_elements")).isNotNull();
        List<?> tasks = (List<?>) all.get("items");
        assertThat(tasks).isNotEmpty();
        assertThat(((Map<?, ?>) tasks.getFirst()).get("id")).isInstanceOf(String.class);
        assertThat(objectMapper.writeValueAsString(all))
                .doesNotContain(TOKEN, ENCODING_AES_KEY, "lease_owner", "unknown_secret");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> awaitSubmission(String messageId) {
        return awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && !"RECEIVED".equals(detail.get("status")),
                Duration.ofSeconds(10));
    }

    private Map<String, Object> submissionDetail(String messageId) {
        List<Map<String, Object>> messages = ((List<?>) listChannelMessages().get("items")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> messageId.equals(item.get("message_id")))
                .map(item -> (Map<String, Object>) item)
                .toList();
        if (messages.isEmpty()) {
            return null;
        }
        ResponseEntity<Map> detail = exchangeGet("/api/v1/channel-messages/" + messages.getFirst().get("id"));
        Map<String, Object> message = detail.getBody();
        Object submissionId = message == null ? null : message.get("submission_id");
        if (submissionId == null) {
            return null;
        }
        ResponseEntity<Map> submission = exchangeGet("/api/v1/message-submissions/" + submissionId);
        return submission.getBody();
    }

    private List<String> interpretationVersions(Map<String, Object> detail) {
        List<?> interpretations = (List<?>) detail.get("interpretations");
        return interpretations.stream()
                .map(item -> String.valueOf(((Map<?, ?>) item).get("version")))
                .toList();
    }

    private Map<String, Object> listChannelMessages() {
        ResponseEntity<Map> response = exchangeGet("/api/v1/channel-messages?size=200");
        return response.getBody();
    }

    private List<Map<String, Object>> listReviewCases() {
        ResponseEntity<Map> response = exchangeGet("/api/v1/review-cases?size=200");
        return ((List<?>) response.getBody().get("items")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private long countSubmissionCases(String reasonCode) {
        return listReviewCases().stream()
                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                .filter(c -> reasonCode == null || reasonCode.equals(c.get("reason_code")))
                .count();
    }

    private long countOrderDraftCases() {
        return countOrderDraftCases(listReviewCases());
    }

    private long countOrderDraftCases(List<Map<String, Object>> cases) {
        return cases.stream()
                .filter(c -> "ORDER_DRAFT".equals(c.get("subject_type")))
                .filter(c -> "WECOM_ORDER_DRAFT".equals(c.get("reason_code")))
                .count();
    }

    private long countBusinessDraftCases() {
        return listReviewCases().stream()
                .filter(c -> "ORDER_DRAFT".equals(c.get("subject_type"))
                        || "TRACKING_DRAFT".equals(c.get("subject_type")))
                .count();
    }

    private long countOpenSubmissionCases() {
        return listReviewCases().stream()
                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                .filter(c -> "OPEN".equals(c.get("status")))
                .count();
    }

    private Map<String, Object> getReviewCase(String caseId) {
        ResponseEntity<Map> response = exchangeGet("/api/v1/review-cases/" + caseId);
        return response.getBody();
    }

    private Map<String, Object> listTasks(String status) {
        String url = status == null
                ? "/api/v1/message-submissions/tasks?size=200"
                : "/api/v1/message-submissions/tasks?status=" + status + "&size=200";
        ResponseEntity<Map> response = exchangeGet(url);
        return response.getBody();
    }

    private ResponseEntity<Map> exchangeGet(String url) {
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
    }

    private void postAndReceipt(String messageId, String content, int nonceSeq) {
        String plaintext = textMessage(messageId, BOT_ID, ALLOWED_GROUP, "USER-FWD-" + nonceSeq, content, false);
        dispatch(plaintext);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "tester");
        return headers;
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
        throw new AssertionError("条件在 " + timeout + " 内未满足，最后值: " + value);
    }

    private static InterpretationResult result(MessageIntent intent, Map<String, Object> output) {
        return new InterpretationResult(intent, output, "test-provider", "test-model", "test-prompt-v1", null);
    }

    private static Stream<Arguments> structuredOutputBoundaryViolations() {
        String bytesSentinel = "MODEL_OUTPUT_BYTES_SENTINEL";
        String depthSentinel = "MODEL_OUTPUT_DEPTH_SENTINEL";
        String fieldsSentinel = "MODEL_OUTPUT_FIELDS_SENTINEL";
        String linesSentinel = "MODEL_OUTPUT_LINES_SENTINEL";
        return Stream.of(
                Arguments.of(
                        "OVER_256_KIB",
                        41,
                        MessageIntent.CUSTOMER_ORDER,
                        customerOrderOutput(Map.of(
                                "oversized_payload",
                                bytesSentinel + "x".repeat(256 * 1024))),
                        bytesSentinel),
                Arguments.of(
                        "OVER_DEPTH_8",
                        42,
                        MessageIntent.CUSTOMER_ORDER,
                        customerOrderOutput(Map.of("nested", nestedObject(9, depthSentinel))),
                        depthSentinel),
                Arguments.of(
                        "OVER_256_FIELDS",
                        43,
                        MessageIntent.CUSTOMER_ORDER,
                        customerOrderOutput(tooManyFields(fieldsSentinel)),
                        fieldsSentinel),
                Arguments.of(
                        "OVER_100_LINES",
                        44,
                        MessageIntent.SUPPLIER_TRACKING,
                        tooManyTrackingLines(linesSentinel),
                        linesSentinel));
    }

    private static Map<String, Object> customerOrderOutput(Map<String, Object> extra) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("customer", "边界测试客户");
        output.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800138000",
                "address", "边界测试地址"));
        output.put("settlement_method", "MONTHLY");
        output.put("items", List.of(Map.of(
                "product", "边界测试商品",
                "quantity", "1")));
        output.putAll(extra);
        return output;
    }

    private static Map<String, Object> nestedObject(int depth, String sentinel) {
        Object nested = sentinel;
        for (int index = 0; index < depth; index++) {
            nested = Map.of("level_" + index, nested);
        }
        return Map.of("root", nested);
    }

    private static Map<String, Object> tooManyFields(String sentinel) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index < 257; index++) {
            fields.put("field_" + index, index == 256 ? sentinel : "value_" + index);
        }
        return fields;
    }

    private static Map<String, Object> tooManyTrackingLines(String sentinel) {
        List<Map<String, Object>> lines = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            lines.add(Map.of(
                    "name", index == 100 ? sentinel : "张*" + index,
                    "tracking_no", "SF" + String.format("%010d", index)));
        }
        return Map.of("lines", lines);
    }

    // ------------------------------------------------------------------
    // 长连接接收接缝：消息体 JSON 直接交给 WecomMessageDispatchHandler（原 HTTP 加密回调
    // 传输已被长连接替换；帧映射与「已接收」回执由 WecomMessageDispatchHandlerTest 覆盖）。
    // ------------------------------------------------------------------

    private static String textMessage(
            String messageId, String botId, String chatId, String sender, String content, boolean withQuote) {
        String quote = withQuote
                ? ",\"quote\":{\"msgtype\":\"text\",\"text\":{\"content\":\"这是被转发的原始客户需求\"}}"
                : "";
        return "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + botId + "\","
                + "\"chatid\":\"" + chatId + "\","
                + "\"chattype\":\"group\","
                + "\"from\":{\"userid\":\"" + sender + "\"},"
                + "\"response_url\":\"https://temporary-response.example/secret\","
                + "\"unknown_secret\":\"must-not-be-rendered\","
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"}"
                + quote + "}";
    }

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
}
