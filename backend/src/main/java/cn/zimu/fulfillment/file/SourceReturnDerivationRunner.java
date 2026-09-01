package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 独立于 Tracking 事实事务的来源回填派生执行器。 */
@Service
public class SourceReturnDerivationRunner {

    static final String FAILURE_CODE = "SOURCE_RETURN_DERIVATION_FAILED";
    private static final Logger log = LoggerFactory.getLogger(SourceReturnDerivationRunner.class);

    private final AsyncTaskStore tasks;
    private final SourceReturnDerivationQueue queue;
    private final TrackingFileService trackingFiles;
    private final Duration lease;
    private final Duration backoff;
    private final int maxTasksPerDrain;
    private final String owner = "source-return-worker-" + UUID.randomUUID();

    public SourceReturnDerivationRunner(
            AsyncTaskStore tasks,
            SourceReturnDerivationQueue queue,
            TrackingFileService trackingFiles,
            @Value("${app.source-return-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.source-return-worker.backoff-seconds:30}") long backoffSeconds,
            @Value("${app.source-return-worker.max-tasks-per-drain:100}") int maxTasksPerDrain) {
        this.tasks = tasks;
        this.queue = queue;
        this.trackingFiles = trackingFiles;
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.backoff = Duration.ofSeconds(Math.max(1, backoffSeconds));
        this.maxTasksPerDrain = Math.max(1, maxTasksPerDrain);
    }

    /** 同步快路与定时 Worker 共用；单任务失败不阻止后续任务。 */
    public void drainDue() {
        requireOutsideTrackingTransaction();
        for (int processed = 0; processed < maxTasksPerDrain; processed++) {
            Optional<AsyncTaskStore.AsyncTask> claimed = tasks.claim(
                    SourceReturnDerivationQueue.TASK_TYPE, owner, lease);
            if (claimed.isEmpty()) return;
            process(claimed.get());
        }
    }

    /**
     * HTTP 提交后的 best-effort 快路：只尝试本次事务返回的任务 ID，绝不消费无关积压；
     * 领取基础设施失败只记诊断，持久 Worker 稍后继续，不能把已提交的 Tracking 响应改成 500。
     */
    public void runDue(Collection<Long> taskIds) {
        requireOutsideTrackingTransaction();
        if (taskIds == null || taskIds.isEmpty()) return;
        int processed = 0;
        for (Long taskId : new LinkedHashSet<>(taskIds)) {
            if (taskId == null) continue;
            if (processed++ >= maxTasksPerDrain) break;
            try {
                tasks.claimById(taskId, SourceReturnDerivationQueue.TASK_TYPE, owner, lease)
                        .ifPresent(this::process);
            } catch (RuntimeException exception) {
                log.warn(
                        "Source return derivation fast path for task {} deferred ({})",
                        taskId,
                        exception.getClass().getSimpleName());
                log.debug("Source return derivation fast path failed", exception);
            }
        }
    }

    private static void requireOutsideTrackingTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("source return derivation must run after tracking commit");
        }
    }

    void process(AsyncTaskStore.AsyncTask task) {
        try {
            SourceReturnDerivationQueue.Payload payload = queue.decode(task.payloadRef());
            trackingFiles.finalizeReadySourceReturnsForShipment(
                    payload.shipmentId(), payload.trackingBatchId(), payload.operator());
            tasks.succeed(task.id(), owner);
        } catch (RuntimeException exception) {
            log.warn(
                    "Source return derivation task {} failed ({})",
                    task.id(),
                    exception.getClass().getSimpleName());
            log.debug("Source return derivation task failed", exception);
            tasks.fail(task.id(), owner, FAILURE_CODE, backoff);
        }
    }
}
