package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.fulfillment.ProviderTrackingDraft;
import cn.zimu.fulfillment.fulfillment.TrackingDraftRepository;
import cn.zimu.fulfillment.order.OrderDraft;
import cn.zimu.fulfillment.order.OrderDraftRepository;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MessageSubmissionCompletionServiceTest {

    private final MessageSubmissionRepository submissions = mock(MessageSubmissionRepository.class);
    private final OrderDraftRepository orderDrafts = mock(OrderDraftRepository.class);
    private final TrackingDraftRepository trackingDrafts = mock(TrackingDraftRepository.class);
    private final MessageSubmissionCompletionService service =
            new MessageSubmissionCompletionService(submissions, orderDrafts, trackingDrafts);

    @Test
    void staysDraftedWhileEitherDraftFamilyHasAnActionableOpenCase() {
        MessageSubmission submission = submission(11L, MessageSubmission.Status.CONFIRMED);
        when(orderDrafts.countActionableBySubmissionIdAndStatus(
                        11L, OrderDraft.Status.OPEN, ReviewCaseStatus.OPEN))
                .thenReturn(0L);
        when(trackingDrafts.countActionableBySubmissionIdAndStatus(
                        11L, ProviderTrackingDraft.Status.OPEN, ReviewCaseStatus.OPEN))
                .thenReturn(1L);

        service.reconcile(11L);

        assertThat(submission.getStatus()).isEqualTo(MessageSubmission.Status.DRAFTED);
    }

    @Test
    void confirmedInEitherDraftFamilyWinsAfterNoActionableOpenCasesRemain() {
        MessageSubmission submission = submission(12L, MessageSubmission.Status.DRAFTED);
        noActionableOpenCases(12L);
        when(orderDrafts.countBySubmissionIdAndStatus(12L, OrderDraft.Status.CONFIRMED))
                .thenReturn(0L);
        when(trackingDrafts.countBySubmissionIdAndStatus(
                        12L, ProviderTrackingDraft.Status.CONFIRMED))
                .thenReturn(1L);

        service.reconcile(12L);

        assertThat(submission.getStatus()).isEqualTo(MessageSubmission.Status.CONFIRMED);
    }

    @Test
    void rejectedOnlyWhenNoActionableOrConfirmedDraftExists() {
        MessageSubmission submission = submission(13L, MessageSubmission.Status.DRAFTED);
        noActionableOpenCases(13L);
        when(orderDrafts.countBySubmissionIdAndStatus(13L, OrderDraft.Status.CONFIRMED))
                .thenReturn(0L);
        when(trackingDrafts.countBySubmissionIdAndStatus(
                        13L, ProviderTrackingDraft.Status.CONFIRMED))
                .thenReturn(0L);

        service.reconcile(13L);

        assertThat(submission.getStatus()).isEqualTo(MessageSubmission.Status.REJECTED);
        verify(submissions).findByIdForUpdate(13L);
    }

    private MessageSubmission submission(long id, MessageSubmission.Status initialStatus) {
        MessageSubmission submission = new MessageSubmission();
        submission.setStatus(initialStatus);
        when(submissions.findByIdForUpdate(id)).thenReturn(Optional.of(submission));
        return submission;
    }

    private void noActionableOpenCases(long submissionId) {
        when(orderDrafts.countActionableBySubmissionIdAndStatus(
                        submissionId, OrderDraft.Status.OPEN, ReviewCaseStatus.OPEN))
                .thenReturn(0L);
        when(trackingDrafts.countActionableBySubmissionIdAndStatus(
                        submissionId,
                        ProviderTrackingDraft.Status.OPEN,
                        ReviewCaseStatus.OPEN))
                .thenReturn(0L);
    }
}
