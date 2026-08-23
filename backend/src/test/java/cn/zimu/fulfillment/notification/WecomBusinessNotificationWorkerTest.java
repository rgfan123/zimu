package cn.zimu.fulfillment.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WecomBusinessNotificationWorkerTest {

    @Test
    void schedulerReturnsImmediatelyAndRepeatedPollsCannotOverlapOneDrain() throws Exception {
        WecomNotificationStore store = mock(WecomNotificationStore.class);
        WecomBusinessNotificationRunner runner = mock(WecomBusinessNotificationRunner.class);
        NotificationBatch batch = batch(41L);
        AtomicInteger claims = new AtomicInteger();
        when(store.claim(anyString(), eq(Duration.ofSeconds(120)), eq(20)))
                .thenAnswer(invocation -> claims.incrementAndGet() == 1 ? Optional.of(batch) : Optional.empty());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
                    started.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return null;
                })
                .when(runner)
                .execute(eq(batch), anyString());
        WecomBusinessNotificationWorker worker =
                new WecomBusinessNotificationWorker(store, runner, true, 120, 20, 60);
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
    void shutdownRaceAfterClaimReleasesBatchWithoutRunningIt() throws Exception {
        WecomNotificationStore store = mock(WecomNotificationStore.class);
        WecomBusinessNotificationRunner runner = mock(WecomBusinessNotificationRunner.class);
        NotificationBatch batch = batch(42L);
        CountDownLatch claimStarted = new CountDownLatch(1);
        when(store.claim(anyString(), any(Duration.class), anyInt())).thenAnswer(invocation -> {
            claimStarted.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                return Optional.empty();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Optional.of(batch);
            }
        });
        WecomBusinessNotificationWorker worker =
                new WecomBusinessNotificationWorker(store, runner, true, 120, 20, 60);

        worker.poll();
        assertThat(claimStarted.await(1, TimeUnit.SECONDS)).isTrue();
        worker.shutdown();

        verify(store).releaseOwnedForShutdown(eq(42L), anyString());
        verify(runner, org.mockito.Mockito.never()).execute(any(), anyString());
    }

    private static NotificationBatch batch(long id) {
        return new NotificationBatch(
                id,
                "ORDER_OPS",
                Instant.now(),
                List.of(new NotificationItem(
                        id,
                        "ORDER_EVENT",
                        id,
                        "ORDER_CREATED",
                        Map.of("order_no", "ORD-" + id))));
    }
}
