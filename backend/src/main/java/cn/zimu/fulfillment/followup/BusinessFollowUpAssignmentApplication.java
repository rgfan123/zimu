package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Projects a confirmed draft into independently executable, locally durable Assignments. */
@Service
public class BusinessFollowUpAssignmentApplication {

    public static final String PROJECTION_TASK_TYPE = "BUSINESS_FOLLOWUP_ASSIGNMENT_PROJECT";
    public static final String EXECUTION_TASK_TYPE = "BUSINESS_FOLLOWUP_ASSIGNMENT_EXECUTE";

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;
    private final ObjectMapper mapper;

    public BusinessFollowUpAssignmentApplication(
            JdbcTemplate jdbc, AsyncTaskStore tasks, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Transactional
    public void project(AsyncTaskStore.AsyncTask task, String owner) {
        long approvalId = projectionApprovalId(task);
        List<ProjectionFacts> rows = jdbc.query(
                """
                SELECT a.id, a.followup_id, a.draft_version, a.application_status, a.decision,
                       bf.current_confirmed_draft_version,
                       d.status AS draft_status, d.agent_run_id, d.upstream_refs::text AS upstream_refs
                FROM app.business_followup_approvals a
                JOIN app.business_followups bf ON bf.id=a.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id=a.followup_id AND d.version=a.draft_version
                WHERE a.id=?
                FOR UPDATE OF a, bf, d
                """,
                (rs, row) -> new ProjectionFacts(
                        rs.getLong("id"),
                        rs.getLong("followup_id"),
                        rs.getInt("draft_version"),
                        rs.getString("application_status"),
                        rs.getString("decision"),
                        rs.getObject("current_confirmed_draft_version", Integer.class),
                        rs.getString("draft_status"),
                        rs.getString("agent_run_id"),
                        rs.getString("upstream_refs")),
                approvalId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment projection lease lost: " + task.id());
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("Approval not found: " + approvalId);
        }
        ProjectionFacts facts = rows.getFirst();
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED
                || !facts.currentConfirmed()) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }

