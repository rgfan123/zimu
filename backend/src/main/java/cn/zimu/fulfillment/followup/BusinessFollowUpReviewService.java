package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.message.IntentRouter;
import cn.zimu.fulfillment.message.InterpretationResult;
import cn.zimu.fulfillment.message.MessageIntent;
import cn.zimu.fulfillment.message.MessageSubmission;
import cn.zimu.fulfillment.message.MessageSubmissionRepository;
import cn.zimu.fulfillment.order.ReviewCaseRepository;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Follow-up-specific review seam that reuses any open submission review without draft conflicts. */
@Service
public class BusinessFollowUpReviewService {

    private final MessageSubmissionRepository submissions;
    private final ReviewCaseRepository reviewCases;
    private final IntentRouter router;
    private final JdbcTemplate jdbc;

    public BusinessFollowUpReviewService(
            MessageSubmissionRepository submissions,
            ReviewCaseRepository reviewCases,
            IntentRouter router,
            JdbcTemplate jdbc) {
        this.submissions = submissions;
        this.reviewCases = reviewCases;
        this.router = router;
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ReviewCase ensureOpen(
            long submissionId,
            long followupId,
            String stableReason,
            CommandContext context) {
        MessageSubmission submission = submissions.findByIdForUpdate(submissionId)
                .orElseThrow(() -> new IllegalStateException("消息提交不存在: " + submissionId));
        List<ReviewCase> open = reviewCases.findOpenBySubmissionId(submissionId, ReviewCaseStatus.OPEN);
        if (!open.isEmpty()) {
            return attachFollowUpReason(open.getFirst(), followupId, stableReason, context.requestId());
        }
        router.route(
                submission,
                new InterpretationResult(
                        MessageIntent.NEED_REVIEW,
                        Map.of("reason", stableReason),
                        "agent",
                        "business-followup",
                        "manual",
                        null));
        ReviewCase created = reviewCases.findOpenBySubmissionId(submissionId, ReviewCaseStatus.OPEN)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("人工复核事项创建后不可见"));
        return attachFollowUpReason(created, followupId, stableReason, context.requestId());
    }

    private ReviewCase attachFollowUpReason(
            ReviewCase reviewCase, long followupId, String reason, String runId) {
        boolean agentRun = runId != null && runId.matches("run_[0-9A-Za-z_]{8,60}");
        jdbc.update(
                """
                UPDATE app.review_cases
                SET detail = detail || jsonb_build_object(
                        'business_followup_id', CAST(? AS text),
                        'followup_reason_code', CAST(? AS text),
                        'followup_trace_id', CAST(? AS text))
                    || CASE WHEN ? THEN jsonb_build_object('agent_run_id', CAST(? AS text))
                            ELSE '{}'::jsonb END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                followupId,
                reason,
                runId,
                agentRun,
                runId,
                reviewCase.getId());
        return reviewCase;
    }
}
