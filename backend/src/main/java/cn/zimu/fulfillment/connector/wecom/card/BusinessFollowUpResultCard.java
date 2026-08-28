package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Business Follow-up 审批终态播报卡；不提供任何可产生第二次决定的按钮。
 *
 * <p><b>为什么是 {@code button_interaction}</b>：{@code text_notice} 强制要求安全的
 * {@code card_action} 深链，而本部署的公网入口只有明文 HTTP，https-only 的深链规则
 * （正确，不放宽）永远给不出合法基址，这张卡因此渲染必然失败、一张也发不出去。
 * 交互卡的 {@code card_action} 官方标注可选，从根上不再依赖深链。
 *
 * <p>唯一的按钮是零参数、零业务写的「知道了」——终态已经落定，它只表达「我看到了」，
 * 不会重开任何决定。没人点时卡面信息依然完整。
 */
public final class BusinessFollowUpResultCard {

    public static final String DOMAIN = "followup-result";

    public static final String ACKNOWLEDGE_BUTTON_KEY = "acknowledge_followup_result";

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
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.approvalId(), view.draftVersion()))
                .title("客户跟进审批" + label)
                .desc(view.followupNo())
                .subTitle("操作人 " + (group
                        ? maskName(view.decidedBy())
                        : view.decidedBy()))
                .field("决定", label)
                .field("版本", "v" + view.draftVersion())
                .field("错误", failed ? view.applicationFailureCode() : null)
                .field("时间", view.decidedAt())
                .callbackButton("知道了", ACKNOWLEDGE_BUTTON_KEY, ButtonStyle.PRIMARY);
        // 深链是可选装饰而非前提：没配 base-url 时整卡照发，只是少一个跳转
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
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
