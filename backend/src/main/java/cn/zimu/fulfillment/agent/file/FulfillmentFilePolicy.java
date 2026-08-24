package cn.zimu.fulfillment.agent.file;

import cn.zimu.fulfillment.batch.ImportBatchProgress;
import java.util.ArrayList;
import java.util.List;

/**
 * 履约单据 Agent 的确定性收口策略。
 *
 * <p>模型的输出经过这里**被事实覆盖**，而不是被信任：
 * <ul>
 *   <li>{@code batch_no} 与 {@code current_stage} 一律以 SQL 投影为准——模型抄错业务号，
 *       运营就会去后台搜一个不存在的单子；</li>
 *   <li>存在阻塞却报 {@code requires_human=false} 时强制翻成 true——模型倾向于把
 *       「看起来能自动继续」说成不需要人，而阻塞的定义就是需要人；</li>
 *   <li>建议为空但链路未走完时补一条转人工建议，不让界面出现「没卡住也没建议」的空白。</li>
 * </ul>
 */
public final class FulfillmentFilePolicy {

    private FulfillmentFilePolicy() {}

    public static FulfillmentFileAssessment enforce(
            FulfillmentFileAssessment raw, ImportBatchProgress progress) {
        List<FulfillmentFileAssessment.SuggestedAction> actions =
                raw == null || raw.suggestedActions() == null
                        ? List.of()
                        : List.copyOf(raw.suggestedActions());
        boolean blocked = totalBlocked(progress) > 0;
        boolean requiresHuman =
                blocked || (raw != null && Boolean.TRUE.equals(raw.requiresHuman())) || !progress.complete();

        if (actions.isEmpty() && requiresHuman) {
            // 需要人却给不出建议时，至少把人引到能看到事实的地方
            actions = List.of(new FulfillmentFileAssessment.SuggestedAction(
                    "去发货台查看该批次",
                    blocked ? "链路上存在阻塞事实，但本次未能给出可执行建议" : "链路尚未走完",
                    progress.batchNo()));
        }

        List<String> missing = raw == null || raw.missingFields() == null
                ? List.of()
                : List.copyOf(raw.missingFields());

        return new FulfillmentFileAssessment(
                // 业务号与当前段以事实为准，不采信模型转述
                progress.batchNo(),
                progress.currentStage(),
                raw == null || raw.summary() == null || raw.summary().isBlank()
                        ? fallbackSummary(progress)
                        : raw.summary(),
                raw == null || raw.stageNotes() == null ? List.of() : List.copyOf(raw.stageNotes()),
                actions,
                requiresHuman,
                missing);
    }

    private static int totalBlocked(ImportBatchProgress progress) {
        int blocked = 0;
        for (ImportBatchProgress.Stage stage :
                List.of(progress.intake(), progress.outbound(), progress.tracking(), progress.sourceReturn())) {
            blocked += stage.blocked();
        }
        return blocked;
    }

    /** 模型没给摘要时的确定性兜底：宁可平铺事实，也不留空白。 */
    private static String fallbackSummary(ImportBatchProgress progress) {
        List<String> parts = new ArrayList<>();
        parts.add("批次 " + progress.batchNo());
        parts.add(progress.complete() ? "四段链路已全部走完" : "当前卡在「" + progress.currentStage() + "」段");
        int blocked = totalBlocked(progress);
        if (blocked > 0) {
            parts.add("共 " + blocked + " 项待人工处理");
        }
        return String.join("，", parts) + "。";
    }
}
