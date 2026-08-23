package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls only WECOM_ORDER_DRAFT_CARD tasks; other async workers cannot steal them. */
@Component
public class OrderDraftCardWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderDraftCardWorker.class);

    private final AsyncTaskStore tasks;
    private final OrderDraftCardRunner runner;
    private final boolean enabled;
    private final Duration lease;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "order-draft-card-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    public OrderDraftCardWorker(
            AsyncTaskStore tasks,
            OrderDraftCardRunner runner,
            @Value("${app.wecom-order-draft-card.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-order-draft-card.lease-seconds:60}") long leaseSeconds,
            @Value("${app.wecom-order-draft-card.claim-error-suppress-seconds:60}") long claimErrorSuppressSeconds) {
        this.tasks = tasks;
        this.runner = runner;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(60, leaseSeconds));
        this.claimErrorSuppressWindow = Duration.ofSeconds(Math.max(1, claimErrorSuppressSeconds));
    }

    @Scheduled(fixedDelayString = "${app.wecom-order-draft-card.poll-ms:1000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant suppressedUntil = claimSuppressUntil;
        if (suppressedUntil != null && Instant.now().isBefore(suppressedUntil)) {
            return;
        }
        claimSuppressUntil = null;
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> task;
            try {
                task = tasks.claim(OrderDraftCardEnqueuer.TASK_TYPE, owner, lease);
            } catch (RuntimeException ex) {
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                log.warn(
                        "订单草稿卡片 Worker 领取任务失败，{} 秒内暂停轮询",
                        claimErrorSuppressWindow.toSeconds());
                return;
            }
            if (task.isEmpty()) {
                return;
            }
            runner.execute(task.get());
        }
    }
}
