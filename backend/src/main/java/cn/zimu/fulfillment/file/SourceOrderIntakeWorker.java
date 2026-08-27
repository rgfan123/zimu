package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 租约式来源订单附件 Worker；意外失败退避重试，领域失败由处理器稳定收口。 */
@Component
class SourceOrderIntakeWorker {

    private static final Logger log = LoggerFactory.getLogger(SourceOrderIntakeWorker.class);

    private final AsyncTaskStore tasks;
    private final SourceOrderIntakeProcessor processor;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "source-order-intake-" + UUID.randomUUID();

    SourceOrderIntakeWorker(
            AsyncTaskStore tasks,
            SourceOrderIntakeProcessor processor,
            @Value("${app.source-order-intake-worker.enabled:true}") boolean enabled,
            @Value("${app.source-order-intake-worker.lease-seconds:120}") long leaseSeconds,
            @Value("${app.source-order-intake-worker.backoff-seconds:10}") long backoffSeconds) {
        this.tasks = tasks;
        this.processor = processor;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
    }

    @Scheduled(fixedDelayString = "${app.source-order-intake-worker.poll-ms:1000}")
    void poll() {
        if (!enabled) {
            return;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed;
            try {
                claimed = tasks.claim(SourceOrderIntakeService.TASK_TYPE, owner, lease);
            } catch (RuntimeException exception) {
                log.warn("来源订单附件 Worker 暂时无法领取任务: {}", exception.getClass().getSimpleName());
                return;
            }
            if (claimed.isEmpty()) {
                return;
            }
            AsyncTaskStore.AsyncTask task = claimed.get();
            try {
                processor.process(task);
                tasks.succeed(task.id(), owner);
            } catch (RuntimeException exception) {
                log.warn("来源订单附件任务 {} 执行失败，将按退避重试", task.id());
                tasks.fail(task.id(), owner, "SOURCE_ORDER_INTAKE_FAILED", backoff);
            }
        }
    }
}

