package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardEnqueuer;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies one persisted human decision after the card callback has returned. */
@Service
public class BusinessFollowUpApprovalApplication {

    public static final String TASK_TYPE = "BUSINESS_FOLLOWUP_APPROVAL";

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;
    private final WecomBusinessCardEnqueuer cards;

    public BusinessFollowUpApprovalApplication(
            JdbcTemplate jdbc, AsyncTaskStore tasks, WecomBusinessCardEnqueuer cards) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.cards = cards;
    }

    @Transactional
    public void apply(AsyncTaskStore.AsyncTask task, String owner) {
        long approvalId = approvalId(task);
        List<ApprovalWork> rows = jdbc.query(
                """
                SELECT a.id, a.followup_id, a.draft_version, a.decision,
                       a.application_status, a.order_draft_id, a.order_draft_revision,
                       bf.source_revision,
                       bf.current_draft_version, d.status AS draft_status
                FROM app.business_followup_approvals a
                JOIN app.business_followups bf ON bf.id = a.followup_id
                JOIN app.business_followup_draft_versions d
                  ON d.followup_id = a.followup_id AND d.version = a.draft_version
                WHERE a.id = ?
                FOR UPDATE OF a, bf, d
                """,
                (rs, row) -> new ApprovalWork(
                        rs.getLong("id"),
                        rs.getLong("followup_id"),
                        rs.getInt("draft_version"),
                        BusinessFollowUpApprovalDecision.valueOf(rs.getString("decision")),
                        rs.getString("application_status"),
                        rs.getObject("order_draft_id", Long.class),
                        rs.getObject("order_draft_revision", Long.class),
                        rs.getInt("source_revision"),
                        rs.getObject("current_draft_version", Integer.class),
                        rs.getString("draft_status")),
                approvalId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("Business Follow-up Approval 不存在: " + approvalId);
        }
        AsyncTaskStore.ApplicationFence fence = tasks.lockApplicationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("异步任务租约已丢失: " + task.id());
        }
        ApprovalWork work = rows.getFirst();
        if (!"PENDING".equals(work.applicationStatus())) {
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.SUPERSEDED
                || work.currentDraftVersion() == null
                || work.currentDraftVersion() != work.draftVersion()) {
            recordOutcome(work.approvalId(), "SUPERSEDED");
            cards.enqueue(WecomTaskId.ofVersion(
                    "followup-result", work.approvalId(), work.draftVersion()));
            tasks.succeedOwned(task.id(), owner);
            return;
        }
        if (work.decision() == BusinessFollowUpApprovalDecision.CONFIRM
                && !orderSnapshotCurrent(work)) {
            recordOutcome(work.approvalId(), "SUPERSEDED");
            cards.enqueue(WecomTaskId.ofVersion(
                    "followup-result", work.approvalId(), work.draftVersion()));
            tasks.succeedOwned(task.id(), owner);
            return;
        }

        switch (work.decision()) {
            case CONFIRM -> confirm(work);
            case REDO -> redo(work);
            case NEEDS_INPUT -> transition(work, "NEEDS_INPUT", "NEEDS_INPUT");
            case PAUSE -> transition(work, "PAUSED", "PAUSED");
        }
        cards.enqueue(WecomTaskId.ofVersion(
                "followup-result", work.approvalId(), work.draftVersion()));
        recordOutcome(work.approvalId(), "APPLIED");
        tasks.succeedOwned(task.id(), owner);
    }

    /** Atomically schedules a retry or records the terminal, public-safe failure outcome. */
    @Transactional
    public void recordFailure(
            AsyncTaskStore.AsyncTask task, String owner, String failureCode, Duration backoff) {
        long approvalId = approvalId(task);
        AsyncTaskStore.FailureTransition transition =
                tasks.recordFailureOwned(task.id(), owner, failureCode, backoff);
        if (transition == AsyncTaskStore.FailureTransition.RETRY_SCHEDULED) {
            return;
        }
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_approvals
                SET application_status='FAILED', application_failure_code=?, applied_at=NULL
                WHERE id=? AND application_status='PENDING'
                """,
                failureCode,
                approvalId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Business Follow-up Approval 失败状态不可写入: " + approvalId);
        }
        jdbc.update(
                """
                UPDATE app.business_followups bf
                SET processing_status='FAILED', updated_at=CURRENT_TIMESTAMP
                FROM app.business_followup_approvals a
                WHERE a.id=? AND bf.id=a.followup_id
                  AND bf.current_draft_version=a.draft_version
                """,
                approvalId);
        cards.enqueue(WecomTaskId.ofVersion("followup-result", approvalId, approvalDraftVersion(approvalId)));
        tasks.finalizeFailedOwned(task.id(), owner, failureCode);
    }

    /** Completes the durable terminal outcome after a FINALIZING task is reclaimed on restart. */
    @Transactional
    public void resumeFinalization(AsyncTaskStore.AsyncTask task, String owner) {
        long approvalId = approvalId(task);
        jdbc.queryForObject(
                "SELECT id FROM app.business_followup_approvals WHERE id=? FOR UPDATE",
                Long.class,
                approvalId);
        AsyncTaskStore.ApplicationFence fence = tasks.lockFinalizationFence(task.id(), owner);
        if (fence.disposition() == AsyncTaskStore.ApplicationDisposition.LOST_LEASE) {
            throw new IllegalStateException("异步任务最终收口租约已丢失: " + task.id());
        }
        String failureCode = task.lastError() == null || task.lastError().isBlank()
                ? "FOLLOWUP_APPROVAL_APPLY_FAILED"
                : task.lastError().substring(0, Math.min(64, task.lastError().length()));
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_approvals
                SET application_status='FAILED', application_failure_code=?, applied_at=NULL
                WHERE id=? AND application_status='PENDING'
                """,
                failureCode,
                approvalId);
        if (updated == 1) {
            jdbc.update(
                    """
                    UPDATE app.business_followups bf
                    SET processing_status='FAILED', updated_at=CURRENT_TIMESTAMP
                    FROM app.business_followup_approvals a
                    WHERE a.id=? AND bf.id=a.followup_id
                      AND bf.current_draft_version=a.draft_version
                    """,
                    approvalId);
            cards.enqueue(WecomTaskId.ofVersion(
                    "followup-result", approvalId, approvalDraftVersion(approvalId)));
        }
        tasks.finalizeFailedOwned(task.id(), owner, failureCode);
    }

    private long approvalDraftVersion(long approvalId) {
        Long version = jdbc.queryForObject(
                "SELECT draft_version FROM app.business_followup_approvals WHERE id=?",
                Long.class,
                approvalId);
        if (version == null) {
            throw new IllegalStateException("Business Follow-up Approval 不存在: " + approvalId);
        }
        return version;
    }

    private boolean orderSnapshotCurrent(ApprovalWork work) {
        if (work.orderDraftId() == null || work.orderDraftRevision() == null) {
            return false;
        }
        return jdbc.query(
                        "SELECT revision, status FROM app.order_drafts WHERE id=? FOR UPDATE",
                        (rs, row) -> rs.getLong("revision") == work.orderDraftRevision()
                                && "OPEN".equals(rs.getString("status")),
                        work.orderDraftId())
                .stream()
                .findFirst()
                .orElse(false);
    }

    private void recordOutcome(long approvalId, String status) {
        int updated = jdbc.update(
                """
                UPDATE app.business_followup_approvals
                SET application_status=?, application_failure_code=NULL,
                    applied_at=CURRENT_TIMESTAMP
                WHERE id=? AND application_status='PENDING'
                """,
                status,
                approvalId);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Business Follow-up Approval 应用状态不可写入: " + approvalId);
        }
    }

    private void confirm(ApprovalWork work) {
        requireReady(work);
        jdbc.update(
                """
                UPDATE app.business_followup_draft_versions
                SET status = 'CONFIRMED'
                WHERE followup_id = ? AND version = ? AND status = 'READY'
                """,
                work.followupId(),
                work.draftVersion());
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = 'CONFIRMED', processing_status = 'SUCCEEDED',
                    current_confirmed_draft_version = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND current_draft_version = ?
                """,
                work.draftVersion(),
                work.followupId(),
                work.draftVersion());
    }

    private void redo(ApprovalWork work) {
        requireActionable(work);
        jdbc.update(
                """
                UPDATE app.business_followup_draft_versions
                SET status = 'SUPERSEDED'
                WHERE followup_id = ? AND version = ? AND status IN ('READY', 'NEEDS_INPUT')
                """,
                work.followupId(),
                work.draftVersion());
        String organizationKey = "business-followup-organize:" + work.followupId()
                + ":revision:" + work.sourceRevision() + ":redo:" + work.approvalId();
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = 'ORGANIZING', processing_status = 'PENDING',
                    organization_task_key = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND current_draft_version = ?
                """,
                organizationKey,
                work.followupId(),
                work.draftVersion());
        tasks.enqueue(
                BusinessFollowUpService.ORGANIZE_TASK_TYPE,
                "business-followup:" + work.followupId() + ":revision:" + work.sourceRevision(),
                organizationKey,
                3);
    }

    private void transition(ApprovalWork work, String draftStatus, String stage) {
        requireActionable(work);
        jdbc.update(
                """
                UPDATE app.business_followup_draft_versions
                SET status = ?
                WHERE followup_id = ? AND version = ? AND status IN ('READY', 'NEEDS_INPUT')
                """,
                draftStatus,
                work.followupId(),
                work.draftVersion());
        jdbc.update(
                """
                UPDATE app.business_followups
                SET stage = ?, processing_status = 'SUCCEEDED', updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND current_draft_version = ?
                """,
                stage,
                work.followupId(),
                work.draftVersion());
    }

    private static void requireReady(ApprovalWork work) {
        if (!"READY".equals(work.draftStatus())) {
            throw new IllegalStateException("Business Follow-up draft is not READY");
        }
    }

    private static void requireActionable(ApprovalWork work) {
        if (!"READY".equals(work.draftStatus()) && !"NEEDS_INPUT".equals(work.draftStatus())) {
            throw new IllegalStateException("Business Follow-up draft is not actionable");
        }
    }

    static long approvalId(AsyncTaskStore.AsyncTask task) {
        if (!TASK_TYPE.equals(task.taskType())
                || task.payloadRef() == null
                || !task.payloadRef().matches("followup-approval:[1-9][0-9]*")) {
            throw new IllegalArgumentException("非法的 Business Follow-up Approval 任务载荷");
        }
        return Long.parseLong(task.payloadRef().substring("followup-approval:".length()));
    }

    private record ApprovalWork(
            long approvalId,
            long followupId,
            int draftVersion,
            BusinessFollowUpApprovalDecision decision,
            String applicationStatus,
            Long orderDraftId,
            Long orderDraftRevision,
            int sourceRevision,
            Integer currentDraftVersion,
            String draftStatus) {}
}
