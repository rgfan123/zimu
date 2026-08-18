package cn.zimu.fulfillment.message;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 消息链路后台 Worker：租约式领取 PostgreSQL 任务，最多三次重试，重启后恢复。
 *
 * <p>领取使用 SKIP LOCKED，多实例并发时每个任务只被一个 Worker 处理；处理完成后显式标记
 * 成功或失败（退避重试/终态失败）。最终失败会创建唯一的 NEED_REVIEW 人工待办，不向群内补发消息。
 */
@Component
public class InterpretationWorker {

    private static final Logger log = LoggerFactory.getLogger(InterpretationWorker.class);

    private final AsyncTaskStore taskStore;
    private final InterpretationService interpretationService;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "worker-" + UUID.randomUUID();

    public InterpretationWorker(
            AsyncTaskStore taskStore,
            InterpretationService interpretationService,
            @Value("${app.message-worker.enabled:true}") boolean enabled,
            @Value("${app.message-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.message-worker.backoff-seconds:5}") long backoffSeconds) {
        this.taskStore = taskStore;
        this.interpretationService = interpretationService;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
    }

    @Scheduled(fixedDelayString = "${app.message-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed = taskStore.claim(owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            process(claimed.get());
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        try {
            switch (task.taskType()) {
                case "INTERPRET_MESSAGE" -> {
                    if ("FINALIZING".equals(task.status())) {
                        interpretationService.resumeFinalization(task);
                    } else {
                        interpretationService.interpret(task);
                    }
                }
                default -> throw new IllegalStateException("未知任务类型: " + task.taskType());
            }
        } catch (Exception ex) {
            String error = "INTERPRET_MESSAGE".equals(task.taskType())
                    ? InterpretationFailureCode.MODEL_CALL_FAILED.name()
                    : "ASYNC_TASK_FAILED";
            log.warn(
                    "异步任务 {} ({}) 执行失败，错误码={}，异常类型={}",
                    task.id(),
                    task.taskType(),
                    error,
                    ex.getClass().getSimpleName());
            if ("INTERPRET_MESSAGE".equals(task.taskType())) {
                interpretationService.recordFailure(task, error, backoff);
            } else {
                taskStore.fail(task.id(), owner, error, backoff);
            }
        }
    }
}
