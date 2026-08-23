package cn.zimu.fulfillment.notification;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ExecutorService drainExecutor;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String owner = "wecom-notification-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    @Autowired
    public WecomBusinessNotificationWorker(
            WecomNotificationStore store,
            WecomBusinessNotificationRunner runner,
            @Value("${app.wecom-notification.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-notification.lease-seconds:120}") long leaseSeconds,
            @Value("${app.wecom-notification.batch-limit:20}") int batchLimit,
            @Value("${app.wecom-notification.claim-error-suppress-seconds:60}") long suppressSeconds) {
        this(store, runner, enabled, leaseSeconds, batchLimit, suppressSeconds, newDrainExecutor());
    }

    WecomBusinessNotificationWorker(
            WecomNotificationStore store,
            WecomBusinessNotificationRunner runner,
            boolean enabled,
            long leaseSeconds,
            int batchLimit,
            long suppressSeconds,
            ExecutorService drainExecutor) {
        this.store = store;
        this.runner = runner;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(30, leaseSeconds));
        this.batchLimit = Math.max(1, Math.min(100, batchLimit));
        this.claimErrorSuppressWindow = Duration.ofSeconds(Math.max(1, suppressSeconds));
        this.drainExecutor = drainExecutor;
    }

    @Scheduled(fixedDelayString = "${app.wecom-notification.poll-ms:1000}")
    public void poll() {
        if (!enabled || closed.get() || !drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            drainExecutor.execute(this::drain);
        } catch (RejectedExecutionException ex) {
            drainScheduled.set(false);
            if (!closed.get()) {
                throw ex;
            }
        }
    }

    private void drain() {
        try {
            drainAvailable();
        } finally {
            drainScheduled.set(false);
        }
    }

    private void drainAvailable() {
        if (closed.get()) {
            return;
        }
        Instant suppressedUntil = claimSuppressUntil;
        if (suppressedUntil != null && Instant.now().isBefore(suppressedUntil)) {
            return;
        }
        claimSuppressUntil = null;
        while (!closed.get() && !Thread.currentThread().isInterrupted()) {
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
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                releaseForShutdown(claimed.get());
                return;
            }
            runner.execute(claimed.get(), owner);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        drainExecutor.shutdown();
        try {
            if (!drainExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                drainExecutor.shutdownNow();
                if (!drainExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("企微业务通知 Worker 专用线程未在关闭窗口内退出");
                }
            }
        } catch (InterruptedException ex) {
            drainExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void releaseForShutdown(NotificationBatch batch) {
        boolean interrupted = Thread.interrupted();
        try {
            store.releaseOwnedForShutdown(batch.id(), owner);
        } catch (RuntimeException ex) {
            log.warn("企微业务通知 Worker 关闭释放批次失败 batchId={} exceptionType={}",
                    batch.id(), ex.getClass().getSimpleName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static ExecutorService newDrainExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1),
                runnable -> {
                    Thread thread = new Thread(runnable, "wecom-notification-drain");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
