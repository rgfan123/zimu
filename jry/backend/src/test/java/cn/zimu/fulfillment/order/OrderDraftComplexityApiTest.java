package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMessageDispatchHandler;
import cn.zimu.fulfillment.message.InterpretationInput;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageInterpreter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ticket 06 public seam: incomplete orders, multi-line/multi-receiver drafts, stable source order
 * numbers, explicit append (draft no / stable parent message id), suspected duplicates without
 * auto-merge, supplement endpoint, and independent confirm/reject of split drafts.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderDraftComplexityApiTest {

    private static final String BOT_ID = "AIBOT-TICKET-06";
    private static final String ALLOWED_GROUP = "CHAT-TICKET-06";
    private static final String ADMIN_USER = "ticket-06-reviewer";
    private static final String ADMIN_PASSWORD = "ticket-06-admin-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void ticketConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.poll-ms", () -> "100");
        registry.add("app.message-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.lease-seconds", () -> "10");
        registry.add("app.gateway.basic-auth.username", () -> ADMIN_USER);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter ticket06Interpreter() {
            return InterpreterControl::next;
        }
    }

    static final class InterpreterControl {

        private static final ArrayBlockingQueue<InterpretationResult> RESULTS = new ArrayBlockingQueue<>(32);

        static void queue(InterpretationResult result) {
            RESULTS.offer(result);
        }

        static InterpretationResult next(InterpretationInput ignored) {
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("ticket 06 interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            RESULTS.clear();
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

    @Test
    void incompleteOrderStillCreatesDraftWithMissingFieldsAndOpenCase() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", Map.of("name", "张三"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String messageId = "MSG-TICKET-06-INCOMPLETE-01";
        postMessage(messageId, 101, null);

        Map<String, Object> draft = awaitSingleDraft(messageId);
        assertThat(draft)
                .containsEntry("status", "OPEN")
                .containsEntry("receiver_name", "张三")
                .containsEntry("receiver_phone", null)
                .containsEntry("receiver_address", null)
                .containsEntry("settlement_method", "MONTHLY");
        assertThat(castList(draft.get("missing_fields")))
                .contains("receiver_phone", "receiver_address");
        assertThat(castList(draft.get("missing_fields")))
                .doesNotContain("customer", "line_1_sku", "settlement_method", "line_1_quantity");

        Map<String, Object> reviewCase = onlyOrderDraftReviewCase(draft.get("id").toString());
        assertThat(reviewCase)
                .containsEntry("status", "OPEN")
                .containsEntry("responsible_team", "ORDER_OPS");
        Map<String, Object> detail = castMap(get("/api/v1/review-cases/" + reviewCase.get("id")).get("detail"));
        assertThat(castList(detail.get("missing_fields")))
                .contains("receiver_phone", "receiver_address");
    }

    @Test
    void incompleteItemsStillCreateDraftWhenQuantityAndSkuAreMissing() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", " ",
                "items", List.of(Map.of("product", "子牧羊小腿")))));
        String messageId = "MSG-TICKET-06-INCOMPLETE-02";
        postMessage(messageId, 102, null);

        Map<String, Object> draft = awaitSingleDraft(messageId);
        assertThat(draft).containsEntry("status", "OPEN");
        assertThat(castList(draft.get("missing_fields")))
                .contains("line_1_quantity", "line_1_sku", "settlement_method");
        assertThat(castList(draft.get("missing_fields")))
                .doesNotContain("receiver_name", "receiver_phone", "receiver_address");
    }

    @Test
    void multipleItemsForOneReceiverFormOneMultiLineDraft() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(
                        item("子牧羊小腿", "2"),
                        item("子牧羊小腿", "1"),
                        item("子牧羊小腿", "3")))));
        String messageId = "MSG-TICKET-06-MULTI-LINE-01";
        postMessage(messageId, 103, null);

        Map<String, Object> draft = awaitSingleDraft(messageId);
        List<Map<String, Object>> lines = castMapList(draft.get("lines"));
        assertThat(lines).hasSize(3);
        assertThat(lines.stream().map(line -> line.get("line_no")).toList())
                .containsExactly(1, 2, 3);
        assertThat(lines.stream().map(line -> line.get("quantity")).toList())
                .containsExactly("2.000", "1.000", "3.000");
        assertThat(castList(draft.get("missing_fields"))).isEmpty();
        assertThat(draft.get("source_order_no").toString()).matches("WECOM-SUB-\\d+-1");
    }

    @Test
    void differentReceiversSplitIntoMultipleDraftsSharingTheSubmission() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(
                        withReceiver(
                                item("子牧羊小腿", "2"),
                                receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号")),
                        withReceiver(
                                item("子牧羊小腿", "1"),
                                receiverOf("李四", "13900000000", "北京市朝阳区测试街 2 号")),
                        withReceiver(
                                item("子牧羊小腿", "1"),
                                receiverOf("李四", "13900000000", "北京市朝阳区测试街 2 号"))))));
        String messageId = "MSG-TICKET-06-MULTI-RECEIVER-01";
        postMessage(messageId, 104, null);

        List<Map<String, Object>> drafts = awaitDrafts(messageId, 2);
        Map<String, Object> zhang = drafts.stream()
                .filter(draft -> "张三".equals(draft.get("receiver_name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> li = drafts.stream()
                .filter(draft -> "李四".equals(draft.get("receiver_name")))
                .findFirst()
                .orElseThrow();

        assertThat(zhang.get("submission_id")).isEqualTo(li.get("submission_id"));
        assertThat(castMapList(zhang.get("lines"))).hasSize(1);
        assertThat(castMapList(li.get("lines"))).hasSize(2);
        assertThat(zhang.get("source_order_no").toString()).matches("WECOM-SUB-\\d+-1");
        assertThat(li.get("source_order_no").toString()).matches("WECOM-SUB-\\d+-2");
        assertThat(zhang.get("source_order_no")).isNotEqualTo(li.get("source_order_no"));
        assertThat(zhang.get("draft_no")).isNotEqualTo(li.get("draft_no"));
        for (Map<String, Object> draft : drafts) {
            assertThat(draft).containsEntry("status", "OPEN");
            assertThat(onlyOrderDraftReviewCase(draft.get("id").toString()))
                    .containsEntry("status", "OPEN")
                    .containsEntry("responsible_team", "ORDER_OPS");
        }
    }

    @Test
    void stableSourceOrderNoDerivesFromSubmissionIdentityAndSequence() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(
                        withReceiver(item("子牧羊小腿", "2"), receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号")),
                        withReceiver(item("子牧羊小腿", "1"), receiverOf("李四", "13900000000", "北京市朝阳区测试街 2 号"))))));
        String messageId = "MSG-TICKET-06-SEQ-01";
        postMessage(messageId, 105, null);

        List<Map<String, Object>> drafts = awaitDrafts(messageId, 2);
        List<String> sourceOrderNos = drafts.stream()
                .map(draft -> draft.get("source_order_no").toString())
                .sorted()
                .toList();
        assertThat(sourceOrderNos).hasSize(2);
        assertThat(sourceOrderNos.getFirst()).matches("WECOM-SUB-\\d+-1");
        assertThat(sourceOrderNos.get(1)).matches("WECOM-SUB-\\d+-2");
        String submissionId = drafts.getFirst().get("submission_id").toString();
        List<String> draftNos = drafts.stream()
                .map(draft -> draft.get("draft_no").toString())
                .sorted()
                .toList();
        assertThat(draftNos).containsExactly("OD-" + submissionId + "-1", "OD-" + submissionId + "-2");
    }

    @Test
    void validDraftNoAppendsItemsToExistingOpenDraftWithEvidence() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String firstMessageId = "MSG-TICKET-06-APPEND-FIRST-01";
        postMessage(firstMessageId, 106, null);
        Map<String, Object> original = awaitSingleDraft(firstMessageId);
        String draftNo = original.get("draft_no").toString();
        long originalRevision = ((Number) original.get("revision")).longValue();
        String originalSubmission = original.get("submission_id").toString();

        InterpreterControl.queue(orderResult(Map.of(
                "draft_no", draftNo,
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "3"), item("子牧羊小腿", "1")))));
        String secondMessageId = "MSG-TICKET-06-APPEND-SECOND-01";
        postMessage(secondMessageId, 107, null);

        Map<String, Object> appended = awaitUntil(
                () -> get("/api/v1/order-drafts/" + original.get("id")),
                draft -> castMapList(draft.get("lines")).size() == 3,
                Duration.ofSeconds(12));
        assertThat(appended).containsEntry("draft_no", draftNo);
        assertThat(appended).containsEntry("submission_id", originalSubmission);
        assertThat(((Number) appended.get("revision")).longValue()).isEqualTo(originalRevision + 1);
        List<Integer> lineNumbers = castMapList(appended.get("lines")).stream()
                .map(line -> line.get("line_no"))
                .map(lineNo -> ((Number) lineNo).intValue())
                .toList();
        assertThat(lineNumbers).containsExactly(1, 2, 3);
        assertThat(castList(appended.get("missing_fields"))).isEmpty();

        // 追加证据进入复核事项 detail；追加消息自己的提交不产生新草稿
        Map<String, Object> reviewCase = onlyOrderDraftReviewCase(original.get("id").toString());
        Map<String, Object> detail = castMap(get("/api/v1/review-cases/" + reviewCase.get("id")).get("detail"));
        List<Map<String, Object>> appendEvents = castMapList(detail.get("append_events"));
        assertThat(appendEvents).hasSize(1);
        assertThat(appendEvents.getFirst()).containsKey("message_submission_id");
        assertThat(appendEvents.getFirst().get("appended_line_count")).isEqualTo(2);

        String secondSubmissionId = submissionForMessage(secondMessageId);
        assertThat(get("/api/v1/order-drafts?submission_id=" + secondSubmissionId + "&size=20"))
                .extracting(page -> ((Number) page.get("total_elements")).longValue())
                .isEqualTo(0L);
        assertThat(get("/api/v1/message-submissions/" + secondSubmissionId))
                .containsEntry("status", "DRAFTED");
    }

    @Test
    void nonExplicitAdjacentMessagesNeverAppendAndOnlyFlagDuplicates() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String firstMessageId = "MSG-TICKET-06-DUP-FIRST-01";
        postMessage(firstMessageId, 108, null);
        Map<String, Object> first = awaitSingleDraft(firstMessageId);
        long firstRevision = ((Number) first.get("revision")).longValue();

        // 相邻消息内容完全相同但无草稿号，且带企微 quote（只作证据）：不追加，仅提示疑似重复
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String secondMessageId = "MSG-TICKET-06-DUP-SECOND-01";
        postMessage(secondMessageId, 109, quote("text", "要两盒子牧羊小腿"));

        Map<String, Object> second = awaitSingleDraft(secondMessageId);
        assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        assertThat(second.get("submission_id")).isNotEqualTo(first.get("submission_id"));
        assertThat(second.get("draft_no")).isNotEqualTo(first.get("draft_no"));
        assertThat(second.get("suspected_duplicate_of")).isEqualTo(first.get("draft_no"));

        Map<String, Object> unchanged = get("/api/v1/order-drafts/" + first.get("id"));
        assertThat(unchanged).containsEntry("status", "OPEN");
        assertThat(((Number) unchanged.get("revision")).longValue()).isEqualTo(firstRevision);
        assertThat(castMapList(unchanged.get("lines"))).hasSize(1);

        // 近似排版（多余空白/换行）也提示疑似重复，且原草稿不受影响
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号 \n"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿  ", "2")))));
        String thirdMessageId = "MSG-TICKET-06-DUP-THIRD-01";
        postMessage(thirdMessageId, 110, null);

        Map<String, Object> third = awaitSingleDraft(thirdMessageId);
        assertThat(third.get("suspected_duplicate_of"))
                .isIn(first.get("draft_no"), second.get("draft_no"));
        assertThat(get("/api/v1/order-drafts/" + first.get("id")))
                .containsEntry("status", "OPEN")
                .extracting(draft -> ((Number) draft.get("revision")).longValue())
                .isEqualTo(firstRevision);
    }

    @Test
    void parentMessageIdAppendsWhenParentSubmissionHasExactlyOneOpenDraft() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String parentMessageId = "MSG-TICKET-06-PARENT-01";
        postMessage(parentMessageId, 111, null);
        Map<String, Object> parent = awaitSingleDraft(parentMessageId);

        InterpreterControl.queue(orderResult(Map.of(
                "parent_message_id", parentMessageId,
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "4")))));
        String childMessageId = "MSG-TICKET-06-PARENT-CHILD-01";
        postMessage(childMessageId, 112, null);

        Map<String, Object> appended = awaitUntil(
                () -> get("/api/v1/order-drafts/" + parent.get("id")),
                draft -> castMapList(draft.get("lines")).size() == 2,
                Duration.ofSeconds(12));
        assertThat(appended).containsEntry("draft_no", parent.get("draft_no"));
        assertThat(((Number) appended.get("revision")).longValue())
                .isEqualTo(((Number) parent.get("revision")).longValue() + 1);
    }

    @Test
    void invalidDraftNoNeverAppendsAndCreatesASeparateDraft() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "2")))));
        String messageId = "MSG-TICKET-06-FORGED-01";
        postMessage(messageId, 113, null);
        Map<String, Object> original = awaitSingleDraft(messageId);

        InterpreterControl.queue(orderResult(Map.of(
                "draft_no", "OD-FORGED-MUST-NOT-APPEND",
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(item("子牧羊小腿", "5")))));
        String forgedMessageId = "MSG-TICKET-06-FORGED-02";
        postMessage(forgedMessageId, 114, null);

        Map<String, Object> separate = awaitSingleDraft(forgedMessageId);
        assertThat(separate.get("id")).isNotEqualTo(original.get("id"));
        assertThat(castMapList(separate.get("lines"))).hasSize(1);
        assertThat(get("/api/v1/order-drafts/" + original.get("id")))
                .containsEntry("status", "OPEN")
                .extracting(draft -> ((Number) draft.get("revision")).longValue())
                .isEqualTo(0L);
    }

    @Test
    void supplementCompletesMissingFieldsAndRevisesQuantities() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", Map.of("name", "张三"),
                "settlement_method", " ",
                "items", List.of(item("子牧羊小腿", "2")))));
        String messageId = "MSG-TICKET-06-SUPPLEMENT-01";
        postMessage(messageId, 115, null);
        Map<String, Object> draft = awaitSingleDraft(messageId);
        assertThat(castList(draft.get("missing_fields")))
                .contains("receiver_phone", "receiver_address", "settlement_method");
        String customerId = castMapList(draft.get("customer_candidates"))
                .getFirst()
                .get("customer_id")
                .toString();

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("expected_revision", ((Number) draft.get("revision")).longValue());
        command.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800000000",
                "address", "上海市浦东新区测试路 1 号"));
        command.put("settlement_method", "MONTHLY");
        command.put("items", List.of(Map.of("line_no", 1, "quantity", "6")));

        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/supplement",
                command,
                "ticket-06-supplement-0001",
                "req-ticket-06-supplement-0001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> supplemented = response.getBody();
        assertThat(supplemented)
                .containsEntry("status", "OPEN")
                .containsEntry("receiver_phone", "13800000000")
                .containsEntry("receiver_address", "上海市浦东新区测试路 1 号")
                .containsEntry("settlement_method", "MONTHLY");
        assertThat(((Number) supplemented.get("revision")).longValue())
                .isEqualTo(((Number) draft.get("revision")).longValue() + 1);
        assertThat(castList(supplemented.get("missing_fields"))).isEmpty();
        assertThat(castMapList(supplemented.get("lines")).getFirst())
                .containsEntry("quantity", "6");

        // 幂等重放返回同一结果，不重复修订
        ResponseEntity<Map> replayed = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/supplement",
                command,
                "ticket-06-supplement-0001",
                "req-ticket-06-supplement-replay-0001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody())
                .containsEntry("receiver_phone", "13800000000");

        // 复核事项保持 OPEN；确认命令必须携带最新版本
        assertThat(onlyOrderDraftReviewCase(draft.get("id").toString()))
                .containsEntry("status", "OPEN");

        Map<String, Object> confirmCommand = new LinkedHashMap<>();
        confirmCommand.put("expected_revision", supplemented.get("revision"));
        confirmCommand.put("expected_case_version", draft.get("review_case_version"));
        confirmCommand.put("customer", Map.of("customer_id", customerId));
        confirmCommand.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800000000",
                "province", "上海市",
                "city", "上海市",
                "district", "浦东新区",
                "town", "测试街道",
                "address", "测试路 1 号"));
        confirmCommand.put("settlement", Map.of(
                "method", "MONTHLY",
                "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()));
        String skuId = castMapList(castMapList(supplemented.get("lines")).getFirst().get("sku_candidates"))
                .getFirst()
                .get("sku_id")
                .toString();
        confirmCommand.put("items", List.of(Map.of("line_no", 1, "sku_id", skuId, "quantity", "6")));
        ResponseEntity<Map> confirmed = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmCommand,
                "ticket-06-confirm-after-supplement-0001",
                "req-ticket-06-confirm-after-supplement-0001");
        assertThat(confirmed.getStatusCode())
                .withFailMessage("confirm response: %s", confirmed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED");
        assertNoCanonicalOrderCount(draft.get("source_order_no").toString(), 1);
    }

    @Test
    void supplementRejectsUnknownLineAndStaleVersion() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", Map.of("name", "张三"),
                "settlement_method", " ",
                "items", List.of(item("子牧羊小腿", "2")))));
        String messageId = "MSG-TICKET-06-SUPPLEMENT-02";
        postMessage(messageId, 116, null);
        Map<String, Object> draft = awaitSingleDraft(messageId);

        Map<String, Object> unknownLine = new LinkedHashMap<>();
        unknownLine.put("expected_revision", draft.get("revision"));
        unknownLine.put("items", List.of(Map.of("line_no", 99, "quantity", "1")));
        ResponseEntity<Map> unknown = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/supplement",
                unknownLine,
                "ticket-06-supplement-unknown-line-0001",
                "req-ticket-06-supplement-unknown-line-0001");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknown.getBody()).containsEntry("business_code", "DRAFT_LINE_NOT_FOUND");

        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("expected_revision", ((Number) draft.get("revision")).longValue() + 1);
        stale.put("settlement_method", "MONTHLY");
        ResponseEntity<Map> staleResponse = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/supplement",
                stale,
                "ticket-06-supplement-stale-0001",
                "req-ticket-06-supplement-stale-0001");
        assertThat(staleResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(staleResponse.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
        assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                .containsEntry("status", "OPEN")
                .containsEntry("settlement_method", null);
    }

    @Test
    void splitDraftsConfirmAndRejectIndependently() throws Exception {
        InterpreterControl.queue(orderResult(Map.of(
                "receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "items", List.of(
                        withReceiver(
                                item("子牧羊小腿", "2"),
                                receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号")),
                        withReceiver(
                                item("子牧羊小腿", "1"),
                                receiverOf("李四", "13900000000", "北京市朝阳区测试街 2 号"))))));
        String messageId = "MSG-TICKET-06-INDEPENDENT-01";
        postMessage(messageId, 117, null);

        List<Map<String, Object>> drafts = awaitDrafts(messageId, 2);
        Map<String, Object> zhang = drafts.stream()
                .filter(draft -> "张三".equals(draft.get("receiver_name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> li = drafts.stream()
                .filter(draft -> "李四".equals(draft.get("receiver_name")))
                .findFirst()
                .orElseThrow();
        String submissionId = zhang.get("submission_id").toString();

        Map<String, Object> confirmCommand = confirmCommandFor(zhang);
        ResponseEntity<Map> confirmed = postCommand(
                "/api/v1/order-drafts/" + zhang.get("id") + "/confirm",
                confirmCommand,
                "ticket-06-confirm-zhang-0001",
                "req-ticket-06-confirm-zhang-0001");
        assertThat(confirmed.getStatusCode())
                .withFailMessage("confirm response: %s", confirmed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody()).containsEntry("status", "CONFIRMED");
        assertThat(confirmed.getBody().get("confirmed_order_id")).isNotNull();

        Map<String, Object> rejectCommand = Map.of(
                "expected_revision", li.get("revision"),
                "expected_case_version", li.get("review_case_version"),
                "reason", "客户只确认张先生的地址，李四地址取消");
        ResponseEntity<Map> rejected = postCommand(
                "/api/v1/order-drafts/" + li.get("id") + "/reject",
                rejectCommand,
                "ticket-06-reject-li-0001",
                "req-ticket-06-reject-li-0001");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody()).containsEntry("status", "REJECTED");

        // 一个草稿失败/拒绝不影响另一个：各自复核事项独立关闭，提交级状态收敛
        assertThat(get("/api/v1/order-drafts/" + zhang.get("id")))
                .containsEntry("status", "CONFIRMED");
        assertThat(get("/api/v1/order-drafts/" + li.get("id")))
                .containsEntry("status", "REJECTED");
        assertThat(get("/api/v1/message-submissions/" + submissionId))
                .containsEntry("status", "CONFIRMED");
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=req-ticket-06-confirm-zhang-0001&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "ORDER_DRAFT_CONFIRMED"));
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=req-ticket-06-reject-li-0001&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.reject")
                        .containsEntry("business_code", "ORDER_DRAFT_REJECTED"));
    }

    // ------------------------------------------------------------------
    // 解释结果构造
    // ------------------------------------------------------------------

    private InterpretationResult orderResult(Map<String, Object> overrides) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("customer", Map.of("name", "子牧测试客户"));
        output.put("customer_ref", "WECOM-CUSTOMER-001");
        output.put("receiver", receiverOf("张三", "13800000000", "上海市浦东新区测试路 1 号"));
        output.put("settlement_method", "MONTHLY");
        output.put("items", List.of(item("子牧羊小腿", "2")));
        output.putAll(overrides);
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                output,
                "ticket-04-model-provider",
                "ticket-04-model",
                "ticket-04-prompt-v1",
                null);
    }

    private static Map<String, Object> receiverOf(String name, String phone, String address) {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("name", name);
        receiver.put("phone", phone);
        receiver.put("address", address);
        return receiver;
    }

    private static Map<String, Object> item(String product, String quantity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("product", product);
        item.put("spec", "500g/盒");
        item.put("unit", "盒");
        item.put("quantity", quantity);
        item.put("source_sku_ref", "WECOM-SKU-JD-001");
        return item;
    }

    private static Map<String, Object> withReceiver(Map<String, Object> item, Map<String, Object> receiver) {
        Map<String, Object> decorated = new LinkedHashMap<>(item);
        decorated.put("receiver", receiver);
        return decorated;
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private Map<String, Object> awaitSingleDraft(String messageId) {
        return awaitDrafts(messageId, 1).getFirst();
    }

    private List<Map<String, Object>> awaitDrafts(String messageId, int expectedCount) {
        return awaitUntil(
                () -> {
                    String submissionId = submissionForMessage(messageId);
                    if (submissionId == null) {
                        return null;
                    }
                    Map<String, Object> page = get("/api/v1/order-drafts?submission_id=" + submissionId + "&size=20");
                    if (((Number) page.get("total_elements")).longValue() != expectedCount) {
                        return null;
                    }
                    return castMapList(page.get("items"));
                },
                value -> value != null,
                Duration.ofSeconds(12));
    }

    private String submissionForMessage(String messageId) {
        Map<String, Object> message = awaitUntil(
                () -> castMapList(get("/api/v1/channel-messages?size=200").get("items")).stream()
                        .filter(item -> messageId.equals(item.get("message_id")))
                        .findFirst()
                        .orElse(null),
                value -> value != null,
                Duration.ofSeconds(5));
        Map<String, Object> detail = get("/api/v1/channel-messages/" + message.get("id"));
        Object submissionId = detail.get("submission_id");
        return submissionId == null ? null : submissionId.toString();
    }

    private Map<String, Object> confirmCommandFor(Map<String, Object> draft) {
        String customerId = castMapList(draft.get("customer_candidates"))
                .getFirst()
                .get("customer_id")
                .toString();
        Map<String, Object> draftLine = castMapList(draft.get("lines")).getFirst();
        String skuId = castMapList(draftLine.get("sku_candidates"))
                .getFirst()
                .get("sku_id")
                .toString();
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("expected_revision", draft.get("revision"));
        command.put("expected_case_version", draft.get("review_case_version"));
        command.put("customer", Map.of("customer_id", customerId));
        command.put("receiver", Map.of(
                "name", draft.get("receiver_name"),
                "phone", draft.get("receiver_phone"),
                "province", "上海市",
                "city", "上海市",
                "district", "浦东新区",
                "town", "测试街道",
                "address", draft.get("receiver_address")));
        command.put("settlement", Map.of(
                "method", "MONTHLY",
                "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()));
        command.put("items", List.of(Map.of(
                "line_no", draftLine.get("line_no"),
                "sku_id", skuId,
                "quantity", draftLine.get("quantity"))));
        command.put("remark", "ticket 06 独立复核");
        return command;
    }

    private Map<String, Object> onlyOrderDraftReviewCase(String draftId) {
        Map<String, Object> page = get("/api/v1/review-cases?status=OPEN&reason_code=WECOM_ORDER_DRAFT&size=20");
        List<Map<String, Object>> matches = castMapList(page.get("items")).stream()
                .filter(item -> "ORDER_DRAFT".equals(item.get("subject_type")))
                .filter(item -> draftId.equals(item.get("subject_id")))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }

    private void assertNoCanonicalOrderCount(String sourceOrderNo, int expected) {
        Map<String, Object> page = get("/api/v1/orders?source_channel=WECOM&query="
                + URLEncoder.encode(sourceOrderNo, StandardCharsets.UTF_8)
                + "&size=20");
        assertThat(((Number) page.get("total_elements")).longValue()).isEqualTo(expected);
    }

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private ResponseEntity<Map> postCommand(
            String path, Map<String, Object> command, String idempotencyKey, String requestId) {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
        headers.set("X-Operator", ADMIN_USER);
        return headers;
    }

    private void postMessage(String messageId, int randomSuffix, ObjectNode quote) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("msgid", messageId);
        body.put("aibotid", BOT_ID);
        body.put("chatid", ALLOWED_GROUP);
        body.put("chattype", "group");
        body.putObject("from").put("userid", "USER-TICKET-06-" + randomSuffix);
        body.put("msgtype", "text");
        body.putObject("text").put("content", "@OrderBot 子牧测试客户要两盒子牧羊小腿");
        if (quote != null) {
            body.set("quote", quote);
        }
        dispatch(body.toString());
    }

    private ObjectNode quote(String msgType, String content) {
        ObjectNode quote = objectMapper.createObjectNode();
        quote.put("msgtype", msgType);
        quote.putObject("text").put("content", content);
        return quote;
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
                throw new IllegalStateException("interrupted while awaiting ticket 06 result", ex);
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("condition not met within " + timeout + ", last value: " + value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castMapList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
