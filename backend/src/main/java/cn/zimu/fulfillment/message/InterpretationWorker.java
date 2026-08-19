package cn.zimu.fulfillment.message;

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

/**
 * 消息链路后台 Worker：租约式领取 PostgreSQL 任务，最多三次重试，重启后恢复。
 *
 * <p>领取使用 SKIP LOCKED，多实例并发时每个任务只被一个 Worker 处理；处理完成后显式标记
 * 成功或失败（退避重试/终态失败）。最终失败会创建唯一的 NEED_REVIEW 人工待办，不向群内补发消息。
 *
 * <p>领取任务失败（数据库不可达或连接池耗尽）时进入退避抑制：暂停轮询一段时间再恢复探测，
 * 而不是每 100-500ms 空转刷屏。这保证测试中容器随测试类结束后、仍随 Spring 上下文缓存存活的
 * 孤儿 Worker 不再对已停止的数据库产生异常风暴（测试隔离缺陷 02 的泄漏源）。
 */
@Component
public class InterpretationWorker {

    private static final Logger log = LoggerFactory.getLogger(InterpretationWorker.class);

    private final AsyncTaskStore taskStore;
    private final InterpretationService interpretationService;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    /** 测试手动驱动的便捷构造器（不注入抑制窗口，默认 60 秒）。 */
    public InterpretationWorker(
            AsyncTaskStore taskStore,
            InterpretationService interpretationService,
            boolean enabled,
            long leaseSeconds,
            long backoffSeconds) {
        this(taskStore, interpretationService, enabled, leaseSeconds, backoffSeconds, 60);
    }

    @Autowired
    public InterpretationWorker(
            AsyncTaskStore taskStore,
            InterpretationService interpretationService,
            @Value("${app.message-worker.enabled:true}") boolean enabled,
            @Value("${app.message-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.message-worker.backoff-seconds:5}") long backoffSeconds,
            @Value("${app.message-worker.claim-error-suppress-seconds:60}") long claimErrorSuppressSeconds) {
        this.taskStore = taskStore;
        this.interpretationService = interpretationService;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
        this.claimErrorSuppressWindow = Duration.ofSeconds(claimErrorSuppressSeconds);
    }

    @Scheduled(fixedDelayString = "${app.message-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant suppressedUntil = claimSuppressUntil;
        if (suppressedUntil != null) {
            if (Instant.now().isBefore(suppressedUntil)) {
                return;
            }
            claimSuppressUntil = null;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed;
            try {
                // 09 票：多 Worker 共享 async_tasks，按类型领取防止互抢 QUALITY 评测任务。
                claimed = taskStore.claim("INTERPRET_MESSAGE", owner, lease);
            } catch (RuntimeException ex) {
                // 只有「领取任务」失败（数据库不可达/连接池耗尽）才进入退避抑制；
                // process 内部的任务级异常保持原有语义继续向外传播（调度器记录，测试依赖该传播）。
                boolean firstFailure = claimSuppressUntil == null;
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                if (firstFailure) {
                    log.warn(
                            "消息 Worker 领取任务失败（数据库不可达或连接池耗尽），"
                                    + "{} 秒内暂停轮询，数据库恢复后自动重试",
                            claimErrorSuppressWindow.toSeconds(),
                            ex);
                } else {
                    log.warn(
                            "消息 Worker 仍无法领取任务，继续抑制轮询 {} 秒",
                            claimErrorSuppressWindow.toSeconds());
                }
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
