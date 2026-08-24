package cn.zimu.fulfillment.agent.dto;

/**
 * 一个分组的消耗汇总（129 票）。token 与耗时并列——两者都是「跑飞」的信号，
 * 只看 token 会漏掉卡在工具上空转的运行。
 *
 * @param groupKey              分组键（agent_slug / 业务日 / 业务实体类型）
 * @param runs                  运行数
 * @param failedRuns            其中失败数
 * @param runsWithoutTokenUsage **诚实字段**：其中没有任何计量的运行数。求和只覆盖
 *                              有计量的那部分，此值 &gt; 0 时汇总数字是下界而非全量，
 *                              界面必须显式说明，不能让读者以为求和就是全部消耗
 * @param overThresholdRuns     其中单次超阈值的运行数（阈值默认关闭时恒为 0）
 * @param promptTokens          输入 token 累计
 * @param completionTokens      输出 token 累计
 * @param totalTokens           总 token 累计
 * @param maxRunTotalTokens     单次运行的 token 峰值；无计量时为 null
 * @param modelCalls            模型调用轮数累计（含工具轮）；用于算「每轮均耗」
 * @param totalLatencyMs        耗时累计（毫秒）
 * @param maxRunLatencyMs       单次运行的耗时峰值；无收口运行时为 null
 */
public record TokenUsageSummaryItem(
        String groupKey,
        long runs,
        long failedRuns,
        long runsWithoutTokenUsage,
        long overThresholdRuns,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        Long maxRunTotalTokens,
        long modelCalls,
        long totalLatencyMs,
        Long maxRunLatencyMs) {

    /** 已计量运行数（求和的实际分母）。 */
    public long measuredRuns() {
        return Math.max(runs - runsWithoutTokenUsage, 0);
    }
}
