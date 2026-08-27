package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 绑定 Business Follow-up 草稿版本的 +1 审批卡。 */
public final class BusinessFollowUpDraftCard {

    public static final String DOMAIN = "followup-draft";
    public static final String CONFIRM_BUTTON_KEY = "confirm_followup";

    private BusinessFollowUpDraftCard() {}

    /**
     * 卡片只接受服务端持久化的标识与版本，不接受 Agent 产生的 summary/facts。
     * Agent 文本可能把 PII 放在任意 label 下，因此不靠关键词脱敏来猜测。
     */
    public record View(
            long followupId,
            long draftVersion,
            String followupNo,
            boolean confirmable,
            String customer,
            String summary,
            String questions,
            String risks,
            String recommendedActions,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String itemSummary,
            String settlement,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        return renderSafe(view);
    }

    /** 群聊只投影脱敏的服务端摘要，不暴露客户名或问题细节。 */
    public static ObjectNode renderGroup(View view) {
        return renderSafe(new View(
                view.followupId(),
                view.draftVersion(),
                view.followupNo(),
                view.confirmable(),
                "已脱敏",
                "已生成待 +1 核对的安全投影",
                null,
                view.risks(),
                view.recommendedActions(),
                maskName(view.receiverName()),
                maskPhone(view.receiverPhone()),
                maskAddress(view.receiverAddress()),
                view.itemSummary(),
                view.settlement(),
                view.detailUrl()));
    }

    private static ObjectNode renderSafe(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.followupId(), view.draftVersion()))
                .title("客户跟进草稿待 +1")
                .desc(view.followupNo() + " · v" + view.draftVersion())
                .subTitle(summaryBlock(view))
                .field("客户", view.customer())
                .field("收货人", view.receiverName())
                .field("电话", view.receiverPhone())
                .field("地址", view.receiverAddress())
                .field("商品", view.itemSummary())
                .field("结算", view.settlement());
        if (view.confirmable()) {
            builder.callbackButton("确认", CONFIRM_BUTTON_KEY, ButtonStyle.PRIMARY);
        }
        return builder.cardAction(view.detailUrl()).build();
    }

    private static String summaryBlock(View view) {
        String prefix = view.confirmable() ? "可确认" : "不可确认，需要补充";
        return prefix + "｜摘要 " + safe(view.summary())
                + "｜待确认 " + safe(view.questions())
                + "｜风险 " + safe(view.risks())
                + "｜下一步 " + safe(view.recommendedActions());
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    private static String maskName(String value) {
        if (value == null || value.isBlank()) return "未投影";
        String trimmed = value.strip();
        int first = trimmed.offsetByCodePoints(0, 1);
        return trimmed.substring(0, first) + "*";
    }

    private static String maskPhone(String value) {
        if (value == null || value.isBlank()) return "未投影";
        String digits = value.replaceAll("\\D", "");
        return digits.length() >= 7
                ? digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4)
                : "***";
    }

    private static String maskAddress(String value) {
        if (value == null || value.isBlank()) return "未投影";
        String trimmed = value.strip();
        int points = trimmed.codePointCount(0, trimmed.length());
        int visible = Math.min(points, 9);
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, visible)) + "…";
    }
}