        List<String> customerRefs = refs(facts.upstreamRefs(), "customer");
        List<String> zimuCustomerRefs = refs(facts.upstreamRefs(), "zimu_customer");
        boolean link = customerRefs.size() == 1 && zimuCustomerRefs.isEmpty();
        boolean create = zimuCustomerRefs.size() == 1 && customerRefs.isEmpty();
        String taskType = create ? "KEHUZX_CUSTOMER_CREATE" : "KEHUZX_CUSTOMER_LINK";
        String logicalTarget = link
                ? "kehuzx-customer:" + customerRefs.getFirst()
                : create
                        ? "customer:zimu-" + zimuCustomerRefs.getFirst()
                        : "followup:" + facts.followupId() + ":customer-unresolved";
        boolean executable = link || create;
        String idempotencyKey = "followup-assignment:" + facts.followupId()
                + ":v" + facts.draftVersion() + ":" + taskType + ":" + logicalTarget;
        String executionTaskKey = "followup-assignment-execute:" + idempotencyKey;
        Long insertedId = jdbc.query(
                        """
                        INSERT INTO app.business_followup_assignments
                            (followup_id, draft_version, approval_id, agent_run_id,
                             task_type, logical_target, assignee_type, assignee_ref,
                             status, due_at, priority, idempotency_key, execution_task_key,
                             started_at, result_code)
                        VALUES (?, ?, ?, ?, ?, ?, 'DETERMINISTIC_MCP',
                                'kehuzx:customer-write', ?, CURRENT_TIMESTAMP + INTERVAL '1 day',
                                'NORMAL', ?, ?,
                                CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END,
                                CASE WHEN ? THEN NULL ELSE 'CONFIRMED_CUSTOMER_REFERENCE_MISSING' END)
                        ON CONFLICT (followup_id, draft_version, task_type, logical_target) DO NOTHING
                        RETURNING id
                        """,
                        (rs, row) -> rs.getLong(1),
                        facts.followupId(),
                        facts.draftVersion(),
                        facts.approvalId(),
                        facts.agentRunId(),
                        taskType,
                        logicalTarget,
                        executable ? "PENDING" : "WAITING_HUMAN",
                        idempotencyKey,
                        executionTaskKey,
                        executable,
                        executable)
                .stream()
                .findFirst()
                .orElse(null);
        long assignmentId = insertedId == null
                ? jdbc.queryForObject(
                        """
                        SELECT id FROM app.business_followup_assignments
                        WHERE followup_id=? AND draft_version=?
                          AND task_type=? AND logical_target=?
                        """,
                        Long.class,
                        facts.followupId(),
                        facts.draftVersion(),
                        taskType,
                        logicalTarget)
                : insertedId;
        if (executable) {
            tasks.enqueue(
                    EXECUTION_TASK_TYPE,
                    "followup-assignment:" + assignmentId,
                    executionTaskKey,
                    3);
        }
        tasks.succeedOwned(task.id(), owner);
    }

    /** #131 owns the real MCP executor; #148 visibly defers instead of fabricating external success. */
    @Transactional
    public void execute(AsyncTaskStore.AsyncTask task, String owner) {
        long assignmentId = executionAssignmentId(task);
        jdbc.queryForObject(
                "SELECT id FROM app.business_followup_assignments WHERE id=? FOR UPDATE",
                Long.class,
                assignmentId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment execution lease lost: " + task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status='WAITING_HUMAN', started_at=coalesce(started_at, CURRENT_TIMESTAMP),
                    result_code='KEHUZX_CUSTOMER_EXECUTOR_NOT_AVAILABLE',
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING'
                """,
                assignmentId);
        if (updated == 0) {
            String status = jdbc.queryForObject(
                    "SELECT status FROM app.business_followup_assignments WHERE id=?",
                    String.class,
                    assignmentId);
            if (!"WAITING_HUMAN".equals(status) && !isTerminal(status)) {
                throw new IllegalStateException("Assignment is not executable: " + assignmentId);
            }
        }
        tasks.succeedOwned(task.id(), owner);
    }

    /** Durable result seam consumed by the deterministic MCP executor introduced in #131. */
    @Transactional
    public ExecutionStartDisposition beginExecution(
            AsyncTaskStore.AsyncTask task,
            String owner,
            String externalRequestId,
            String payloadHash) {
        String requestId = blankToNull(externalRequestId);
        if (requestId == null) {
            throw new IllegalArgumentException("Assignment external request id is required");
        }
        if (payloadHash == null || !payloadHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Assignment payload hash is invalid");
        }
        long assignmentId = executionAssignmentId(task);
        List<ExecutionState> rows = jdbc.query(
                """
                SELECT x.status, x.request_id, x.payload_hash, x.draft_version,
                       a.application_status, a.decision,
                       bf.current_confirmed_draft_version,
                       d.status AS draft_status
                FROM app.business_followup_assignments x
                JOIN app.business_followup_approvals a ON a.id=x.approval_id
                JOIN app.business_followups bf ON bf.id=x.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id=x.followup_id AND d.version=x.draft_version
                WHERE x.id=?
                FOR UPDATE OF x, a, bf, d
                """,
                (rs, row) -> new ExecutionState(
                        rs.getString("status"),
                        rs.getString("request_id"),
                        rs.getString("payload_hash"),
                        "APPLIED".equals(rs.getString("application_status"))
                                && "CONFIRM".equals(rs.getString("decision"))
                                && rs.getObject("current_confirmed_draft_version", Integer.class) != null
                                && rs.getInt("current_confirmed_draft_version")
                                        == rs.getInt("draft_version")
                                && "CONFIRMED".equals(rs.getString("draft_status"))),
                assignmentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Assignment not found: " + assignmentId);
        }
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment execution lease lost: " + task.id());
        }
        ExecutionState state = rows.getFirst();
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED
                || !state.currentConfirmed()) {
            finalizeSupersededExecution(assignmentId, state.requestId());
            tasks.succeedOwned(task.id(), owner);
            return ExecutionStartDisposition.DO_NOT_EXECUTE;
        }
        if ("RUNNING".equals(state.status())
                && requestId.equals(state.requestId())
                && payloadHash.equals(state.payloadHash())) {
            return ExecutionStartDisposition.RECONCILE_EXISTING;
        }
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status='RUNNING', request_id=?, payload_hash=?, started_at=CURRENT_TIMESTAMP,
                    completed_at=NULL, result_code=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING' AND request_id IS NULL
                """,
                requestId,
                payloadHash,
                assignmentId);
        if (updated != 1) {
            throw new IllegalStateException("Assignment execution intent is no longer writable: " + assignmentId);
        }
        return ExecutionStartDisposition.READY_TO_SUBMIT;
    }

    /** Starts or resumes a read-only link verification without fabricating a write request id. */
    @Transactional
    public ExecutionStartDisposition beginReadExecution(
            AsyncTaskStore.AsyncTask task, String owner, String payloadHash) {
        if (payloadHash == null || !payloadHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Assignment payload hash is invalid");
        }
        long assignmentId = executionAssignmentId(task);
        List<ExecutionState> rows = lockExecutionState(assignmentId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Assignment not found: " + assignmentId);
        }
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment execution lease lost: " + task.id());
        }
        ExecutionState state = rows.getFirst();
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED
                || !state.currentConfirmed()) {
            finalizeSupersededExecution(assignmentId, null);
            tasks.succeedOwned(task.id(), owner);
            return ExecutionStartDisposition.DO_NOT_EXECUTE;
        }
        if ("RUNNING".equals(state.status())
                && state.requestId() == null
                && payloadHash.equals(state.payloadHash())) {
            return ExecutionStartDisposition.READY_TO_SUBMIT;
        }
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status='RUNNING', payload_hash=?, started_at=coalesce(started_at, CURRENT_TIMESTAMP),
                    completed_at=NULL, result_code=NULL, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='PENDING' AND request_id IS NULL
                """,
                payloadHash,
                assignmentId);
        if (updated != 1) {
            throw new IllegalStateException("Assignment read intent is no longer writable: " + assignmentId);
        }
        return ExecutionStartDisposition.READY_TO_SUBMIT;
    }

    private List<ExecutionState> lockExecutionState(long assignmentId) {
        return jdbc.query(
                """
                SELECT x.status, x.request_id, x.payload_hash, x.draft_version,
                       a.application_status, a.decision,
                       bf.current_confirmed_draft_version,
                       d.status AS draft_status
                FROM app.business_followup_assignments x
                JOIN app.business_followup_approvals a ON a.id=x.approval_id
                JOIN app.business_followups bf ON bf.id=x.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id=x.followup_id AND d.version=x.draft_version
                WHERE x.id=?
                FOR UPDATE OF x, a, bf, d
                """,
                (rs, row) -> new ExecutionState(
                        rs.getString("status"),
                        rs.getString("request_id"),
                        rs.getString("payload_hash"),
                        "APPLIED".equals(rs.getString("application_status"))
                                && "CONFIRM".equals(rs.getString("decision"))
                                && rs.getObject("current_confirmed_draft_version", Integer.class) != null
                                && rs.getInt("current_confirmed_draft_version")
                                        == rs.getInt("draft_version")
                                && "CONFIRMED".equals(rs.getString("draft_status"))),
                assignmentId);
    }

    /** Finalizes a previously persisted external request without changing its durable identity. */
    @Transactional
    public void recordExecutionOutcome(
            AsyncTaskStore.AsyncTask task,
            String owner,
            String status,
            String externalEntityType,
            String externalEntityId,
            String resultCode) {
        if (!"SUCCEEDED".equals(status)
                && !"RECONCILIATION_REQUIRED".equals(status)
                && !"WAITING_HUMAN".equals(status)
                && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("Unsupported Assignment outcome: " + status);
        }
        long assignmentId = executionAssignmentId(task);
        jdbc.queryForObject(
                "SELECT id FROM app.business_followup_assignments WHERE id=? FOR UPDATE",
                Long.class,
                assignmentId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment outcome lease lost: " + task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        boolean completed = !"WAITING_HUMAN".equals(status);
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status=?, external_entity_type=?, external_entity_id=?,
                    result_code=?, started_at=coalesce(started_at, CURRENT_TIMESTAMP),
                    completed_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status='RUNNING'
                """,
                status,
                blankToNull(externalEntityType),
                blankToNull(externalEntityId),
                stableCode(resultCode),
                completed,
                assignmentId);
        if (updated != 1) {
            throw new IllegalStateException("Assignment outcome is no longer writable: " + assignmentId);
        }
        tasks.succeedOwned(task.id(), owner);
    }

    @Transactional
    public void recordExecutionFailure(
            AsyncTaskStore.AsyncTask task, String owner, String code, Duration backoff) {
        long assignmentId = executionAssignmentId(task);
        ExecutionState state = jdbc.queryForObject(
                "SELECT status, request_id FROM app.business_followup_assignments WHERE id=? FOR UPDATE",
                (rs, row) -> new ExecutionState(rs.getString("status"), rs.getString("request_id")),
                assignmentId);
        AsyncTaskStore.FailureTransition transition =
                tasks.recordFailureOwned(task.id(), owner, stableCode(code), backoff);
        if (transition == AsyncTaskStore.FailureTransition.RETRY_SCHEDULED) {
            return;
        }
        finalizeExecutionFailure(assignmentId, state.requestId(), stableCode(code));
        tasks.finalizeFailedOwned(task.id(), owner, stableCode(code));
    }

    @Transactional
    public void recordProjectionFailure(
            AsyncTaskStore.AsyncTask task, String owner, String code, Duration backoff) {
        long approvalId = projectionApprovalId(task);
        ProjectionFailureFacts facts = projectionFailureFacts(approvalId);
        AsyncTaskStore.FailureTransition transition =
                tasks.recordFailureOwned(task.id(), owner, stableCode(code), backoff);
        if (transition == AsyncTaskStore.FailureTransition.RETRY_SCHEDULED) {
            return;
        }
        if (facts.currentConfirmed()) {
            persistProjectionFailure(facts, stableCode(code));
        }
        tasks.finalizeFailedOwned(task.id(), owner, stableCode(code));
    }

    @Transactional
    public void resumeExecutionFinalization(AsyncTaskStore.AsyncTask task, String owner) {
        long assignmentId = executionAssignmentId(task);
        ExecutionState state = jdbc.queryForObject(
                "SELECT status, request_id FROM app.business_followup_assignments WHERE id=? FOR UPDATE",
                (rs, row) -> new ExecutionState(rs.getString("status"), rs.getString("request_id")),
                assignmentId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockFinalizationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment finalization lease lost: " + task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        String taskCode = stableCode(task.lastError());
        finalizeExecutionFailure(assignmentId, state.requestId(), taskCode);
        tasks.finalizeFailedOwned(task.id(), owner, taskCode);
    }

    private void finalizeExecutionFailure(long assignmentId, String requestId, String taskCode) {
        boolean outcomeUnknown = requestId != null && !knownPreSubmissionFailure(taskCode);
        jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status=?, started_at=coalesce(started_at, CURRENT_TIMESTAMP),
                    completed_at=CURRENT_TIMESTAMP, result_code=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('PENDING', 'RUNNING')
                """,
                outcomeUnknown ? "RECONCILIATION_REQUIRED" : "FAILED",
                outcomeUnknown ? "KEHUZX_WRITE_OUTCOME_UNKNOWN" : taskCode,
                assignmentId);
    }

    private void finalizeSupersededExecution(long assignmentId, String requestId) {
        boolean outcomeUnknown = requestId != null;
        jdbc.update(
                """
                UPDATE app.business_followup_assignments
                SET status=?, started_at=coalesce(started_at, CURRENT_TIMESTAMP),
                    completed_at=CURRENT_TIMESTAMP, result_code=?, updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND status IN ('PENDING', 'RUNNING')
                """,
                outcomeUnknown ? "RECONCILIATION_REQUIRED" : "FAILED",
                outcomeUnknown ? "KEHUZX_WRITE_OUTCOME_UNKNOWN" : "ASSIGNMENT_SUPERSEDED",
                assignmentId);
    }

    @Transactional
    public void resumeProjectionFinalization(AsyncTaskStore.AsyncTask task, String owner) {
        long approvalId = projectionApprovalId(task);
        ProjectionFailureFacts facts = projectionFailureFacts(approvalId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockFinalizationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("Assignment projection finalization lease lost: " + task.id());
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        String code = stableCode(task.lastError());
        if (facts.currentConfirmed()) {
            persistProjectionFailure(facts, code);
        }
        tasks.finalizeFailedOwned(task.id(), owner, code);
    }

    private ProjectionFailureFacts projectionFailureFacts(long approvalId) {
        return jdbc.queryForObject(
                """
                SELECT a.followup_id, a.draft_version, a.application_status, a.decision,
                       bf.current_confirmed_draft_version,
                       d.status AS draft_status, d.agent_run_id
                FROM app.business_followup_approvals a
                JOIN app.business_followups bf ON bf.id=a.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id=a.followup_id AND d.version=a.draft_version
                WHERE a.id=?
                FOR UPDATE OF a, bf, d
                """,
                (rs, row) -> new ProjectionFailureFacts(
                        approvalId,
                        rs.getLong("followup_id"),
                        rs.getInt("draft_version"),
                        rs.getString("agent_run_id"),
                        "APPLIED".equals(rs.getString("application_status"))
                                && "CONFIRM".equals(rs.getString("decision"))
                                && rs.getObject("current_confirmed_draft_version", Integer.class) != null
                                && rs.getInt("current_confirmed_draft_version")
                                        == rs.getInt("draft_version")
                                && "CONFIRMED".equals(rs.getString("draft_status"))),
                approvalId);
    }

    private void persistProjectionFailure(ProjectionFailureFacts facts, String code) {
        String logicalTarget = "followup:" + facts.followupId() + ":projection-failed";
        String key = "followup-assignment-projection-failed:" + facts.approvalId();
        jdbc.update(
                """
                INSERT INTO app.business_followup_assignments
                    (followup_id, draft_version, approval_id, agent_run_id,
                     task_type, logical_target, assignee_type, assignee_ref,
                     status, due_at, priority, idempotency_key, execution_task_key,
                     result_code, started_at, completed_at)
                VALUES (?, ?, ?, ?, 'KEHUZX_CUSTOMER_LINK', ?, 'DETERMINISTIC_MCP',
                        'kehuzx:customer-write', 'FAILED', CURRENT_TIMESTAMP, 'HIGH',
                        ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (followup_id, draft_version, task_type, logical_target)
                DO UPDATE SET status='FAILED', result_code=excluded.result_code,
                              started_at=coalesce(app.business_followup_assignments.started_at,
                                                  CURRENT_TIMESTAMP),
                              completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                """,
                facts.followupId(),
                facts.draftVersion(),
                facts.approvalId(),
                facts.agentRunId(),
                logicalTarget,
                key,
                key,
                code);
    }

    private List<String> refs(String raw, String entityType) {
        try {
            JsonNode refs = mapper.readTree(raw);
            List<String> values = new ArrayList<>();
            if (refs.isArray()) {
                refs.forEach(ref -> {
                    if (entityType.equals(ref.path("entity_type").asText())
                            && ref.path("id").isTextual()
                            && !ref.path("id").asText().isBlank()) {
                        values.add(ref.path("id").asText());
                    }
                });
            }
            return values.stream().distinct().toList();
        } catch (Exception ex) {
            return List.of();
        }
    }

    static long projectionApprovalId(AsyncTaskStore.AsyncTask task) {
        return payloadId(task, PROJECTION_TASK_TYPE, "followup-assignment-projection:");
    }

    static long executionAssignmentId(AsyncTaskStore.AsyncTask task) {
        return payloadId(task, EXECUTION_TASK_TYPE, "followup-assignment:");
    }

    private static long payloadId(AsyncTaskStore.AsyncTask task, String type, String prefix) {
        if (!type.equals(task.taskType())
                || task.payloadRef() == null
                || !task.payloadRef().matches(java.util.regex.Pattern.quote(prefix) + "[1-9][0-9]*")) {
            throw new IllegalArgumentException("Invalid Assignment task payload");
        }
        return Long.parseLong(task.payloadRef().substring(prefix.length()));
    }

    private static String stableCode(String code) {
        String value = code == null || !code.matches("^[A-Z][A-Z0-9_]{2,63}$")
                ? "ASSIGNMENT_EXECUTION_FAILED"
                : code;
        return value.substring(0, Math.min(64, value.length()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "RECONCILIATION_REQUIRED".equals(status);
    }

    private static boolean knownPreSubmissionFailure(String code) {
        return code != null
                && code.startsWith("KEHUZX_")
                && !code.contains("OUTCOME_UNKNOWN");
    }

    private record ProjectionFacts(
            long approvalId,
            long followupId,
            int draftVersion,
            String applicationStatus,
            String decision,
            Integer currentConfirmedDraftVersion,
            String draftStatus,
            String agentRunId,
            String upstreamRefs) {
        boolean currentConfirmed() {
            return "APPLIED".equals(applicationStatus)
                    && "CONFIRM".equals(decision)
                    && currentConfirmedDraftVersion != null
                    && currentConfirmedDraftVersion == draftVersion
                    && "CONFIRMED".equals(draftStatus);
        }
    }

    private record ExecutionState(
            String status, String requestId, String payloadHash, boolean currentConfirmed) {
        ExecutionState(String status, String requestId) {
            this(status, requestId, null, true);
        }
    }

    private record ProjectionFailureFacts(
            long approvalId,
            long followupId,
            int draftVersion,
            String agentRunId,
            boolean currentConfirmed) {}

    public enum ExecutionStartDisposition {
        READY_TO_SUBMIT,
        RECONCILE_EXISTING,
        DO_NOT_EXECUTE
    }
}
