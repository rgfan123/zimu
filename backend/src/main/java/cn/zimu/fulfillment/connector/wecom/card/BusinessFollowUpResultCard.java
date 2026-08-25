package cn.zimu.fulfillment.connector.wecom.card;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** Business Follow-up 审批终态播报卡；不再提供可产生第二次决定的按钮。 */
public final class BusinessFollowUpResultCard {

    public static final String DOMAIN = "followup-result";

    private static final Map<String, String> DECISION_LABELS = Map.of(
            "CONFIRM", "已确认",
            "REDO", "已要求 Agent 重做",
            "NEEDS_INPUT", "已转待补充",
            "PAUSE", "已暂停");

    private BusinessFollowUpResultCard() {}

    public record View(
            long approvalId,
            long followupId,
            long draftVersion,
            String followupNo,
            String decision,
            String applicationStatus,
            String applicationFailureCode,
            String decidedBy,
            String decidedAt,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        return render(view, false);
    }

    public static ObjectNode renderGroup(View view) {
        return render(view, true);
    }

    private static ObjectNode render(View view, boolean group) {
        boolean failed = "FAILED".equals(view.applicationStatus());
        boolean superseded = "SUPERSEDED".equals(view.applicationStatus());
        String label = failed
                ? "处理失败"
                : superseded ? "版本已被取代" : DECISION_LABELS.getOrDefault(view.decision(), "已处理");
        return WecomCardBuilder
                .textNotice(WecomTaskId.ofVersion(DOMAIN, view.approvalId(), view.draftVersion()))
                .title("客户跟进审批" + label)
                .desc(view.followupNo())
                .subTitle("操作人 " + (group
                        ? maskName(view.decidedBy())
                        : view.decidedBy()))
                .field("决定", label)
                .field("版本", "v" + view.draftVersion())
                .field("错误", failed ? view.applicationFailureCode() : null)
                .field("时间", view.decidedAt())
                .cardAction(view.detailUrl())
                .build();
    }

    private static String maskName(String value) {
        if (value == null || value.isBlank()) {
            return "未标注";
        }
        String trimmed = value.strip();
        int firstEnd = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, firstEnd) + "*";
    }
}
