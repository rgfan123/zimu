package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
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

/** 租约式单聊运单文件 Worker；与模型解释队列按 task_type 隔离。 */
@Component
public class WecomTrackingFileWorker {

    private static final Logger log = LoggerFactory.getLogger(WecomTrackingFileWorker.class);

    private final AsyncTaskStore tasks;
    private final WecomTrackingFileProcessor processor;
    private final WecomTrackingFileDraftService drafts;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final Duration claimErrorSuppressWindow;
    private final ExecutorService drainExecutor;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final String owner = "wecom-tracking-file-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    /** 测试手动驱动构造器。 */
    public WecomTrackingFileWorker(
            AsyncTaskStore tasks,
            WecomTrackingFileProcessor processor,
            WecomTrackingFileDraftService drafts,
            boolean enabled,
            long leaseSeconds,
            long backoffSeconds) {
        this(tasks, processor, drafts, enabled, leaseSeconds, backoffSeconds, 60, new DirectExecutorService());
    }

    @Autowired
    public WecomTrackingFileWorker(
            AsyncTaskStore tasks,
            WecomTrackingFileProcessor processor,
            WecomTrackingFileDraftService drafts,
            @Value("${app.wecom-tracking-file-worker.enabled:${app.message-worker.enabled:true}}") boolean enabled,
            @Value("${app.wecom-tracking-file-worker.lease-seconds:60}") long leaseSeconds,
            @Value("${app.wecom-tracking-file-worker.backoff-seconds:5}") long backoffSeconds,
            @Value("${app.wecom-tracking-file-worker.claim-error-suppress-seconds:60}")
                    long claimErrorSuppressSeconds) {
        this(
                tasks,
                processor,
                drafts,
                enabled,
                leaseSeconds,
                backoffSeconds,
                claimErrorSuppressSeconds,
                newDrainExecutor());
    }

    private WecomTrackingFileWorker(
            AsyncTaskStore tasks,
            WecomTrackingFileProcessor processor,
            WecomTrackingFileDraftService drafts,
            boolean enabled,
            long leaseSeconds,
            long backoffSeconds,
            long claimErrorSuppressSeconds,
            ExecutorService drainExecutor) {
        this.tasks = tasks;
        this.processor = processor;
        this.drafts = drafts;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
        this.claimErrorSuppressWindow = Duration.ofSeconds(claimErrorSuppressSeconds);
        this.drainExecutor = drainExecutor;
    }

    @Scheduled(fixedDelayString = "${app.wecom-tracking-file-worker.poll-ms:500}")
    public void poll() {
        if (!enabled || closed.get()) {
            return;
        }
        if (!drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            drainExecutor.execute(this::drain);
        } catch (RejectedExecutionException exception) {
            drainScheduled.set(false);
            if (!closed.get()) {
                throw exception;
            }
        }
    }

    private void drain() {
        try {
            if (closed.get()) {
                return;
            }
            Instant suppressedUntil = claimSuppressUntil;
            if (suppressedUntil != null && Instant.now().isBefore(suppressedUntil)) {
                return;
            }
            claimSuppressUntil = null;
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                Optional<AsyncTaskStore.AsyncTask> claimed;
                try {
                    claimed = tasks.claim(
                            MessageSubmissionService.WECOM_TRACKING_FILE_TASK_TYPE,
                            owner,
                            lease);
                } catch (RuntimeException exception) {
                    claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                    log.warn(
                            "企微运单文件 Worker 领取任务失败，{} 秒内暂停轮询 exceptionType={}",
                            claimErrorSuppressWindow.toSeconds(),
                            exception.getClass().getSimpleName());
                    return;
                }
                if (claimed.isEmpty()) {
                    claimSuppressUntil = null;
                    return;
                }
                if (closed.get() || Thread.currentThread().isInterrupted()) {
                    // 关闭与 claim 返回竞态时不进入下载/解析，也不伪造业务失败。
                    releaseForShutdown(claimed.get());
                    return;
                }
                process(claimed.get());
            }
        } finally {
            drainScheduled.set(false);
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
                    log.warn("企微运单文件 Worker 专用线程未在关闭窗口内退出");
                }
            }
        } catch (InterruptedException exception) {
            drainExecutor.shutdownNow();
            Thread.currentThread().interrupt();
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
                    Thread thread = new Thread(runnable, "wecom-tracking-file-drain");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** Existing integration tests drive poll synchronously and deterministically. */
    private static final class DirectExecutorService extends AbstractExecutorService {

        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            if (shutdown) {
                throw new RejectedExecutionException("direct executor is shut down");
            }
            command.run();
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        try {
            if ("FINALIZING".equals(task.status())) {
                drafts.resumeFinalization(task);
            } else {
                processor.process(task);
            }
        } catch (WecomTrackingFileException exception) {
            if (isShutdownCancellation()) {
                releaseForShutdown(task);
                return;
            }
            drafts.recordFailure(task, exception.code(), backoff);
        } catch (RuntimeException exception) {
            if (isShutdownCancellation()) {
                releaseForShutdown(task);
                return;
            }
            log.warn(
                    "企微运单文件任务处理失败 taskId={} exceptionType={}",
                    task.id(),
                    exception.getClass().getSimpleName());
            drafts.recordFailure(
                    task,
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PROCESSING_FAILED,
                    backoff);
        }
    }

    private boolean isShutdownCancellation() {
        return closed.get() && Thread.currentThread().isInterrupted();
    }

    private void releaseForShutdown(AsyncTaskStore.AsyncTask task) {
        if (!"RUNNING".equals(task.status())) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            tasks.releaseOwnedForShutdown(task.id(), owner);
        } catch (RuntimeException exception) {
            log.warn(
                    "企微运单文件 Worker 关闭释放任务失败 taskId={} exceptionType={}",
                    task.id(),
                    exception.getClass().getSimpleName());
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
