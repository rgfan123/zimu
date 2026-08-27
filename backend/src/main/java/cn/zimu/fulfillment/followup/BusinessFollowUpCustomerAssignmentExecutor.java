package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Links an exact existing Customer or creates one from confirmed Zimu customer master data. */
@Service
public class BusinessFollowUpCustomerAssignmentExecutor {

    private static final String KEHUZX_CUSTOMER_PREFIX = "kehuzx-customer:";
    private static final String ZIMU_CUSTOMER_PREFIX = "customer:zimu-";

    private final JdbcTemplate jdbc;
    private final BusinessFollowUpAssignmentApplication assignments;
    private final KehuzxReadGateway kehuzxRead;
    private final KehuzxWriteGateway kehuzxWrite;
    private final ObjectMapper mapper;

    public BusinessFollowUpCustomerAssignmentExecutor(
            JdbcTemplate jdbc,
            BusinessFollowUpAssignmentApplication assignments,
            KehuzxReadGateway kehuzxRead,
            KehuzxWriteGateway kehuzxWrite,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.assignments = assignments;
        this.kehuzxRead = kehuzxRead;
        this.kehuzxWrite = kehuzxWrite;
        this.mapper = mapper;
    }

    public void execute(AsyncTaskStore.AsyncTask task, String owner) {
        long assignmentId = BusinessFollowUpAssignmentApplication.executionAssignmentId(task);
        AssignmentTarget target = loadTarget(assignmentId);
        if (target == null) {
            throw new IllegalStateException("Assignment not found: " + assignmentId);
        }
        if ("KEHUZX_CUSTOMER_LINK".equals(target.taskType())) {
            linkExisting(task, owner, target);
            return;
        }
        if ("KEHUZX_CUSTOMER_CREATE".equals(target.taskType())) {
            createCustomer(task, owner, target);
            return;
        }
        throw new IllegalStateException("Assignment is not an executable customer task: " + assignmentId);
    }

    private void linkExisting(
            AsyncTaskStore.AsyncTask task, String owner, AssignmentTarget target) {
        if (!target.logicalTarget().startsWith(KEHUZX_CUSTOMER_PREFIX)) {
            throw new IllegalStateException("Customer link target is invalid: " + target.logicalTarget());
        }
        String customerId = target.logicalTarget().substring(KEHUZX_CUSTOMER_PREFIX.length());
        if (customerId.isBlank()) {
            throw new IllegalStateException("Assignment customer target is blank");
        }
        Map<String, Object> payload = Map.of("customer_id", customerId);
        String payloadHash = KehuzxApprovalGrantSigner.payloadHash(mapper, payload);
        BusinessFollowUpAssignmentApplication.ExecutionStartDisposition disposition =
                assignments.beginReadExecution(task, owner, payloadHash);
        if (disposition
                == BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.DO_NOT_EXECUTE) {
            return;
        }

        JsonNode detail = kehuzxRead.call("get_customer_detail", payload);
        String verifiedCustomerId = detail.path("customer").path("id").asText("");
        if (!customerId.equals(verifiedCustomerId)) {
            assignments.recordExecutionOutcome(
                    task,
                    owner,
                    "WAITING_HUMAN",
                    null,
                    null,
                    "KEHUZX_CUSTOMER_REFERENCE_MISMATCH");
            return;
        }
        assignments.recordExecutionOutcome(
                task,
                owner,
                "SUCCEEDED",
                "KEHUZX_CUSTOMER",
                verifiedCustomerId,
                "KEHUZX_CUSTOMER_LINKED");
    }

