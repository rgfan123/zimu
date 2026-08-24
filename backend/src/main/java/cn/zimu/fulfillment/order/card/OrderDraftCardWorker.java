package cn.zimu.fulfillment.order.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
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

/** Polls only WECOM_ORDER_DRAFT_CARD tasks; other async workers cannot steal them. */
@Component
public class OrderDraftCardWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderDraftCardWorker.class);
    private static final String UNHANDLED_ERROR = "WECOM_ORDER_DRAFT_CARD_RUNNER_FAILED";

    private final AsyncTaskStore tasks;
    private final OrderDraftCardRunner runner;
    private final OrderDraftCardFailureCoordinator failures;
    private final boolean enabled;
    private final Duration lease;
    private final Duration claimErrorSuppressWindow;
    private final ExecutorService drainExecutor;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String owner = "order-draft-card-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    @Autowired
    public OrderDraftCardWorker(
            AsyncTaskStore tasks,
            OrderDraftCardRunner runner,
            OrderDraftCardFailureCoordinator failures,
            @Value("${app.wecom-order-draft-card.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-order-draft-card.lease-seconds:60}") long leaseSeconds,
            @Value("${app.wecom-order-draft-card.claim-error-suppress-seconds:60}") long claimErrorSuppressSeconds) {
        this(
                tasks,
                runner,
                failures,
                enabled,
                leaseSeconds,
                claimErrorSuppressSeconds,
                newDrainExecutor());
    }

    OrderDraftCardWorker(
            AsyncTaskStore tasks,
            OrderDraftCardRunner runner,
            OrderDraftCardFailureCoordinator failures,
            boolean enabled,
            long leaseSeconds,
            long claimErrorSuppressSeconds,
            ExecutorService drainExecutor) {
        this.tasks = tasks;
        this.runner = runner;
        this.failures = failures;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(60, leaseSeconds));
        this.claimErrorSuppressWindow = Duration.ofSeconds(Math.max(1, claimErrorSuppressSeconds));
        this.drainExecutor = drainExecutor;
    }

    @Scheduled(fixedDelayString = "${app.wecom-order-draft-card.poll-ms:1000}")
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
            if (closed.get() || Thread.currentThread().isInterrupted()) {
                releaseForShutdown(task.get());
                return;
            }
            process(task.get());
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        if ("FINALIZING".equals(task.status())) {
            recover(task);
            return;
        }
        try {
            runner.execute(task);
        } catch (RuntimeException ex) {
            recover(task);
        }
    }

    private void recover(AsyncTaskStore.AsyncTask task) {
        try {
            failures.recoverUnhandledFailure(task, UNHANDLED_ERROR);
        } catch (RuntimeException recoveryFailure) {
            // Leave the owned lease intact. Once the database recovers, lease expiry makes the
            // task claimable again; swallowing here lets this drain continue with unrelated work.
            log.warn(
                    "订单草稿卡片任务异常收口失败 taskId={} exceptionType={}",
                    task.id(),
                    recoveryFailure.getClass().getSimpleName());
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
                    log.warn("订单草稿卡片 Worker 专用线程未在关闭窗口内退出");
                }
            }
        } catch (InterruptedException ex) {
            drainExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private void releaseForShutdown(AsyncTaskStore.AsyncTask task) {
        if (!"RUNNING".equals(task.status())) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            tasks.releaseOwnedForShutdown(task.id(), owner);
        } catch (RuntimeException ex) {
            log.warn("订单草稿卡片 Worker 关闭释放任务失败 taskId={} exceptionType={}",
                    task.id(), ex.getClass().getSimpleName());
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
                    Thread thread = new Thread(runnable, "order-draft-card-drain");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }
}
