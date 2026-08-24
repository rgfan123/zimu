package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OrderDraftCardWorkerTest {

    @Test
    void schedulerReturnsImmediatelyAndRepeatedPollsCannotOverlapOneDrain() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        OrderDraftCardRunner runner = mock(OrderDraftCardRunner.class);
        OrderDraftCardFailureCoordinator failures = mock(OrderDraftCardFailureCoordinator.class);
        AsyncTaskStore.AsyncTask task = task(1L);
        AtomicInteger claims = new AtomicInteger();
        when(tasks.claim(eq(OrderDraftCardEnqueuer.TASK_TYPE), any(String.class), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> claims.incrementAndGet() == 1 ? Optional.of(task) : Optional.empty());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return null;
                })
                .when(runner)
                .execute(task);
        OrderDraftCardWorker worker = new OrderDraftCardWorker(tasks, runner, failures, true, 60, 60);
        try {
            long before = System.nanoTime();
            worker.poll();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)).isLessThan(1_000L);
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < 10; index++) {
                worker.poll();
            }
            assertThat(claims).hasValue(1);
        } finally {
            release.countDown();
            worker.shutdown();
        }
    }

    @Test
    void shutdownRaceAfterClaimReleasesTaskWithoutRunningIt() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        OrderDraftCardRunner runner = mock(OrderDraftCardRunner.class);
        OrderDraftCardFailureCoordinator failures = mock(OrderDraftCardFailureCoordinator.class);
        AsyncTaskStore.AsyncTask task = task(2L);
        CountDownLatch claimStarted = new CountDownLatch(1);
        when(tasks.claim(eq(OrderDraftCardEnqueuer.TASK_TYPE), any(String.class), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    claimStarted.countDown();
                    try {
                        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                        return Optional.empty();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return Optional.of(task);
                    }
                });
        OrderDraftCardWorker worker = new OrderDraftCardWorker(tasks, runner, failures, true, 60, 60);

        worker.poll();
        assertThat(claimStarted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.shutdown();

        verify(tasks).releaseOwnedForShutdown(eq(2L), any(String.class));
        verify(runner, never()).execute(any());
    }

    @Test
    void runnerAndRecoveryFailuresAreIsolatedSoDrainContinues() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        OrderDraftCardRunner runner = mock(OrderDraftCardRunner.class);
        OrderDraftCardFailureCoordinator failures = mock(OrderDraftCardFailureCoordinator.class);
        AsyncTaskStore.AsyncTask first = task(3L);
        AsyncTaskStore.AsyncTask second = task(4L);
        AtomicInteger claims = new AtomicInteger();
        when(tasks.claim(eq(OrderDraftCardEnqueuer.TASK_TYPE), any(String.class), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> switch (claims.getAndIncrement()) {
                    case 0 -> Optional.of(first);
                    case 1 -> Optional.of(second);
                    default -> Optional.empty();
                });
        doThrow(new IllegalStateException("runner failed")).when(runner).execute(first);
        doThrow(new IllegalStateException("database still unavailable"))
                .when(failures)
                .recoverUnhandledFailure(first, "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED");
        CountDownLatch secondProcessed = new CountDownLatch(1);
        doAnswer(invocation -> {
                    secondProcessed.countDown();
                    return null;
                })
                .when(runner)
                .execute(second);
        OrderDraftCardWorker worker = new OrderDraftCardWorker(
                tasks, runner, failures, true, 60, 60);
        try {
            worker.poll();
            assertThat(secondProcessed.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.shutdown();
        }

        verify(failures).recoverUnhandledFailure(
                first, "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED");
        verify(runner).execute(second);
    }

    @Test
    void finalizingTaskIsRecoveredWithoutRunningExternalSendAgain() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        OrderDraftCardRunner runner = mock(OrderDraftCardRunner.class);
        OrderDraftCardFailureCoordinator failures = mock(OrderDraftCardFailureCoordinator.class);
        AsyncTaskStore.AsyncTask finalizing = task(5L, "FINALIZING");
        AtomicInteger claims = new AtomicInteger();
        when(tasks.claim(eq(OrderDraftCardEnqueuer.TASK_TYPE), any(String.class), eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> claims.getAndIncrement() == 0
                        ? Optional.of(finalizing)
                        : Optional.empty());
        CountDownLatch recovered = new CountDownLatch(1);
        doAnswer(invocation -> {
                    recovered.countDown();
                    return null;
                })
                .when(failures)
                .recoverUnhandledFailure(finalizing, "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED");
        OrderDraftCardWorker worker = new OrderDraftCardWorker(
                tasks, runner, failures, true, 60, 60);
        try {
            worker.poll();
            assertThat(recovered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            worker.shutdown();
        }

        verify(runner, never()).execute(finalizing);
    }

    private static AsyncTaskStore.AsyncTask task(long id) {
        return task(id, "RUNNING");
    }

    private static AsyncTaskStore.AsyncTask task(long id, String status) {
        Instant now = Instant.now();
        return new AsyncTaskStore.AsyncTask(
                id,
                OrderDraftCardEnqueuer.TASK_TYPE,
                "card:7",
                status,
                1,
                3,
                now,
                now.plusSeconds(60),
                "test-owner",
                null,
                "card-task-" + id,
                now,
                now);
    }
}
