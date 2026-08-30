package cn.zimu.fulfillment.connector.schedule;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 自动发货端口：拉取完成后，把「完全就绪」的来源批次确认发货。
 *
 * <p>做成端口而不是直接调用，是为了让**关掉自动发货**成为一件结构上的事情而不是一个
 * if：{@code app.scheduled-pull.auto-ship.enabled=false} 时实现 bean 根本不存在，
 * 编排拿到 {@link #disabled()}，不可能有任何代码路径把货发出去。
 */
interface SourceBatchAutoShipper {

    /**
     * 确认当天该渠道下所有完全就绪的来源批次。
     *
     * <p><b>为什么按渠道收窄</b>（V85）：运行记录已经下沉到渠道，同一时段会有三次运行。
     * 不收窄的话，一批「有阻断行、等人处理」的批次会在同一个早上被三次运行各报一遍，
     * 企微群里就是三张一模一样的卡——而这类卡最怕的就是变成噪声。
     *
     * <p><b>已知取舍</b>：不在定时拉取名单里的渠道（中汇、大者、万齐等文件导入来源）
     * 因此不再被自动发货扫到。这是本改动缩小的行为面，写在这里以便日后有人要找回它。
     * 现状下代价为零：{@code app.scheduled-pull.auto-ship.enabled} 生产默认 false，
     * 自动发货本来就没在跑；而本票明确不做自动发货口径。
     *
     * @param runDate       运行日期，同时是幂等键的日期部分
     * @param sourceChannel 本次运行负责的来源渠道
     */
    Outcome shipReadyBatches(LocalDate runDate, String sourceChannel);

    /**
     * 一次自动发货的结果。
     *
     * @param entries        每批次一条摘要（无 PII）
     * @param problemCount   需要人处理的条数
     * @param shippedBatches 真正确认发货的批次数
     */
    record Outcome(List<Map<String, Object>> entries, int problemCount, int shippedBatches) {

        static Outcome none() {
            return new Outcome(List.of(), 0, 0);
        }
    }

    /** 未启用自动发货时的实现：只拉取，绝不确认任何批次。 */
    static SourceBatchAutoShipper disabled() {
        return (runDate, sourceChannel) -> Outcome.none();
    }
}
