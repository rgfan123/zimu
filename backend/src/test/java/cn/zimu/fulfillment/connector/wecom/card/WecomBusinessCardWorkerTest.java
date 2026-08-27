package cn.zimu.fulfillment.connector.wecom.card;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WecomBusinessCardWorkerTest {

    @Test
    void unhandledRunnerFailureFencesAnySendingCardAsUnknown() {
        AsyncTaskStore tasks = mock(AsyncTaskStore.class);
        WecomBusinessCardRunner runner = mock(WecomBusinessCardRunner.class);
        WecomBusinessCardStore cards = mock(WecomBusinessCardStore.class);
        WecomBusinessCardWorker worker = new WecomBusinessCardWorker(tasks, runner, cards, true, 60, 60);
        AsyncTaskStore.AsyncTask task = new AsyncTaskStore.AsyncTask(
                7,
                WecomBusinessCardEnqueuer.TASK_TYPE,
                "card:19",
                "RUNNING",
                1,
                3,
                Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60),
                "worker-a",
                null,
                "card-19",
                Instant.EPOCH,
                Instant.EPOCH);
        when(tasks.claim(eq(WecomBusinessCardEnqueuer.TASK_TYPE), any(), eq(Duration.ofSeconds(60))))
                .thenReturn(Optional.of(task), Optional.empty());
        org.mockito.Mockito.doThrow(new IllegalStateException("ack persistence failed"))
                .when(runner)
                .execute(task);

        worker.poll();

        verify(cards).recordUnknown(19, "WECOM_BUSINESS_CARD_RUNNER_FAILED");
        verify(tasks).fail(
                eq(7L), any(), eq("WECOM_BUSINESS_CARD_RUNNER_FAILED"), eq(Duration.ofSeconds(30)));
    }
}
