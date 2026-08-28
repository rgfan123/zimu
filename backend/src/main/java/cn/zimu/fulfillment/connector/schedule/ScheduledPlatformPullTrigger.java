package cn.zimu.fulfillment.connector.schedule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天早上 9 点与下午 6 点触发三平台拉取（Asia/Shanghai）。
 *
 * <p><b>触发与执行分离</b>：{@code @Scheduled} 方法只把任务丢进自己的单线程执行器就返回。
 * 直接在调度线程里跑是不行的——单渠道脚本超时上限就有 10 分钟，三渠道串行最坏 30 分钟，
 * 而全应用共用一个 13 线程的 {@code taskScheduler}（{@code ScheduledTaskPoolConfiguration}），
 * 上面已经挂了 20 多条 {@code @Scheduled} 流。占住一根线程半小时会让不相干的 worker 集体饿死。
 *
 * <p>单线程执行器同时给出第二个性质：09:00 那次还没跑完时，18:00 那次排队而不是并行。
 * 加上 {@code scheduled_pull_runs.run_key} 的唯一约束（跨实例）与
 * {@code PlatformPullSingleFlight} 的会话锁（跨实例、按渠道），
 * 定时与人工点击三层都不会打架。
 *
 * <p>两级开关：{@code app.scheduling.enabled}（全局，测试整体关）与
 * {@code app.scheduled-pull.enabled}（本特性）。自动发货另有独立开关，
 * 见 {@code app.scheduled-pull.auto-ship.enabled}——关掉它只停发货，拉取照常。
 */
@Component
@ConditionalOnProperty(name = "app.scheduled-pull.enabled", havingValue = "true", matchIfMissing = true)
class ScheduledPlatformPullTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlatformPullTrigger.class);

    private final ScheduledPlatformPullService service;
    private final ExecutorService worker;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    ScheduledPlatformPullTrigger(ScheduledPlatformPullService service) {
        this.service = service;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zimu-scheduled-pull");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 早上 9 点：时间表进配置，不写死。 */
    @Scheduled(
            cron = "${app.scheduled-pull.morning-cron:0 0 9 * * *}",
            zone = "${app.scheduled-pull.zone:Asia/Shanghai}")
    void morning() {
        submit(ScheduledPullRunStore.Slot.MORNING);
    }

    /** 下午 6 点。 */
    @Scheduled(
            cron = "${app.scheduled-pull.evening-cron:0 0 18 * * *}",
            zone = "${app.scheduled-pull.zone:Asia/Shanghai}")
    void evening() {
        submit(ScheduledPullRunStore.Slot.EVENING);
    }

    private void submit(ScheduledPullRunStore.Slot slot) {
        if (shuttingDown.get()) {
            return;
        }
        try {
            worker.submit(() -> {
                try {
                    service.runOnce(slot);
                } catch (RuntimeException exception) {
                    // runOnce 自己已经收口；这里只是最后一道保险，绝不让异常吃掉执行器线程。
                    log.error("定时拉取任务异常 slot={}", slot, exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            log.warn("定时拉取执行器已关闭，本次触发丢弃 slot={}", slot);
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        // 不等待：进行中的拉取由运行记录与单飞锁自愈（连接断开时 PostgreSQL 自动释放会话锁），
        // 停机等半小时反而会拖垮滚动发布。
        worker.shutdownNow();
    }
}
