package cn.zimu.fulfillment.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restart-safe polling worker for Issue #90 notification digests. */
@Component
public class WecomBusinessNotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessNotificationWorker.class);

    private final WecomNotificationStore store;
    private final WecomBusinessNotificationRunner runner;
    private final boolean enabled;
    private final Duration lease;
    private final int batchLimit;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "wecom-notification-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    public WecomBusinessNotificationWorker(
            WecomNotificationStore store,
            WecomBusinessNotificationRunner runner,
            @Value("${app.wecom-notification.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-notification.lease-seconds:120}") long leaseSeconds,
            @Value("${app.wecom-notification.batch-limit:20}") int batchLimit,
            @Value("${app.wecom-notification.claim-error-suppress-seconds:60}") long suppressSeconds) {
        this.store = store;
        this.runner = runner;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(30, leaseSeconds));
        this.batchLimit = Math.max(1, Math.min(100, batchLimit));
        this.claimErrorSuppressWindow = Duration.ofSeconds(Math.max(1, suppressSeconds));
    }

    @Scheduled(fixedDelayString = "${app.wecom-notification.poll-ms:1000}")
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
            Optional<NotificationBatch> claimed;
            try {
                claimed = store.claim(owner, lease, batchLimit);
            } catch (RuntimeException ex) {
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                log.warn("企微业务通知领取失败，{} 秒内暂停轮询", claimErrorSuppressWindow.toSeconds());
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            runner.execute(claimed.get(), owner);
        }
    }
}
