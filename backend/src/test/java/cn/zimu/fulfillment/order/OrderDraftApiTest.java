package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMessageDispatchHandler;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerCodeGenerator;
import cn.zimu.fulfillment.message.ChannelIdentity;
import cn.zimu.fulfillment.message.ChannelIdentityService;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.InterpretationInput;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageInterpreter;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.card.CardSendAction;
import cn.zimu.fulfillment.order.card.OrderDraftCard;
import cn.zimu.fulfillment.order.card.OrderDraftCardStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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

/**
 * Ticket 04 public seam: long-connection intake ({@link WecomMessageDispatchHandler}) →
 * CUSTOMER_ORDER interpretation → versioned draft and one review case. PostgreSQL is real;
 * only the model adapter is replaced.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderDraftApiTest {

    private static final String BOT_ID = "AIBOT-TICKET-04";
    private static final String ALLOWED_GROUP = "CHAT-TICKET-04";
    private static final String ADMIN_USER = "ticket-04-reviewer";
    private static final String ADMIN_PASSWORD = "ticket-04-admin-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void ticketConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.poll-ms", () -> "100");
        registry.add("app.message-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.lease-seconds", () -> "10");
        registry.add("app.wecom-tracking-file-worker.enabled", () -> "false");
        registry.add("app.wecom-export-worker.enabled", () -> "false");
        registry.add("app.wecom-reminder.enabled", () -> "false");
        registry.add("app.wecom-notification.enabled", () -> "false");
        registry.add("app.wecom-order-draft-card.enabled", () -> "false");
        registry.add("app.agent-worker.enabled", () -> "false");
        registry.add("app.gateway.basic-auth.username", () -> ADMIN_USER);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter ticket04Interpreter() {
            return InterpreterControl::next;
        }

    }

    static final class InterpreterControl {

        private static final ArrayBlockingQueue<InterpretationResult> RESULTS = new ArrayBlockingQueue<>(16);

        static void queue(InterpretationResult result) {
            RESULTS.offer(result);
        }

        static InterpretationResult next(InterpretationInput ignored) {
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("ticket 04 interpreter queue exhausted");
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

    @Autowired
    private ReviewCaseRepository reviewCases;

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private ChannelIdentityService channelIdentityService;

    @Autowired
    private CustomerCodeGenerator customerCodeGenerator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OrderDraftCardStore orderDraftCards;

    @BeforeEach
    void resetInterpreter() {
        InterpreterControl.reset();
    }

    @Test
    void completeCustomerOrderInterpretationCreatesOneVersionedDraftAndOneReviewCase() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-DRAFT-01";
        postEncryptedMessage(messageId, 1);

        Map<String, Object> draft = awaitDraftForMessage(messageId);
        assertThat(draft)
                .containsEntry("status", "OPEN")
                .containsEntry("revision", 0)
                .containsEntry("customer_name_raw", "子牧测试客户")
                .containsEntry("receiver_name", "张三")
                .containsEntry("receiver_phone", "13800000000")
                .containsEntry("receiver_address", "上海市浦东新区测试路 1 号")
                .containsEntry("settlement_method", "MONTHLY")
                .containsEntry("settlement_time", "2026-08-31T16:00:00Z");
        assertThat(draft.get("draft_no").toString()).startsWith("OD-");
        assertThat(draft.get("source_order_no").toString()).startsWith("WECOM-SUB-");
        assertThat(castList(draft.get("missing_fields"))).isEmpty();

        List<Map<String, Object>> customerCandidates = castMapList(draft.get("customer_candidates"));
        assertThat(customerCandidates).hasSize(1);
        assertThat(customerCandidates.getFirst().get("customer_id")).isNotEqualTo("999999991");

        List<Map<String, Object>> lines = castMapList(draft.get("lines"));
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst())
                .containsEntry("line_no", 1)
                .containsEntry("product_name_raw", "子牧羊小腿")
                .containsEntry("quantity", "2.000");
        List<Map<String, Object>> skuCandidates = castMapList(lines.getFirst().get("sku_candidates"));
        assertThat(skuCandidates).hasSize(1);
        assertThat(skuCandidates.getFirst().get("sku_id")).isNotEqualTo("999999992");
        assertThat(skuCandidates.getFirst().get("provider_id")).isNotEqualTo("999999993");

        Map<String, Object> reviewCase = onlyOrderDraftReviewCase(draft.get("id").toString());
        assertThat(reviewCase)
                .containsEntry("status", "OPEN")
                .containsEntry("responsible_team", "ORDER_OPS")
                .containsEntry("reason_code", "WECOM_ORDER_DRAFT")
                .containsEntry("subject_type", "ORDER_DRAFT")
                .containsEntry("subject_id", draft.get("id"));
        assertThat(castList(reviewCase.get("allowed_actions")))
                .containsExactly("CONFIRM_ORDER_DRAFT", "REJECT_ORDER_DRAFT");
        assertThat(draft.get("review_case_id")).isEqualTo(reviewCase.get("id"));
        assertThat(draft.get("review_case_version")).isEqualTo(0);

        Map<String, Object> reviewCaseDetail = get("/api/v1/review-cases/" + reviewCase.get("id"));
        Map<String, Object> modelOutput = castMap(castMap(reviewCaseDetail.get("detail")).get("model_output"));
        assertThat(modelOutput).containsOnlyKeys("customer", "receiver", "items");
        assertThat(castMapList(modelOutput.get("items")).getFirst())
                .containsOnlyKeys("product", "spec", "unit", "quantity", "source_sku_ref");
        assertThat(objectMapper.writeValueAsString(reviewCaseDetail))
                .doesNotContain(
                        "999999991",
                        "999999992",
                        "999999993",
                        "ticket-04-secret-must-not-serialize",
                        "model-channel-identity-must-not-bind",
                        "OD-MODEL-MUST-NOT-APPEND");

        Map<String, Object> submission = get("/api/v1/message-submissions/" + draft.get("submission_id"));
        assertThat(objectMapper.writeValueAsString(submission))
                .doesNotContain(
                        "structured_output",
                        "999999991",
                        "999999992",
                        "999999993",
                        "ticket-04-secret-must-not-serialize",
                        "model-channel-identity-must-not-bind",
                        "OD-MODEL-MUST-NOT-APPEND");
    }

    @Test
    void templateCardClickUsesTheHumanConfirmationPathAndDuplicateDoesNotCreateAnotherOrder() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String sourceMessageId = "MSG-TICKET-87-CARD-DRAFT";
        postEncryptedMessage(sourceMessageId, 87);
        Map<String, Object> draft = awaitDraftForMessage(sourceMessageId);
        String draftId = draft.get("id").toString();
        OrderDraftCard outboundCard = orderDraftCards.create(
                Long.parseLong(draftId), ((Number) draft.get("revision")).longValue());
        assertThat(orderDraftCards.beginSend(outboundCard.id()).action())
                .isEqualTo(CardSendAction.SEND);
        orderDraftCards.recordSent(
                outboundCard.id(), "REQ-TICKET-87-OUTBOUND-ACK", Instant.now());
        String cardEvent = "{\"cmd\":\"aibot_event_callback\","
                + "\"headers\":{\"req_id\":\"REQ-TICKET-88-UPDATE\"},\"body\":{"
                + "\"msgid\":\"EVT-TICKET-87-CONFIRM\",\"create_time\":1787486400,"
                + "\"aibotid\":\"" + BOT_ID + "\",\"chatid\":\"" + ALLOWED_GROUP + "\","
                + "\"chattype\":\"group\",\"from\":{\"userid\":\"ticket-87-operator\"},"
                + "\"msgtype\":\"event\",\"event\":{\"eventtype\":\"template_card_event\","
                + "\"template_card_event\":{\"event_key\":\"confirm_order\","
                + "\"task_id\":\"order-draft:" + draftId + "\"}}}}";
        JsonNode eventFrame = objectMapper.readTree(cardEvent);

        wecomDispatchHandler.onFrame("aibot_event_callback", eventFrame);

        Map<String, Object> confirmed = get("/api/v1/order-drafts/" + draftId);
        assertThat(confirmed)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("confirmed_by", "wecom:ticket-87-operator")
                .containsKey("confirmed_order_id");
        assertThat(canonicalOrders(draft.get("source_order_no").toString()))
                .containsEntry("total_elements", 1);
        Map<String, Object> persistedEvent = jdbc.queryForMap(
                """
                SELECT processing_status, business_code, processed_by, processing_attempt,
                       processing_claim_token::text AS processing_claim_token,
                       update_status, update_error_code, fallback_status, fallback_error_code,
                       task_id, order_draft_id::text AS order_draft_id
                FROM app.wecom_events
                WHERE event_type='template_card_event' AND msgid='EVT-TICKET-87-CONFIRM'
                """);
        assertThat(persistedEvent)
                .containsEntry("processing_status", "CONFIRMED")
                .containsEntry("business_code", "ORDER_DRAFT_CONFIRMED")
                .containsEntry("processed_by", "wecom:ticket-87-operator")
                .containsEntry("processing_attempt", 1)
                .containsEntry("update_status", "FAILED")
                .containsEntry("update_error_code", "CONNECTION_NOT_READY")
                .containsEntry("fallback_status", "FAILED")
                .containsEntry("fallback_error_code", "CONNECTION_NOT_READY")
                .containsEntry("task_id", "order-draft:" + draftId)
                .containsEntry("order_draft_id", draftId);
        assertThat(persistedEvent.get("processing_claim_token")).isNotNull();

        wecomDispatchHandler.onFrame("aibot_event_callback", eventFrame);

        assertThat(canonicalOrders(draft.get("source_order_no").toString()))
                .containsEntry("total_elements", 1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.wecom_events "
                                + "WHERE event_type='template_card_event' AND msgid='EVT-TICKET-87-CONFIRM'",
                        Long.class))
                .isEqualTo(1);
    }

    @Test
    void modelDraftNumberCannotAppendToAnExistingOpenDraft() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        postEncryptedMessage("MSG-TICKET-04-DRAFT-OWNER", 5);
        Map<String, Object> original = awaitDraftForMessage("MSG-TICKET-04-DRAFT-OWNER");
        String originalDraftNo = original.get("draft_no").toString();
        String originalDraftId = original.get("id").toString();
        long originalRevision = ((Number) original.get("revision")).longValue();
        int originalLines = castMapList(original.get("lines")).size();

        // 票 06 起只有有效的系统草稿号才允许追加；模型伪造的草稿号必须按新建处理，绝不吞单。
        InterpreterControl.queue(customerOrderResult("OD-FORGED-MUST-NOT-APPEND"));
        InterpreterControl.queue(customerOrderResult(originalDraftNo));
        postEncryptedMessage("MSG-TICKET-04-FORGED-APPEND", 6);
        Map<String, Object> separate = awaitDraftForMessage("MSG-TICKET-04-FORGED-APPEND");

        assertThat(separate.get("id")).isNotEqualTo(originalDraftId);
        assertThat(separate.get("draft_no")).isNotEqualTo(originalDraftNo);
        Map<String, Object> unchanged = get("/api/v1/order-drafts/" + originalDraftId);
        assertThat(unchanged).containsEntry("status", "OPEN");
        assertThat(((Number) unchanged.get("revision")).longValue()).isEqualTo(originalRevision);
        assertThat(castMapList(unchanged.get("lines"))).hasSize(originalLines);
    }

    @Test
    void confirmationRequiresExactlyOneCustomerChoiceAndNeverCreatesOneFromRequestText() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        postEncryptedMessage("MSG-TICKET-05-NO-CUSTOMER-CREATE", 7);
        Map<String, Object> draft = awaitDraftForMessage("MSG-TICKET-05-NO-CUSTOMER-CREATE");
        long customersBefore = customersTotal();

        // 既没有 customer_id 也没有 new_customer_name：明确拒绝，不创建客户、不产生订单。
        Map<String, Object> emptyChoice = confirmationCommand(draft);
        emptyChoice.put("customer", Map.of());
        ResponseEntity<Map> rejected = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                emptyChoice,
                "ticket-05-no-customer-choice-0001",
                "req-ticket-05-no-customer-choice-0001");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody()).containsEntry("business_code", "CUSTOMER_REQUIRED");

        // 同时提供 customer_id 与 new_customer_name：二义性选择，明确拒绝，不创建客户。
        Map<String, Object> ambiguous = confirmationCommand(draft);
        ambiguous.put("customer", Map.of(
                "customer_id", castMapList(draft.get("customer_candidates")).getFirst().get("customer_id"),
                "new_customer_name", "模型请求创建的客户"));
        ResponseEntity<Map> ambiguousResponse = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                ambiguous,
                "ticket-05-ambiguous-customer-0001",
                "req-ticket-05-ambiguous-customer-0001");
        assertThat(ambiguousResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(ambiguousResponse.getBody()).containsEntry("business_code", "CUSTOMER_CHOICE_AMBIGUOUS");

        assertThat(customersTotal()).isEqualTo(customersBefore);
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
        assertThat(get("/api/v1/order-drafts/" + draft.get("id"))).containsEntry("status", "OPEN");
    }

    @Test
    void confirmationRequiresAnExactUniqueProjectionOfEveryPersistedDraftLine() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-LINE-PROJECTION-01";
        postEncryptedMessage(messageId, 8);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        Map<String, Object> command = confirmationCommand(draft);
        Map<String, Object> line = new java.util.LinkedHashMap<>(castMapList(command.get("items")).getFirst());

        command.put("items", List.of(line, new java.util.LinkedHashMap<>(line)));
        String duplicateRequestId = "req-ticket-04-duplicate-line-0001";
        ResponseEntity<Map> duplicate = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                "ticket-04-duplicate-line-0001",
                duplicateRequestId);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicate.getBody()).containsEntry("business_code", "DRAFT_LINES_MISMATCH");

        Map<String, Object> extra = new java.util.LinkedHashMap<>(line);
        extra.put("line_no", 99);
        command.put("items", List.of(line, extra));
        ResponseEntity<Map> extraLine = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                "ticket-04-extra-line-0001",
                "req-ticket-04-extra-line-0001");
        assertThat(extraLine.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(extraLine.getBody()).containsEntry("business_code", "DRAFT_LINES_MISMATCH");

        assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                .containsEntry("status", "OPEN");
        assertThat(onlyOrderDraftReviewCase(draft.get("id").toString()))
                .containsEntry("status", "OPEN");
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=" + duplicateRequestId + "&size=20").get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "DRAFT_LINES_MISMATCH")
                        .containsEntry("http_status", 422));
    }

    @Test
    void reviewerCreatesNewCustomerDuringConfirmationWithSystemGeneratedCode() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-05-CREATE-CUSTOMER-01";
        postEncryptedMessage(messageId, 21);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        String caseId = draft.get("review_case_id").toString();
        long customersBefore = customersTotal();
        Map<String, Object> command = confirmationCommand(draft);
        command.put("customer", Map.of("new_customer_name", "新客户-张三-20260814"));

        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                "ticket-05-create-customer-0001",
                "req-ticket-05-create-customer-0001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> confirmed = get("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(confirmed).containsEntry("status", "CONFIRMED");
        String createdCode = confirmed.get("customer_code").toString();
        assertThat(createdCode).startsWith("CUST-WECOM-");
        assertThat(customersTotal()).isEqualTo(customersBefore + 1);

        Map<String, Object> search = get("/api/v1/customers?query=新客户-张三-20260814&size=20");
        assertThat(castMapList(search.get("items")))
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("code", createdCode)
                        .containsEntry("name", "新客户-张三-20260814"));

        Map<String, Object> resolution = castMap(get("/api/v1/review-cases/" + caseId).get("resolution"));
        assertThat(resolution)
                .containsEntry("resolution_type", "ORDER_DRAFT_CONFIRMED")
                .containsEntry("customer_code", createdCode);
        assertThat(canonicalOrders(draft.get("source_order_no").toString()))
                .containsEntry("total_elements", 1);
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=req-ticket-05-create-customer-0001&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "ORDER_DRAFT_CONFIRMED"));
    }

    @Test
    void messageEntryWithRealCustomerIdentityBindsExactlyOneCustomerOnConfirmation() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String externalUserId = "EXTERNAL-USER-TICKET-05-01";
        String accessType = "WECOM_CUSTOMER_CONTACT";
        submitCustomerIdentityMessage("MSG-TICKET-05-BIND-01", externalUserId, accessType);
        Map<String, Object> draft = awaitDraftForMessage("MSG-TICKET-05-BIND-01");
        String caseId = draft.get("review_case_id").toString();
        String chosenCustomerId =
                castMapList(draft.get("customer_candidates")).getFirst().get("customer_id").toString();

        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmationCommand(draft),
                "ticket-05-bind-identity-0001",
                "req-ticket-05-bind-identity-0001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                "SELECT customer_id::text FROM app.channel_identities "
                        + "WHERE corp_id=? AND access_type=? AND channel_identity=?",
                String.class,
                BOT_ID,
                accessType,
                externalUserId))
                .isEqualTo(chosenCustomerId);

        // 已绑定身份的后续消息可经 findBound 带出唯一客户候选（一期仍须人工确认订单）。
        Optional<ChannelIdentity> bound = channelIdentityService.findBound(BOT_ID, accessType, externalUserId);
        assertThat(bound).isPresent();
        assertThat(bound.get().getCustomerId()).isEqualTo(Long.valueOf(chosenCustomerId));
        Map<String, Object> resolution = castMap(get("/api/v1/review-cases/" + caseId).get("resolution"));
        assertThat(resolution).containsEntry("channel_identity_id", bound.get().getId().toString());
    }

    @Test
    void forwardedEmployeeMessageNeverCreatesChannelIdentityBinding() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-05-EMPLOYEE-FORWARD-01";
        postEncryptedMessage(messageId, 22);
        Map<String, Object> draft = awaitDraftForMessage(messageId);

        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmationCommand(draft),
                "ticket-05-employee-forward-0001",
                "req-ticket-05-employee-forward-0001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 普通微信群转发员工只有传输身份：确认成单但不建立任何渠道身份绑定。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.channel_identities WHERE corp_id=? AND channel_identity=?",
                Integer.class,
                BOT_ID,
                "USER-TICKET-04"))
                .isZero();
    }

    @Test
    void conflictingChannelIdentityBindingRejectsConfirmationAndLeavesAuditEvidence() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String externalUserId = "EXTERNAL-USER-CONFLICT-05-01";
        String accessType = "WECOM_CUSTOMER_CONTACT";
        submitCustomerIdentityMessage("MSG-TICKET-05-CONFLICT-01", externalUserId, accessType);
        Map<String, Object> draft = awaitDraftForMessage("MSG-TICKET-05-CONFLICT-01");

        // 该渠道身份已由其他业务线绑定到另一客户：确认事务必须拒绝并审计。
        Customer other = customerCodeGenerator.createBusinessCustomer("占用客户-20260814");
        channelIdentityService.bind(BOT_ID, accessType, externalUserId, other.getId(), Map.of());
        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmationCommand(draft),
                "ticket-05-bind-conflict-0001",
                "req-ticket-05-bind-conflict-0001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("business_code", "CHANNEL_IDENTITY_CONFLICT");
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
        assertThat(get("/api/v1/order-drafts/" + draft.get("id"))).containsEntry("status", "OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT customer_id::text FROM app.channel_identities "
                        + "WHERE corp_id=? AND access_type=? AND channel_identity=?",
                String.class,
                BOT_ID,
                accessType,
                externalUserId))
                .isEqualTo(other.getId().toString());
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=req-ticket-05-bind-conflict-0001&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "CHANNEL_IDENTITY_CONFLICT")
                        .containsEntry("http_status", 409));
    }

    @Test
    void concurrentConfirmationsCreatingNewCustomerCreateExactlyOneCustomerAndOrder() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-05-CONCURRENT-CREATE-01";
        postEncryptedMessage(messageId, 23);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        long customersBefore = customersTotal();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Map>> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                Map<String, Object> command = confirmationCommand(draft);
                command.put("customer", Map.of("new_customer_name", "并发新客户-20260814"));
                return postCommand(
                        "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                        command,
                        "ticket-05-concurrent-create-a",
                        "req-ticket-05-concurrent-create-a");
            });
            Future<ResponseEntity<Map>> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                Map<String, Object> command = confirmationCommand(draft);
                command.put("customer", Map.of("new_customer_name", "并发新客户-20260814"));
                return postCommand(
                        "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                        command,
                        "ticket-05-concurrent-create-b",
                        "req-ticket-05-concurrent-create-b");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(first.get(15, TimeUnit.SECONDS).getStatusCode().value()),
                    HttpStatus.valueOf(second.get(15, TimeUnit.SECONDS).getStatusCode().value()));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }

        // 并发创建只落一个客户与一个订单；客户编码由系统生成。
        assertThat(customersTotal()).isEqualTo(customersBefore + 1);
        assertThat(canonicalOrders(draft.get("source_order_no").toString()))
                .containsEntry("total_elements", 1);
        Map<String, Object> confirmed = get("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(confirmed).containsEntry("status", "CONFIRMED");
        assertThat(confirmed.get("customer_code").toString()).startsWith("CUST-WECOM-");
    }

    @Test
    void spoofedOperatorWithoutAuthenticatedGatewayCredentialsCannotConfirmDraft()
            throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-SPOOFED-OPERATOR";
        postEncryptedMessage(messageId, 13);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        String requestId = "req-ticket-04-spoofed-operator-0001";

        ResponseEntity<Map> response = postCommandWithoutGatewayCredentials(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmationCommand(draft),
                "ticket-04-spoofed-operator-0001",
                requestId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "ORDER_DRAFT_OPERATOR_UNAUTHORIZED");
        assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                .containsEntry("status", "OPEN");
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
        assertThat(castMapList(get("/api/v1/audit-logs?request_id=" + requestId + "&size=20")
                        .get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "ORDER_DRAFT_OPERATOR_UNAUTHORIZED")
                        .containsEntry("http_status", 403)
                        .containsEntry("operator", "unauthenticated"));
    }

    @Test
    void emptyDraftCannotBeTurnedIntoAZeroLineCanonicalOrder() throws Exception {
        InterpreterControl.queue(customerOrderResultWithoutItems());
        String messageId = "MSG-TICKET-04-ZERO-LINE-01";
        postEncryptedMessage(messageId, 9);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        assertThat(castMapList(draft.get("lines"))).isEmpty();

        String customerId = castMapList(draft.get("customer_candidates"))
                .getFirst()
                .get("customer_id")
                .toString();
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("expected_revision", ((Number) draft.get("revision")).longValue());
        command.put("expected_case_version", ((Number) draft.get("review_case_version")).longValue());
        command.put("customer", Map.of("customer_id", customerId));
        command.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800000000",
                "province", "上海市",
                "city", "上海市",
                "district", "浦东新区",
                "town", "测试街道",
                "address", "测试路 1 号"));
        command.put("settlement", Map.of(
                "method", "MONTHLY",
                "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()));
        command.put("items", List.of(Map.of("line_no", 1, "sku_id", "1", "quantity", "1")));

        ResponseEntity<Map> response = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                "ticket-04-zero-line-0001",
                "req-ticket-04-zero-line-0001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "DRAFT_LINES_MISMATCH");
        assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                .containsEntry("status", "OPEN");
        assertThat(onlyOrderDraftReviewCase(draft.get("id").toString()))
                .containsEntry("status", "OPEN");
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
    }

    @Test
    void confirmationRejectsAReviewCaseWithTheWrongBusinessReason() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-WRONG-CASE-REASON";
        postEncryptedMessage(messageId, 10);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        Map<String, Object> visibleCase = onlyOrderDraftReviewCase(draft.get("id").toString());
        ReviewCase malformed = reviewCases.findById(Long.parseLong(visibleCase.get("id").toString()))
                .orElseThrow();
        malformed.setReasonCode("WECOM_NEED_REVIEW");
        reviewCases.saveAndFlush(malformed);
        try {
            Map<String, Object> refreshed = get("/api/v1/order-drafts/" + draft.get("id"));
            ResponseEntity<Map> rejected = postCommand(
                    "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                    confirmationCommand(refreshed),
                    "ticket-04-wrong-case-reason-0001",
                    "req-ticket-04-wrong-case-reason-0001");

            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(rejected.getBody())
                    .containsEntry("business_code", "DRAFT_REVIEW_CASE_REASON_INVALID");
            assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                    .containsEntry("status", "OPEN");
            assertNoCanonicalOrder(draft.get("source_order_no").toString());
        } finally {
            ReviewCase current = reviewCases.findById(malformed.getId()).orElseThrow();
            current.setReasonCode("WECOM_ORDER_DRAFT");
            reviewCases.saveAndFlush(current);
        }
    }

    @Test
    void confirmationRejectsWhenMoreThanOneOpenReviewCaseOwnsTheDraft() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-DUPLICATE-CASE";
        postEncryptedMessage(messageId, 11);
        Map<String, Object> draft = awaitDraftForMessage(messageId);

        ReviewCase duplicate = new ReviewCase();
        duplicate.setCaseNo("RC-WECOM-TICKET04-DUP");
        duplicate.setCaseType("WECOM_DRAFT");
        duplicate.setStatus(ReviewCaseStatus.OPEN);
        duplicate.setResponsibleTeam("ORDER_OPS");
        duplicate.setReasonCode("WECOM_NEED_REVIEW");
        duplicate.setOrderDraftId(Long.parseLong(draft.get("id").toString()));
        duplicate.setDetail(Map.of("fixture", "duplicate-open-case"));
        Long duplicateId = reviewCases.saveAndFlush(duplicate).getId();
        try {
            ResponseEntity<Map> rejected = postCommand(
                    "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                    confirmationCommand(draft),
                    "ticket-04-duplicate-case-0001",
                    "req-ticket-04-duplicate-case-0001");

            assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(rejected.getBody())
                    .containsEntry("business_code", "DRAFT_REVIEW_CASE_MISSING");
            assertThat(get("/api/v1/order-drafts/" + draft.get("id")))
                    .containsEntry("status", "OPEN");
            assertNoCanonicalOrder(draft.get("source_order_no").toString());
        } finally {
            reviewCases.deleteById(duplicateId);
            reviewCases.flush();
        }
    }

    @Test
    void inactiveCustomerAndSkuMappingsNeverBecomeDraftCandidates() throws Exception {
        Map<String, Object> customer = castMapList(get("/api/v1/customers?query=CUST-WECOM-0001&size=20")
                        .get("items"))
                .stream()
                .filter(item -> "CUST-WECOM-0001".equals(item.get("code")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> sku = castMapList(get("/api/v1/skus?size=200").get("items"))
                .stream()
                .filter(item -> "500g/盒".equals(castMap(item.get("attributes")).get("specification")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> disabledCustomer = null;
        Map<String, Object> disabledSku = null;
        try {
            ResponseEntity<Map> customerPatch = patchCommand(
                    "/api/v1/customers/" + customer.get("id"),
                    Map.of("expected_version", customer.get("version"), "active", false),
                    "ticket-04-disable-customer-0001",
                    "req-ticket-04-disable-customer-0001");
            assertThat(customerPatch.getStatusCode()).isEqualTo(HttpStatus.OK);
            disabledCustomer = customerPatch.getBody();

            ResponseEntity<Map> skuPatch = patchCommand(
                    "/api/v1/skus/" + sku.get("id"),
                    Map.of("expected_version", sku.get("version"), "active", false),
                    "ticket-04-disable-sku-0001",
                    "req-ticket-04-disable-sku-0001");
            assertThat(skuPatch.getStatusCode()).isEqualTo(HttpStatus.OK);
            disabledSku = skuPatch.getBody();

            InterpreterControl.queue(customerOrderResult());
            String messageId = "MSG-TICKET-04-INACTIVE-CANDIDATES";
            postEncryptedMessage(messageId, 12);
            Map<String, Object> draft = awaitDraftForMessage(messageId);

            assertThat(castMapList(draft.get("customer_candidates"))).isEmpty();
            assertThat(castMapList(castMapList(draft.get("lines")).getFirst().get("sku_candidates")))
                    .isEmpty();
            assertThat(castList(draft.get("missing_fields")))
                    .contains("customer", "line_1_sku");
        } finally {
            if (disabledSku != null) {
                ResponseEntity<Map> restored = patchCommand(
                        "/api/v1/skus/" + disabledSku.get("id"),
                        Map.of("expected_version", disabledSku.get("version"), "active", true),
                        "ticket-04-restore-sku-0001",
                        "req-ticket-04-restore-sku-0001");
                assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            if (disabledCustomer != null) {
                ResponseEntity<Map> restored = patchCommand(
                        "/api/v1/customers/" + disabledCustomer.get("id"),
                        Map.of("expected_version", disabledCustomer.get("version"), "active", true),
                        "ticket-04-restore-customer-0001",
                        "req-ticket-04-restore-customer-0001");
                assertThat(restored.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }
    }

    @Test
    void reviewerConfirmsCompleteDraftOnceAndCanQueryTheCanonicalOrder() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-CONFIRM-01";
        postEncryptedMessage(messageId, 2);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        Map<String, Object> reviewCase = onlyOrderDraftReviewCase(draft.get("id").toString());

        String customerId = castMapList(draft.get("customer_candidates"))
                .getFirst()
                .get("customer_id")
                .toString();
        Map<String, Object> draftLine = castMapList(draft.get("lines")).getFirst();
        String skuId = castMapList(draftLine.get("sku_candidates"))
                .getFirst()
                .get("sku_id")
                .toString();
        Map<String, Object> command = Map.of(
                "expected_revision", ((Number) draft.get("revision")).longValue(),
                "expected_case_version", ((Number) draft.get("review_case_version")).longValue(),
                "customer", Map.of("customer_id", customerId),
                "receiver", Map.of(
                        "name", "张三",
                        "phone", "13800000000",
                        "province", "上海市",
                        "city", "上海市",
                        "district", "浦东新区",
                        "town", "测试街道",
                        "address", "测试路 1 号"),
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()),
                "items", List.of(Map.of("line_no", 1, "sku_id", skuId, "quantity", "3")),
                "remark", "已对照企微原始消息和主数据");
        String idempotencyKey = "ticket-04-confirm-once-0001";
        String requestId = "req-ticket-04-confirm-once-0001";

        ResponseEntity<Map> confirmed = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                idempotencyKey,
                requestId);
        assertThat(confirmed.getStatusCode())
                .withFailMessage("confirm response: %s", confirmed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody())
                .containsEntry("status", "CONFIRMED")
                .containsEntry("customer_id", customerId)
                .containsEntry("receiver_address", "测试路 1 号")
                .containsEntry("confirmed_by", "ticket-04-reviewer");
        assertThat(castMapList(confirmed.getBody().get("lines")).getFirst())
                .containsEntry("sku_id", skuId)
                .containsEntry("quantity", "3");
        String orderId = confirmed.getBody().get("confirmed_order_id").toString();

        ResponseEntity<Map> replayed = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                command,
                idempotencyKey,
                "req-ticket-04-confirm-replay-0001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).containsEntry("confirmed_order_id", orderId);

        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(order)
                .containsEntry("source_channel", "WECOM")
                .containsEntry("source_ref", draft.get("source_order_no"))
                .containsEntry("customer_id", customerId)
                .containsEntry("receiver_name", "张三")
                .containsEntry("order_status", "SKU_MAPPED");
        List<Map<String, Object>> orderLines = castMapList(order.get("lines"));
        assertThat(orderLines).hasSize(1);
        assertThat(orderLines.getFirst())
                .containsEntry("sku_id", skuId)
                .containsEntry("product_name", "子牧羊小腿")
                .containsEntry("requested_quantity", "3.000");
        assertThat(orderLines.getFirst().get("provider_id")).isNotEqualTo("999999993");

        Map<String, Object> resolvedCase = get("/api/v1/review-cases/" + reviewCase.get("id"));
        assertThat(resolvedCase)
                .containsEntry("status", "RESOLVED")
                .containsEntry("resolved_by", "ticket-04-reviewer");
        assertThat(castMap(resolvedCase.get("resolution")))
                .containsEntry("resolution_type", "ORDER_DRAFT_CONFIRMED")
                .containsEntry("order_id", orderId);

        List<Map<String, Object>> timeline = castMapList(getList("/api/v1/orders/" + orderId + "/timeline"));
        assertThat(timeline.stream().map(item -> item.get("event_type_code")).toList())
                .contains("ORDER_RECEIVED", "SKU_MAPPED", "ORDER_DRAFT_CONFIRMED");
        List<Map<String, Object>> versions = castMapList(getList("/api/v1/orders/" + orderId + "/versions"));
        assertThat(versions).hasSizeGreaterThanOrEqualTo(2);

        Map<String, Object> auditPage = get("/api/v1/audit-logs?request_id=" + requestId + "&size=20");
        assertThat(castMapList(auditPage.get("items")))
                .anySatisfy(item -> assertThat(item)
                        .containsEntry("operation", "order_draft.confirm")
                        .containsEntry("business_code", "ORDER_DRAFT_CONFIRMED")
                        .containsEntry("order_id", orderId));
    }

    @Test
    void staleConfirmationCreatesNoOrderAndReviewerCanExplicitlyRejectTheDraft() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-REJECT-01";
        postEncryptedMessage(messageId, 3);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        Map<String, Object> reviewCase = onlyOrderDraftReviewCase(draft.get("id").toString());
        Map<String, Object> confirmCommand = confirmationCommand(draft);
        confirmCommand.put("expected_revision", ((Number) draft.get("revision")).longValue() + 1);

        ResponseEntity<Map> stale = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                confirmCommand,
                "ticket-04-stale-confirm-0001",
                "req-ticket-04-stale-confirm-0001");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        Map<String, Object> stillOpen = get("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(stillOpen)
                .containsEntry("status", "OPEN")
                .containsEntry("revision", draft.get("revision"))
                .containsEntry("review_case_version", draft.get("review_case_version"));
        assertNoCanonicalOrder(draft.get("source_order_no").toString());

        Map<String, Object> rejectCommand = Map.of(
                "expected_revision", ((Number) draft.get("revision")).longValue(),
                "expected_case_version", ((Number) draft.get("review_case_version")).longValue(),
                "reason", "客户已明确取消这次需求");
        String idempotencyKey = "ticket-04-reject-once-0001";
        ResponseEntity<Map> rejected = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/reject",
                rejectCommand,
                idempotencyKey,
                "req-ticket-04-reject-once-0001");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody())
                .containsEntry("status", "REJECTED")
                .containsEntry("confirmed_by", "ticket-04-reviewer");

        ResponseEntity<Map> replayed = postCommand(
                "/api/v1/order-drafts/" + draft.get("id") + "/reject",
                rejectCommand,
                idempotencyKey,
                "req-ticket-04-reject-replay-0001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).containsEntry("status", "REJECTED");

        Map<String, Object> dismissedCase = get("/api/v1/review-cases/" + reviewCase.get("id"));
        assertThat(dismissedCase)
                .containsEntry("status", "DISMISSED")
                .containsEntry("resolved_by", "ticket-04-reviewer");
        assertThat(castMap(dismissedCase.get("resolution")))
                .containsEntry("resolution_type", "ORDER_DRAFT_REJECTED")
                .containsEntry("reason", "客户已明确取消这次需求");
        assertNoCanonicalOrder(draft.get("source_order_no").toString());
    }

    @Test
    void concurrentConfirmationsCreateExactlyOneCanonicalOrder() throws Exception {
        InterpreterControl.queue(customerOrderResult());
        String messageId = "MSG-TICKET-04-CONCURRENT-01";
        postEncryptedMessage(messageId, 4);
        Map<String, Object> draft = awaitDraftForMessage(messageId);
        Map<String, Object> command = confirmationCommand(draft);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Map>> first = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postCommand(
                        "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                        command,
                        "ticket-04-concurrent-confirm-a",
                        "req-ticket-04-concurrent-confirm-a");
            });
            Future<ResponseEntity<Map>> second = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return postCommand(
                        "/api/v1/order-drafts/" + draft.get("id") + "/confirm",
                        command,
                        "ticket-04-concurrent-confirm-b",
                        "req-ticket-04-concurrent-confirm-b");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<HttpStatus> statuses = List.of(
                    HttpStatus.valueOf(first.get(15, TimeUnit.SECONDS).getStatusCode().value()),
                    HttpStatus.valueOf(second.get(15, TimeUnit.SECONDS).getStatusCode().value()));
            assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
        }

        Map<String, Object> orderPage = canonicalOrders(draft.get("source_order_no").toString());
        assertThat(orderPage).containsEntry("total_elements", 1);
        Map<String, Object> confirmed = get("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(confirmed)
                .containsEntry("status", "CONFIRMED")
                .containsKey("confirmed_order_id");
    }

    private InterpretationResult customerOrderResult() {
        return customerOrderResult("OD-MODEL-MUST-NOT-APPEND");
    }

    private InterpretationResult customerOrderResult(String modelDraftNo) {
        Map<String, Object> output = Map.of(
                "customer", Map.of("name", "子牧测试客户"),
                "customer_ref", "WECOM-CUSTOMER-001",
                "customer_id", "999999991",
                "receiver", Map.of(
                        "name", "张三",
                        "phone", "13800000000",
                        "address", "上海市浦东新区测试路 1 号"),
                "settlement_method", "MONTHLY",
                "settlement_time", "2026-08-31T16:00:00Z",
                "secret_token", "ticket-04-secret-must-not-serialize",
                "channel_identity", Map.of(
                        "corp_id", "model-corp-must-not-bind",
                        "access_type", "EXTERNAL_CONTACT",
                        "channel_identity", "model-channel-identity-must-not-bind"),
                "draft_no", modelDraftNo,
                "items", List.of(Map.of(
                        "product", "子牧羊小腿",
                        "spec", "500g/盒",
                        "unit", "盒",
                        "quantity", "2",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "sku_id", "999999992",
                        "fulfillment_provider_id", "999999993")));
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                output,
                "ticket-04-model-provider",
                "ticket-04-model",
                "ticket-04-prompt-v1",
                null);
    }

    private InterpretationResult customerOrderResultWithoutItems() {
        InterpretationResult base = customerOrderResult();
        Map<String, Object> output = new java.util.LinkedHashMap<>(base.structuredOutput());
        output.put("items", List.of());
        return new InterpretationResult(
                base.intent(),
                output,
                base.provider(),
                base.model(),
                base.promptVersion(),
                base.error());
    }

    private Map<String, Object> confirmationCommand(Map<String, Object> draft) {
        String customerId = castMapList(draft.get("customer_candidates"))
                .getFirst()
                .get("customer_id")
                .toString();
        Map<String, Object> draftLine = castMapList(draft.get("lines")).getFirst();
        String skuId = castMapList(draftLine.get("sku_candidates"))
                .getFirst()
                .get("sku_id")
                .toString();
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("expected_revision", ((Number) draft.get("revision")).longValue());
        command.put("expected_case_version", ((Number) draft.get("review_case_version")).longValue());
        command.put("customer", Map.of("customer_id", customerId));
        command.put("receiver", Map.of(
                "name", "张三",
                "phone", "13800000000",
                "province", "上海市",
                "city", "上海市",
                "district", "浦东新区",
                "town", "测试街道",
                "address", "测试路 1 号"));
        command.put("settlement", Map.of(
                "method", "MONTHLY",
                "settlement_time", Instant.parse("2026-08-31T16:00:00Z").toString()));
        command.put("items", List.of(Map.of("line_no", 1, "sku_id", skuId, "quantity", "3")));
        command.put("remark", "已对照企微原始消息和主数据");
        return command;
    }

    private long customersTotal() {
        return ((Number) get("/api/v1/customers?size=200").get("total_elements")).longValue();
    }

    /**
     * 模拟未来"客户联系/微信客服"入口：直接经应用边界提交带真实客户渠道身份的证据，
     * 不经过当前群机器人长连接（该连接只提供转发员工传输身份）。
     */
    private void submitCustomerIdentityMessage(String messageId, String senderUserId, String accessType) {
        String plaintext = "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + BOT_ID + "\","
                + "\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"" + senderUserId + "\"},"
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"@OrderBot 子牧测试客户要两盒子牧羊小腿\"}}";
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_msg_callback");
        try {
            frame.set("body", objectMapper.readTree(plaintext));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        submissionService.submit(new ChannelMessageCommand(
                BOT_ID,
                "wecom-customer-contact",
                BOT_ID,
                messageId,
                "single:" + senderUserId,
                "single",
                senderUserId,
                "text",
                "@OrderBot 子牧测试客户要两盒子牧羊小腿",
                null,
                null,
                frame,
                ChannelIdentityService.SENDER_IDENTITY_CUSTOMER,
                accessType));
    }

    private void assertNoCanonicalOrder(String sourceOrderNo) {
        assertThat(canonicalOrders(sourceOrderNo)).containsEntry("total_elements", 0);
    }

    private Map<String, Object> canonicalOrders(String sourceOrderNo) {
        return get("/api/v1/orders?source_channel=WECOM&query="
                + URLEncoder.encode(sourceOrderNo, StandardCharsets.UTF_8)
                + "&size=20");
    }

    private Map<String, Object> awaitDraftForMessage(String messageId) {
        Map<String, Object> message = awaitUntil(
                () -> castMapList(get("/api/v1/channel-messages?size=200").get("items")).stream()
                        .filter(item -> messageId.equals(item.get("message_id")))
                        .findFirst()
                        .orElse(null),
                value -> value != null,
                Duration.ofSeconds(5));
        Map<String, Object> messageDetail = get("/api/v1/channel-messages/" + message.get("id"));
        String submissionId = messageDetail.get("submission_id").toString();
        Map<String, Object> page = awaitUntil(
                () -> get("/api/v1/order-drafts?submission_id=" + submissionId + "&size=20"),
                value -> ((Number) value.get("total_elements")).longValue() == 1,
                Duration.ofSeconds(12));
        return castMapList(page.get("items")).getFirst();
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

    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private Object getList(String path) {
        ResponseEntity<Object> response = http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(adminHeaders()), Object.class);
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

    private ResponseEntity<Map> postCommandWithoutGatewayCredentials(
            String path, Map<String, Object> command, String idempotencyKey, String requestId) {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.remove(HttpHeaders.AUTHORIZATION);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(command, headers), Map.class);
    }

    private ResponseEntity<Map> patchCommand(
            String path, Map<String, Object> command, String idempotencyKey, String requestId) {
        HttpHeaders headers = adminHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        return http.exchange(path, HttpMethod.PATCH, new HttpEntity<>(command, headers), Map.class);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
        headers.set("X-Operator", ADMIN_USER);
        return headers;
    }

    /** 长连接接收接缝：把企微消息帧交给 {@link WecomMessageDispatchHandler}（原 HTTP 加密回调已被长连接替换）。 */
    private void postEncryptedMessage(String messageId, int randomSuffix) throws Exception {
        String plaintext = "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + BOT_ID + "\","
                + "\"chatid\":\"" + ALLOWED_GROUP + "\","
                + "\"chattype\":\"group\","
                + "\"from\":{\"userid\":\"USER-TICKET-04\"},"
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"@OrderBot 子牧测试客户要两盒子牧羊小腿\"}}";
        dispatch(plaintext);
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
                throw new IllegalStateException("interrupted while awaiting ticket 04 result", ex);
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
