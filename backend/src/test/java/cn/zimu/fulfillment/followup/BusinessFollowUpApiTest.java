package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Issue #146: Business Follow-up material intake through the public HTTP seam. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false"
        })
class BusinessFollowUpApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private MessageSubmissionService submissions;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private AsyncTaskStore tasks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void employeeMaterialCreatesOneStableFollowUpAndReplayReturnsIt() {
        long submissionId = sourceSubmission("followup-create");
        Map<String, Object> request = Map.of(
                "message_submission_id", submissionId,
                "employee_draft", "客户希望先看牛肩切片样品，再确认月结正式订单");

        ResponseEntity<Map> created = create(request, "followup-create-001");
        ResponseEntity<Map> replayed = create(request, "followup-create-001");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(created.getBody());
        assertThat(created.getBody())
                .containsEntry("message_submission_id", String.valueOf(submissionId))
                .containsEntry("stage", "PENDING_ORGANIZATION")
                .containsEntry("processing_status", "NOT_STARTED")
                .containsEntry("source_revision", 1)
                .containsEntry("created_by", "manager-zhang");
        assertThat(String.valueOf(created.getBody().get("followup_no"))).startsWith("BF-");

        String id = String.valueOf(created.getBody().get("id"));
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .containsEntry("id", id)
                .containsEntry("employee_draft", "客户希望先看牛肩切片样品，再确认月结正式订单");
        ResponseEntity<Map> page = http.exchange(
                "/api/v1/business-followups?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        Map<?, ?> summary = (Map<?, ?>) ((java.util.List<?>) page.getBody().get("items")).getFirst();
        assertThat(summary.containsKey("employee_draft")).isFalse();
        assertThat(summary.get("task_failure_code")).isNull();
        String idempotencySnapshot = jdbc.queryForObject(
                "SELECT response_snapshot::text FROM app.idempotency_registry "
                        + "WHERE scope = 'business_followup.create' AND idempotency_key = ?",
                String.class,
                "followup-create-001");
        assertThat(idempotencySnapshot).doesNotContain("客户希望先看牛肩切片样品");
        String auditPayload = jdbc.queryForObject(
                """
                SELECT coalesce(request_payload::text, '') || coalesce(response_payload::text, '')
                FROM app.audit_logs
                WHERE operation = 'business_followup.create'
                  AND (response_payload ->> 'followup_id')::bigint = ?
                ORDER BY id DESC LIMIT 1
                """,
                String.class,
                Long.parseLong(id));
        assertThat(auditPayload).doesNotContain("客户希望先看牛肩切片样品");
    }

