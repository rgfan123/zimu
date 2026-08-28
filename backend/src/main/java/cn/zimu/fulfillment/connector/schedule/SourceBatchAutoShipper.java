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
     * 确认当天所有完全就绪的来源批次。
     *
     * @param runDate 运行日期，同时是幂等键的日期部分
     */
    Outcome shipReadyBatches(LocalDate runDate);

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
        return runDate -> Outcome.none();
    }
}
