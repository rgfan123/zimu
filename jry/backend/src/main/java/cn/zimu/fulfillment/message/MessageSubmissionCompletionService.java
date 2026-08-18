package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftRepository;
import cn.zimu.fulfillment.order.OrderDraft;
import cn.zimu.fulfillment.order.OrderDraftRepository;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles the terminal status of one message submission across every draft family.
 *
 * <p>The submission row is the shared serialization point. A reinterpretation may append order
 * and tracking drafts to the same submission, so neither draft service may decide the submission
 * status by looking only at its own table.
 */
@Service
public class MessageSubmissionCompletionService {

    private final MessageSubmissionRepository submissions;
    private final OrderDraftRepository orderDrafts;
    private final TrackingDraftRepository trackingDrafts;

    public MessageSubmissionCompletionService(
            MessageSubmissionRepository submissions,
            OrderDraftRepository orderDrafts,
            TrackingDraftRepository trackingDrafts) {
        this.submissions = submissions;
        this.orderDrafts = orderDrafts;
        this.trackingDrafts = trackingDrafts;
    }

    /**
     * Acquires the shared submission lock before a draft or review-case mutation starts.
     *
     * <p>Interpretation already uses the same submission-first order. Confirm/reject flows must do
     * likewise; otherwise a confirmation can hold a review-case row while waiting for the
     * submission row, while reinterpretation holds the submission row and waits for that case.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(long submissionId) {
        requireForUpdate(submissionId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void reconcile(long submissionId) {
        MessageSubmission submission = requireForUpdate(submissionId);

        boolean anyOpen = orderDrafts.countActionableBySubmissionIdAndStatus(
                                submissionId, OrderDraft.Status.OPEN, ReviewCaseStatus.OPEN)
                        > 0
                || trackingDrafts.countActionableBySubmissionIdAndStatus(
                                submissionId,
                                ProviderTrackingDraft.Status.OPEN,
                                ReviewCaseStatus.OPEN)
                        > 0;
        if (anyOpen) {
            submission.setStatus(MessageSubmission.Status.DRAFTED);
            return;
        }

        boolean anyConfirmed = orderDrafts.countBySubmissionIdAndStatus(
                                submissionId, OrderDraft.Status.CONFIRMED)
                        > 0
                || trackingDrafts.countBySubmissionIdAndStatus(
                                submissionId, ProviderTrackingDraft.Status.CONFIRMED)
                        > 0;
        submission.setStatus(anyConfirmed
                ? MessageSubmission.Status.CONFIRMED
                : MessageSubmission.Status.REJECTED);
    }

    private MessageSubmission requireForUpdate(long submissionId) {
        return submissions
                .findByIdForUpdate(submissionId)
                .orElseThrow(() -> BusinessException.notFound("消息提交不存在: " + submissionId));
    }
}
