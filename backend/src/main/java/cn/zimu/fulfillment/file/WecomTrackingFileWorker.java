package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
        this(tasks, processor, drafts, enabled, leaseSeconds, backoffSeconds, 60);
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
        this.tasks = tasks;
        this.processor = processor;
        this.drafts = drafts;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
        this.claimErrorSuppressWindow = Duration.ofSeconds(claimErrorSuppressSeconds);
    }

    @Scheduled(fixedDelayString = "${app.wecom-tracking-file-worker.poll-ms:500}")
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
            process(claimed.get());
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
            drafts.recordFailure(task, exception.code(), backoff);
        } catch (RuntimeException exception) {
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
}
