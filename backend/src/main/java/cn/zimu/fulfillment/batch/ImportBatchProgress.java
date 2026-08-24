package cn.zimu.fulfillment.batch;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一个导入批次在「收表 → 发货 → 回填 → 回传」四段链路上的确定性进度。
 *
 * <p>这份投影是 Agent 的**唯一事实来源**：Agent 读它、解释它、给建议，但四段的
 * 判定全部在这里由 SQL 算出，Agent 不参与。数字由谁算，决定了出错时该找谁——
 * 让模型去数「发了几单」，错了没人能复现。
 *
 * <p>每段都带 {@code blocked} 计数与阻塞原因，未接入的段位如实标注而不是补零：
 * 「0 单待发」与「这段还没接进来」在界面上必须能区分。
 */
public record ImportBatchProgress(
        long batchId,
        String batchNo,
        String batchType,
        String sourceChannel,
        String status,
        int revisionNo,
        OffsetDateTime receivedAt,
        OffsetDateTime processedAt,
        Stage intake,
        Stage outbound,
        Stage tracking,
        Stage sourceReturn,
        List<Blocker> blockers) {

    /**
     * 一段链路的进度。
     *
     * @param name      段名
     * @param total     该段应处理的总数
     * @param done      已完成数
     * @param blocked   卡住的数量（失败 / 需人工）
     * @param supported 该段是否已接入。**false 时 total/done/blocked 无意义**，
     *                  界面须写「暂无汇总」而不是显示 0——0 会被读成「没有待办」
     */
    public record Stage(String name, int total, int done, int blocked, boolean supported) {

        public static Stage unsupported(String name) {
            return new Stage(name, 0, 0, 0, false);
        }

        /** 该段是否已经走完（未接入的段不算走完）。 */
        public boolean complete() {
            return supported && total > 0 && done == total && blocked == 0;
        }
    }

    /**
     * 一条阻塞事实。
     *
     * @param stage      卡在哪一段
     * @param code       稳定码（review reason_code / JD 失败码 / 回传失败码）
     * @param count      条数
     * @param sampleNo   一个可去后台搜的业务号；无则 null
     */
    public record Blocker(String stage, String code, int count, String sampleNo) {}

    /** 整条链路是否已经全部走完。 */
    public boolean complete() {
        return intake.complete() && outbound.complete() && tracking.complete() && sourceReturn.complete();
    }

    /** 当前卡在哪一段（最早未完成的段）；全部完成返回 null。 */
    public String currentStage() {
        for (Stage stage : List.of(intake, outbound, tracking, sourceReturn)) {
            if (!stage.complete()) {
                return stage.name();
            }
        }
        return null;
    }
}
