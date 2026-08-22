package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.message.AsyncTaskStore;
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
 * 履约导出企微发送后台 Worker：租约式领取 {@code WECOM_EXPORT_DELIVERY} 任务（INITIAL 与
 * REMINDER 共用），重启后从持久化 delivery 证据恢复；领取失败（数据库不可达）时退避抑制，
 * 不空转刷屏。
 *
 * <p>租约时长：默认 40 分钟（{@code app.wecom-export-worker.lease-seconds}）——必须覆盖
 * #82 上传器的完整最坏恢复预算 = pre-init 5 次 resume（每次最多 15s 等 ACK + 60s 退避）+
 * 15s init ACK = 5×(15+60)+15 = 390s + 30 分钟会话 + 15s = 1815s + 最终 send ACK 5s =
 * 2210s（36m50s），2400s 留约 190s 余量作为保守下限。租约必须严格高于该上界，保证真实执行
 * 仍活跃时任务绝不被第二实例重新领取误判为 crash（SENDING 只在租约过期后的重新领取时转
 * UNKNOWN 人工对账）。
 */
@Component
public class FulfillmentExportWecomWorker {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentExportWecomWorker.class);

    /** 默认/下限租约（秒）= 40 分钟：5×(15+60)+15 + 1800+15 + 5 = 2210s，2400s 留约 190s，见类注释。 */
    static final long DEFAULT_LEASE_SECONDS = 2400;

    private final AsyncTaskStore taskStore;
    private final FulfillmentExportWecomDeliveryRunner runner;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "wecom-export-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    @Autowired
    public FulfillmentExportWecomWorker(
            AsyncTaskStore taskStore,
            FulfillmentExportWecomDeliveryRunner runner,
            @Value("${app.wecom-export-worker.enabled:true}") boolean enabled,
            @Value("${app.wecom-export-worker.lease-seconds:2400}") long leaseSeconds,
            @Value("${app.wecom-export-worker.backoff-seconds:30}") long backoffSeconds,
            @Value("${app.wecom-export-worker.claim-error-suppress-seconds:60}") long claimErrorSuppressSeconds) {
        this.taskStore = taskStore;
        this.runner = runner;
        this.enabled = enabled;
        // 租约下限 = 默认值（40 分钟）：pre-init 390s + session 1815s + send ACK 5s = 2210s，
        // 真实执行必须绝不被重新领取，显式配置更小值也不得突破该安全下限（仅可调大）。
        this.lease = Duration.ofSeconds(Math.max(DEFAULT_LEASE_SECONDS, leaseSeconds));
        this.backoff = Duration.ofSeconds(Math.max(1, backoffSeconds));
        this.claimErrorSuppressWindow = Duration.ofSeconds(claimErrorSuppressSeconds);
    }

    /** 租约时长（测试观察 seam：断言配置低于下限时被钳制到 40 分钟）。 */
    Duration lease() {
        return lease;
    }

    @Scheduled(fixedDelayString = "${app.wecom-export-worker.poll-ms:1000}")
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
                claimed = taskStore.claim(FulfillmentExportWecomService.TASK_TYPE, owner, lease);
            } catch (RuntimeException ex) {
                boolean firstFailure = claimSuppressUntil == null;
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                log.warn(
                        "履约导出企微 Worker 领取任务失败，{} 秒内暂停轮询（{}）",
                        claimErrorSuppressWindow.toSeconds(),
                        firstFailure ? "首次" : "持续");
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
            runner.execute(task);
        } catch (Exception ex) {
            // 执行器内部未处理异常（代码缺陷）：按异步任务退避重试/终态，交付证据保持可恢复
            log.warn("履约导出企微任务 {} 执行异常: {}", task.id(), ex.getMessage());
            taskStore.fail(task.id(), owner, "WECOM_EXPORT_DELIVERY_FAILED", backoff);
        }
    }
}
