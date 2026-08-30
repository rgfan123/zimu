package cn.zimu.fulfillment.connector.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「现在这一分钟，哪些渠道的哪一档该拉了？」——纯函数，不碰数据库。
 *
 * <p><b>为什么改成每分钟一跳再问一遍</b>：两个固定 cron 只能表达两个固定时刻，各平台各自设
 * 时间之后就表达不了了。每分钟跳一次、跳的时候逐渠道问一遍，是唯一能支持任意时间点又不需要
 * 动态注册/注销调度任务的做法（动态注册要处理配置改动与调度器状态同步，出错方式更隐蔽）。
 *
 * <h2>补偿窗口</h2>
 *
 * <p>每分钟一跳有个显然的坑：应用重启、GC 停顿、上一次拉取占住执行器超过一分钟——只要
 * 配置时刻那一分钟没跳成，那个渠道当天这一档就永远没了，而且没有任何报错。
 *
 * <p>所以判据不是「now 的时分正好等于配置时刻」，而是「now 落在 [配置时刻, 配置时刻+补偿窗口)
 * 里」。窗口内每一跳都会再试一次，重复由 {@code scheduled_pull_runs.run_key} 的唯一约束挡住
 * ——同一渠道同一档当天只可能真正跑一次。
 *
 * <p><b>补偿窗口不跨天</b>：判据里的「配置时刻」永远取 {@code now} 当天的那个时刻。否则
 * 23:50 配的那一档在次日 00:10 仍在窗口内，但 {@code run_date} 已经翻篇，run_key 变成新的一把，
 * 唯一约束拦不住——那就成了每天多跑一次。宁可丢掉跨零点的补偿，也不要一个自己会加倍的定时器。
 */
final class ScheduledPullPlanner {

    private ScheduledPullPlanner() {}

    /**
     * 一次该跑的拉取。
     *
     * @param sourceChannel 渠道
     * @param slot          档位
     * @param notifyWecom   拉完是否允许发企微播报卡，按此刻配置固化进运行记录
     */
    record Due(String sourceChannel, ScheduledPullRunStore.Slot slot, boolean notifyWecom) {}

    /**
     * @param now       当前本地时间（必须是 Asia/Shanghai，与 run_date 同一时区，否则跨零点会错位）
     * @param schedules 渠道 → 时间表；调用方保证每个参与定时的渠道都有值（读不到也给默认）
     * @param catchUp   补偿窗口；<= 0 视为「只在正点那一分钟跑」
     */
    static List<Due> due(
            LocalDateTime now, Map<String, ChannelPullSchedule> schedules, Duration catchUp) {
        List<Due> out = new ArrayList<>();
        schedules.forEach((channel, schedule) -> {
            if (isDue(now, schedule.morning(), catchUp)) {
                out.add(new Due(channel, ScheduledPullRunStore.Slot.MORNING, schedule.notifyWecom()));
            }
            if (isDue(now, schedule.evening(), catchUp)) {
                out.add(new Due(channel, ScheduledPullRunStore.Slot.EVENING, schedule.notifyWecom()));
            }
        });
        // 排序只为让日志与测试稳定；执行顺序本身无所谓，各渠道互不依赖。
        out.sort((left, right) -> {
            int byChannel = left.sourceChannel().compareTo(right.sourceChannel());
            return byChannel != 0 ? byChannel : left.slot().compareTo(right.slot());
        });
        return List.copyOf(out);
    }

    private static boolean isDue(LocalDateTime now, ChannelPullSchedule.Slot slot, Duration catchUp) {
        if (!slot.enabled()) {
            // 停用必须是显式的 false（见 ChannelPullSchedule 的空值纪律）：能走到这里说明
            // 有人在界面上真的把这一档关了，那就是关了。
            return false;
        }
        LocalTime at = slot.at();
        LocalDateTime scheduled = now.toLocalDate().atTime(at);
        if (now.isBefore(scheduled)) {
            return false;
        }
        // 至少覆盖配置时刻所在的那一整分钟；补偿窗口再往后延。
        Duration window = catchUp == null || catchUp.isNegative() || catchUp.isZero()
                ? Duration.ofMinutes(1)
                : catchUp;
        return now.isBefore(scheduled.plus(window));
    }
}