    @Test
    void sameSourceConvergesAcrossDifferentRequestKeysWithoutTextDeduplication() {
        long sharedSubmission = sourceSubmission("followup-source-dedup");
        Map<String, Object> shared = Map.of(
                "message_submission_id", sharedSubmission,
                "employee_draft", "相同来源第一次提交");

        ResponseEntity<Map> first = create(shared, "followup-source-key-001");
        ResponseEntity<Map> second = create(shared, "followup-source-key-002");
        ResponseEntity<Map> sameTextDifferentSource = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-independent"),
                        "employee_draft", "相同来源第一次提交"),
                "followup-source-key-003");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(sameTextDifferentSource.getBody().get("id"))
                .isNotEqualTo(first.getBody().get("id"));
    }

    @Test
    void legacyCreateDefaultsToCustomerAndBusinessKindIsVisibleWithoutLeakingPlanInList() {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-legacy-kind"),
                        "employee_draft", "仅整理客户跟进档案"),
                "followup-legacy-kind-create");
        String id = String.valueOf(created.getBody().get("id"));

        assertThat(created.getBody()).containsEntry("business_kind", "CUSTOMER");
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(detail.getBody())
                .containsEntry("business_kind", "CUSTOMER")
                .containsEntry("execution_plan", null);

        ResponseEntity<Map> page = http.exchange(
                "/api/v1/business-followups?page=0&size=200",
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        Map<?, ?> row = ((java.util.List<Map<?, ?>>) page.getBody().get("items")).stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(row.get("business_kind")).isEqualTo("CUSTOMER");
        assertThat(row.containsKey("execution_plan")).isFalse();

        Map<String, Object> explicitNullKind = new java.util.LinkedHashMap<>();
        explicitNullKind.put("message_submission_id", sourceSubmission("followup-null-kind"));
        explicitNullKind.put("employee_draft", "旧客户端显式 null 与省略等价");
        explicitNullKind.put("business_kind", null);
        ResponseEntity<Map> nullKind = create(explicitNullKind, "followup-null-kind-create");
        assertThat(nullKind.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(nullKind.getBody()).containsEntry("business_kind", "CUSTOMER");
    }

    @Test
    void sampleCreateRequiresAndPersistsTheExplicitKehuzxWriteContract() {
        long submissionId = sourceSubmission("followup-sample-plan");
        Map<String, Object> plan = Map.of(
                "sample_name", "牛肩切片样品",
                "product_name", "牛肩切片",
                "quantity_per_unit", 0.5,
                "quantity_unit", "kg",
                "unit_count", 4,
                "requested_date", "2026-09-01",
                "commercial_terms", Map.of("payment_terms", "到付"),
                "business_note", "仅限本次确认样品");

        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", submissionId,
                        "employee_draft", "这里的文字不得成为执行计划",
                        "business_kind", "SAMPLE",
                        "execution_plan", plan),
                "followup-sample-plan-create");
        String id = String.valueOf(created.getBody().get("id"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("business_kind", "SAMPLE");
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(detail.getBody())
                .containsEntry("business_kind", "SAMPLE")
                .containsEntry("execution_plan", plan);
        assertThat(jdbc.queryForMap(
                        "SELECT business_kind, execution_plan::text AS execution_plan "
                                + "FROM app.business_followups WHERE id=?",
                        Long.parseLong(id)))
                .containsEntry("business_kind", "SAMPLE");
    }

    @Test
    void formalCreatePersistsAndReturnsTheExplicitOrderContract() {
        Map<String, Object> plan = Map.of(
                "order_type", "formal",
                "name", "月度供货订单",
                "delivery_date", "2026-09-10",
                "delivery_address", "已授权交付地址",
                "items", java.util.List.of(Map.of(
                        "product_name", "牛肩切片",
                        "quantity_per_unit", 10,
                        "quantity_unit", "kg",
                        "unit_count", 5)));
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-formal-plan"),
                        "employee_draft", "正式订单文字只作证据",
                        "business_kind", "FORMAL",
                        "execution_plan", plan),
                "followup-formal-plan-create");
        String id = String.valueOf(created.getBody().get("id"));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("business_kind", "FORMAL");
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(detail.getBody())
                .containsEntry("business_kind", "FORMAL")
                .containsEntry("execution_plan", plan);
    }

    @Test
    void sampleAndFormalKindsFailClosedOnIncompleteOrDriftingPlans() {
        ResponseEntity<Map> incompleteSample = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-sample-invalid"),
                        "employee_draft", "缺少权威样品字段",
                        "business_kind", "SAMPLE",
                        "execution_plan", Map.of("sample_name", "不完整样品")),
                "followup-sample-invalid-create");
        assertThat(incompleteSample.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(incompleteSample.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");

        ResponseEntity<Map> driftingFormal = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-formal-invalid"),
                        "employee_draft", "不得把草稿文字当作正式订单",
                        "business_kind", "FORMAL",
                        "execution_plan", Map.of(
                                "order_type", "sample",
                                "name", "月度供货订单",
                                "delivery_date", "2026-09-10",
                                "delivery_address", "已授权交付地址",
                                "items", java.util.List.of(Map.of(
                                        "product_name", "牛肩切片",
                                        "quantity_per_unit", 10,
                                        "quantity_unit", "kg",
                                        "unit_count", 5)))),
                "followup-formal-invalid-create");
        assertThat(driftingFormal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(driftingFormal.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");

        Map<String, Object> sampleWithInjectedTarget = new java.util.LinkedHashMap<>();
        sampleWithInjectedTarget.put("sample_name", "样品 A");
        sampleWithInjectedTarget.put("product_name", "产品 A");
        sampleWithInjectedTarget.put("quantity_per_unit", 1);
        sampleWithInjectedTarget.put("quantity_unit", "kg");
        sampleWithInjectedTarget.put("unit_count", 1);
        sampleWithInjectedTarget.put("requested_date", "2026-09-01");
        sampleWithInjectedTarget.put("customer_id", "model-injected-customer");
        ResponseEntity<Map> unknownSampleField = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-sample-unknown-field"),
                        "employee_draft", "未授权目标字段必须被拒绝",
                        "business_kind", "SAMPLE",
                        "execution_plan", sampleWithInjectedTarget),
                "followup-sample-unknown-field-create");
        assertThat(unknownSampleField.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownSampleField.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");

        Map<String, Object> sampleWithNull = new java.util.LinkedHashMap<>(sampleWithInjectedTarget);
        sampleWithNull.remove("customer_id");
        sampleWithNull.put("business_note", null);
        ResponseEntity<Map> explicitNull = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-sample-explicit-null"),
                        "employee_draft", "显式 null 不得产生等价性漂移",
                        "business_kind", "SAMPLE",
                        "execution_plan", sampleWithNull),
                "followup-sample-explicit-null-create");
        assertThat(explicitNull.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(explicitNull.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");

        Map<String, Object> formalItem = new java.util.LinkedHashMap<>();
        formalItem.put("product_name", "牛肩切片");
        formalItem.put("quantity_per_unit", 10);
        formalItem.put("quantity_unit", "kg");
        formalItem.put("unit_count", 5);
        formalItem.put("status", "submitted");
        ResponseEntity<Map> unknownFormalItemField = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-formal-item-unknown"),
                        "employee_draft", "未授权行状态必须被拒绝",
                        "business_kind", "FORMAL",
                        "execution_plan", Map.of(
                                "order_type", "formal",
                                "name", "月度供货订单",
                                "delivery_date", "2026-09-10",
                                "delivery_address", "已授权交付地址",
                                "items", java.util.List.of(formalItem))),
                "followup-formal-item-unknown-create");
        assertThat(unknownFormalItemField.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknownFormalItemField.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");
    }

    @Test
    void executionPlansRejectValuesOutsideKehuzxStorageBounds() throws Exception {
        Map<String, Object> validSample = Map.of(
                "sample_name", "样品 A",
                "product_name", "产品 A",
                "quantity_per_unit", 1,
                "quantity_unit", "kg",
                "unit_count", 1,
                "requested_date", "2026-09-01");
        Map<String, Object> longName = new java.util.LinkedHashMap<>(validSample);
        longName.put("sample_name", "x".repeat(201));
        Map<String, Object> excessiveScale = new java.util.LinkedHashMap<>(validSample);
        excessiveScale.put("quantity_per_unit", 0.0004);

        int index = 0;
        for (Map<String, Object> invalid : java.util.List.of(longName, excessiveScale)) {
            String suffix = "followup-sample-storage-bound-" + index++;
            ResponseEntity<Map> response = create(
                    Map.of(
                            "message_submission_id", sourceSubmission(suffix),
                            "employee_draft", "超出 Kehuzx 存储边界的计划",
                            "business_kind", "SAMPLE",
                            "execution_plan", invalid),
                    suffix + "-create");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");
        }

        ResponseEntity<Map> longAddress = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-formal-address-bound"),
                        "employee_draft", "超出 Kehuzx 地址边界的计划",
                        "business_kind", "FORMAL",
                        "execution_plan", Map.of(
                                "order_type", "formal",
                                "name", "月度供货订单",
                                "delivery_date", "2026-09-10",
                                "delivery_address", "x".repeat(501),
                                "items", java.util.List.of(Map.of(
                                        "product_name", "牛肩切片",
                                        "quantity_per_unit", 10,
                                        "quantity_unit", "kg",
                                        "unit_count", 5)))),
                "followup-formal-address-bound-create");
        assertThat(longAddress.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(longAddress.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_INVALID");

        var nonFinite = mapper.readTree("""
                {"sample_name":"样品 A","product_name":"产品 A",
                 "quantity_per_unit":1e309,"quantity_unit":"kg","unit_count":1,
                 "requested_date":"2026-09-01"}
                """);
        assertThatThrownBy(() -> BusinessFollowUpExecutionPlan.validate(
                        BusinessFollowUpBusinessKind.SAMPLE, nonFinite))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sameSourceCannotSilentlyReplaceAnExplicitExecutionPlan() {
        long submissionId = sourceSubmission("followup-plan-conflict");
        Map<String, Object> firstPlan = Map.of(
                "sample_name", "样品 A",
                "product_name", "产品 A",
                "quantity_per_unit", 1,
                "quantity_unit", "kg",
                "unit_count", 1,
                "requested_date", "2026-09-01");
        Map<String, Object> changedPlan = new java.util.LinkedHashMap<>(firstPlan);
        changedPlan.put("unit_count", 2);
        Map<String, Object> equivalentPlan = new java.util.LinkedHashMap<>(firstPlan);
        equivalentPlan.put("quantity_per_unit", 1.0);
        Map<String, Object> first = Map.of(
                "message_submission_id", submissionId,
                "employee_draft", "第一次提交",
                "business_kind", "SAMPLE",
                "execution_plan", firstPlan);
        Map<String, Object> changed = Map.of(
                "message_submission_id", submissionId,
                "employee_draft", "第二次提交",
                "business_kind", "SAMPLE",
                "execution_plan", changedPlan);

        ResponseEntity<Map> created = create(first, "followup-plan-conflict-first");
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> equivalent = create(
                Map.of(
                        "message_submission_id", submissionId,
                        "employee_draft", "等价数字表示重放",
                        "business_kind", "SAMPLE",
                        "execution_plan", equivalentPlan),
                "followup-plan-conflict-equivalent");
        assertThat(equivalent.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(equivalent.getBody()).containsEntry("id", created.getBody().get("id"));
        ResponseEntity<Map> conflict = create(changed, "followup-plan-conflict-second");
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody())
                .containsEntry("business_code", "FOLLOWUP_EXECUTION_PLAN_CONFLICT");
    }

    @Test
    void plusOnePinsAnEnabledAgentVersionAndQueuesOneRecoverableTask() {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-organize"),
                        "employee_draft", "请整理客户订单跟进"),
                "followup-organize-create-001");
        String id = String.valueOf(created.getBody().get("id"));
        Map<String, Object> request = Map.of(
                "agent_slug", "customer-followup-agent",
                "agent_version", 1);

        ResponseEntity<Map> started = organize(id, request, "followup-organize-start-001");
        ResponseEntity<Map> replayed = organize(id, request, "followup-organize-start-001");
        ResponseEntity<Map> secondKey = organize(id, request, "followup-organize-start-003");

        assertThat(started.getStatusCode())
                .as(String.valueOf(started.getBody()))
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replayed.getBody()).isEqualTo(started.getBody());
        assertThat(secondKey.getBody()).isEqualTo(started.getBody());
        assertThat(started.getBody())
                .containsEntry("stage", "ORGANIZING")
                .containsEntry("processing_status", "PENDING")
                .containsEntry("designated_reviewer", "跟进审批人")
                .containsEntry("agent_slug", "customer-followup-agent")
                .containsEntry("agent_version", 1)
                .containsEntry("task_status", "PENDING");
        jdbc.update(
                "UPDATE app.async_tasks SET last_error = ? WHERE payload_ref LIKE ?",
                "password=must-not-leak",
                "business-followup:" + id + ":%");
        ResponseEntity<Map> sanitizedDetail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(sanitizedDetail.getBody())
                .containsEntry("task_failure_code", "FOLLOWUP_ORGANIZATION_FAILED");
        assertThat(String.valueOf(sanitizedDetail.getBody())).doesNotContain("must-not-leak");

        ResponseEntity<Map> drifted = organize(
                id,
                Map.of("agent_slug", "customer-followup-agent", "agent_version", 999),
                "followup-organize-start-002");
        assertThat(drifted.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(drifted.getBody())
                .containsEntry("business_code", "AGENT_VERSION_MISMATCH");

        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/business-followups/" + id,
                HttpMethod.GET,
                new HttpEntity<>(readHeaders()),
                Map.class);
        assertThat(detail.getBody()).containsEntry("task_status", "PENDING");

        Integer taskCount = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE task_type = ? AND payload_ref LIKE ?",
                Integer.class,
                BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                "business-followup:" + id + ":%");
        assertThat(taskCount).isEqualTo(1);
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status = 'SUCCEEDED', lease_owner = NULL, lease_until = NULL
                WHERE task_type = ? AND payload_ref NOT LIKE ?
                """,
                BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                "business-followup:" + id + ":%");

        AsyncTaskStore.AsyncTask firstClaim = tasks.claim(
                        BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                        "organize-worker-before-restart",
                        Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(firstClaim.payloadRef()).startsWith("business-followup:" + id + ":revision:");
        assertThat(tasks.releaseOwnedForShutdown(
                        firstClaim.id(), "organize-worker-before-restart"))
                .isTrue();
        AsyncTaskStore.AsyncTask recovered = tasks.claim(
                        BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                        "organize-worker-after-restart",
                        Duration.ofSeconds(30))
                .orElseThrow();
        assertThat(recovered.id()).isEqualTo(firstClaim.id());
        assertThat(recovered.payloadRef()).isEqualTo(firstClaim.payloadRef());
        assertThat(tasks.releaseOwnedForShutdown(
                        recovered.id(), "organize-worker-after-restart"))
                .isTrue();
    }

    @Test
    void concurrentPlusOneStartsConvergeOnOneTask() throws Exception {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-concurrent"),
                        "employee_draft", "并发发起整理"),
                "followup-concurrent-create-001");
        String id = String.valueOf(created.getBody().get("id"));
        Map<String, Object> request = Map.of(
                "agent_slug", "customer-followup-agent",
                "agent_version", 1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<ResponseEntity<Map>> first = CompletableFuture.supplyAsync(
                    () -> organize(id, request, "followup-concurrent-start-001"), executor);
            CompletableFuture<ResponseEntity<Map>> second = CompletableFuture.supplyAsync(
                    () -> organize(id, request, "followup-concurrent-start-002"), executor);

            assertThat(first.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.get().getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE payload_ref LIKE ?",
                Integer.class,
                "business-followup:" + id + ":%");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sameAgentVersionCannotReplayOrganizationWithADifferentReviewer() {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-reviewer-idempotency"),
                        "employee_draft", "审批人是组织命令的一部分"),
                "followup-reviewer-idempotency-create");
        String id = String.valueOf(created.getBody().get("id"));
        long firstReviewer = reviewerOperatorId();
        long secondReviewer = jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES ('另一位跟进审批人', 'CUSTOMER_OPS', ?, true)
                RETURNING id
                """,
                Long.class,
                "followup-reviewer-" + UUID.randomUUID());

        ResponseEntity<Map> started = organize(
                id,
                Map.of(
                        "agent_slug", "customer-followup-agent",
                        "agent_version", 1,
                        "reviewer_operator_id", String.valueOf(firstReviewer)),
                "followup-reviewer-idempotency-start-1");
        ResponseEntity<Map> conflicting = organize(
                id,
                Map.of(
                        "agent_slug", "customer-followup-agent",
                        "agent_version", 1,
                        "reviewer_operator_id", String.valueOf(secondReviewer)),
                "followup-reviewer-idempotency-start-2");

        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicting.getBody())
                .containsEntry("business_code", "FOLLOWUP_ALREADY_ORGANIZING");
        assertThat(jdbc.queryForObject(
                "SELECT designated_reviewer_operator_id FROM app.business_followups WHERE id=?",
                Long.class,
                Long.parseLong(id))).isEqualTo(firstReviewer);
    }

    @Test
    void organizeIdempotencyKeyIsBoundToTheFollowUpPathTarget() {
        String firstId = String.valueOf(create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-idem-first"),
                        "employee_draft", "第一个跟进"),
                "followup-idem-first-create").getBody().get("id"));
        String secondId = String.valueOf(create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-idem-second"),
                        "employee_draft", "第二个跟进"),
                "followup-idem-second-create").getBody().get("id"));
        Map<String, Object> request = Map.of(
                "agent_slug", "customer-followup-agent",
                "agent_version", 1);

        ResponseEntity<Map> first = organize(firstId, request, "followup-cross-target-key");
        ResponseEntity<Map> second = organize(secondId, request, "followup-cross-target-key");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("business_code", "IDEMPOTENCY_CONFLICT");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE payload_ref LIKE ?",
                        Integer.class,
                        "business-followup:" + secondId + ":%"))
                .isZero();
    }

    @Test
    void missingAgentFailsClosedWithoutQueuingWork() {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-agent-missing"),
                        "employee_draft", "无效 Agent 不应运行"),
                "followup-agent-missing-create");
        String id = String.valueOf(created.getBody().get("id"));

        ResponseEntity<Map> response = organize(
                id,
                Map.of("agent_slug", "missing-customer-agent", "agent_version", 1),
                "followup-agent-missing-start");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "FOLLOWUP_AGENT_REQUIRED");
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE payload_ref LIKE ?",
                Integer.class,
                "business-followup:" + id + ":%");
        assertThat(count).isZero();
    }

    @Test
    void persistedAgentDisableWinsOverAStaleInMemoryRegistrySnapshot() {
        ResponseEntity<Map> created = create(
                Map.of(
                        "message_submission_id", sourceSubmission("followup-agent-disabled"),
                        "employee_draft", "禁用 Agent 不应运行"),
                "followup-agent-disabled-create");
        String id = String.valueOf(created.getBody().get("id"));
        jdbc.update(
                "UPDATE app.agent_definitions SET enabled = false "
                        + "WHERE agent_slug = 'customer-followup-agent' AND version = 1");
        try {
            ResponseEntity<Map> response = organize(
                    id,
                    Map.of("agent_slug", "customer-followup-agent", "agent_version", 1),
                    "followup-agent-disabled-start");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(response.getBody()).containsEntry("business_code", "AGENT_NOT_ENABLED");
        } finally {
            jdbc.update(
                    "UPDATE app.agent_definitions SET enabled = true "
                            + "WHERE agent_slug = 'customer-followup-agent' AND version = 1");
        }
    }

    private ResponseEntity<Map> create(Map<String, Object> body, String key) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(body);
        request.computeIfPresent("message_submission_id", (ignored, value) -> String.valueOf(value));
        HttpHeaders headers = readHeaders();
        headers.set("Idempotency-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/business-followups",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);
    }

    private ResponseEntity<Map> organize(String id, Map<String, Object> body, String key) {
        Map<String, Object> request = new java.util.LinkedHashMap<>(body);
        request.putIfAbsent("reviewer_operator_id", String.valueOf(reviewerOperatorId()));
        HttpHeaders headers = readHeaders();
        headers.set("Idempotency-Key", key);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/business-followups/" + id + "/organize",
                HttpMethod.POST,
                new HttpEntity<>(request, headers),
                Map.class);
    }

    private long reviewerOperatorId() {
        Long existing = jdbc.query(
                        "SELECT id FROM app.internal_operators WHERE wecom_userid='followup-reviewer'",
                        (rs, row) -> rs.getLong(1))
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing;
        }
        return jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES ('跟进审批人', 'CUSTOMER_OPS', 'followup-reviewer', true)
                RETURNING id
                """,
                Long.class);
    }

    private HttpHeaders readHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "manager-zhang");
        return headers;
    }

    private long sourceSubmission(String suffix) {
        String messageId = suffix + "-" + UUID.randomUUID();
        return submissions.submit(new ChannelMessageCommand(
                "corp-followup",
                "connection-followup",
                "bot-followup",
                messageId,
                "chat-followup",
                "single",
                "employee-followup",
                "text",
                "客户面谈材料",
                null,
                null,
                mapper.createObjectNode().put("message_id", messageId)));
    }
}
