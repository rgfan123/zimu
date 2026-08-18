package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reinterpretation is a public command boundary: replacing an interpretation must never leave an
 * actionable draft without its one authoritative OPEN ReviewCase.
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.gateway.basic-auth.username=reinterpret-operator",
            "app.gateway.basic-auth.password=reinterpret-password"
        })
class MessageReinterpretationInvariantApiTest {

    private static final String OPERATOR = "reinterpret-operator";
    private static final String PASSWORD = "reinterpret-password";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter reinterpretationInterpreter() {
            return ignored -> InterpreterControl.next();
        }
    }

    static final class InterpreterControl {

        private static final ConcurrentLinkedQueue<InterpretationResult> RESULTS =
                new ConcurrentLinkedQueue<>();

        static void queue(InterpretationResult result) {
            RESULTS.add(result);
        }

        static InterpretationResult next() {
            InterpretationResult result = RESULTS.poll();
            if (result == null) {
                throw new IllegalStateException("reinterpretation test interpreter queue exhausted");
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
    private ObjectMapper objectMapper;

    @Autowired
    private MessageSubmissionService submissionService;

    @Autowired
    private AsyncTaskStore taskStore;

    @Autowired
    private InterpretationService interpretationService;

    @BeforeEach
    void resetInterpreter() {
        InterpreterControl.reset();
    }

    @Test
    void orderDraftToNonBusinessSupersedesDraftAndCaseWithTheAuthenticatedOperator() {
        long submissionId = submitAndInterpret("REINTERPRET-ORDER-NON-BUSINESS-001", customerOrder());
        Map<String, Object> originalDraft = onlyItem("/api/v1/order-drafts?submission_id=" + submissionId);
        assertThat(originalDraft.get("status")).isEqualTo("OPEN");
        String caseId = String.valueOf(originalDraft.get("review_case_id"));
        assertThat(get("/api/v1/review-cases/" + caseId).get("status")).isEqualTo("OPEN");

        InterpreterControl.queue(nonBusiness());
        String requestId = "req-reinterpret-order-non-business-001";
        ResponseEntity<Map> queued = http.exchange(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "reinterpret-order-non-business-001", requestId)),
                Map.class);

        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/order-drafts/" + originalDraft.get("id")))
                .containsEntry("status", "REJECTED");
        assertThat(get("/api/v1/review-cases/" + caseId))
                .containsEntry("status", "DISMISSED")
                .containsEntry("resolved_by", OPERATOR)
                .satisfies(reviewCase -> assertThat(castMap(reviewCase.get("resolution")))
                        .containsEntry("resolution_type", "SUPERSEDED_BY_NEW_INTERPRETATION"));

        Map<String, Object> audit = onlyItem(
                "/api/v1/audit-logs?request_id=" + requestId
                        + "&operation=message_submission.reinterpret&size=20");
        assertThat(audit)
                .containsEntry("operator", OPERATOR)
                .containsEntry("actor_type", "HUMAN")
                .containsEntry("business_code", "MESSAGE_REINTERPRETATION_QUEUED");

        pollWorker();
        assertThat(get("/api/v1/message-submissions/" + submissionId))
                .containsEntry("status", "INTERPRETED")
                .containsEntry("current_intent", "NON_BUSINESS");
        assertThat(get("/api/v1/order-drafts/" + originalDraft.get("id")))
                .containsEntry("status", "REJECTED");
        assertThat(openCasesForSubmission(submissionId)).isEmpty();
    }

    @Test
    void trackingDraftToNonBusinessSupersedesDraftAndCaseAndRejectsTheOldFinalAction() {
        long submissionId = submitAndInterpret(
                "REINTERPRET-TRACKING-NON-BUSINESS-001",
                supplierTracking("SF991000000001"));
        Map<String, Object> originalDraft =
                onlyItem("/api/v1/tracking-drafts?submission_id=" + submissionId);
        assertThat(originalDraft.get("status")).isEqualTo("OPEN");
        String caseId = String.valueOf(originalDraft.get("review_case_id"));
        assertThat(get("/api/v1/review-cases/" + caseId).get("status")).isEqualTo("OPEN");

        InterpreterControl.queue(nonBusiness());
        String requestId = "req-reinterpret-tracking-non-business-001";
        assertThat(reinterpret(
                        submissionId,
                        "reinterpret-tracking-non-business-001",
                        requestId)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertSuperseded(
                "/api/v1/tracking-drafts/" + originalDraft.get("id"),
                caseId,
                originalDraft.get("revision"));
        assertReinterpretationAudit(requestId);
        assertThat(post(
                                "/api/v1/tracking-drafts/" + originalDraft.get("id") + "/confirm",
                                Map.of(
                                        "expected_draft_revision", originalDraft.get("revision"),
                                        "expected_case_version", originalDraft.get("review_case_version")),
                                "confirm-superseded-tracking-non-business-001",
                                "req-confirm-superseded-tracking-non-business-001")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        pollWorker();
        assertThat(get("/api/v1/message-submissions/" + submissionId))
                .containsEntry("status", "INTERPRETED")
                .containsEntry("current_intent", "NON_BUSINESS");
        assertThat(get("/api/v1/tracking-drafts/" + originalDraft.get("id")))
                .containsEntry("status", "REJECTED")
                .containsEntry("confirmed_by", OPERATOR);
        assertThat(openCasesForSubmission(submissionId)).isEmpty();
    }

    @Test
    void orderToTrackingKeepsOnlyTheNewDraftAndCaseOpenAndRejectsTheOldFinalAction() {
        long submissionId = submitAndInterpret(
                "REINTERPRET-ORDER-TO-TRACKING-001",
                customerOrder());
        Map<String, Object> oldDraft = onlyItem("/api/v1/order-drafts?submission_id=" + submissionId);
        String oldCaseId = String.valueOf(oldDraft.get("review_case_id"));

        InterpreterControl.queue(supplierTracking("SF991000000002"));
        String requestId = "req-reinterpret-order-to-tracking-001";
        assertThat(reinterpret(
                        submissionId,
                        "reinterpret-order-to-tracking-001",
                        requestId)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertSuperseded(
                "/api/v1/order-drafts/" + oldDraft.get("id"),
                oldCaseId,
                oldDraft.get("revision"));
        assertReinterpretationAudit(requestId);
        assertThat(post(
                                "/api/v1/order-drafts/" + oldDraft.get("id") + "/reject",
                                Map.of(
                                        "expected_revision", oldDraft.get("revision"),
                                        "expected_case_version", oldDraft.get("review_case_version"),
                                        "reason", "superseded generation must stay terminal"),
                                "reject-superseded-order-to-tracking-001",
                                "req-reject-superseded-order-to-tracking-001")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        pollWorker();
        Map<String, Object> currentDraft =
                onlyOpenItem("/api/v1/tracking-drafts?submission_id=" + submissionId);
        assertAuthoritativeOpenCase(currentDraft, "TRACKING_DRAFT", "CONFIRM_TRACKING_DRAFT");
        assertThat(items("/api/v1/order-drafts?submission_id=" + submissionId))
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("id", oldDraft.get("id"))
                        .containsEntry("status", "REJECTED"));
        assertThat(get("/api/v1/message-submissions/" + submissionId))
                .containsEntry("status", "DRAFTED")
                .containsEntry("current_intent", "SUPPLIER_TRACKING");
    }

    @Test
    void trackingToOrderKeepsOnlyTheNewDraftAndCaseOpenAndRejectsTheOldFinalAction() {
        long submissionId = submitAndInterpret(
                "REINTERPRET-TRACKING-TO-ORDER-001",
                supplierTracking("SF991000000003"));
        Map<String, Object> oldDraft =
                onlyItem("/api/v1/tracking-drafts?submission_id=" + submissionId);
        String oldCaseId = String.valueOf(oldDraft.get("review_case_id"));

        InterpreterControl.queue(customerOrder());
        String requestId = "req-reinterpret-tracking-to-order-001";
        assertThat(reinterpret(
                        submissionId,
                        "reinterpret-tracking-to-order-001",
                        requestId)
                .getStatusCode())
                .isEqualTo(HttpStatus.OK);

        assertSuperseded(
                "/api/v1/tracking-drafts/" + oldDraft.get("id"),
                oldCaseId,
                oldDraft.get("revision"));
        assertReinterpretationAudit(requestId);
        assertThat(post(
                                "/api/v1/tracking-drafts/" + oldDraft.get("id") + "/confirm",
                                Map.of(
                                        "expected_draft_revision", oldDraft.get("revision"),
                                        "expected_case_version", oldDraft.get("review_case_version")),
                                "confirm-superseded-tracking-to-order-001",
                                "req-confirm-superseded-tracking-to-order-001")
                        .getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        pollWorker();
        Map<String, Object> currentDraft =
                onlyOpenItem("/api/v1/order-drafts?submission_id=" + submissionId);
        assertAuthoritativeOpenCase(currentDraft, "ORDER_DRAFT", "CONFIRM_ORDER_DRAFT");
        assertThat(items("/api/v1/tracking-drafts?submission_id=" + submissionId))
                .singleElement()
                .satisfies(item -> assertThat(item)
                        .containsEntry("id", oldDraft.get("id"))
                        .containsEntry("status", "REJECTED"));
        assertThat(get("/api/v1/message-submissions/" + submissionId))
                .containsEntry("status", "DRAFTED")
                .containsEntry("current_intent", "CUSTOMER_ORDER");
    }

    private long submitAndInterpret(String messageId, InterpretationResult result) {
        InterpreterControl.queue(result);
        long submissionId = submissionService.submit(new ChannelMessageCommand(
                "corp-reinterpret-test",
                "connection-reinterpret-test",
                "bot-reinterpret-test",
                messageId,
                "chat-reinterpret-test",
                "group",
                "sender-reinterpret-test",
                "text",
                "message for reinterpretation invariant acceptance",
                null,
                null,
                objectMapper.createObjectNode().put("message_id", messageId)));
        pollWorker();
        return submissionId;
    }

    private void pollWorker() {
        new InterpretationWorker(taskStore, interpretationService, true, 30, 0).poll();
    }

    private static InterpretationResult customerOrder() {
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of(
                        "customer", "reinterpretation customer",
                        "receiver", Map.of(
                                "name", "customer",
                                "phone", "13800000000",
                                "address", "test address"),
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

    private static InterpretationResult nonBusiness() {
        return new InterpretationResult(
                MessageIntent.NON_BUSINESS,
                Map.of("kind", "chat"),
                "test-provider",
                "test-model",
                "prompt-v2",
                null);
    }

    private static InterpretationResult supplierTracking(String trackingNumber) {
        return new InterpretationResult(
                MessageIntent.SUPPLIER_TRACKING,
                Map.of("lines", List.of(Map.of(
                        "name", "reinterpretation customer",
                        "tracking_no", trackingNumber,
                        "carrier", "顺丰速运",
                        "shipment", "FULL"))),
                "test-provider",
                "test-model",
                "prompt-v1",
                null);
    }

    private ResponseEntity<Map> reinterpret(long submissionId, String idempotencyKey, String requestId) {
        return post(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                Map.of(),
                idempotencyKey,
                requestId);
    }

    private void assertSuperseded(String draftPath, String caseId, Object previousRevision) {
        Map<String, Object> retired = get(draftPath);
        assertThat(retired)
                .containsEntry("status", "REJECTED")
                .containsEntry("confirmed_by", OPERATOR);
        assertThat(((Number) retired.get("revision")).longValue())
                .isEqualTo(((Number) previousRevision).longValue() + 1L);
        assertThat(get("/api/v1/review-cases/" + caseId))
                .containsEntry("status", "DISMISSED")
                .containsEntry("resolved_by", OPERATOR)
                .satisfies(reviewCase -> assertThat(castMap(reviewCase.get("resolution")))
                        .containsEntry("resolution_type", "SUPERSEDED_BY_NEW_INTERPRETATION"));
    }

    private void assertReinterpretationAudit(String requestId) {
        Map<String, Object> audit = onlyItem(
                "/api/v1/audit-logs?request_id=" + requestId
                        + "&operation=message_submission.reinterpret&size=20");
        assertThat(audit)
                .containsEntry("operator", OPERATOR)
                .containsEntry("actor_type", "HUMAN")
                .containsEntry("business_code", "MESSAGE_REINTERPRETATION_QUEUED");
    }

    private void assertAuthoritativeOpenCase(
            Map<String, Object> draft,
            String subjectType,
            String requiredAction) {
        String draftId = String.valueOf(draft.get("id"));
        List<Map<String, Object>> openCases = items("/api/v1/review-cases?status=OPEN&size=200")
                .stream()
                .filter(item -> subjectType.equals(item.get("subject_type")))
                .filter(item -> draftId.equals(String.valueOf(item.get("subject_id"))))
                .toList();
        assertThat(openCases).singleElement().satisfies(reviewCase -> {
            assertThat(reviewCase)
                    .containsEntry("id", String.valueOf(draft.get("review_case_id")))
                    .containsEntry("status", "OPEN");
            assertThat(((List<?>) reviewCase.get("allowed_actions"))
                            .stream()
                            .map(String::valueOf)
                            .toList())
                    .contains(requiredAction);
        });
    }

    private Map<String, Object> onlyOpenItem(String path) {
        List<Map<String, Object>> open = items(path).stream()
                .filter(item -> "OPEN".equals(item.get("status")))
                .toList();
        assertThat(open).singleElement();
        return open.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> onlyItem(String path) {
        List<Map<String, Object>> items = items(path);
        assertThat(items).singleElement();
        return items.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(String path) {
        return (List<Map<String, Object>>) get(path).get("items");
    }

    private List<Map<String, Object>> openCasesForSubmission(long submissionId) {
        String submission = String.valueOf(submissionId);
        Set<String> orderDrafts = items("/api/v1/order-drafts?submission_id=" + submission).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> trackingDrafts = items("/api/v1/tracking-drafts?submission_id=" + submission).stream()
                .map(item -> String.valueOf(item.get("id")))
                .collect(java.util.stream.Collectors.toSet());
        return items("/api/v1/review-cases?status=OPEN&size=200").stream()
                .filter(item -> switch (String.valueOf(item.get("subject_type"))) {
                    case "MESSAGE_SUBMISSION" -> submission.equals(String.valueOf(item.get("subject_id")));
                    case "ORDER_DRAFT" -> orderDrafts.contains(String.valueOf(item.get("subject_id")));
                    case "TRACKING_DRAFT" -> trackingDrafts.contains(String.valueOf(item.get("subject_id")));
                    default -> false;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.exchange(
                path, HttpMethod.GET, new HttpEntity<>(readHeaders()), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> post(
            String path,
            Map<String, Object> body,
            String idempotencyKey,
            String requestId) {
        return http.exchange(
                path,
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(idempotencyKey, requestId)),
                Map.class);
    }

    private static HttpHeaders readHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", OPERATOR);
        return headers;
    }

    private static HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(OPERATOR, PASSWORD);
        headers.set("X-Operator", OPERATOR);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
