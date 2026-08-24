package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * QUALITY 评测后台 Worker（meta-agent-platform-impl 09）：租约式领取 {@code QUALITY_EVAL}
 * 任务并执行（与消息链路 {@code InterpretationWorker} 同款 Spring Worker 模式）。
 * 默认关闭（{@code app.quality-eval.enabled=false}，参考指标按需启用）；执行失败记 fail
 * 重试，不阻断草稿确认。
 */
@Component
public class QualityEvalWorker {

    private static final Logger log = LoggerFactory.getLogger(QualityEvalWorker.class);

    private final AsyncTaskStore taskStore;
    private final QualityEvalService service;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "quality-worker-" + UUID.randomUUID();

    public QualityEvalWorker(
            AsyncTaskStore taskStore,
            QualityEvalService service,
            @Value("${app.quality-eval.enabled:false}") boolean enabled,
            @Value("${app.quality-eval.lease-seconds:60}") long leaseSeconds,
            @Value("${app.quality-eval.backoff-seconds:10}") long backoffSeconds) {
        this.taskStore = taskStore;
        this.service = service;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
    }

    @Scheduled(fixedDelayString = "${app.quality-eval.poll-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed = taskStore.claim(QualityEvalService.TASK_TYPE, owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            AsyncTaskStore.AsyncTask task = claimed.get();
            try {
                service.execute(task);
                taskStore.succeed(task.id(), owner);
            } catch (Exception ex) {
                log.warn("QUALITY 评测任务 {} ({}) 失败: {}", task.id(), task.payloadRef(), ex.getMessage());
                taskStore.fail(task.id(), owner, "QUALITY_EVAL_FAILED", backoff);
            }
        }
    }
}
