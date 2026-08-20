package cn.zimu.fulfillment.agent.dto;

import java.time.OffsetDateTime;

/**
 * 运行关联的评测结果摘要（12 票；09 票 QUALITY 链路回写 {@code app.agent_eval_results}）。
 *
 * <p>按 run_id 关联（run_mode=PREVIEW 的 QUALITY 评测行）：整体状态
 * （RUNNING/SUCCEEDED/FAILED）与用例通过数。details 逐条得分不在此投影
 * （避免暴露用例细节之外的模型输出摘要）。
 */
public record RunEvalResultItem(
        String status,
        int caseCount,
        int passedCount,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {}