    private void createCustomer(
            AsyncTaskStore.AsyncTask task, String owner, AssignmentTarget target) {
        if (!target.logicalTarget().startsWith(ZIMU_CUSTOMER_PREFIX)) {
            throw new IllegalStateException("Customer create target is invalid: " + target.logicalTarget());
        }
        long zimuCustomerId;
        try {
            zimuCustomerId = Long.parseLong(
                    target.logicalTarget().substring(ZIMU_CUSTOMER_PREFIX.length()));
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException("Zimu customer target is invalid", invalid);
        }
        if (!String.valueOf(zimuCustomerId).equals(target.zimuCustomerId())) {
            throw new IllegalStateException("Confirmed Zimu customer snapshot does not match target");
        }
        String customerName = target.customerName();
        if (customerName == null || customerName.isBlank()) {
            throw new IllegalStateException("Zimu customer master data is unavailable");
        }
        Map<String, Object> payload = Map.of(
                "customer_name", customerName,
                "source", "Zimu BusinessFollowUp");
        String payloadHash = KehuzxApprovalGrantSigner.payloadHash(mapper, payload);
        String requestId = "zimu-assignment-" + target.assignmentId();
        BusinessFollowUpAssignmentApplication.ExecutionStartDisposition disposition =
                assignments.beginExecution(task, owner, requestId, payloadHash);
        if (disposition
                == BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.DO_NOT_EXECUTE) {
            return;
        }
        KehuzxApprovalGrantSigner.Approval approval = new KehuzxApprovalGrantSigner.Approval(
                String.valueOf(target.approvalId()),
                String.valueOf(target.operatorId()),
                target.operatorName(),
                target.logicalTarget(),
                "create_customer",
                payload,
                target.draftVersion(),
                null,
                requestId,
                target.idempotencyKey());
        KehuzxWriteResult result;
        if (disposition
                == BusinessFollowUpAssignmentApplication.ExecutionStartDisposition.RECONCILE_EXISTING) {
            try {
                result = kehuzxWrite.reconcile(requestId);
            } catch (KehuzxWriteException unavailableReconciliation) {
                assignments.recordExecutionOutcome(
                        task,
                        owner,
                        "RECONCILIATION_REQUIRED",
                        null,
                        null,
                        "KEHUZX_WRITE_OUTCOME_UNKNOWN");
                return;
            }
        } else {
            result = kehuzxWrite.execute(approval);
        }
        if (result.status() == KehuzxWriteStatus.FAILED_RETRYABLE) {
            result = kehuzxWrite.execute(approval);
        }
        applyWriteResult(task, owner, result);
    }

    private void applyWriteResult(
            AsyncTaskStore.AsyncTask task, String owner, KehuzxWriteResult result) {
        switch (result.status()) {
            case SUCCEEDED -> {
                if (!"customer".equals(result.externalEntityType())
                        || result.externalEntityId() == null
                        || result.externalEntityId().isBlank()) {
                    throw new KehuzxWriteException(
                            KehuzxWriteException.Code.KEHUZX_WRITE_CONTRACT_DRIFT);
                }
                assignments.recordExecutionOutcome(
                        task,
                        owner,
                        "SUCCEEDED",
                        "KEHUZX_CUSTOMER",
                        result.externalEntityId(),
                        "KEHUZX_CUSTOMER_CREATED");
            }
            case FAILED -> assignments.recordExecutionOutcome(
                    task, owner, "FAILED", null, null, stableResultCode(result.errorCode()));
            case FAILED_RETRYABLE -> throw new KehuzxWriteException(
                    KehuzxWriteException.Code.KEHUZX_WRITE_RETRYABLE);
            case IN_PROGRESS, RECONCILIATION_REQUIRED -> assignments.recordExecutionOutcome(
                    task,
                    owner,
                    "RECONCILIATION_REQUIRED",
                    null,
                    null,
                    "KEHUZX_WRITE_OUTCOME_UNKNOWN");
        }
    }

    private AssignmentTarget loadTarget(long assignmentId) {
        return jdbc.query(
                        """
                        SELECT x.id, x.task_type, x.logical_target, x.draft_version,
                               x.approval_id, x.idempotency_key,
                               a.decided_by_operator_id, actor.display_name,
                               d.content -> 'customer_assignment' ->> 'zimu_customer_id'
                                   AS zimu_customer_id,
                               d.content -> 'customer_assignment' ->> 'customer_name'
                                   AS customer_name
                        FROM app.business_followup_assignments x
                        JOIN app.business_followup_approvals a ON a.id=x.approval_id
                        JOIN app.internal_operators actor ON actor.id=a.decided_by_operator_id
                        JOIN app.business_followup_draft_versions d
                          ON d.followup_id=x.followup_id AND d.version=x.draft_version
                        WHERE x.id=?
                        """,
                        (rs, row) -> new AssignmentTarget(
                                rs.getLong("id"),
                                rs.getString("task_type"),
                                rs.getString("logical_target"),
                                rs.getInt("draft_version"),
                                rs.getLong("approval_id"),
                                rs.getString("idempotency_key"),
                                rs.getLong("decided_by_operator_id"),
                                rs.getString("display_name"),
                                rs.getString("zimu_customer_id"),
                                rs.getString("customer_name")),
                        assignmentId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static String stableResultCode(String code) {
        return code != null && code.matches("^[A-Z][A-Z0-9_]{2,63}$")
                ? code
                : "KEHUZX_CUSTOMER_CREATE_REJECTED";
    }

    private record AssignmentTarget(
            long assignmentId,
            String taskType,
            String logicalTarget,
            int draftVersion,
            long approvalId,
            String idempotencyKey,
            long operatorId,
            String operatorName,
            String zimuCustomerId,
            String customerName) {}
}
