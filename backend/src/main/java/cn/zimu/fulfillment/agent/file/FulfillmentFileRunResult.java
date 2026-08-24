package cn.zimu.fulfillment.agent.file;

import cn.zimu.fulfillment.batch.ImportBatchProgress;

/**
 * 履约单据 Agent 一次运行的结果。
 *
 * <p>{@code progress} 与 {@code assessment} 并列返回是刻意的：前者是确定性事实，
 * 后者是模型解读。界面必须能分开呈现——把两者揉成一段文字，读者就无从判断
 * 哪些数字可以拿去做决定。
 *
 * @param progress   四段链路的确定性事实（永远存在，即使模型调用失败）
 * @param assessment 模型解读与建议；失败时为 null
 * @param error      稳定失败码；成功时为 null
 */
public record FulfillmentFileRunResult(
        ImportBatchProgress progress,
        FulfillmentFileAssessment assessment,
        String provider,
        String model,
        String promptVersion,
        String error) {}
