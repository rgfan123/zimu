package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 业务卡 Worker（#87/#88）：只领 {@code WECOM_BUSINESS_CARD}，与订单草稿卡的 Worker
 * 各领各的，互不抢单。
 *
 * <p>领取失败时进入抑制窗口而不是继续空转——数据库不可用时每秒重试只会把日志刷满，
 * 且抢占本就紧张的连接（这套栈有过连接耗尽的历史）。
 *
 * <p>Runner 抛出未捕获异常时保守收口：若投递行已是 SENDING，外部提交可能已发生，
 * 必须收口为 UNKNOWN 等待对账，不得降级成可重试 FAILED。
 */
@Component
public class WecomBusinessCardWorker {

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardWorker.class);
    private static final String UNHANDLED = "WECOM_BUSINESS_CARD_RUNNER_FAILED";

    private final AsyncTaskStore tasks;
    private final WecomBusinessCardRunner runner;
    private final WecomBusinessCardStore cards;
    private final boolean enabled;
    private final Duration lease;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "wecom-business-card-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    public WecomBusinessCardWorker(
            AsyncTaskStore tasks,
            WecomBusinessCardRunner runner,
            WecomBusinessCardStore cards,
            @Value("${app.wecom-business-card.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-business-card.lease-seconds:60}") long leaseSeconds,
            @Value("${app.wecom-business-card.claim-error-suppress-seconds:60}") long suppressSeconds) {
        this.tasks = tasks;
        this.runner = runner;
        this.cards = cards;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(60, leaseSeconds));
        this.claimErrorSuppressWindow = Duration.ofSeconds(Math.max(1, suppressSeconds));
    }

    @Scheduled(fixedDelayString = "${app.wecom-business-card.poll-ms:1000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant suppressedUntil = claimSuppressUntil;
        if (suppressedUntil != null && Instant.now().isBefore(suppressedUntil)) {
            return;
        }
        claimSuppressUntil = null;
        while (!Thread.currentThread().isInterrupted()) {
            Optional<AsyncTaskStore.AsyncTask> task;
            try {
                task = tasks.claim(WecomBusinessCardEnqueuer.TASK_TYPE, owner, lease);
            } catch (RuntimeException ex) {
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                log.warn(
                        "业务卡 Worker 领取任务失败，{} 秒内暂停轮询",
                        claimErrorSuppressWindow.toSeconds());
                return;
            }
            if (task.isEmpty()) {
                return;
            }
            process(task.get());
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        try {
            runner.execute(task);
        } catch (RuntimeException ex) {
            log.error("业务卡执行异常 task={}", task.id(), ex);
            recover(task);
        }
    }

    /** 兜底收口：SENDING 表示可能已外发，只能单调进 UNKNOWN，禁止盲发。 */
    private void recover(AsyncTaskStore.AsyncTask task) {
        try {
            cards.recordUnknown(WecomBusinessCardRunner.cardId(task), UNHANDLED);
        } catch (RuntimeException ignored) {
            // 投递行收口失败不掩盖任务收口
        }
        try {
            tasks.fail(task.id(), owner, UNHANDLED, Duration.ofSeconds(30));
        } catch (RuntimeException ignored) {
            // 任务收口失败由租约超时兜底
        }
    }
}
