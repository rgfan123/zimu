package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WecomTrackingFileWorkerTest {

    @Test
    void scheduledPollReturnsWhileSlowFileProcessingContinuesOffThread() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomTrackingFileProcessor processor = mock(WecomTrackingFileProcessor.class);
        WecomTrackingFileDraftService drafts = mock(WecomTrackingFileDraftService.class);
        AsyncTaskStore.AsyncTask task = runningTask(1L);
        when(tasks.claim(
                        eq(MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE),
                        any(String.class),
                        eq(Duration.ofSeconds(60))))
                .thenReturn(Optional.of(task), Optional.empty());
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        AtomicBoolean processingFinished = new AtomicBoolean();
        doAnswer(invocation -> {
                    processingStarted.countDown();
                    releaseProcessing.await(2, TimeUnit.SECONDS);
                    processingFinished.set(true);
                    return null;
                })
                .when(processor)
                .process(task);
        WecomTrackingFileWorker worker =
                new WecomTrackingFileWorker(tasks, processor, drafts, true, 60, 0, 60);

        try {
            long startedAt = System.nanoTime();
            worker.poll();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            assertThat(elapsedMillis).isLessThan(1_000L);
            assertThat(processingStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(processingFinished).isFalse();
        } finally {
            releaseProcessing.countDown();
            worker.shutdown();
        }
    }

    @Test
    void repeatedScheduledPollsDoNotQueueOrRunConcurrentDrains() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomTrackingFileProcessor processor = mock(WecomTrackingFileProcessor.class);
        WecomTrackingFileDraftService drafts = mock(WecomTrackingFileDraftService.class);
        AsyncTaskStore.AsyncTask task = runningTask(2L);
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch releaseProcessing = new CountDownLatch(1);
        CountDownLatch emptyClaimObserved = new CountDownLatch(1);
        AtomicInteger claimCount = new AtomicInteger();
        AtomicInteger activeProcessors = new AtomicInteger();
        AtomicInteger maxConcurrentProcessors = new AtomicInteger();
        when(tasks.claim(
                        eq(MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE),
                        any(String.class),
                        eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    int claim = claimCount.incrementAndGet();
                    if (claim == 1) {
                        return Optional.of(task);
                    }
                    emptyClaimObserved.countDown();
                    return Optional.empty();
                });
        doAnswer(invocation -> {
                    int active = activeProcessors.incrementAndGet();
                    maxConcurrentProcessors.accumulateAndGet(active, Math::max);
                    processingStarted.countDown();
                    try {
                        releaseProcessing.await(2, TimeUnit.SECONDS);
                    } finally {
                        activeProcessors.decrementAndGet();
                    }
                    return null;
                })
                .when(processor)
                .process(task);
        WecomTrackingFileWorker worker =
                new WecomTrackingFileWorker(tasks, processor, drafts, true, 60, 0, 60);

        try {
            worker.poll();
            assertThat(processingStarted.await(1, TimeUnit.SECONDS)).isTrue();
            try {
                assertThatCode(() -> {
                            for (int index = 0; index < 10; index++) {
                                worker.poll();
                            }
                        })
                        .doesNotThrowAnyException();
            } finally {
                releaseProcessing.countDown();
            }

            assertThat(emptyClaimObserved.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(claimCount).hasValue(2);
            assertThat(maxConcurrentProcessors).hasValue(1);
        } finally {
            releaseProcessing.countDown();
            worker.shutdown();
        }
    }

    @Test
    void shutdownInterruptsSlowDrainAndMakesFuturePollsNoOps() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomTrackingFileProcessor processor = mock(WecomTrackingFileProcessor.class);
        WecomTrackingFileDraftService drafts = mock(WecomTrackingFileDraftService.class);
        AsyncTaskStore.AsyncTask task = runningTask(3L);
        AtomicInteger claimCount = new AtomicInteger();
        when(tasks.claim(
                        eq(MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE),
                        any(String.class),
                        eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    claimCount.incrementAndGet();
                    return Optional.of(task);
                });
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch processingInterrupted = new CountDownLatch(1);
        doAnswer(invocation -> {
                    processingStarted.countDown();
                    try {
                        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        processingInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("test processor interrupted during shutdown", exception);
                    }
                    return null;
                })
                .when(processor)
                .process(task);
        WecomTrackingFileWorker worker =
                new WecomTrackingFileWorker(tasks, processor, drafts, true, 60, 0, 60);

        worker.poll();
        assertThat(processingStarted.await(1, TimeUnit.SECONDS)).isTrue();
        long startedAt = System.nanoTime();
        worker.shutdown();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis).isLessThan(2_500L);
        assertThat(processingInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThatCode(worker::poll).doesNotThrowAnyException();
        assertThatCode(worker::shutdown).doesNotThrowAnyException();
        Thread.sleep(100);
        assertThat(claimCount).hasValue(1);
        verify(tasks).releaseOwnedForShutdown(eq(3L), any(String.class));
        verifyNoInteractions(drafts);
    }

    @Test
    void taskClaimedDuringShutdownIsLeftForLeaseRecoveryWithoutProcessing() throws Exception {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomTrackingFileProcessor processor = mock(WecomTrackingFileProcessor.class);
        WecomTrackingFileDraftService drafts = mock(WecomTrackingFileDraftService.class);
        AsyncTaskStore.AsyncTask task = runningTask(4L);
        CountDownLatch claimStarted = new CountDownLatch(1);
        CountDownLatch claimInterrupted = new CountDownLatch(1);
        AtomicInteger claimCount = new AtomicInteger();
        when(tasks.claim(
                        eq(MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE),
                        any(String.class),
                        eq(Duration.ofSeconds(60))))
                .thenAnswer(invocation -> {
                    claimCount.incrementAndGet();
                    claimStarted.countDown();
                    try {
                        new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                        return Optional.empty();
                    } catch (InterruptedException exception) {
                        claimInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        return Optional.of(task);
                    }
                });
        WecomTrackingFileWorker worker =
                new WecomTrackingFileWorker(tasks, processor, drafts, true, 60, 0, 60);

        worker.poll();
        assertThat(claimStarted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.shutdown();

        assertThat(claimInterrupted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(claimCount).hasValue(1);
        verify(tasks).releaseOwnedForShutdown(eq(4L), any(String.class));
        verifyNoInteractions(processor, drafts);
    }

    private static AsyncTaskStore.AsyncTask runningTask(long id) {
        Instant now = Instant.now();
        return new AsyncTaskStore.AsyncTask(
                id,
                MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE,
                "submission:" + id,
                "RUNNING",
                1,
                3,
                now,
                now.plusSeconds(60),
                "owner",
                null,
                "wecom-file-" + id,
                now,
                now);
    }
}
