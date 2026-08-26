package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Issue #148: confirmed Business Follow-up drafts project independent Assignments. */
@Testcontainers
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.followup-worker.enabled=false",
        "app.followup-approval-worker.enabled=false",
        "app.followup-assignment-worker.enabled=false",
        "app.mcp.enabled=false"
})
class BusinessFollowUpAssignmentIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BusinessFollowUpAssignmentApplication assignments;
    @Autowired BusinessFollowUpService followUps;
    @Autowired AsyncTaskStore tasks;
    @Autowired JdbcTemplate jdbc;

    @Test
    void appliedConfirmationProjectsOneCustomerLinkAndWaitsForTheTicket131Executor() {
        Fixture fixture = confirmed("customer-local-link");
        enqueueProjection(fixture.approvalId(), "first");

        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "projection-owner");
        assignments.project(projection, "projection-owner");

        Map<String, Object> pending = jdbc.queryForMap(
                """
                SELECT followup_id, draft_version, approval_id, agent_run_id,
                       task_type, logical_target, assignee_type, assignee_ref,
                       status, priority, request_id, external_entity_type,
                       external_entity_id, result_code
                FROM app.business_followup_assignments
                WHERE followup_id=?
                """,
                fixture.followupId());
        assertThat(pending)
                .containsEntry("followup_id", fixture.followupId())
                .containsEntry("draft_version", 1)
                .containsEntry("approval_id", fixture.approvalId())
                .containsEntry("agent_run_id", fixture.agentRunId())
                .containsEntry("task_type", "KEHUZX_CUSTOMER_LINK")
                .containsEntry("logical_target", "kehuzx-customer:customer-local-link")
                .containsEntry("assignee_type", "DETERMINISTIC_MCP")
                .containsEntry("assignee_ref", "kehuzx:customer-write")
                .containsEntry("status", "PENDING")
                .containsEntry("priority", "NORMAL")
                .containsEntry("external_entity_type", null)
                .containsEntry("external_entity_id", null)
                .containsEntry("result_code", null);
        assertThat(pending.get("request_id")).isNull();

        AsyncTaskStore.AsyncTask execution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE,
                "execution-owner");
        assignments.execute(execution, "execution-owner");

        assertThat(jdbc.queryForMap(
                        """
                        SELECT status, request_id, external_entity_type,
                               external_entity_id, result_code,
                               started_at IS NOT NULL AS started,
                               completed_at IS NOT NULL AS completed
                        FROM app.business_followup_assignments
                        WHERE followup_id=?
                        """,
                fixture.followupId()))
                .containsEntry("status", "WAITING_HUMAN")
                .containsEntry("external_entity_type", null)
                .containsEntry("external_entity_id", null)
                .containsEntry("result_code", "KEHUZX_CUSTOMER_EXECUTOR_NOT_AVAILABLE")
                .containsEntry("started", true)
                .containsEntry("completed", false);
    }

    @Test
    void duplicateAndConcurrentProjectionCreatesOneAssignmentAndOneExecutionTask() {
        Fixture fixture = confirmed("customer-concurrent-link");
        enqueueProjection(fixture.approvalId(), "concurrent-a");
        enqueueProjection(fixture.approvalId(), "concurrent-b");
        AsyncTaskStore.AsyncTask first = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-concurrent-a");
        AsyncTaskStore.AsyncTask second = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-concurrent-b");

        CompletableFuture<Void> a = CompletableFuture.runAsync(
                () -> assignments.project(first, "projection-concurrent-a"));
        CompletableFuture<Void> b = CompletableFuture.runAsync(
                () -> assignments.project(second, "projection-concurrent-b"));
        CompletableFuture.allOf(a, b).join();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_assignments WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.async_tasks t
                JOIN app.business_followup_assignments a ON t.payload_ref='followup-assignment:' || a.id
                WHERE t.task_type=? AND a.followup_id=?
                """,
                Integer.class,
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE,
                fixture.followupId())).isEqualTo(1);

        AsyncTaskStore.AsyncTask execution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "execution-concurrent");
        assignments.execute(execution, "execution-concurrent");
    }

    @Test
    void supersededConfirmedVersionHasNoAssignmentSideEffect() {
        Fixture fixture = confirmed("customer-stale-link");
        jdbc.update(
                "UPDATE app.business_followups SET current_confirmed_draft_version=NULL WHERE id=?",
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='SUPERSEDED' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());
        enqueueProjection(fixture.approvalId(), "stale");

        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-stale");
        assignments.project(projection, "projection-stale");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_assignments WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void partialUnknownOutcomeDoesNotRollbackSuccessfulSiblingInSameConfirmedVersion() {
        Fixture fixture = confirmed("customer-success-link");
        enqueueProjection(fixture.approvalId(), "partial-outcome");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "projection-partial-outcome");
        assignments.project(projection, "projection-partial-outcome");

        long siblingId = jdbc.queryForObject(
                """
                INSERT INTO app.business_followup_assignments
                    (followup_id, draft_version, approval_id, agent_run_id,
                     task_type, logical_target, assignee_type, assignee_ref,
                     status, due_at, priority, idempotency_key, execution_task_key)
                SELECT followup_id, draft_version, approval_id, agent_run_id,
                       task_type, 'kehuzx-customer:customer-unknown-link',
                       assignee_type, assignee_ref, 'PENDING', due_at, priority,
                       idempotency_key || ':unknown-sibling',
                       execution_task_key || ':unknown-sibling'
                FROM app.business_followup_assignments
                WHERE followup_id=?
                RETURNING id
                """,
                Long.class,
                fixture.followupId());
        tasks.enqueue(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE,
                "followup-assignment:" + siblingId,
                "followup-assignment-test-sibling:" + siblingId,
                3);

        AsyncTaskStore.AsyncTask successfulExecution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "execution-success");
        assertThat(assignments.beginExecution(
                        successfulExecution, "execution-success", "request-outcome-success"))
                .isEqualTo(BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.READY_TO_SUBMIT);
        assignments.recordExecutionOutcome(
                successfulExecution,
                "execution-success",
                "SUCCEEDED",
                "KEHUZX_CUSTOMER",
                "customer-success-link",
                "KEHUZX_CUSTOMER_LINKED");

        AsyncTaskStore.AsyncTask uncertainExecution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "execution-unknown");
        assignments.beginExecution(
                uncertainExecution, "execution-unknown", "request-outcome-unknown");
        assignments.recordExecutionOutcome(
                uncertainExecution,
                "execution-unknown",
                "RECONCILIATION_REQUIRED",
                null,
                null,
                "KEHUZX_WRITE_OUTCOME_UNKNOWN");

        assertThat(jdbc.queryForMap(
                        """
                        SELECT
                          count(*) FILTER (WHERE a.status='SUCCEEDED') AS succeeded,
                          count(*) FILTER (WHERE a.status='RECONCILIATION_REQUIRED') AS uncertain,
                          min(d.status) AS draft_status,
                          min(bf.stage) AS followup_stage
                        FROM app.business_followup_assignments a
                        JOIN app.business_followup_draft_versions d
                          ON d.followup_id=a.followup_id AND d.version=a.draft_version
                        JOIN app.business_followups bf ON bf.id=a.followup_id
                        WHERE a.followup_id=?
                        """,
                        fixture.followupId()))
                .containsEntry("succeeded", 1L)
                .containsEntry("uncertain", 1L)
                .containsEntry("draft_status", "CONFIRMED")
                .containsEntry("followup_stage", "CONFIRMED");
    }

    @Test
    void persistedExternalRequestSurvivesLostLeaseBeforeUnknownOutcomeFinalization() {
        Fixture fixture = confirmed("customer-crash-window");
        enqueueProjection(fixture.approvalId(), "crash-window");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-crash-window");
        assignments.project(projection, "projection-crash-window");

        AsyncTaskStore.AsyncTask abandoned = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "execution-abandoned");
        assertThat(assignments.beginExecution(
                        abandoned, "execution-abandoned", "request-crash-window"))
                .isEqualTo(BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.READY_TO_SUBMIT);
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id=?",
                abandoned.id());

        AsyncTaskStore.AsyncTask reclaimed = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "execution-reclaimed");
        assertThat(assignments.beginExecution(
                        reclaimed, "execution-reclaimed", "request-crash-window"))
                .isEqualTo(
                        BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.RECONCILE_EXISTING);
        assignments.recordExecutionOutcome(
                reclaimed,
                "execution-reclaimed",
                "RECONCILIATION_REQUIRED",
                null,
                null,
                "KEHUZX_WRITE_OUTCOME_UNKNOWN");

        assertThat(jdbc.queryForMap(
                        "SELECT status, request_id, result_code FROM app.business_followup_assignments "
                                + "WHERE followup_id=?",
                        fixture.followupId()))
                .containsEntry("status", "RECONCILIATION_REQUIRED")
                .containsEntry("request_id", "request-crash-window")
                .containsEntry("result_code", "KEHUZX_WRITE_OUTCOME_UNKNOWN");
    }

    @Test
    void terminalProjectionFailureIsVisibleFromFollowUpDetail() {
        Fixture fixture = confirmed("customer-projection-failure");
        enqueueProjection(fixture.approvalId(), "projection-terminal-failure");

        for (int attempt = 1; attempt <= 3; attempt++) {
            String owner = "projection-failure-" + attempt;
            AsyncTaskStore.AsyncTask task = claim(
                    BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, owner);
            assignments.recordProjectionFailure(
                    task, owner, "ASSIGNMENT_PROJECTION_FAILED", Duration.ofSeconds(1));
            if (attempt < 3) {
                jdbc.update(
                        "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",
                        task.id());
            }
        }

        assertThat(followUps.detail(fixture.followupId()).assignments())
                .singleElement()
                .satisfies(assignment -> {
                    assertThat(assignment.status()).isEqualTo("FAILED");
                    assertThat(assignment.resultCode()).isEqualTo("ASSIGNMENT_PROJECTION_FAILED");
                    assertThat(assignment.logicalTarget()).endsWith(":projection-failed");
                });
    }

    @Test
    void supersededFinalizingExecutionDoesNotFailAssignmentOwnedByNewerTask() {
        Fixture fixture = confirmed("customer-superseded-finalizing");
        enqueueProjection(fixture.approvalId(), "superseded-finalizing");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "projection-superseded-finalizing");
        assignments.project(projection, "projection-superseded-finalizing");
        AsyncTaskStore.AsyncTask oldTask = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "old-executor");
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status='FINALIZING', attempts=max_attempts,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    last_error='ASSIGNMENT_EXECUTION_INTERRUPTED'
                WHERE id=?
                """,
                oldTask.id());
        tasks.enqueue(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE,
                oldTask.payloadRef(),
                oldTask.idempotencyKey() + ":newer",
                3);

        AsyncTaskStore.AsyncTask reclaimedOld = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "recovery-owner");
        assignments.resumeExecutionFinalization(reclaimedOld, "recovery-owner");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.business_followup_assignments WHERE followup_id=?",
                String.class,
                fixture.followupId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE id=?",
                String.class,
                oldTask.id())).isEqualTo("SUCCEEDED");

        AsyncTaskStore.AsyncTask newer = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "newer-executor");
        assignments.execute(newer, "newer-executor");
    }

    @Test
    void terminalExecutionFailureDoesNotRollbackConfirmedFacts() {
        Fixture fixture = confirmed("customer-terminal-failure");
        enqueueProjection(fixture.approvalId(), "terminal-failure");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-failure");
        assignments.project(projection, "projection-failure");

        for (int attempt = 1; attempt <= 3; attempt++) {
            String owner = "execution-failure-" + attempt;
            AsyncTaskStore.AsyncTask execution = claim(
                    BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, owner);
            assignments.recordExecutionFailure(
                    execution, owner, "KEHUZX_WRITE_REJECTED", Duration.ofSeconds(1));
            if (attempt < 3) {
                jdbc.update(
                        "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE id=?",
                        execution.id());
            }
        }

        assertThat(jdbc.queryForMap(
                        """
                        SELECT a.status, a.result_code, d.status AS draft_status, bf.stage
                        FROM app.business_followup_assignments a
                        JOIN app.business_followup_draft_versions d
                          ON d.followup_id=a.followup_id AND d.version=a.draft_version
                        JOIN app.business_followups bf ON bf.id=a.followup_id
                        WHERE a.followup_id=?
                        """,
                        fixture.followupId()))
                .containsEntry("status", "FAILED")
                .containsEntry("result_code", "KEHUZX_WRITE_REJECTED")
                .containsEntry("draft_status", "CONFIRMED")
                .containsEntry("stage", "CONFIRMED");
    }

    @Test
    void expiredFinalizingLeaseIsRecoveredWithoutExternalExecution() {
        Fixture fixture = confirmed("customer-finalizing-recovery");
        enqueueProjection(fixture.approvalId(), "finalizing-recovery");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-finalizing");
        assignments.project(projection, "projection-finalizing");
        AsyncTaskStore.AsyncTask execution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "abandoned-executor");
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status='FINALIZING', attempts=max_attempts,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    last_error='ASSIGNMENT_EXECUTION_INTERRUPTED'
                WHERE id=?
                """,
                execution.id());

        new BusinessFollowUpAssignmentWorker(tasks, assignments, true, 30, 1).poll();

        assertThat(jdbc.queryForMap(
                        "SELECT status, result_code FROM app.business_followup_assignments WHERE followup_id=?",
                        fixture.followupId()))
                .containsEntry("status", "FAILED")
                .containsEntry("result_code", "ASSIGNMENT_EXECUTION_INTERRUPTED");
    }

    @Test
    void exhaustedExecutionAfterPersistedRequestRequiresReconciliation() {
        Fixture fixture = confirmed("customer-finalizing-unknown");
        enqueueProjection(fixture.approvalId(), "finalizing-unknown");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE, "projection-finalizing-unknown");
        assignments.project(projection, "projection-finalizing-unknown");
        AsyncTaskStore.AsyncTask execution = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "unknown-executor");
        assignments.beginExecution(execution, "unknown-executor", "request-finalizing-unknown");
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status='FINALIZING', attempts=max_attempts,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    last_error='ASSIGNMENT_EXECUTION_INTERRUPTED'
                WHERE id=?
                """,
                execution.id());

        new BusinessFollowUpAssignmentWorker(tasks, assignments, true, 30, 1).poll();

        assertThat(jdbc.queryForMap(
                        "SELECT status, request_id, result_code FROM app.business_followup_assignments "
                                + "WHERE followup_id=?",
                        fixture.followupId()))
                .containsEntry("status", "RECONCILIATION_REQUIRED")
                .containsEntry("request_id", "request-finalizing-unknown")
                .containsEntry("result_code", "KEHUZX_WRITE_OUTCOME_UNKNOWN");
    }

    @Test
    void staleFinalizingProjectionClosesWithoutCreatingAssignment() {
        Fixture fixture = confirmed("customer-stale-finalizing-projection");
        enqueueProjection(fixture.approvalId(), "stale-finalizing-projection");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "stale-finalizing-projection-owner");
        jdbc.update(
                """
                UPDATE app.async_tasks
                SET status='FINALIZING', attempts=max_attempts,
                    lease_until=CURRENT_TIMESTAMP - INTERVAL '1 second',
                    last_error='ASSIGNMENT_PROJECTION_FAILED'
                WHERE id=?
                """,
                projection.id());
        jdbc.update(
                "UPDATE app.business_followups SET current_confirmed_draft_version=NULL WHERE id=?",
                fixture.followupId());
        jdbc.update(
                "UPDATE app.business_followup_draft_versions SET status='SUPERSEDED' "
                        + "WHERE followup_id=? AND version=1",
                fixture.followupId());

        new BusinessFollowUpAssignmentWorker(tasks, assignments, true, 30, 1).poll();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.business_followup_assignments WHERE followup_id=?",
                Integer.class,
                fixture.followupId())).isZero();
    }

    @Test
    void supersededExecutionStartExplicitlyForbidsExternalSubmission() {
        Fixture fixture = confirmed("customer-superseded-start");
        enqueueProjection(fixture.approvalId(), "superseded-start");
        AsyncTaskStore.AsyncTask projection = claim(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "projection-superseded-start");
        assignments.project(projection, "projection-superseded-start");
        AsyncTaskStore.AsyncTask oldTask = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "old-start-owner");
        tasks.enqueue(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE,
                oldTask.payloadRef(),
                oldTask.idempotencyKey() + ":new-start",
                3);

        assertThat(assignments.beginExecution(oldTask, "old-start-owner", "must-not-submit"))
                .isEqualTo(BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.DO_NOT_EXECUTE);
        assertThat(jdbc.queryForMap(
                        "SELECT status, request_id FROM app.business_followup_assignments WHERE followup_id=?",
                        fixture.followupId()))
                .containsEntry("status", "PENDING")
                .containsEntry("request_id", null);

        AsyncTaskStore.AsyncTask newer = claim(
                BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE, "new-start-owner");
        assignments.execute(newer, "new-start-owner");
    }

    private Fixture confirmed(String customerId) {
        String suffix = UUID.randomUUID().toString();
        long messageId = jdbc.queryForObject(
                """
                INSERT INTO app.channel_messages
                    (channel, corp_id, connection_id, bot_id, message_id,
                     chat_id, chat_type, sender_user_id, message_type, content, raw_payload)
                VALUES ('WECOM', 'assignment-corp', 'assignment-connection', 'assignment-bot', ?,
                        'assignment-chat', 'single', 'employee', 'text',
                        '客户跟进材料', '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                "assignment-message-" + suffix);
        long submissionId = jdbc.queryForObject(
                """
                INSERT INTO app.message_submissions (submission_no, source_message_id)
                VALUES (?, ?) RETURNING id
                """,
                Long.class,
                "SUB-ASSIGN-" + suffix,
                messageId);
        long operatorId = jdbc.queryForObject(
                """
                INSERT INTO app.internal_operators
                    (display_name, responsible_team, wecom_userid, active)
                VALUES ('Assignment +1', 'CUSTOMER_OPS', ?, true)
                RETURNING id
                """,
                Long.class,
                "assignment-reviewer-" + suffix);
        long followupId = jdbc.queryForObject(
                """
                INSERT INTO app.business_followups
                    (message_submission_id, employee_draft, created_by,
                     designated_reviewer, designated_reviewer_operator_id,
                     agent_slug, agent_version, stage, processing_status,
                     current_draft_version, current_confirmed_draft_version)
                VALUES (?, '客户跟进', 'manager', 'Assignment +1', ?,
                        'customer-followup-agent', 1, 'CONFIRMED', 'SUCCEEDED', NULL, NULL)
                RETURNING id
                """,
                Long.class,
                submissionId,
                operatorId);
        String agentRunId = "run_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update(
                """
                INSERT INTO app.business_followup_draft_versions
                    (followup_id, version, source_revision, status, agent_run_id,
                     agent_slug, agent_version, content, zimu_source_summary,
                     kehuzx_source_summary, upstream_refs)
                VALUES (?, 1, 1, 'CONFIRMED', ?, 'customer-followup-agent', 1,
                        '{"requires_human":false}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                        CAST(? AS jsonb))
                """,
                followupId,
                agentRunId,
                "[{\"entity_type\":\"customer\",\"id\":\"" + customerId + "\"}]");
        jdbc.update(
                """
                UPDATE app.business_followups
                SET current_draft_version=1, current_confirmed_draft_version=1
                WHERE id=?
                """,
                followupId);
        long approvalId = jdbc.queryForObject(
                """
                INSERT INTO app.business_followup_approvals
                    (followup_id, draft_version, designated_reviewer_operator_id,
                     decided_by_operator_id, decision, source_kind, request_id,
                     idempotency_key, request_fingerprint, application_status, applied_at)
                VALUES (?, 1, ?, ?, 'CONFIRM', 'REST', ?, ?, repeat('a', 64),
                        'APPLIED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                followupId,
                operatorId,
                operatorId,
                "assignment-approval-request-" + suffix,
                "assignment-approval-idem-" + suffix);
        return new Fixture(followupId, approvalId, agentRunId);
    }

    private void enqueueProjection(long approvalId, String generation) {
        tasks.enqueue(
                BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE,
                "followup-assignment-projection:" + approvalId,
                "test-assignment-projection:" + approvalId + ":" + generation,
                3);
    }

    private AsyncTaskStore.AsyncTask claim(String taskType, String owner) {
        return tasks.claim(taskType, owner, Duration.ofSeconds(30)).orElseThrow();
    }

    private record Fixture(long followupId, long approvalId, String agentRunId) {}
}
