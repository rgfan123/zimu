package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.AsyncTaskStore.ApplicationDisposition;
import cn.zimu.fulfillment.message.AsyncTaskStore.ApplicationFence;
import cn.zimu.fulfillment.order.OperationalAlertService;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FulfillmentExportWecomDeliveryFinalizerTest {

    private final FulfillmentExportWecomStore store = mock(FulfillmentExportWecomStore.class);
    private final AsyncTaskStore taskStore = mock(AsyncTaskStore.class);
    private final OperationalAlertService alerts = mock(OperationalAlertService.class);
    private final FulfillmentExportWecomDeliveryFinalizer finalizer =
            new FulfillmentExportWecomDeliveryFinalizer(store, taskStore, alerts, 2400);
    private final AsyncTaskStore.AsyncTask task = task();

    @BeforeEach
    void currentOwnerBeforeRenewal() {
        when(taskStore.lockApplicationFence(task.id(), task.leaseOwner()))
                .thenReturn(new ApplicationFence(ApplicationDisposition.CURRENT, task));
        when(taskStore.renewLease(task.id(), task.leaseOwner(), Duration.ofMinutes(40)))
                .thenReturn(false);
    }

    @Test
    void initialClaimStopsBeforeDeliveryCasWhenRenewalLosesTheLease() {
        assertThat(finalizer.claimInitial(task, 41L, 1, 1))
                .isEqualTo(FulfillmentExportWecomDeliveryFinalizer.ClaimOutcome.LOST_LEASE);

        verify(store, never()).beginAttempt(anyLong(), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void reminderClaimStopsBeforeDeliveryCasWhenRenewalLosesTheLease() {
        assertThat(finalizer.claimReminder(task, 41L, 1, 1))
                .isEqualTo(FulfillmentExportWecomDeliveryFinalizer.ClaimOutcome.LOST_LEASE);

        verify(store, never()).prepareReminder(anyLong(), anyInt(), anyInt(), anyString());
    }

    private static AsyncTaskStore.AsyncTask task() {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        return new AsyncTaskStore.AsyncTask(
                17L,
                FulfillmentExportWecomService.TASK_TYPE,
                "export:41:INITIAL:1",
                "RUNNING",
                1,
                3,
                now,
                now.plusSeconds(2400),
                "owner-1",
                null,
                "wecom-export-initial:41",
                now,
                now);
    }
}
