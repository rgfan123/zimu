package cn.zimu.fulfillment.order.card;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderDraftCardFailureCoordinatorTest {

    private OrderDraftCardStore cards;
    private AsyncTaskStore tasks;
    private OrderDraftCardFailureCoordinator coordinator;

    @BeforeEach
    void setUp() {
        cards = mock(OrderDraftCardStore.class);
        tasks = mock(AsyncTaskStore.class);
        coordinator = new OrderDraftCardFailureCoordinator(cards, tasks);
    }

    @Test
    void exhaustedRetryFinalizesCardAndTaskTogether() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 3);
        when(cards.lock(7L)).thenReturn(card("SENDING"));
        when(tasks.recordFailureOwned(
                        task.id(),
                        task.leaseOwner(),
                        "WECOM_RETRYABLE",
                        OrderDraftCardFailureCoordinator.RETRY_BACKOFF))
                .thenReturn(AsyncTaskStore.FailureTransition.FINALIZING);

        coordinator.recordRetryableFailure(task, 7L, "WECOM_RETRYABLE");

        verify(cards).recordFailed(7L, "WECOM_RETRYABLE");
        verify(tasks).finalizeFailedOwned(task.id(), task.leaseOwner(), "WECOM_RETRYABLE");
    }

    @Test
    void retryableFailureReturnsSendingCardAndTaskToPending() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("SENDING"));
        when(tasks.recordFailureOwned(
                        task.id(),
                        task.leaseOwner(),
                        "WECOM_RETRYABLE",
                        OrderDraftCardFailureCoordinator.RETRY_BACKOFF))
                .thenReturn(AsyncTaskStore.FailureTransition.RETRY_SCHEDULED);

        coordinator.recordRetryableFailure(task, 7L, "WECOM_RETRYABLE");

        verify(cards).recordRetryable(7L, "WECOM_RETRYABLE");
        verify(cards, never()).recordFailed(7L, "WECOM_RETRYABLE");
        verify(tasks, never()).finalizeFailedOwned(task.id(), task.leaseOwner(), "WECOM_RETRYABLE");
    }

    @Test
    void retryRecoveryAfterCardAlreadySupersededConvergesTaskToSuccess() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("SUPERSEDED"));

        coordinator.recordRetryableFailure(task, 7L, "WECOM_RETRYABLE");

        verify(tasks).succeedOwned(task.id(), task.leaseOwner());
        verify(tasks, never()).recordFailureOwned(
                task.id(),
                task.leaseOwner(),
                "WECOM_RETRYABLE",
                OrderDraftCardFailureCoordinator.RETRY_BACKOFF);
    }

    @Test
    void unhandledFailureAfterSendStartsBecomesUnknownAndTerminal() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("SENDING"));

        coordinator.recoverUnhandledFailure(task, "WECOM_RUNNER_FAILED");

        verify(cards).recordUnknown(7L, "WECOM_RUNNER_FAILED");
        verify(tasks).failTerminal(
                task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
    }

    @Test
    void unhandledPendingFailureSchedulesARecoverableRetry() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("PENDING"));
        when(tasks.recordFailureOwned(
                        task.id(),
                        task.leaseOwner(),
                        "WECOM_RUNNER_FAILED",
                        OrderDraftCardFailureCoordinator.RETRY_BACKOFF))
                .thenReturn(AsyncTaskStore.FailureTransition.RETRY_SCHEDULED);

        coordinator.recoverUnhandledFailure(task, "WECOM_RUNNER_FAILED");

        verify(tasks).recordFailureOwned(
                task.id(),
                task.leaseOwner(),
                "WECOM_RUNNER_FAILED",
                OrderDraftCardFailureCoordinator.RETRY_BACKOFF);
        verify(cards, never()).recordFailed(7L, "WECOM_RUNNER_FAILED");
    }

    @Test
    void unhandledPendingFailureAtMaxAttemptFinalizesBothRows() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 3);
        when(cards.lock(7L)).thenReturn(card("PENDING"));
        when(tasks.recordFailureOwned(
                        task.id(),
                        task.leaseOwner(),
                        "WECOM_RUNNER_FAILED",
                        OrderDraftCardFailureCoordinator.RETRY_BACKOFF))
                .thenReturn(AsyncTaskStore.FailureTransition.FINALIZING);

        coordinator.recoverUnhandledFailure(task, "WECOM_RUNNER_FAILED");

        verify(cards).recordFailed(7L, "WECOM_RUNNER_FAILED");
        verify(tasks).finalizeFailedOwned(
                task.id(), task.leaseOwner(), "WECOM_RUNNER_FAILED");
    }

    @Test
    void finalizingPendingFailureAlwaysBecomesTerminal() {
        AsyncTaskStore.AsyncTask task = task("FINALIZING", 3);
        when(cards.lock(7L)).thenReturn(card("PENDING"));

        coordinator.recoverUnhandledFailure(task, "WECOM_RUNNER_FAILED");

        verify(cards).recordFailed(7L, "WECOM_RUNNER_FAILED");
        verify(tasks).finalizeFailedOwned(task.id(), task.leaseOwner(), "WECOM_RUNNER_FAILED");
    }

    @Test
    void finalizingTaskForAlreadySentCardConvergesToSuccess() {
        AsyncTaskStore.AsyncTask task = task("FINALIZING", 3);
        when(cards.lock(7L)).thenReturn(card("SENT"));

        coordinator.recoverUnhandledFailure(task, "WECOM_RUNNER_FAILED");

        verify(tasks).succeedOwned(task.id(), task.leaseOwner());
    }

    @Test
    void explicitPlatformRejectionFailsCardAndTaskTogether() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("SENDING"));

        coordinator.recordKnownFailure(
                task,
                7L,
                "WECOM_93000",
                "WECOM_ORDER_DRAFT_CARD_SEND_FAILED");

        verify(cards).recordFailed(7L, "WECOM_93000");
        verify(tasks).failTerminal(
                task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_SEND_FAILED");
    }

    @Test
    void indeterminateDeliveryMarksUnknownAndFailsTaskTogether() {
        AsyncTaskStore.AsyncTask task = task("RUNNING", 1);
        when(cards.lock(7L)).thenReturn(card("SENDING"));

        coordinator.recordDeliveryUnknown(task, 7L, "WECOM_SEND_EXCEPTION");

        verify(cards).recordUnknown(7L, "WECOM_SEND_EXCEPTION");
        verify(tasks).failTerminal(
                task.id(), task.leaseOwner(), "WECOM_ORDER_DRAFT_CARD_DELIVERY_UNKNOWN");
    }

    private static OrderDraftCard card(String status) {
        return new OrderDraftCard(
                7L, 41L, 0L, "order-draft:41", "GROUP", "group-41", status, 1);
    }

    private static AsyncTaskStore.AsyncTask task(String status, int attempts) {
        Instant now = Instant.now();
        return new AsyncTaskStore.AsyncTask(
                1L,
                OrderDraftCardEnqueuer.TASK_TYPE,
                "card:7",
                status,
                attempts,
                3,
                now,
                now.plus(Duration.ofMinutes(1)),
                "card-worker",
                null,
                "card-task-7",
                now,
                now);
    }
}
