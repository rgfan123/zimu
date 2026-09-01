package cn.zimu.fulfillment.fulfillment;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 票 08/09/10 主验收接缝：任务号/脱敏姓名/前缀候选、默认整项发货、部分/缺货/异常人工数量、
 * 单条确认（SHIPPED 且无伪发货时间）、重复/并发拒绝与批量逐行成功/失败。
 *
 * <p>通过真实 PostgreSQL + 长连接接收接缝（{@link WecomMessageDispatchHandler}）/管理 API 验证；
 * 模型只在 {@link MessageInterpreter} 边界替换。测试共享数据库，一律用 before/after 计数或唯一标识断言。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-tracking-draft-test")
class TrackingDraftApiTest {

    private static final String BOT_ID = "AIBOT-ORDER-OPS";
    private static final String ALLOWED_GROUP = "CHAT-ORDER-OPS";
    private static final String OPERATOR = "tracking-ops";
    private static final String ADMIN_PASSWORD = "tracking-ops-admin-password";

    /**
     * 本机 Docker 不稳定（Testcontainers 容器会被 Docker Desktop 不定期 SIGKILL）时，
     * 可设置 APP_TEST_DB_URL/APP_TEST_DB_USERNAME/APP_TEST_DB_PASSWORD 改用外部 PostgreSQL 验证；
     * 未设置时行为与 @Container + @ServiceConnection 完全一致（同库名、同账号、同端口映射）。
     */
    private static final String EXTERNAL_DB_URL = System.getenv("APP_TEST_DB_URL");
    private static final String EXTERNAL_DB_USERNAME = System.getenv("APP_TEST_DB_USERNAME");
    private static final String EXTERNAL_DB_PASSWORD = System.getenv("APP_TEST_DB_PASSWORD");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void startPostgres() {
        if (EXTERNAL_DB_URL == null) {
            postgres.start();
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (EXTERNAL_DB_URL == null) {
            postgres.stop();
        }
    }

    @DynamicPropertySource
    static void trackingDraftConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.scheduling.enabled", () -> "true");
        registry.add("app.message-worker.enabled", () -> "true");
        registry.add("app.message-worker.poll-ms", () -> "100");
        registry.add("app.message-worker.backoff-seconds", () -> "1");
        registry.add("app.message-worker.lease-seconds", () -> "10");
        registry.add("app.gateway.basic-auth.username", () -> OPERATOR);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
        if (EXTERNAL_DB_URL == null) {
            registry.add("spring.datasource.url", () -> postgres.getJdbcUrl());
            registry.add("spring.datasource.username", () -> postgres.getUsername());
            registry.add("spring.datasource.password", () -> postgres.getPassword());
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_DB_URL);
            registry.add("spring.datasource.username", () -> EXTERNAL_DB_USERNAME);
            registry.add("spring.datasource.password", () -> EXTERNAL_DB_PASSWORD);
        }
        registry.add("app.carrier-prefixes.carriers.SF_EXPRESS.name", () -> "顺丰速运");
        registry.add("app.carrier-prefixes.carriers.SF_EXPRESS.enabled", () -> "true");
        registry.add("app.carrier-prefixes.carriers.JD.name", () -> "京东物流");
        registry.add("app.carrier-prefixes.carriers.JD.enabled", () -> "true");
        registry.add("app.carrier-prefixes.carriers.JDVA_EXPRESS.name", () -> "京东亚洲一号仓配");
        registry.add("app.carrier-prefixes.carriers.JDVA_EXPRESS.enabled", () -> "true");
        registry.add("app.carrier-prefixes.carriers.DISABLED_CARRIER.name", () -> "停用物流");
        registry.add("app.carrier-prefixes.carriers.DISABLED_CARRIER.enabled", () -> "false");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MessageInterpreter testInterpreter() {
            return TrackingInterpreterControl::next;
        }

    }

    /** 测试替身解释器：先注入结果队列，Worker 依次消费（本测试专用，不与既有测试类重名）。 */
    static final class TrackingInterpreterControl {

        private static final java.util.concurrent.ArrayBlockingQueue<InterpretationResult> QUEUE =
                new java.util.concurrent.ArrayBlockingQueue<>(64);

        static void queue(InterpretationResult result) {
            QUEUE.offer(result);
        }

        static InterpretationResult next(InterpretationInput input) {
            InterpretationResult result = QUEUE.poll();
            if (result == null) {
                throw new IllegalStateException("test interpreter queue exhausted");
            }
            return result;
        }

        static void reset() {
            QUEUE.clear();
        }
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WecomMessageDispatchHandler wecomDispatchHandler;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetInterpreterAndAddSourceMappings() {
        TrackingInterpreterControl.reset();
        jdbc.update(
                """
                INSERT INTO app.carrier_prefix_mappings(prefix, carrier_code) VALUES
                    ('JDVA', 'JDVA_EXPRESS'), ('DISABLED', 'DISABLED_CARRIER')
                ON CONFLICT (prefix) DO UPDATE SET carrier_code=excluded.carrier_code
                """);
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT customer_id, 'FEIXIANG', 'FX-MEMBER-TRACKING'
                FROM app.customer_source_refs WHERE source_channel='WECOM'
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-TRACKING', '子牧羊小腿', '标准箱', 1.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    // ------------------------------------------------------------------
    // 票 08：任务号候选与整项发货
    // ------------------------------------------------------------------

    @Test
    void spoofedOperatorWithoutAuthenticatedGatewayCredentialsCannotConfirmTrackingDraft()
            throws Exception {
        Map<String, Object> order = createThirdPartyOrder(
                "TRK-TASK-SPOOFED-OPERATOR-001", "鉴权客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-SPOOFED-OPERATOR-001");
        pendingShipmentFacts("TRK-TASK-SPOOFED-OPERATOR-001");
        Map<String, Object> draft = singleDraft(sendTracking(
                "MSG-TRACKING-SPOOFED-OPERATOR-01",
                61,
                lines(line(
                        "鉴权客户",
                        "SF123456AUTH01",
                        facts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null))));
        String key = "confirm-spoofed-operator-001";

        ResponseEntity<Map> response = confirmWithoutGatewayCredentials(draft, key, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .containsEntry("business_code", "TRACKING_DRAFT_OPERATOR_UNAUTHORIZED");
        assertThat(detail(draft.get("id").toString())).containsEntry("status", "OPEN");
        assertThat(shipmentOf(order))
                .containsEntry("shipment_status", "CREATED")
                .containsEntry("tracking", null);
        assertThat((List<?>) get("/api/v1/audit-logs?request_id=req-" + key).get("items"))
                .anySatisfy(item -> {
                    Map<?, ?> audit = (Map<?, ?>) item;
                    assertThat(audit.get("operation")).isEqualTo("tracking_draft.confirm");
                    assertThat(audit.get("business_code"))
                            .isEqualTo("TRACKING_DRAFT_OPERATOR_UNAUTHORIZED");
                    assertThat(audit.get("http_status")).isEqualTo(403);
                    assertThat(audit.get("operator")).isEqualTo("unauthenticated");
                });
    }

    @Test
    void taskNumberUniqueMatchBringsTaskIdAndDefaultsToFullShipment() throws Exception {
        Map<String, Object> order = createThirdPartyOrder("TRK-TASK-UNIQUE-001", "张三", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-UNIQUE-001");
        Map<String, Object> pendingShipment = pendingShipmentFacts("TRK-TASK-UNIQUE-001");
        String taskNo = (String) facts.get("fulfillment_no");

        Map<String, Object> submission = sendTracking(
                "MSG-TASK-UNIQUE-01",
                1,
                lines(line("张三", "SF123456001", taskNo, "顺丰速运", null)));
        Map<String, Object> draft = singleDraft(submission);

        assertThat(draft.get("status")).isEqualTo("OPEN");
        assertThat(draft.get("review_case_version")).isEqualTo(0);
        assertThat(draft.get("task_id")).isEqualTo(String.valueOf(facts.get("fulfillment_id")));
        assertThat(((List<?>) draft.get("task_candidates"))).singleElement().satisfies(candidate ->
                assertThat(((Map<?, ?>) candidate).get("shipment_id"))
                        .isEqualTo(String.valueOf(pendingShipment.get("shipment_id"))));
        assertThat(draft.get("carrier_code")).isEqualTo("SF_EXPRESS");
        assertThat(draft.get("shipment_judgment")).isEqualTo("FULL");
        // 页面清楚展示“默认该任务全部指令数量已发”的约定
        assertThat(draft.get("default_full_shipment")).isEqualTo(true);
        assertThat((List<?>) draft.get("validation_issues")).isEmpty();

        ResponseEntity<Map> confirmed = confirm(draft, "confirm-task-unique-001", Map.of());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> refreshed = detail(String.valueOf(draft.get("id")));
        assertThat(refreshed.get("status")).isEqualTo("CONFIRMED");
        assertThat(refreshed.get("confirmed_by")).isEqualTo(OPERATOR);
        assertThat(refreshed.get("actual_quantity")).isEqualTo(2);
        assertThat(refreshed.get("review_case_id")).isNull();
        assertThat(refreshed.get("review_case_version")).isNull();
        assertThat(submissionDetail("MSG-TASK-UNIQUE-01").get("status")).isEqualTo("CONFIRMED");

        Map<String, Object> shipment = shipmentOf(order);
        assertThat(shipment)
                .containsEntry("id", String.valueOf(pendingShipment.get("shipment_id")))
                .containsEntry("shipment_status", "SHIPPED")
                .containsEntry("shipped_at", null);
        Map<?, ?> tracking = (Map<?, ?>) shipment.get("tracking");
        assertThat(tracking.get("tracking_number")).isEqualTo("SF123456001");
        assertThat(tracking.get("received_at")).isNotNull();

        assertTrackingCaseResolved(draft, "TRACKING_CONFIRMED");
        assertFactsAndAudit(order, "confirm-task-unique-001");
    }

    @Test
    void historicalTaskCandidateDecimalStringsAreProjectedAsJsonIntegers() throws Exception {
        createThirdPartyOrder("TRK-TASK-HISTORY-001", "历史草稿客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-HISTORY-001");
        String taskNo = (String) facts.get("fulfillment_no");
        Map<String, Object> draft = singleDraft(sendTracking(
                "MSG-TASK-HISTORY-01",
                1,
                lines(line("历史草稿客户", "SF123456009", taskNo, "顺丰速运", null))));
        long draftId = Long.parseLong(draft.get("id").toString());
        jdbc.update(
                """
                UPDATE app.provider_tracking_drafts
                SET task_candidates=(
                    SELECT jsonb_agg(
                        jsonb_set(
                            jsonb_set(
                                jsonb_set(candidate, '{requested_quantity}',
                                    to_jsonb((candidate->>'requested_quantity') || '.000')),
                                '{shipped_quantity}',
                                to_jsonb((candidate->>'shipped_quantity') || '.000')),
                            '{instructed_quantity}',
                            to_jsonb((candidate->>'instructed_quantity') || '.000'))
                        ORDER BY ordinal)
                    FROM jsonb_array_elements(task_candidates)
                         WITH ORDINALITY AS legacy(candidate, ordinal))
                WHERE id=?
                """,
                draftId);

        Map<?, ?> candidate = (Map<?, ?>) ((List<?>) detail(Long.toString(draftId)).get("task_candidates"))
                .getFirst();
        assertThat(candidate.get("requested_quantity")).isEqualTo(2).isInstanceOf(Integer.class);
        assertThat(candidate.get("shipped_quantity")).isEqualTo(0).isInstanceOf(Integer.class);
        assertThat(candidate.get("instructed_quantity")).isEqualTo(2).isInstanceOf(Integer.class);
    }

    @Test
    void invalidOrNotApplicableTaskNumberGoesToManualProcessing() throws Exception {
        createThirdPartyOrder("TRK-TASK-INVALID-001", "李四", "2.000");
        Map<String, Object> jdOrder = createJdOrder("TRK-TASK-JD-002", "钱七", "1");
        String jdTaskNo = (String) fulfillmentFacts("TRK-TASK-JD-002").get("fulfillment_no");

        Map<String, Object> missing = sendTracking(
                "MSG-TASK-NOTFOUND-01",
                1,
                lines(line("李四", "SF123456002", "FL-NOPE-1", null, null)));
        Map<String, Object> missingDraft = singleDraft(missing);
        assertThat(missingDraft.get("task_id")).isNull();
        assertThat((List<?>) missingDraft.get("task_candidates")).isEmpty();
        assertThat((List<String>) missingDraft.get("validation_issues")).contains("TASK_NOT_FOUND");
        // 无效任务号必须人工选择后才可确认
        ResponseEntity<Map> withoutTask = confirm(missingDraft, "confirm-task-missing-001", Map.of());
        assertThat(withoutTask.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withoutTask.getBody().get("business_code")).isEqualTo("TASK_REQUIRED");
        ResponseEntity<Map> unknownExactTask = confirm(
                missingDraft,
                "confirm-task-missing-exact-001",
                Map.of("task_no", "FL-NOPE-1"));
        assertThat(unknownExactTask.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unknownExactTask.getBody().get("business_code")).isEqualTo("TASK_NOT_FOUND");
        Map<?, ?> rejectedSelection = (Map<?, ?>) auditDetail(
                        "confirm-task-missing-exact-001", "tracking_draft.confirm")
                .get("request_payload");
        assertThat(rejectedSelection.get("task_no_choice_present")).isEqualTo(true);
        assertThat(rejectedSelection.containsKey("task_no")).isFalse();

        Map<String, Object> notApplicable = sendTracking(
                "MSG-TASK-NA-01",
                2,
                lines(line("钱七", "SF123456003", jdTaskNo, null, null)));
        Map<String, Object> jdDraft = singleDraft(notApplicable);
        assertThat(jdDraft.get("task_id")).isNull();
        assertThat((List<String>) jdDraft.get("validation_issues")).contains("TASK_NOT_APPLICABLE");
        ResponseEntity<Map> outOfScopeExactTask = confirm(
                jdDraft,
                "confirm-task-not-applicable-exact-001",
                Map.of("task_no", jdTaskNo));
        assertThat(outOfScopeExactTask.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(outOfScopeExactTask.getBody().get("business_code")).isEqualTo("TASK_NOT_APPLICABLE");

        // 人工改选正确的第三方任务后确认成功
        Map<String, Object> rightTask = fulfillmentFacts("TRK-TASK-INVALID-001");
        ResponseEntity<Map> manual = confirm(
                missingDraft, "confirm-task-manual-001", Map.of("task_id", String.valueOf(rightTask.get("fulfillment_id"))));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) manual.getBody().get("task_id"))
                .isEqualTo(String.valueOf(rightTask.get("fulfillment_id")));
    }

    @Test
    void stuckTrackingDraftCanBeRejectedAndClosedWithReason() throws Exception {
        createThirdPartyOrder("TRK-REJECT-001", "王五", "1.000");
        Map<String, Object> missing = sendTracking(
                "MSG-REJECT-01",
                1,
                lines(line("王五", "SF123456010", "FL-REJECT-NOPE", null, null)));
        Map<String, Object> draft = singleDraft(missing);
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NOT_FOUND");

        ResponseEntity<Map> confirmAttempt = confirm(
                draft, "confirm-reject-draft-001", Map.of("task_no", "FL-REJECT-NOPE"));
        assertThat(confirmAttempt.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(confirmAttempt.getBody().get("business_code")).isEqualTo("TASK_NOT_FOUND");

        long rejectCaseVersion = caseVersion(draft);
        ResponseEntity<Map> rejected = rejectDraft(
                draft, "reject-draft-001", rejectCaseVersion, "任务号不存在，来源信息有误，退回");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status")).isEqualTo("REJECTED");
        assertThat(rejected.getBody().get("review_case_id")).isNull();

        Map<String, Object> reviewCase = caseOf(draft);
        assertThat(reviewCase.get("status")).isEqualTo("DISMISSED");
        assertThat(reviewCase.get("resolved_by")).isEqualTo(OPERATOR);
        Map<?, ?> resolution = (Map<?, ?>) reviewCase.get("resolution");
        assertThat(resolution.get("resolution_type")).isEqualTo("TRACKING_DRAFT_REJECTED");
        assertThat(resolution.get("reason")).isEqualTo("任务号不存在，来源信息有误，退回");

        ResponseEntity<Map> replayed = rejectDraft(
                draft, "reject-draft-001", rejectCaseVersion, "任务号不存在，来源信息有误，退回");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(rejected.getBody());

        ResponseEntity<Map> again = rejectDraft(
                draft, "reject-draft-again-001", caseVersion(draft), "再次拒绝");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(again.getBody().get("business_code")).isEqualTo("DRAFT_NOT_OPEN");

        createThirdPartyOrder("TRK-REJECT-002", "赵六", "1.000");
        Map<String, Object> missingReason = sendTracking(
                "MSG-REJECT-02",
                2,
                lines(line("赵六", "SF123456011", "FL-REJECT-NOPE-2", null, null)));
        Map<String, Object> draftNoReason = singleDraft(missingReason);
        HttpHeaders noReasonHeaders = writeHeaders("reject-no-reason-001", "req-reject-no-reason-001");
        ResponseEntity<Map> noReason = http.exchange(
                "/api/v1/tracking-drafts/" + draftNoReason.get("id") + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_draft_revision", ((Number) draftNoReason.get("revision")).longValue(),
                        "expected_case_version", caseVersion(draftNoReason)),
                        noReasonHeaders),
                Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        Map<?, ?> rejectAudit = auditDetail("reject-draft-001", "tracking_draft.reject");
        assertThat(rejectAudit.get("business_code")).isEqualTo("TRACKING_DRAFT_REJECTED");
        Map<?, ?> requestPayload = (Map<?, ?>) rejectAudit.get("request_payload");
        assertThat(requestPayload.get("reason_present")).isEqualTo(true);
        assertThat(requestPayload.containsKey("reason")).isFalse();
    }

    @Test
    void readyToExportTaskWithoutAnExistingShipmentCannotBeLinkedOrConfirmed() throws Exception {
        createUnexportedThirdPartyOrder("TRK-TASK-NOT-EXPORTED-001", "未导出客户", "2");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-NOT-EXPORTED-001");
        assertThat(jdbc.queryForObject(
                        "SELECT processing_stage FROM app.order_lines WHERE id=?",
                        String.class,
                        facts.get("order_line_id")))
                .isEqualTo("READY_TO_EXPORT");

        Map<String, Object> submission = sendTracking(
                "MSG-TASK-NOT-EXPORTED-01",
                1,
                lines(line(
                        "未导出客户",
                        "SF123456004",
                        facts.get("fulfillment_no").toString(),
                        null,
                        null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isNull();
        assertThat((List<?>) draft.get("task_candidates")).isEmpty();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NOT_APPLICABLE");

        ResponseEntity<Map> rejected = confirm(
                draft,
                "confirm-not-exported-001",
                Map.of("task_id", facts.get("fulfillment_id").toString()));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_INVALID");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.shipments WHERE order_id=?",
                        Long.class,
                        facts.get("order_id")))
                .isZero();
    }

    @Test
    void sharedShipmentWithTwoPendingItemsStaysInManualReview() throws Exception {
        Map<String, Object> order = createThirdPartyOrderWithTwoLines(
                "TRK-TASK-SHARED-SHIPMENT-001", "合票客户", "1.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-SHARED-SHIPMENT-001");
        Map<String, Object> shipment = shipmentOf(order);
        assertThat((List<?>) shipment.get("items")).hasSize(2);

        Map<String, Object> submission = sendTracking(
                "MSG-TASK-SHARED-SHIPMENT-01",
                1,
                lines(line(
                        "合票客户",
                        "SF1234560041",
                        facts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NOT_APPLICABLE");

        ResponseEntity<Map> rejected = confirm(
                draft,
                "confirm-shared-shipment-001",
                Map.of("task_id", facts.get("fulfillment_id").toString()));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_INVALID");
        assertThat(shipmentOf(order))
                .containsEntry("shipment_status", "CREATED")
                .containsEntry("tracking", null);
    }

    @Test
    void sharedShipmentWithAnySiblingItemStaysInManualReview() throws Exception {
        Map<String, Object> order = createThirdPartyOrderWithTwoLines(
                "TRK-TASK-SHARED-SHIPMENT-MIXED-001", "混合合票客户", "1.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-SHARED-SHIPMENT-MIXED-001");
        Map<String, Object> shipment = shipmentOf(order);
        List<?> items = (List<?>) shipment.get("items");
        assertThat(items).hasSize(2);
        Long siblingId = jdbc.queryForObject(
                """
                SELECT max(si.id)
                FROM app.shipment_items si
                JOIN app.shipments s ON s.id=si.shipment_id
                WHERE s.id=?
                """,
                Long.class,
                Long.valueOf(shipment.get("id").toString()));
        jdbc.update("UPDATE app.shipment_items SET shipped_quantity=instructed_quantity WHERE id=?", siblingId);

        Map<String, Object> draft = singleDraft(sendTracking(
                "MSG-TASK-SHARED-SHIPMENT-MIXED-01",
                1,
                lines(line(
                        "混合合票客户",
                        "SF1234560042",
                        facts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null))));

        assertThat(draft.get("task_id")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NOT_APPLICABLE");
        ResponseEntity<Map> rejected = confirm(
                draft,
                "confirm-shared-shipment-mixed-001",
                Map.of("task_id", facts.get("fulfillment_id").toString()));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_INVALID");
    }

    @Test
    void confirmationRequiresTheOnlyOpenCaseToBeTheTrackingDraftCase() throws Exception {
        createThirdPartyOrder("TRK-CASE-GATE-001", "事项门禁客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-CASE-GATE-001");
        Map<String, Object> draft = singleDraft(sendTracking(
                "MSG-CASE-GATE-01",
                1,
                lines(line(
                        "事项门禁客户",
                        "SF1234560043",
                        facts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null))));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     provider_tracking_draft_id, detail)
                VALUES (?, 'WECOM_DRAFT', 'OPEN', 'ORDER_OPS', 'WECOM_ORDER_CHANGE', ?, '{}'::jsonb)
                """,
                "RC-TRACKING-WRONG-" + draft.get("id"),
                Long.valueOf(draft.get("id").toString()));

        ResponseEntity<Map> rejected = confirm(draft, "confirm-case-gate-001", Map.of());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("CASE_NOT_OPEN");
        assertThat(detail(String.valueOf(draft.get("id"))))
                .containsEntry("status", "OPEN")
                .containsEntry("review_case_id", null)
                .containsEntry("review_case_version", null);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.trackings WHERE tracking_number='SF1234560043'",
                        Long.class))
                .isZero();
        assertThat(((List<?>) get("/api/v1/audit-logs?request_id=req-confirm-case-gate-001&size=20")
                                .get("items"))
                        .stream()
                        .map(item -> (Map<?, ?>) item)
                        .anyMatch(item -> "CASE_NOT_OPEN".equals(item.get("business_code"))
                                && Integer.valueOf(409).equals(item.get("http_status"))))
                .isTrue();
    }

    @Test
    void multiplePendingShipmentsForOneTaskRemainAmbiguousAndCreateNoTracking() throws Exception {
        createUnexportedThirdPartyOrder("TRK-TASK-MULTI-SHIP-001", "多批次客户", "2");
        Map<String, Object> facts = fulfillmentFacts("TRK-TASK-MULTI-SHIP-001");
        long firstShipmentId = addPendingShipment("TRK-TASK-MULTI-SHIP-001", 1, "1.000");
        long secondShipmentId = addPendingShipment("TRK-TASK-MULTI-SHIP-001", 2, "1.000");

        Map<String, Object> submission = sendTracking(
                "MSG-TASK-MULTI-SHIP-01",
                1,
                lines(line(
                        "多批次客户",
                        "SF123456005",
                        facts.get("fulfillment_no").toString(),
                        null,
                        null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_SHIPMENT_MULTI_MATCH");
        assertThat(((List<?>) draft.get("task_candidates")).stream()
                        .map(candidate -> String.valueOf(((Map<?, ?>) candidate).get("shipment_id")))
                        .toList())
                .containsExactlyInAnyOrder(String.valueOf(firstShipmentId), String.valueOf(secondShipmentId));

        ResponseEntity<Map> rejected = confirm(
                draft,
                "confirm-multi-shipment-001",
                Map.of("task_id", facts.get("fulfillment_id").toString()));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_SHIPMENT_AMBIGUOUS");
        ResponseEntity<Map> rejectedByExactTaskNo = confirm(
                draft,
                "confirm-multi-shipment-task-no-001",
                Map.of("task_no", facts.get("fulfillment_no").toString()));
        assertThat(rejectedByExactTaskNo.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejectedByExactTaskNo.getBody().get("business_code"))
                .isEqualTo("TASK_SHIPMENT_MULTI_MATCH");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.trackings WHERE tracking_number='SF123456005'",
                        Long.class))
                .isZero();
    }

    @Test
    void unknownNonBlankShipmentJudgmentNeverDefaultsToFullShipment() throws Exception {
        Map<String, Object> order = createThirdPartyOrder("TRK-JUDGMENT-UNKNOWN-001", "未知判断客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-JUDGMENT-UNKNOWN-001");

        Map<String, Object> submission = sendTracking(
                "MSG-JUDGMENT-UNKNOWN-01",
                1,
                lines(line(
                        "未知判断客户",
                        "SF1234560051",
                        facts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        "PARTAIL")));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("default_full_shipment")).isEqualTo(false);
        assertThat((List<String>) draft.get("validation_issues"))
                .contains("SHIPMENT_JUDGMENT_INVALID");

        ResponseEntity<Map> rejected = confirm(draft, "confirm-unknown-judgment-001", Map.of());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("SHIPMENT_JUDGMENT_INVALID");
        assertThat(shipmentOf(order))
                .containsEntry("shipment_status", "CREATED")
                .containsEntry("tracking", null);
    }

    @Test
    void reinterpretationAppendsANewDraftNumberAndKeepsSubmissionDrafted() throws Exception {
        createThirdPartyOrder("TRK-REINTERPRET-001", "重新解释客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-REINTERPRET-001");
        String messageId = "MSG-TRACKING-REINTERPRET-01";
        Map<String, Object> submission = sendTracking(
                messageId,
                1,
                lines(line(
                        "重新解释客户",
                        "SF123456006",
                        facts.get("fulfillment_no").toString(),
                        null,
                        null)));

        TrackingInterpreterControl.queue(new InterpretationResult(
                MessageIntent.SUPPLIER_TRACKING,
                lines(line(
                        "重新解释客户",
                        "SF123456007",
                        facts.get("fulfillment_no").toString(),
                        null,
                        null)),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null));
        HttpHeaders headers = writeHeaders("reinterpret-tracking-001", "req-reinterpret-tracking-001");
        ResponseEntity<Map> queued = http.exchange(
                "/api/v1/message-submissions/" + submission.get("id") + "/reinterpret",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(queued.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> drafts = awaitUntil(
                () -> draftsOf(submission),
                items -> items.size() == 2,
                Duration.ofSeconds(15));
        assertThat(drafts.stream().map(item -> item.get("draft_no")).toList())
                .containsExactlyInAnyOrder(
                        "TD-" + submission.get("id") + "-1",
                        "TD-" + submission.get("id") + "-2");
        assertThat(submissionDetail(messageId).get("status")).isEqualTo("DRAFTED");

        Map<String, Object> current = drafts.stream()
                .max(java.util.Comparator.comparingInt(item -> ((Number) item.get("line_no")).intValue()))
                .orElseThrow();
        ResponseEntity<Map> confirmed = confirm(current, "confirm-reinterpreted-tracking-001", Map.of());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submissionDetail(messageId).get("status"))
                .as("a superseded OPEN draft without an OPEN review case must not keep the submission DRAFTED")
                .isEqualTo("CONFIRMED");
    }

    @Test
    void crossFamilyReinterpretationDismissesStaleCasesAndSerializesFinalActions() throws Exception {
        Map<String, Object> trackingOrder =
                createThirdPartyOrder("TRK-CROSS-FAMILY-CONFIRM-001", "跨族确认客户", "1.000");
        Map<String, Object> trackingFacts = fulfillmentFacts("TRK-CROSS-FAMILY-CONFIRM-001");
        String confirmMessageId = "MSG-CROSS-FAMILY-ORDER-TO-TRACKING-01";
        Map<String, Object> orderSubmission = sendCustomerOrder(confirmMessageId, 1);
        Map<String, Object> staleOrderDraft = singleOrderDraft(orderSubmission);
        Map<String, Object> staleOrderCase = getCase(staleOrderDraft.get("review_case_id").toString());
        assertThat(staleOrderCase.get("status")).isEqualTo("OPEN");

        TrackingInterpreterControl.queue(new InterpretationResult(
                MessageIntent.SUPPLIER_TRACKING,
                lines(line(
                        "跨族确认客户",
                        "SF123459901",
                        trackingFacts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null)),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null));
        reinterpret(orderSubmission, "reinterpret-order-to-tracking-001");
        Map<String, Object> currentTrackingDraft = awaitUntil(
                        () -> draftsOf(orderSubmission),
                        items -> items.size() == 1,
                        Duration.ofSeconds(15))
                .getFirst();

        assertSupersededCase(staleOrderCase);
        assertThat(submissionDetail(confirmMessageId))
                .containsEntry("current_intent", "SUPPLIER_TRACKING")
                .containsEntry("status", "DRAFTED");

        Map<String, Object> staleOrderConfirm = orderConfirmationCommand(staleOrderDraft);
        Map<String, Object> currentTrackingConfirm = new LinkedHashMap<>(Map.of(
                "expected_draft_revision",
                ((Number) currentTrackingDraft.get("revision")).longValue(),
                "expected_case_version",
                caseVersion(currentTrackingDraft)));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ResponseEntity<Map>> staleOrderAction = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return postOrderCommand(
                        staleOrderDraft,
                        "confirm",
                        staleOrderConfirm,
                        "confirm-stale-order-family-001");
            });
            Future<ResponseEntity<Map>> currentTrackingAction = pool.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return confirmWithBody(
                        currentTrackingDraft,
                        "confirm-current-tracking-family-001",
                        currentTrackingConfirm);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            ResponseEntity<Map> staleOrderResponse = staleOrderAction.get(15, TimeUnit.SECONDS);
            ResponseEntity<Map> currentTrackingResponse = currentTrackingAction.get(15, TimeUnit.SECONDS);
            assertThat(staleOrderResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(currentTrackingResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        assertThat(submissionDetail(confirmMessageId).get("status")).isEqualTo("CONFIRMED");
        assertThat(detail(currentTrackingDraft.get("id").toString()).get("status"))
                .isEqualTo("CONFIRMED");
        assertThat(orderDraftDetail(staleOrderDraft).get("status")).isEqualTo("REJECTED");
        assertThat(canonicalOrders(staleOrderDraft.get("source_order_no").toString()))
                .containsEntry("total_elements", 0);
        assertThat(shipmentOf(trackingOrder))
                .containsEntry("shipment_status", "SHIPPED")
                .satisfies(shipment -> assertThat(((Map<?, ?>) shipment.get("tracking")).get("tracking_number"))
                        .isEqualTo("SF123459901"));

        Map<String, Object> rejectedTrackingOrder =
                createThirdPartyOrder("TRK-CROSS-FAMILY-REJECT-001", "跨族拒绝客户", "1.000");
        Map<String, Object> rejectedTrackingFacts = fulfillmentFacts("TRK-CROSS-FAMILY-REJECT-001");
        String rejectMessageId = "MSG-CROSS-FAMILY-TRACKING-TO-ORDER-01";
        Map<String, Object> trackingSubmission = sendTracking(
                rejectMessageId,
                2,
                lines(line(
                        "跨族拒绝客户",
                        "SF123459902",
                        rejectedTrackingFacts.get("fulfillment_no").toString(),
                        "顺丰速运",
                        null)));
        Map<String, Object> staleTrackingDraft = singleDraft(trackingSubmission);
        Map<String, Object> staleTrackingCase = caseOf(staleTrackingDraft);
        assertThat(staleTrackingCase.get("status")).isEqualTo("OPEN");

        TrackingInterpreterControl.queue(customerOrderResult());
        reinterpret(trackingSubmission, "reinterpret-tracking-to-order-001");
        Map<String, Object> currentOrderDraft = awaitUntil(
                        () -> orderDraftsOf(trackingSubmission),
                        items -> items.size() == 1,
                        Duration.ofSeconds(15))
                .getFirst();

        assertSupersededCase(staleTrackingCase);
        assertThat(submissionDetail(rejectMessageId))
                .containsEntry("current_intent", "CUSTOMER_ORDER")
                .containsEntry("status", "DRAFTED");
        ResponseEntity<Map> rejected = postOrderCommand(
                currentOrderDraft,
                "reject",
                Map.of(
                        "expected_revision", ((Number) currentOrderDraft.get("revision")).longValue(),
                        "expected_case_version",
                                ((Number) currentOrderDraft.get("review_case_version")).longValue(),
                        "reason", "重新解释后确认不是客户订单"),
                "reject-current-order-family-001");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status")).isEqualTo("REJECTED");

        assertThat(submissionDetail(rejectMessageId).get("status")).isEqualTo("REJECTED");
        assertThat(orderDraftDetail(currentOrderDraft).get("status")).isEqualTo("REJECTED");
        assertThat(detail(staleTrackingDraft.get("id").toString()))
                .containsEntry("status", "REJECTED")
                .containsEntry("review_case_id", null);
        assertThat(shipmentOf(rejectedTrackingOrder))
                .containsEntry("shipment_status", "CREATED")
                .containsEntry("tracking", null);
    }

    @Test
    void maskedNameWildcardUniqueHitFormsCandidateWithoutAnyImplicitWeight() throws Exception {
        createThirdPartyOrder("TRK-NAME-UNIQUE-001", "郑一", "2.000");
        createThirdPartyOrder("TRK-NAME-OTHER-001", "张三", "1.000");

        // 只有收货人姓名快照参与匹配；发送者/群/时间不参与
        Map<String, Object> submission = sendTracking(
                "MSG-NAME-UNIQUE-01",
                1,
                lines(line("郑*", "SF123456010", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isEqualTo(String.valueOf(fulfillmentFacts("TRK-NAME-UNIQUE-001").get("fulfillment_id")));
        assertThat(((List<?>) draft.get("task_candidates"))).hasSize(1);
        assertThat(draft.get("carrier_code")).isEqualTo("SF_EXPRESS");
        assertThat((List<?>) draft.get("validation_issues")).isEmpty();
    }

    @Test
    void fullyMaskedNameNeverAutoLinksOrEnumeratesCandidatePool() throws Exception {
        createThirdPartyOrder("TRK-NAME-FULLY-MASKED-001", "全脱敏客户", "1.000");

        Map<String, Object> submission = sendTracking(
                "MSG-NAME-FULLY-MASKED-01",
                1,
                lines(line("*", "SF123456014", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);

        assertThat(draft.get("task_id")).isNull();
        assertThat((List<?>) draft.get("task_candidates")).isEmpty();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NAME_INSUFFICIENT");
    }

    @Test
    void zeroMatchesCanBeManuallyResolvedByExactTaskNumberAndEnabledCarrierOption() throws Exception {
        createThirdPartyOrder("TRK-MANUAL-ZERO-001", "精确任务客户", "1.000");
        Map<String, Object> task = fulfillmentFacts("TRK-MANUAL-ZERO-001");

        Map<String, Object> submission = sendTracking(
                "MSG-MANUAL-ZERO-01",
                1,
                lines(line("不存在*", "XYZ123456019", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isNull();
        assertThat((List<?>) draft.get("task_candidates")).isEmpty();
        assertThat(draft.get("carrier_code")).isNull();
        assertThat((List<?>) draft.get("carrier_candidates")).isEmpty();

        List<?> carrierOptions = (List<?>) draft.get("manual_carrier_options");
        assertThat(carrierOptions).allSatisfy(option ->
                assertThat(((Map<?, ?>) option).keySet().stream()
                                .map(String::valueOf)
                                .toList())
                        .containsExactlyInAnyOrder("code", "name"));
        assertThat(carrierOptions.stream()
                        .map(option -> String.valueOf(((Map<?, ?>) option).get("code")))
                        .toList())
                .containsExactlyInAnyOrder("JD", "JDVA_EXPRESS", "SF_EXPRESS")
                .doesNotContain("DISABLED_CARRIER");

        String taskNo = String.valueOf(task.get("fulfillment_no"));
        ResponseEntity<Map> conflictingReferences = confirm(
                draft,
                "confirm-manual-zero-conflict-001",
                Map.of(
                        "task_id", String.valueOf(task.get("fulfillment_id")),
                        "task_no", taskNo,
                        "carrier_code", "SF_EXPRESS"));
        assertThat(conflictingReferences.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(conflictingReferences.getBody().get("business_code"))
                .isEqualTo("TASK_REFERENCE_CONFLICT");

        ResponseEntity<Map> confirmed = confirm(
                draft,
                "confirm-manual-zero-001",
                Map.of("task_no", taskNo, "carrier_code", "SF_EXPRESS"));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("task_id"))
                .isEqualTo(String.valueOf(task.get("fulfillment_id")));
        assertThat(confirmed.getBody().get("carrier_code")).isEqualTo("SF_EXPRESS");

        Map<String, Object> audit = auditDetail("confirm-manual-zero-001", "tracking_draft.confirm");
        Map<?, ?> auditedSelection = (Map<?, ?>) audit.get("request_payload");
        assertThat(auditedSelection.get("task_no")).isEqualTo(taskNo);
        assertThat(auditedSelection.get("task_selection_source")).isEqualTo("OPERATOR_TASK_NO");
        assertThat(auditedSelection.get("carrier_selection_source")).isEqualTo("OPERATOR");
        assertConfirmationAuditWhitelist(auditedSelection);
    }

    @Test
    void maskedNameZeroOrMultipleHitNeverAutoLinks() throws Exception {
        createThirdPartyOrder("TRK-NAME-ZERO-001", "陈二", "2.000");
        createThirdPartyOrder("TRK-NAME-MULTI-001", "何三", "1.000");
        createThirdPartyOrder("TRK-NAME-MULTI-002", "何四", "1.000");

        // 零命中：姓名通配匹配不到任何待回传任务（套件里没有“裴”姓收货人）
        Map<String, Object> zero = sendTracking(
                "MSG-NAME-ZERO-01",
                1,
                lines(line("裴*", "SF123456011", null, null, null)));
        Map<String, Object> zeroDraft = singleDraft(zero);
        assertThat(zeroDraft.get("task_id")).isNull();
        assertThat((List<String>) zeroDraft.get("validation_issues")).contains("TASK_NAME_NO_MATCH");

        Map<String, Object> multi = sendTracking(
                "MSG-NAME-MULTI-01",
                2,
                lines(line("何*", "SF123456012", null, null, null)));
        Map<String, Object> multiDraft = singleDraft(multi);
        assertThat(multiDraft.get("task_id")).isNull();
        List<?> taskCandidates = (List<?>) multiDraft.get("task_candidates");
        assertThat(taskCandidates).hasSize(2).allSatisfy(candidate ->
                assertThat(((Map<?, ?>) candidate).keySet().stream()
                                .map(String::valueOf)
                                .toList())
                        .containsExactlyInAnyOrder(
                                "task_id",
                                "fulfillment_no",
                                "order_id",
                                "order_no",
                                "order_line_id",
                                "shipment_id",
                                "receiver_name",
                                "requested_quantity",
                                "shipped_quantity",
                                "instructed_quantity"));
        assertThat((List<String>) multiDraft.get("validation_issues")).contains("TASK_NAME_MULTI_MATCH");

        // 多命中必须人工选择任务
        ResponseEntity<Map> rejected = confirm(multiDraft, "confirm-name-multi-001", Map.of());
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_REQUIRED");
        Map<String, Object> picked = fulfillmentFacts("TRK-NAME-MULTI-001");
        ResponseEntity<Map> manual = confirm(
                multiDraft, "confirm-name-multi-002", Map.of("task_id", String.valueOf(picked.get("fulfillment_id"))));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> audit = auditDetail("confirm-name-multi-002", "tracking_draft.confirm");
        assertThat(audit.get("operator")).isEqualTo(OPERATOR);
        assertThat(audit.get("actor_type")).isEqualTo("HUMAN");
        Map<?, ?> auditedSelection = (Map<?, ?>) audit.get("request_payload");
        assertThat(auditedSelection.get("task_id"))
                .isEqualTo(String.valueOf(picked.get("fulfillment_id")));
        assertThat(auditedSelection.get("task_selection_source")).isEqualTo("OPERATOR");
        assertThat(auditedSelection.get("carrier_selection_source")).isEqualTo("UNIQUE_CANDIDATE");
        assertConfirmationAuditWhitelist(auditedSelection);
    }

    @Test
    void missingNameDoesNotAutoLinkTask() throws Exception {
        createThirdPartyOrder("TRK-NAME-MISSING-001", "周八", "2.000");
        Map<String, Object> submission = sendTracking(
                "MSG-NAME-MISSING-01",
                1,
                lines(line(null, "SF123456013", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("task_id")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("TASK_NAME_MISSING");
    }

    // ------------------------------------------------------------------
    // 票 09：Carrier 前缀候选
    // ------------------------------------------------------------------

    @Test
    void carrierPrefixUniqueHitZeroHitAndDisabledCarrier() throws Exception {
        createThirdPartyOrder("TRK-CARRIER-UNIQUE-001", "孙九", "2.000");

        Map<String, Object> unique = sendTracking(
                "MSG-CARRIER-UNIQUE-01",
                1,
                lines(line("孙九", "SF123456020", null, null, null)));
        Map<String, Object> uniqueDraft = singleDraft(unique);
        assertThat(uniqueDraft.get("carrier_code")).isEqualTo("SF_EXPRESS");
        assertThat(((List<?>) uniqueDraft.get("carrier_candidates"))).singleElement().satisfies(candidate -> {
            Map<?, ?> entry = (Map<?, ?>) candidate;
            assertThat(entry.get("source")).isEqualTo("PREFIX");
        });
        assertThat((List<?>) uniqueDraft.get("validation_issues")).isEmpty();

        Map<String, Object> zero = sendTracking(
                "MSG-CARRIER-ZERO-01",
                2,
                lines(line("孙九", "XYZ123456021", null, null, null)));
        Map<String, Object> zeroDraft = singleDraft(zero);
        assertThat(zeroDraft.get("carrier_code")).isNull();
        assertThat((List<String>) zeroDraft.get("validation_issues")).contains("CARRIER_PREFIX_UNMATCHED");

        // 停用 Carrier 不形成候选
        Map<String, Object> disabled = sendTracking(
                "MSG-CARRIER-DISABLED-01",
                3,
                lines(line("孙九", "DISABLED123456022", null, null, null)));
        Map<String, Object> disabledDraft = singleDraft(disabled);
        assertThat(disabledDraft.get("carrier_code")).isNull();
        assertThat((List<String>) disabledDraft.get("validation_issues")).contains("CARRIER_PREFIX_UNMATCHED");

        ResponseEntity<Map> manual = confirm(
                zeroDraft, "confirm-carrier-zero-001", Map.of("carrier_code", "SF_EXPRESS"));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void carrierPrefixMultipleHitRequiresManualChoiceAndShowsBothCandidates() throws Exception {
        createThirdPartyOrder("TRK-CARRIER-MULTI-001", "吴十", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-CARRIER-MULTI-01",
                1,
                lines(line("吴十", "JDVA123456023", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("carrier_code")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("CARRIER_MULTI_HIT");
        List<String> codes = ((List<?>) draft.get("carrier_candidates")).stream()
                .map(entry -> (String) ((Map<?, ?>) entry).get("code"))
                .toList();
        assertThat(codes).containsExactlyInAnyOrder("JD", "JDVA_EXPRESS");

        ResponseEntity<Map> without = confirm(draft, "confirm-carrier-multi-001", Map.of());
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(without.getBody().get("business_code")).isEqualTo("CARRIER_REQUIRED");
        ResponseEntity<Map> manual = confirm(draft, "confirm-carrier-multi-002", Map.of("carrier_code", "JD"));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) manual.getBody().get("carrier_code")).isEqualTo("JD");
    }

    @Test
    void statedCarrierConflictWithPrefixRequiresManualChoice() throws Exception {
        createThirdPartyOrder("TRK-CARRIER-CONFLICT-001", "徐十一", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-CARRIER-CONFLICT-01",
                1,
                lines(line("徐十一", "SF123456024", null, "京东物流", null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("carrier_code")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("CARRIER_CONFLICT");
        List<String> sources = ((List<?>) draft.get("carrier_candidates")).stream()
                .map(entry -> (String) ((Map<?, ?>) entry).get("source"))
                .toList();
        assertThat(sources).containsExactlyInAnyOrder("STATED", "PREFIX");

        // 冲突必须人工选择并展示依据；改选前缀命中的物流公司后确认成功
        ResponseEntity<Map> without = confirm(draft, "confirm-carrier-conflict-001", Map.of());
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        ResponseEntity<Map> manual = confirm(
                draft, "confirm-carrier-conflict-002", Map.of("carrier_code", "SF_EXPRESS"));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> audit = auditDetail("confirm-carrier-conflict-002", "tracking_draft.confirm");
        assertThat(audit.get("operator")).isEqualTo(OPERATOR);
        assertThat(audit.get("actor_type")).isEqualTo("HUMAN");
        Map<?, ?> auditedSelection = (Map<?, ?>) audit.get("request_payload");
        assertThat(auditedSelection.get("carrier_code")).isEqualTo("SF_EXPRESS");
        assertThat(auditedSelection.get("task_selection_source")).isEqualTo("UNIQUE_CANDIDATE");
        assertThat(auditedSelection.get("carrier_selection_source")).isEqualTo("OPERATOR");
        assertConfirmationAuditWhitelist(auditedSelection);
    }

    @Test
    void unresolvedStatedCarrierNeverAutoPicksPrefix() throws Exception {
        createThirdPartyOrder("TRK-CARRIER-STATED-001", "沈十二", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-CARRIER-STATED-01",
                1,
                lines(line("沈十二", "SF123456025", null, "丰网速运", null)));
        Map<String, Object> draft = singleDraft(submission);
        assertThat(draft.get("carrier_code")).isNull();
        assertThat((List<String>) draft.get("validation_issues")).contains("CARRIER_STATED_UNRESOLVED");

        ResponseEntity<Map> without = confirm(draft, "confirm-carrier-stated-001", Map.of());
        assertThat(without.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(without.getBody().get("business_code")).isEqualTo("CARRIER_REQUIRED");
        ResponseEntity<Map> manual = confirm(
                draft, "confirm-carrier-stated-002", Map.of("carrier_code", "SF_EXPRESS"));
        assertThat(manual.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ------------------------------------------------------------------
    // 票 10：部分/缺货/异常与批量
    // ------------------------------------------------------------------

    @Test
    void partialShortageAndExceptionRequireManualQuantityAndValidateIt() throws Exception {
        createThirdPartyOrder("TRK-QTY-PARTIAL-001", "韩十三", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-QTY-PARTIAL-001");

        Map<String, Object> partial = sendTracking(
                "MSG-QTY-PARTIAL-01",
                1,
                lines(line("韩十三", "SF123456030", null, null, "PARTIAL")));
        Map<String, Object> partialDraft = singleDraft(partial);
        assertThat(partialDraft.get("shipment_judgment")).isEqualTo("PARTIAL");
        assertThat((List<String>) partialDraft.get("validation_issues")).contains("REQUIRES_ACTUAL_QUANTITY");

        Map<String, Object> shortage = sendTracking(
                "MSG-QTY-SHORTAGE-01",
                2,
                lines(line("韩十三", "SF123456031", null, null, "SHORTAGE")));
        assertThat(singleDraft(shortage).get("shipment_judgment")).isEqualTo("SHORTAGE");

        Map<String, Object> exception = sendTracking(
                "MSG-QTY-EXCEPTION-01",
                3,
                lines(line("韩十三", "SF123456032", null, null, "EXCEPTION")));
        assertThat(singleDraft(exception).get("shipment_judgment")).isEqualTo("EXCEPTION");

        // 不录入数量拒绝；数量超过剩余数量拒绝
        ResponseEntity<Map> withoutQuantity = confirm(partialDraft, "confirm-qty-missing-001", Map.of());
        assertThat(withoutQuantity.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(withoutQuantity.getBody().get("business_code")).isEqualTo("ACTUAL_QUANTITY_REQUIRED");

        ResponseEntity<Map> excessive = confirm(partialDraft, "confirm-qty-excessive-001", Map.of("actual_quantity", 5));
        assertThat(excessive.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(excessive.getBody().get("business_code")).isEqualTo("ACTUAL_QUANTITY_INVALID");

        // 人工录入实际数量后确认：履约进入部分发货
        ResponseEntity<Map> confirmed = confirm(partialDraft, "confirm-qty-001", Map.of("actual_quantity", 1));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> fulfillment = get("/api/v1/fulfillments/" + facts.get("fulfillment_id"));
        assertThat(fulfillment)
                .containsEntry("shipping_progress", "PARTIALLY_SHIPPED")
                .containsEntry("outcome", "IN_PROGRESS");
    }

    @Test
    void batchConfirmConfirmsMultipleLinesWithPerLineResults() throws Exception {
        createThirdPartyOrder("TRK-BATCH-001", "彭十四", "2.000");
        createThirdPartyOrder("TRK-BATCH-002", "蒋十五", "2.000");
        createThirdPartyOrder("TRK-BATCH-003", "蔡十六", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-BATCH-01",
                1,
                Map.of(
                        "batch", true,
                        "lines", List.of(
                                line("彭十四", "SF123456040", null, null, null),
                                line("蒋十五", "SF123456041", null, null, null),
                                line("蔡十六", "SF123456042", null, null, null))));
        assertThat(submission.get("status")).isEqualTo("DRAFTED");
        List<Map<String, Object>> drafts = draftsOf(submission);
        assertThat(drafts).hasSize(3);
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.get("status")).isEqualTo("OPEN");
            assertThat(draft.get("review_case_id")).isNotNull();
            assertThat(draft.get("submission_id")).isEqualTo(submission.get("id"));
            assertThat(draft.get("default_full_shipment")).isEqualTo(true);
        });
        // 同一消息的多个草稿各有一个开放事项，且都引用同一提交
        for (Map<String, Object> draft : drafts) {
            Map<String, Object> reviewCase = caseOf(draft);
            assertThat(reviewCase.get("subject_type")).isEqualTo("TRACKING_DRAFT");
            assertThat(reviewCase.get("subject_id")).isEqualTo(draft.get("id"));
            assertThat(reviewCase.get("reason_code")).isEqualTo("WECOM_TRACKING_DRAFT");
            assertThat(((List<?>) reviewCase.get("allowed_actions")).stream().map(String::valueOf).toList())
                    .containsExactlyInAnyOrder("CONFIRM_TRACKING_DRAFT", "REJECT_TRACKING_DRAFT");
            assertThat(reviewCase.get("status")).isEqualTo("OPEN");
        }

        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(batchLine(drafts.get(0), "line-key-batch-001"));
        lines.add(batchLine(drafts.get(1), "line-key-batch-002"));
        lines.add(batchLine(drafts.get(2), "line-key-batch-003"));
        ResponseEntity<Map> response = batchConfirm(lines, "batch-key-all-success-001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("success_count")).isEqualTo(3);
        assertThat(body.get("failure_count")).isEqualTo(0);
        assertThat((List<?>) body.get("results")).hasSize(3);

        for (Map<String, Object> draft : drafts) {
            Map<String, Object> refreshed = detail(String.valueOf(draft.get("id")));
            assertThat(refreshed.get("status")).isEqualTo("CONFIRMED");
        }
    }

    @Test
    void batchMixedSuccessAndFailureKeepsFailedLinesOpenWithoutRollback() throws Exception {
        createThirdPartyOrder("TRK-MIXED-001", "余十七", "2.000");
        createThirdPartyOrder("TRK-MIXED-002", "杜十八", "2.000");
        createThirdPartyOrder("TRK-MIXED-003", "戴十九", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-MIXED-01",
                1,
                Map.of(
                        "batch", true,
                        "lines", List.of(
                                line("余十七", "SF123456050", null, null, null),
                                line("杜十八", "SF123456051", null, null, null),
                                line("戴十九", "SF123456052", null, null, null))));
        List<Map<String, Object>> drafts = draftsOf(submission);

        // 第二行给过期版本，制造单行失败
        Map<String, Object> stale = new LinkedHashMap<>(batchLine(drafts.get(1), "line-key-mixed-002"));
        stale.put("expected_draft_revision", 99L);
        List<Map<String, Object>> lines = new ArrayList<>();
        lines.add(batchLine(drafts.get(0), "line-key-mixed-001"));
        lines.add(stale);
        lines.add(batchLine(drafts.get(2), "line-key-mixed-003"));

        ResponseEntity<Map> response = batchConfirm(lines, "batch-key-mixed-001");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body.get("success_count")).isEqualTo(2);
        assertThat(body.get("failure_count")).isEqualTo(1);
        List<?> results = (List<?>) body.get("results");
        Map<?, ?> failed = results.stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> !Boolean.TRUE.equals(item.get("success")))
                .findFirst()
                .orElseThrow();
        assertThat(failed.get("draft_id")).isEqualTo(drafts.get(1).get("id"));
        assertThat(failed.get("business_code")).isEqualTo("VERSION_CONFLICT");

        // 失败行保持 OPEN 并展示可执行错误；成功行不能被再次确认
        Map<String, Object> failedDraft = detail(String.valueOf(drafts.get(1).get("id")));
        assertThat(failedDraft.get("status")).isEqualTo("OPEN");
        assertThat(detail(String.valueOf(drafts.get(0).get("id"))).get("status")).isEqualTo("CONFIRMED");
        ResponseEntity<Map> retry = confirm(drafts.get(0), "confirm-retry-success-001", Map.of());
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody().get("business_code")).isEqualTo("DRAFT_NOT_OPEN");

        // 失败行修订版本后单独补确认成功（用新幂等键）
        Map<String, Object> fresh = detail(String.valueOf(drafts.get(1).get("id")));
        ResponseEntity<Map> retryLine = confirm(fresh, "confirm-retry-failed-001", Map.of());
        assertThat(retryLine.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void batchLinesAreIdempotentPerLineKeyAndReplayCreatesNoSecondFacts() throws Exception {
        createThirdPartyOrder("TRK-REPLAY-001", "田二十", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-REPLAY-001");

        Map<String, Object> submission = sendTracking(
                "MSG-REPLAY-01",
                1,
                lines(line("田二十", "SF123456060", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);

        List<Map<String, Object>> first = List.of(batchLine(draft, "line-key-replay-001"));
        ResponseEntity<Map> once = batchConfirm(first, "batch-key-replay-001");
        ResponseEntity<Map> replay = batchConfirm(first, "batch-key-replay-001");
        assertThat(once.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("success_count")).isEqualTo(1);

        Long shipments = jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE order_id=?",
                Long.class,
                facts.get("order_id"));
        assertThat(shipments).isEqualTo(1);
        Long trackings = jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE tracking_number='SF123456060'",
                Long.class);
        assertThat(trackings).isEqualTo(1);
        Long cases = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases rc
                JOIN app.provider_tracking_drafts d ON d.id=rc.provider_tracking_draft_id
                WHERE d.tracking_no='SF123456060'
                """,
                Long.class);
        assertThat(cases).isEqualTo(1);

        // 单条确认接口用同一行幂等键与同一命令载荷（原 OPEN 状态版本）重放同样返回原结果
        ResponseEntity<Map> singleReplay = confirmWithBody(
                draft,
                "line-key-replay-001",
                new LinkedHashMap<>(Map.of(
                        "expected_draft_revision", 0L,
                        "expected_case_version", 0L)));
        assertThat(singleReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(singleReplay.getBody().get("status")).isEqualTo("CONFIRMED");
    }

    @Test
    void duplicateTrackingNumberIsRejected() throws Exception {
        createThirdPartyOrder("TRK-DUP-001", "范二十一", "2.000");
        createThirdPartyOrder("TRK-DUP-002", "方二十二", "2.000");

        Map<String, Object> first = sendTracking(
                "MSG-DUP-01",
                1,
                lines(line("范二十一", "SF123456070", null, null, null)));
        Map<String, Object> firstDraft = singleDraft(first);
        assertThat(confirm(firstDraft, "confirm-dup-first-001", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        Map<String, Object> second = sendTracking(
                "MSG-DUP-02",
                2,
                lines(line("方二十二", "SF123456070", null, null, null)));
        Map<String, Object> secondDraft = singleDraft(second);
        ResponseEntity<Map> duplicate = confirm(secondDraft, "confirm-dup-second-001", Map.of());
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getBody().get("business_code")).isEqualTo("TRACKING_DUPLICATE");
        assertThat(detail(String.valueOf(secondDraft.get("id"))).get("status")).isEqualTo("OPEN");
    }

    @Test
    void staleDraftVersionIsRejected() throws Exception {
        createThirdPartyOrder("TRK-STALE-001", "罗二十三", "2.000");

        Map<String, Object> submission = sendTracking(
                "MSG-STALE-01",
                1,
                lines(line("罗二十三", "SF123456080", null, null, null)));
        Map<String, Object> draft = singleDraft(submission);

        Map<String, Object> staleBody = new LinkedHashMap<>(Map.of(
                "expected_draft_revision", 5L,
                "expected_case_version", caseVersion(draft)));
        ResponseEntity<Map> stale = confirmWithBody(draft, "confirm-stale-001", staleBody);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody().get("business_code")).isEqualTo("VERSION_CONFLICT");
    }

    @Test
    void twoDraftsForTheSameTaskAndShipmentCannotBothBeConfirmed() throws Exception {
        Map<String, Object> order = createThirdPartyOrder("TRK-CONCURRENT-001", "并发客户", "2.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-CONCURRENT-001");
        String taskNo = facts.get("fulfillment_no").toString();
        Map<String, Object> firstDraft = singleDraft(sendTracking(
                "MSG-CONCURRENT-01",
                1,
                lines(line("并发客户", "SF123456081", taskNo, "顺丰速运", null))));
        Map<String, Object> secondDraft = singleDraft(sendTracking(
                "MSG-CONCURRENT-02",
                2,
                lines(line("并发客户", "SF123456082", taskNo, "顺丰速运", null))));
        assertThat(firstDraft.get("task_id")).isEqualTo(secondDraft.get("task_id"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<Map>> first =
                    pool.submit(() -> confirm(firstDraft, "confirm-concurrent-001", Map.of()));
            Future<ResponseEntity<Map>> second =
                    pool.submit(() -> confirm(secondDraft, "confirm-concurrent-002", Map.of()));
            ResponseEntity<Map> firstResponse = first.get();
            ResponseEntity<Map> secondResponse = second.get();

            assertThat(List.of(firstResponse.getStatusCode(), secondResponse.getStatusCode()))
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            ResponseEntity<Map> rejected = firstResponse.getStatusCode().is2xxSuccessful()
                    ? secondResponse
                    : firstResponse;
            Map<String, Object> rejectedDraft = firstResponse.getStatusCode().is2xxSuccessful()
                    ? secondDraft
                    : firstDraft;
            assertThat(rejected.getBody().get("business_code")).isEqualTo("TASK_NOT_PENDING");
            Map<String, Object> stillOpen = detail(String.valueOf(rejectedDraft.get("id")));
            assertThat(stillOpen.get("status")).isEqualTo("OPEN");
            assertThat(caseOf(stillOpen).get("status")).isEqualTo("OPEN");

            Map<String, Object> shipment = shipmentOf(order);
            assertThat(shipment.get("shipment_status")).isEqualTo("SHIPPED");
            assertThat(((Map<?, ?>) shipment.get("tracking")).get("tracking_number"))
                    .isIn("SF123456081", "SF123456082");
            assertThat(jdbc.queryForObject(
                            """
                            SELECT count(*) FROM app.trackings t
                            JOIN app.shipments s ON s.id=t.shipment_id
                            WHERE s.order_id=?
                            """,
                            Long.class,
                            facts.get("order_id")))
                    .isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentFinalDraftConfirmationsConvergeSharedSubmissionToConfirmed() throws Exception {
        createThirdPartyOrder("TRK-SUBMISSION-CONCURRENT-A", "归并客户甲", "1.000");
        createThirdPartyOrder("TRK-SUBMISSION-CONCURRENT-B", "归并客户乙", "1.000");
        Map<String, Object> firstFacts = fulfillmentFacts("TRK-SUBMISSION-CONCURRENT-A");
        Map<String, Object> secondFacts = fulfillmentFacts("TRK-SUBMISSION-CONCURRENT-B");
        String messageId = "MSG-TRACKING-SUBMISSION-CONCURRENT-01";
        Map<String, Object> submission = sendTracking(
                messageId,
                1,
                lines(
                        line(
                                "归并客户甲",
                                "SF123456091",
                                firstFacts.get("fulfillment_no").toString(),
                                "顺丰速运",
                                null),
                        line(
                                "归并客户乙",
                                "SF123456092",
                                secondFacts.get("fulfillment_no").toString(),
                                "顺丰速运",
                                null)));
        List<Map<String, Object>> drafts = draftsOf(submission);
        assertThat(drafts).hasSize(2);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<ResponseEntity<Map>>> responses = drafts.stream()
                    .map(draft -> pool.submit(() -> {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        return confirm(
                                draft,
                                "confirm-shared-submission-" + draft.get("line_no"),
                                Map.of());
                    }))
                    .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (Future<ResponseEntity<Map>> response : responses) {
                assertThat(response.get().getStatusCode()).isEqualTo(HttpStatus.OK);
            }
        }

        assertThat(submissionDetail(messageId).get("status"))
                .as("the shared submission row serializes the final status across draft confirmations")
                .isEqualTo("CONFIRMED");
    }

    @Test
    void trackingDraftReviewCaseWhitelistsModelEvidence() throws Exception {
        createThirdPartyOrder("TRK-MODEL-WHITELIST-001", "白名单客户", "1.000");
        Map<String, Object> facts = fulfillmentFacts("TRK-MODEL-WHITELIST-001");
        Map<String, Object> modelLine = line(
                "白名单客户",
                "SF123456083",
                facts.get("fulfillment_no").toString(),
                "顺丰速运",
                null);
        modelLine.put("secret_token", "do-not-persist");

        Map<String, Object> draft = singleDraft(sendTracking(
                "MSG-MODEL-WHITELIST-01", 1, lines(modelLine)));
        Map<?, ?> detail = (Map<?, ?>) caseOf(draft).get("detail");
        Map<?, ?> modelEvidence = (Map<?, ?>) detail.get("model_line");
        assertThat(modelEvidence.get("tracking_no")).isEqualTo("SF123456083");
        assertThat(modelEvidence.containsKey("secret_token")).isFalse();
        assertThat(objectMapper.writeValueAsString(detail))
                .doesNotContain("secret_token", "do-not-persist");
    }

    @Test
    void unpairedLinesCreateAnExplicitNeedReviewCaseInsteadOfGuessingPairs() throws Exception {
        long needReviewBefore = countSubmissionCases("WECOM_NEED_REVIEW");

        // 模型输出没有可解析的 lines（例如两个平行列表），不得按位置猜测配对
        Map<String, Object> submission = sendTracking(
                "MSG-UNPAIRED-01",
                1,
                Map.of(
                        "names", List.of("张三", "李四"),
                        "tracking_nos", List.of("SF1", "SF2"),
                        "secret_token", "do-not-persist"));
        assertThat(submission.get("status")).isEqualTo("INTERPRETED");
        assertThat(draftsOf(submission)).isEmpty();

        List<Map<String, Object>> cases = awaitUntil(
                () -> listReviewCases(),
                list -> list.stream()
                                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                                .filter(c -> "WECOM_NEED_REVIEW".equals(c.get("reason_code")))
                                .count()
                        == needReviewBefore + 1,
                Duration.ofSeconds(5));
        Map<String, Object> created = cases.stream()
                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                .filter(c -> "WECOM_NEED_REVIEW".equals(c.get("reason_code")))
                .findFirst()
                .orElseThrow();
        assertThat(created.get("status")).isEqualTo("OPEN");
        Map<String, Object> detail =
                (Map<String, Object>) getCase(String.valueOf(created.get("id"))).get("detail");
        assertThat(detail).containsEntry("reason", "LINE_PAIRING_UNRESOLVED");
        Map<?, ?> modelOutput = (Map<?, ?>) detail.get("model_output");
        assertThat(modelOutput.get("names")).isEqualTo(List.of("张三", "李四"));
        assertThat(modelOutput.get("tracking_nos")).isEqualTo(List.of("SF1", "SF2"));
        assertThat(modelOutput.containsKey("secret_token")).isFalse();
        assertThat(objectMapper.writeValueAsString(detail))
                .doesNotContain("secret_token", "do-not-persist");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private Map<String, Object> sendTracking(String messageId, int seq, Map<String, Object> output) throws Exception {
        TrackingInterpreterControl.queue(new InterpretationResult(
                MessageIntent.SUPPLIER_TRACKING, output, "test-provider", "test-model", "test-prompt-v1", null));
        postAndReceipt(messageId, "第三方发货回传", seq);
        return awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && !"RECEIVED".equals(detail.get("status")),
                Duration.ofSeconds(15));
    }

    private Map<String, Object> sendCustomerOrder(String messageId, int seq) throws Exception {
        TrackingInterpreterControl.queue(customerOrderResult());
        postAndReceipt(messageId, "客户订单需求", seq);
        return awaitUntil(
                () -> submissionDetail(messageId),
                detail -> detail != null && "DRAFTED".equals(detail.get("status")),
                Duration.ofSeconds(15));
    }

    private InterpretationResult customerOrderResult() {
        return new InterpretationResult(
                MessageIntent.CUSTOMER_ORDER,
                Map.of(
                        "customer", Map.of("name", "子牧测试客户"),
                        "customer_ref", "WECOM-CUSTOMER-001",
                        "receiver", Map.of(
                                "name", "张三",
                                "phone", "13800000000",
                                "address", "上海市浦东新区测试路 1 号"),
                        "settlement_method", "MONTHLY",
                        "items", List.of(Map.of(
                                "product", "子牧羊小腿",
                                "spec", "500g/盒",
                                "unit", "盒",
                                "quantity", 1,
                                "source_sku_ref", "WECOM-SKU-JD-001"))),
                "test-provider",
                "test-model",
                "test-prompt-v1",
                null);
    }

    private void reinterpret(Map<String, Object> submission, String key) {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/message-submissions/" + submission.get("id") + "/reinterpret",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(key, "req-" + key)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> singleOrderDraft(Map<String, Object> submission) {
        List<Map<String, Object>> drafts = orderDraftsOf(submission);
        assertThat(drafts).hasSize(1);
        return drafts.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orderDraftsOf(Map<String, Object> submission) {
        ResponseEntity<Map> response = exchangeGet(
                "/api/v1/order-drafts?submission_id=" + submission.get("id") + "&size=20");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) response.getBody().get("items")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Map<String, Object> orderDraftDetail(Map<String, Object> draft) {
        ResponseEntity<Map> response = exchangeGet("/api/v1/order-drafts/" + draft.get("id"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> canonicalOrders(String sourceOrderNo) {
        return get("/api/v1/orders?source_channel=WECOM&query="
                + URLEncoder.encode(sourceOrderNo, StandardCharsets.UTF_8)
                + "&size=20");
    }

    private Map<String, Object> orderConfirmationCommand(Map<String, Object> draft) {
        Map<?, ?> customer = ((List<?>) draft.get("customer_candidates")).stream()
                .map(candidate -> (Map<?, ?>) candidate)
                .findFirst()
                .orElseThrow();
        Map<?, ?> line = (Map<?, ?>) ((List<?>) draft.get("lines")).getFirst();
        Map<?, ?> sku = ((List<?>) line.get("sku_candidates")).stream()
                .map(candidate -> (Map<?, ?>) candidate)
                .findFirst()
                .orElseThrow();
        return Map.of(
                "expected_revision", ((Number) draft.get("revision")).longValue(),
                "expected_case_version", ((Number) draft.get("review_case_version")).longValue(),
                "customer", Map.of("customer_id", customer.get("customer_id")),
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
                        "settlement_time", "2026-08-31T16:00:00Z"),
                "items", List.of(Map.of(
                        "line_no", line.get("line_no"),
                        "sku_id", sku.get("sku_id"),
                        "quantity", 1)));
    }

    private ResponseEntity<Map> postOrderCommand(
            Map<String, Object> draft,
            String action,
            Map<String, Object> command,
            String key) {
        return http.exchange(
                "/api/v1/order-drafts/" + draft.get("id") + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders(key, "req-" + key)),
                Map.class);
    }

    private void assertSupersededCase(Map<String, Object> original) {
        Map<String, Object> current = getCase(original.get("id").toString());
        assertThat(current)
                .containsEntry("status", "DISMISSED")
                .containsEntry("resolved_by", OPERATOR);
        Map<?, ?> resolution = (Map<?, ?>) current.get("resolution");
        assertThat(resolution.get("note")).isEqualTo("SUPERSEDED_BY_NEW_INTERPRETATION");
    }

    private Map<String, Object> createThirdPartyOrder(String sourceRef, String receiverName, String quantity) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String row = String.join(",", List.of(
                sourceRef,
                "FX-MEMBER-TRACKING",
                "子牧羊小腿",
                "FX-PRODUCT-TRACKING",
                sourceRef + "-LINE",
                quantity,
                receiverName,
                "13800000000",
                "上海市浦东新区测试路1号",
                "2026-08-11 10:00:00",
                "",
                "",
                ""));
        byte[] source = ("\uFEFF" + header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(source) {
            @Override
            public String getFilename() {
                return sourceRef + ".csv";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = writeHeaders("source-import-" + sourceRef, "req-source-import-" + sourceRef);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> imported = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 契约：导入仅接收与建单，不生成履约导出；操作员对整个批次确认后才生成（excel-closed-loop-spec §5）
        assertThat((List<?>) imported.getBody().get("generated_fulfillment_export_ids")).isEmpty();
        assertThat(imported.getBody().get("confirmed_at")).isNull();
        ResponseEntity<Map> confirmed = confirmSourceImport(imported.getBody().get("id").toString(), "confirm-" + sourceRef);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);

        Map<String, Object> page = get("/api/v1/orders?query=" + sourceRef + "&page=0&size=20");
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
        return get("/api/v1/orders/" + summary.get("id"));
    }

    private Map<String, Object> createThirdPartyOrderWithTwoLines(
            String sourceRef, String receiverName, String quantity) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        List<String> rows = new ArrayList<>();
        for (int lineNo = 1; lineNo <= 2; lineNo++) {
            rows.add(String.join(",", List.of(
                    sourceRef,
                    "FX-MEMBER-TRACKING",
                    "子牧羊小腿",
                    "FX-PRODUCT-TRACKING",
                    sourceRef + "-LINE-" + lineNo,
                    quantity,
                    receiverName,
                    "13800000000",
                    "上海市浦东新区测试路1号",
                    "2026-08-11 10:00:00",
                    "",
                    "",
                    "")));
        }
        byte[] source = ("\uFEFF" + header + "\r\n" + String.join("\r\n", rows) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(source) {
            @Override
            public String getFilename() {
                return sourceRef + ".csv";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = writeHeaders("source-import-" + sourceRef, "req-source-import-" + sourceRef);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> imported = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // 契约：导入仅接收与建单，不生成履约导出；操作员对整个批次确认后才生成（excel-closed-loop-spec §5）
        assertThat((List<?>) imported.getBody().get("generated_fulfillment_export_ids")).isEmpty();
        assertThat(imported.getBody().get("confirmed_at")).isNull();
        ResponseEntity<Map> confirmed = confirmSourceImport(imported.getBody().get("id").toString(), "confirm-" + sourceRef);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);

        Map<String, Object> page = get("/api/v1/orders?query=" + sourceRef + "&page=0&size=20");
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) page.get("items")).getFirst();
        return get("/api/v1/orders/" + summary.get("id"));
    }

    private Map<String, Object> createJdOrder(String sourceRef, String receiverName, String quantity) {
        return createOrder(sourceRef, receiverName, quantity, "WECOM-SKU-JD-001");
    }

    private ResponseEntity<Map> confirmSourceImport(String batchId, String key) {
        HttpHeaders headers = writeHeaders(key, "req-" + key);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private Map<String, Object> createUnexportedThirdPartyOrder(
            String sourceRef, String receiverName, String quantity) {
        jdbc.update(
                """
                UPDATE app.source_channel_skus
                SET quantity_multiplier=1.000
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                  AND quantity_multiplier IS NULL
                """);
        return createOrder(sourceRef, receiverName, quantity, "WECOM-SKU-TP-001");
    }

    private long addPendingShipment(String sourceRef, int sequence, String instructedQuantity) {
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                SELECT ?, o.id, f.fulfillment_provider_id, ?,
                       o.receiver_name, o.receiver_phone, o.receiver_address
                FROM app.orders o
                JOIN app.order_lines ol ON ol.order_id=o.id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                WHERE o.source_ref=?
                RETURNING id
                """,
                Long.class,
                "SHIP-TEST-" + sourceRef + "-" + sequence,
                sequence,
                sourceRef);
        Map<String, Object> facts = fulfillmentFacts(sourceRef);
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?::numeric)",
                shipmentId,
                facts.get("fulfillment_id"),
                instructedQuantity);
        jdbc.update(
                "UPDATE app.order_lines SET processing_stage='WAITING_PROVIDER' WHERE id=?",
                facts.get("order_line_id"));
        return shipmentId;
    }

    private Map<String, Object> createOrder(
            String sourceRef, String receiverName, String quantity, String sourceSkuRef) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", receiverName, "phone", "13800000000", "address", "上海市浦东新区测试路 1 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", sourceSkuRef,
                        "product_name", "子牧羊小腿",
                        "specification", sourceSkuRef.endsWith("JD") ? "500g/盒" : "标准箱",
                        "unit", sourceSkuRef.endsWith("JD") ? "盒" : "箱",
                        "quantity", Integer.parseInt(quantity))),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-11T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-" + sourceRef, "req-order-" + sourceRef)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    /** 读第三方履约任务事实（order/line/fulfillment/provider），仅用于定位测试数据。 */
    private Map<String, Object> fulfillmentFacts(String sourceRef) {
        return jdbc.queryForMap(
                """
                SELECT o.id order_id, o.order_no, ol.id order_line_id, ol.line_no,
                       f.id fulfillment_id, f.fulfillment_no, f.requested_quantity, f.fulfillment_provider_id,
                       fp.provider_type
                FROM app.orders o
                JOIN app.order_lines ol ON ol.id=(SELECT MIN(id) FROM app.order_lines WHERE order_id=o.id)
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                WHERE o.source_ref=?
                """,
                sourceRef);
    }

    private Map<String, Object> pendingShipmentFacts(String sourceRef) {
        return jdbc.queryForMap(
                """
                SELECT s.id shipment_id, s.shipment_status, si.instructed_quantity, si.shipped_quantity
                FROM app.orders o
                JOIN app.order_lines ol ON ol.order_id=o.id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.shipment_items si ON si.fulfillment_id=f.id
                JOIN app.shipments s ON s.id=si.shipment_id
                WHERE o.source_ref=?
                """,
                sourceRef);
    }

    private Map<String, Object> singleDraft(Map<String, Object> submission) {
        List<Map<String, Object>> drafts = draftsOf(submission);
        assertThat(drafts).hasSize(1);
        return drafts.getFirst();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> draftsOf(Map<String, Object> submission) {
        ResponseEntity<Map> response = exchangeGet(
                "/api/v1/tracking-drafts?submission_id=" + submission.get("id") + "&size=50");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) response.getBody().get("items")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    private Map<String, Object> detail(String draftId) {
        ResponseEntity<Map> response = exchangeGet("/api/v1/tracking-drafts/" + draftId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private Map<String, Object> shipmentOf(Map<String, Object> order) {
        ResponseEntity<Map[]> response = http.getForEntity(
                "/api/v1/orders/" + order.get("id") + "/shipments", Map[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        return response.getBody()[0];
    }

    private Map<String, Object> caseOf(Map<String, Object> draft) {
        return getCase(String.valueOf(draft.get("review_case_id")));
    }

    private Map<String, Object> getCase(String caseId) {
        ResponseEntity<Map> response = exchangeGet("/api/v1/review-cases/" + caseId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private long caseVersion(Map<String, Object> draft) {
        return ((Number) caseOf(draft).get("version")).longValue();
    }

    private void assertTrackingCaseResolved(Map<String, Object> draft, String resolutionType) {
        Map<String, Object> reviewCase = caseOf(draft);
        assertThat(reviewCase.get("status")).isEqualTo("RESOLVED");
        assertThat(reviewCase.get("resolved_by")).isEqualTo(OPERATOR);
        assertThat(reviewCase.get("resolved_at")).isNotNull();
        assertThat(((Map<?, ?>) reviewCase.get("resolution")).get("resolution_type"))
                .isEqualTo(resolutionType);
    }

    /** 确认后订单的事实闭环：TRACKING_RECEIVED 事件、订单版本追加与审计记录。 */
    private void assertFactsAndAudit(Map<String, Object> order, String idempotencyKey) {
        ResponseEntity<Map[]> timeline = http.getForEntity(
                "/api/v1/orders/" + order.get("id") + "/timeline", Map[].class);
        assertThat(java.util.stream.Stream.of(timeline.getBody())
                        .map(item -> item.get("event_type_code"))
                        .toList())
                .contains("TRACKING_RECEIVED");

        ResponseEntity<Map[]> versions = http.getForEntity(
                "/api/v1/orders/" + order.get("id") + "/versions", Map[].class);
        assertThat(versions.getBody()).isNotEmpty();

        // 同一请求还会产生 ShipmentTrackingService 内部的 tracking.accept 审计，
        // 这里只断言草稿确认命令自己的审计记录
        Map<String, Object> audits = get("/api/v1/audit-logs?request_id=req-" + idempotencyKey);
        List<?> confirmAudits = ((List<?>) audits.get("items")).stream()
                .filter(item -> "tracking_draft.confirm".equals(((Map<?, ?>) item).get("operation")))
                .toList();
        assertThat(confirmAudits).singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("operation")).isEqualTo("tracking_draft.confirm"));
    }

    private Map<String, Object> auditDetail(String idempotencyKey, String operation) {
        Map<String, Object> audits = get("/api/v1/audit-logs?request_id=req-" + idempotencyKey + "&size=20");
        Map<?, ?> summary = ((List<?>) audits.get("items")).stream()
                .map(item -> (Map<?, ?>) item)
                .filter(item -> operation.equals(item.get("operation")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到审计记录: " + operation));
        return get("/api/v1/audit-logs/" + summary.get("id"));
    }

    private static void assertConfirmationAuditWhitelist(Map<?, ?> selection) {
        assertThat(selection.keySet().stream().map(String::valueOf).toList())
                .containsExactlyInAnyOrder(
                        "draft_id",
                        "expected_draft_revision",
                        "expected_case_version",
                        "task_id",
                        "task_no",
                        "task_selection_source",
                        "carrier_code",
                        "carrier_selection_source",
                        "actual_quantity",
                        "remark_present");
        assertThat(selection.get("remark_present")).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // 确认请求
    // ------------------------------------------------------------------

    private ResponseEntity<Map> confirm(Map<String, Object> draft, String key, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "expected_draft_revision", ((Number) draft.get("revision")).longValue(),
                "expected_case_version", caseVersion(draft)));
        body.putAll(overrides);
        return confirmWithBody(draft, key, body);
    }

    private ResponseEntity<Map> confirmWithBody(
            Map<String, Object> draft, String key, Map<String, Object> body) {
        HttpHeaders headers = writeHeaders(key, "req-" + key);
        return http.exchange(
                "/api/v1/tracking-drafts/" + draft.get("id") + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> rejectDraft(
            Map<String, Object> draft, String key, long caseVersion, String reason) {
        HttpHeaders headers = writeHeaders(key, "req-" + key);
        return http.exchange(
                "/api/v1/tracking-drafts/" + draft.get("id") + "/reject",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_draft_revision", ((Number) draft.get("revision")).longValue(),
                        "expected_case_version", caseVersion,
                        "reason", reason), headers),
                Map.class);
    }

    private ResponseEntity<Map> confirmWithoutGatewayCredentials(
            Map<String, Object> draft, String key, Map<String, Object> overrides) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "expected_draft_revision", ((Number) draft.get("revision")).longValue(),
                "expected_case_version", caseVersion(draft)));
        body.putAll(overrides);
        HttpHeaders headers = writeHeaders(key, "req-" + key);
        headers.remove(HttpHeaders.AUTHORIZATION);
        return http.exchange(
                "/api/v1/tracking-drafts/" + draft.get("id") + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private Map<String, Object> batchLine(Map<String, Object> draft, String lineKey) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("draft_id", draft.get("id"));
        line.put("idempotency_key", lineKey);
        line.put("expected_draft_revision", ((Number) draft.get("revision")).longValue());
        line.put("expected_case_version", caseVersion(draft));
        return line;
    }

    private ResponseEntity<Map> batchConfirm(List<Map<String, Object>> lines, String batchKey) {
        HttpHeaders headers = writeHeaders(batchKey, "req-" + batchKey);
        return http.exchange(
                "/api/v1/tracking-drafts/batch-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("lines", lines), headers),
                Map.class);
    }

    private static Map<String, Object> line(
            String name, String trackingNo, String taskNo, String carrier, String shipment) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("name", name);
        line.put("tracking_no", trackingNo);
        line.put("task_no", taskNo);
        line.put("carrier", carrier);
        line.put("shipment", shipment);
        return line;
    }

    private static Map<String, Object> lines(Map<String, Object>... values) {
        return Map.of("lines", List.of(values));
    }

    private long countSubmissionCases(String reasonCode) {
        return listReviewCases().stream()
                .filter(c -> "MESSAGE_SUBMISSION".equals(c.get("subject_type")))
                .filter(c -> reasonCode == null || reasonCode.equals(c.get("reason_code")))
                .count();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listReviewCases() {
        ResponseEntity<Map> response = exchangeGet("/api/v1/review-cases?size=200");
        return ((List<?>) response.getBody().get("items")).stream()
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    // ------------------------------------------------------------------
    // 消息与 HTTP 助手
    // ------------------------------------------------------------------

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
        Object submissionId = detail.getBody() == null ? null : detail.getBody().get("submission_id");
        if (submissionId == null) {
            return null;
        }
        ResponseEntity<Map> submission = exchangeGet("/api/v1/message-submissions/" + submissionId);
        return submission.getBody();
    }

    private Map<String, Object> listChannelMessages() {
        ResponseEntity<Map> response = exchangeGet("/api/v1/channel-messages?size=200");
        return response.getBody();
    }

    private ResponseEntity<Map> exchangeGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", OPERATOR);
        return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private void postAndReceipt(String messageId, String content, int nonceSeq) {
        String plaintext = textMessage(messageId, BOT_ID, ALLOWED_GROUP, "USER-FWD-" + nonceSeq, content, false);
        dispatch(plaintext);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(OPERATOR, ADMIN_PASSWORD);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", OPERATOR);
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
