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
 * 每分钟跳一次，问每个来源渠道「你现在该拉了吗」（Asia/Shanghai）。
 *
 * <p><b>为什么不是两个固定 cron</b>：各平台要能各自设自己的两个拉取时间，而 {@code @Scheduled}
 * 的 cron 在启动时就固定了。每分钟跳一次、跳的时候逐渠道比对配置时刻，是唯一能支持任意时间点
 * 又不必动态注册/注销调度任务的做法。命中判据与补偿窗口见 {@link ScheduledPullPlanner}。
 *
 * <p><b>触发与执行分离</b>：{@code @Scheduled} 方法只把任务丢进自己的单线程执行器就返回。
 * 直接在调度线程里跑是不行的——单渠道脚本超时上限就有 10 分钟，三渠道串行最坏 30 分钟，
 * 而全应用共用一个 13 线程的 {@code taskScheduler}（{@code ScheduledTaskPoolConfiguration}），
 * 上面已经挂了 20 多条 {@code @Scheduled} 流。占住一根线程半小时会让不相干的 worker 集体饿死。
 *
 * <p><b>跳动合并</b>：改成每分钟一跳之后，一次长达十分钟的拉取会在执行器队列里积压十个跳动。
 * 所以只允许队列里存在一个未开始的跳动（{@code tickQueued}），多余的直接丢弃——被丢掉的
 * 跳动不会造成漏拉，因为补偿窗口内后续每一跳都会把错过的那一档重新试一遍。
 *
 * <p>重复触发由三层挡住：{@code scheduled_pull_runs.run_key}（现已含渠道段）的唯一约束
 * （跨实例、跨补偿窗口）、{@code PlatformPullSingleFlight} 的会话锁（跨实例、按渠道）、
 * 以及这里的单线程执行器。定时与人工点击都不会打架。
 *
 * <p>两级开关：{@code app.scheduling.enabled}（全局，测试整体关）与
 * {@code app.scheduled-pull.enabled}（本特性）。自动发货另有独立开关，
 * 见 {@code app.scheduled-pull.auto-ship.enabled}——关掉它只停发货，拉取照常。
 * 单个渠道的单个档位由界面上的开关停用，不需要动这些配置，也不需要重启。
 */
@Component
@ConditionalOnProperty(name = "app.scheduled-pull.enabled", havingValue = "true", matchIfMissing = true)
class ScheduledPlatformPullTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPlatformPullTrigger.class);

    private final ScheduledPlatformPullService service;
    private final ExecutorService worker;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    /** 队列里是否已有一个未开始的跳动。见类注释「跳动合并」。 */
    private final AtomicBoolean tickQueued = new AtomicBoolean();

    ScheduledPlatformPullTrigger(ScheduledPlatformPullService service) {
        this.service = service;
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zimu-scheduled-pull");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 每分钟第 0 秒跳一次。时区必须与 {@code ScheduledPlatformPullService} 的
     * {@code LocalDate.now(SHANGHAI)} 同源，否则跨零点时「今天」的边界会和 run_key 里的日期错位。
     */
    @Scheduled(
            cron = "${app.scheduled-pull.tick-cron:0 * * * * *}",
            zone = "${app.scheduled-pull.zone:Asia/Shanghai}")
    void tick() {
        submit();
    }

    private void submit() {
        if (shuttingDown.get()) {
            return;
        }
        if (!tickQueued.compareAndSet(false, true)) {
            // 上一跳还没轮到执行。丢弃是安全的：补偿窗口内后面每一跳都会重试错过的档位。
            log.debug("上一次定时拉取跳动尚未开始执行，本跳合并丢弃");
            return;
        }
        try {
            worker.submit(() -> {
                tickQueued.set(false);
                try {
                    service.runDue();
                } catch (RuntimeException exception) {
                    // runDue 自己已经收口；这里只是最后一道保险，绝不让异常吃掉执行器线程。
                    log.error("定时拉取跳动异常", exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            tickQueued.set(false);
            log.warn("定时拉取执行器已关闭，本次跳动丢弃");
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        // 不等待：进行中的拉取由运行记录与单飞锁自愈（连接断开时 PostgreSQL 自动释放会话锁），
        // 停机等半小时反而会拖垮滚动发布。重启后错过的档位由补偿窗口补回来。
        worker.shutdownNow();
    }
}
