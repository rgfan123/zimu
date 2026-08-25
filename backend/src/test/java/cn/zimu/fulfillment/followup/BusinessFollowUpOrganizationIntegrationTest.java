package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(properties = {
        "app.message-worker.enabled=false",
        "app.followup-worker.enabled=false",
        "app.mcp.enabled=false"
})
class BusinessFollowUpOrganizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BusinessFollowUpService followUps;
    @Autowired BusinessFollowUpOrganizationService organization;
    @Autowired BusinessFollowUpDraftApplicationService application;
    @Autowired MessageSubmissionService submissions;
    @Autowired AsyncTaskStore tasks;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @MockitoBean AgentRuntimeFacade agents;

    @Test
    void modelInvocationReceivesBusinessIdentifiersButNeverEmployeeFreeText() throws Exception {
        AtomicReference<String> observedInput = new AtomicReference<>();
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any()))
                .thenAnswer(invocation -> {
                    observedInput.set(invocation.getArgument(3));
                    return agentResult("run_safe_input", true, List.of());
                });
        queued(
                "safe-model-input",
                "张三说送到海淀区知春路27号，手机 138 0000 0000；"
                        + "请核对客户 KH-260826-001 和订单 FO-20260826-19");

        runWorkerOnce();

        var input = mapper.readTree(observedInput.get());
        assertThat(input.has("employee_draft")).isFalse();
        assertThat(input.path("customer_identifiers"))
                .isEqualTo(mapper.valueToTree(List.of("KH-260826-001")));
        assertThat(observedInput.get())
                .doesNotContain("张三", "知春路", "138 0000 0000");
    }

    @Test
    void uniqueRemoteCustomerProducesVersionedDraftWithSeparateSources() {
        long followupId = queued("unique-customer");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_unique_customer";
            recordCustomerEvidence(runId, List.of(Map.of(
                    "id", "kehuzx-customer-1",
                    "customer_id", "kehuzx-customer-1",
                    "code", "KH-260826-001",
                    "name", "华北餐饮")));
            return AgentRunResult.success(
                            mapper.valueToTree(Map.of(
                                    "title", "华北餐饮跟进",
                                    "summary", "待确认样品需求",
                                    "facts", List.of(Map.of(
                                            "source", "KEHUZX",
                                            "label", "客户编号",
                                            "value", "KH-260826-001")),
                                    "requires_human", false,
                                    "missing_fields", List.of())),
                            "stub", "stub", "customer-followup-v1")
                    .withRunMetadata(runId, 2);
        });

        runWorkerOnce();

        assertThat(jdbc.queryForObject(
                        "SELECT stage FROM app.business_followups WHERE id = ?",
                        String.class, followupId))
                .isEqualTo("DRAFT_READY");
        Map<String, Object> draft = jdbc.queryForMap(
                """
                SELECT version, status, agent_run_id,
                       zimu_source_summary::text AS zimu,
                       kehuzx_source_summary::text AS kehuzx,
                       upstream_refs::text AS refs
                FROM app.business_followup_draft_versions WHERE followup_id = ?
                """,
                followupId);
        assertThat(draft)
                .containsEntry("version", 1)
                .containsEntry("status", "DRAFT")
                .containsEntry("agent_run_id", "run_unique_customer");
        assertThat(String.valueOf(draft.get("zimu"))).contains("ZIMU");
        assertThat(String.valueOf(draft.get("kehuzx")))
                .contains("KEHUZX", "kehuzx-mcp-v1", "c6a2418");
        assertThat(String.valueOf(draft.get("refs"))).contains("kehuzx-customer-1");
    }

    @Test
    void ambiguousRemoteCustomersCannotBecomeReadyDraftAndCreateReview() {
        long followupId = queued("ambiguous-customer");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_ambiguous_customer";
            recordCustomerEvidence(runId, List.of(
                    Map.of("id", "customer-a", "name", "同名客户"),
                    Map.of("id", "customer-b", "name", "同名客户")));
            return AgentRunResult.success(
                            mapper.valueToTree(Map.of(
                                    "title", "错误的确定草稿",
                                    "summary", "模型错误地声称已确认",
                                    "facts", List.of(),
                                    "requires_human", false,
                                    "missing_fields", List.of())),
                            "stub", "stub", "customer-followup-v1")
                    .withRunMetadata(runId, 2);
        });

        runWorkerOnce();

        assertThat(jdbc.queryForObject(
                        "SELECT stage FROM app.business_followups WHERE id = ?",
                        String.class, followupId))
                .isEqualTo("NEEDS_INPUT");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.business_followup_draft_versions WHERE followup_id = ?",
                        String.class, followupId))
                .isEqualTo("NEEDS_INPUT");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.review_cases rc
                        JOIN app.business_followups bf ON bf.message_submission_id = rc.message_submission_id
                        WHERE bf.id = ? AND rc.status = 'OPEN'
                        """,
                        Integer.class, followupId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT detail ->> 'followup_reason_code' FROM app.review_cases rc
                        JOIN app.business_followups bf
                          ON bf.message_submission_id = rc.message_submission_id
                        WHERE bf.id = ? AND rc.status = 'OPEN'
                        """,
                        String.class, followupId))
                .isEqualTo("KEHUZX_CUSTOMER_AMBIGUOUS");
    }

    @Test
    void zeroRemoteCustomersNeedsInputWithoutInventingIdentity() {
        long followupId = queued("zero-customer");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_zero_customer";
            recordCustomerEvidence(runId, List.of());
            return agentResult(runId, false, List.of());
        });

        runWorkerOnce();

        assertNeedsInput(followupId, "KEHUZX_CUSTOMER_NOT_RESOLVED");
    }

    @Test
    void modelRequiresHumanWinsEvenWithOneCustomer() {
        long followupId = queued("model-needs-human");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_model_needs_human";
            recordCustomerEvidence(runId, List.of(Map.of(
                    "id", "customer-1", "customer_id", "customer-1",
                    "code", "KH-260826-001", "name", "华北餐饮")));
            return agentResult(runId, true, List.of());
        });

        runWorkerOnce();

        assertNeedsInput(followupId, "FOLLOWUP_REQUIRES_HUMAN");
    }

    @Test
    void stableRemoteTimeoutIsBusinessEvidenceAndCannotBeIgnoredByTheModel() {
        long followupId = queued("remote-timeout");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_remote_timeout";
            recordCustomerEvidence(runId, List.of(Map.of(
                    "id", "customer-1", "customer_id", "customer-1",
                    "code", "KH-260826-001", "name", "华北餐饮")));
            jdbc.update(
                    """
                    INSERT INTO app.kehuzx_read_failures
                        (agent_run_id, tool_name, failure_code, contract_version,
                         upstream_commit, queried_at)
                    VALUES (?, 'get_customer_detail', 'KEHUZX_TIMEOUT',
                            'kehuzx-mcp-v1', 'c6a2418', CURRENT_TIMESTAMP)
                    """,
                    runId);
            return agentResult(runId, false, List.of());
        });

        runWorkerOnce();

        assertNeedsInput(followupId, "KEHUZX_TIMEOUT");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT kehuzx_source_summary -> 'failures' ->> 0
                        FROM app.business_followup_draft_versions WHERE followup_id = ?
                        """,
                        String.class, followupId))
                .isEqualTo("KEHUZX_TIMEOUT");
    }

    @Test
    void anotherCustomersOrderCannotEnterFactsOrUpstreamRefs() {
        long followupId = queued("foreign-order");
        when(agents.invokePinnedWithRunId(eq("customer-followup-agent"), eq(1), any(), any(), any())).thenAnswer(invocation -> {
            String runId = "run_foreign_order";
            recordCustomerEvidence(runId, List.of(Map.of(
                    "id", "customer-1", "customer_id", "customer-1",
                    "code", "KH-260826-001", "name", "华北餐饮")));
            recordEvidence(runId, "search_orders", Map.of(
                    "total", 1,
                    "items", List.of(Map.of(
                            "id", "foreign-order-1",
                            "customer_id", "customer-2",
                            "code", "SO-FOREIGN"))));
            return agentResult(runId, false, List.of(Map.of(
                    "source", "KEHUZX", "label", "订单编号", "value", "SO-FOREIGN")));
        });

        runWorkerOnce();

        assertNeedsInput(followupId, "FOLLOWUP_FACT_NOT_EVIDENCED");
        String projected = jdbc.queryForObject(
                """
                SELECT content::text || upstream_refs::text
                FROM app.business_followup_draft_versions WHERE followup_id = ?
                """,
                String.class, followupId);
        assertThat(projected).doesNotContain("SO-FOREIGN", "foreign-order-1");
    }

    @Test
    void reclaimedTaskRejectsOldOwnerAndReplayCannotCreateAnotherVersion() {
        long followupId = queued("lease-reclaim");
        AsyncTaskStore.AsyncTask oldClaim = tasks.claim(
                        BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                        "old-owner",
                        Duration.ofSeconds(30))
                .orElseThrow();
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' WHERE id = ?",
                oldClaim.id());
        AsyncTaskStore.AsyncTask newClaim = tasks.claim(
                        BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                        "new-owner",
                        Duration.ofSeconds(30))
                .orElseThrow();
        BusinessFollowUpOrganizationService.Work work = organization.load(newClaim.payloadRef());
        String runId = "run_reclaimed_task";
        recordCustomerEvidence(runId, List.of(Map.of(
                "id", "customer-1", "customer_id", "customer-1",
                "code", "KH-260826-001", "name", "华北餐饮")));
        AgentRunResult result = agentResult(runId, false, List.of());

        assertThatThrownBy(() -> application.apply(oldClaim, "old-owner", work, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.business_followup_draft_versions WHERE followup_id = ?",
                        Integer.class, followupId))
                .isZero();

        application.apply(newClaim, "new-owner", work, result);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.business_followup_draft_versions WHERE followup_id = ?",
                        Integer.class, followupId))
                .isEqualTo(1);
        assertThatThrownBy(() -> application.apply(newClaim, "new-owner", work, result))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租约");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.business_followup_draft_versions WHERE followup_id = ?",
                        Integer.class, followupId))
                .isEqualTo(1);
    }

    private long queued(String suffix) {
        return queued(suffix, "华北餐饮希望确认牛肉样品和后续订单");
    }

    private long queued(String suffix, String employeeDraft) {
        String messageId = suffix + "-" + UUID.randomUUID();
        long submissionId = submissions.submit(new ChannelMessageCommand(
                "corp-followup", "connection-followup", "bot-followup", messageId,
                "chat-followup", "single", "employee-followup", "text",
                "客户面谈材料", null, null,
                mapper.createObjectNode().put("message_id", messageId)));
        CommandContext context = new CommandContext("request-" + suffix, "trace-" + suffix,
                "manager-zhang", "manager-zhang");
        long followupId = Long.parseLong(followUps.create(
                new BusinessFollowUpService.CreateCommand(
                        submissionId, employeeDraft),
                context).id());
        followUps.organize(
                new BusinessFollowUpService.OrganizeCommand(
                        followupId, "customer-followup-agent", 1),
                context);
        return followupId;
    }

    private void recordCustomerEvidence(String runId, List<Map<String, Object>> items) {
        recordEvidence(runId, "search_customers", Map.of("total", items.size(), "items", items));
    }

    private void recordEvidence(String runId, String toolName, Map<String, Object> data) {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>(Map.of(
                "source", "KEHUZX",
                "contract_version", "kehuzx-mcp-v1",
                "upstream_commit", "c6a2418",
                "queried_at", "2026-08-26T04:00:00Z",
                "data", data));
        if ("search_customers".equals(toolName)) {
            envelope.put("authorized_customer_code", "KH-260826-001");
        }
        String payload = mapper.valueToTree(envelope).toString();
        jdbc.update(
                """
                INSERT INTO app.kehuzx_read_evidence
                    (agent_run_id, tool_name, arguments_digest, response_digest,
                     response_payload, contract_version, upstream_commit, queried_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb),
                        'kehuzx-mcp-v1', 'c6a2418', '2026-08-26T04:00:00Z')
                """,
                runId, toolName, "a".repeat(64), "b".repeat(64), payload);
    }

    private AgentRunResult agentResult(
            String runId, boolean requiresHuman, List<Map<String, Object>> facts) {
        return AgentRunResult.success(
                        mapper.valueToTree(Map.of(
                                "title", "客户跟进草稿",
                                "summary", "待人工核对",
                                "facts", facts,
                                "requires_human", requiresHuman,
                                "missing_fields", requiresHuman ? List.of("人工确认") : List.of())),
                        "stub", "stub", "customer-followup-v1")
                .withRunMetadata(runId, 2);
    }

    private void assertNeedsInput(long followupId, String reasonCode) {
        assertThat(jdbc.queryForObject(
                        "SELECT stage FROM app.business_followups WHERE id = ?",
                        String.class, followupId))
                .isEqualTo("NEEDS_INPUT");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.business_followup_draft_versions WHERE followup_id = ?",
                        String.class, followupId))
                .isEqualTo("NEEDS_INPUT");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT detail ->> 'followup_reason_code' FROM app.review_cases rc
                        JOIN app.business_followups bf
                          ON bf.message_submission_id = rc.message_submission_id
                        WHERE bf.id = ? AND rc.status = 'OPEN'
                        """,
                        String.class, followupId))
                .isEqualTo(reasonCode);
    }

    private void runWorkerOnce() {
        new BusinessFollowUpOrganizationWorker(
                tasks, organization, application, true, 30, 1).poll();
    }
}
