package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 复核事项卡（wecom-card-review §F 第一行）：有事项需要人认领时推给对应团队。
 *
 * <p>**不放「确认」按钮**是刻意的：复核的处置动作（resolveCustomer / resolveSku）都需要
 * 选客户、选 SKU 这类参数，零参数按钮承载不了。卡上只放「我来处理」（认领到点击人，
 * 零参数且幂等）与「去后台处理」（深链）。让人点了「确认」才发现要填参数，是最差的设计。
 *
 * <p>task_id 域 {@code review}，版本对齐 {@code review_cases.resolution_version}：
 * 点旧卡自动 VERSION_CONFLICT，不需要另造防重放。
 */
public final class ReviewCaseCard {

    public static final String DOMAIN = "review";
    public static final String CLAIM_BUTTON_KEY = "claim_review_case";

    private ReviewCaseCard() {}

    /**
     * @param caseId          review_cases.id
     * @param resolutionVersion 当前处置版本（乐观锁），进 task_id 作版本断言
     * @param caseNo          稳定业务号——群里的人要拿它去后台搜、也要拿它在群里回话
     * @param caseTypeLabel   事项类型的人话文案（不是枚举名）
     * @param reasonLabel     原因码的人话文案（不是 reason_code）
     * @param responsibleTeam 责任团队
     * @param relatedNo       关联订单号 / 批次号；无关联传 null
     * @param createdAtLabel  创建时间文案
     * @param detailUrl       后台深链
     */
    public record View(
            long caseId,
            long resolutionVersion,
            String caseNo,
            String caseTypeLabel,
            String reasonLabel,
            String responsibleTeam,
            String relatedNo,
            String createdAtLabel,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.caseId(), view.resolutionVersion()))
                .title("复核事项待处理")
                .desc(view.caseNo())
                .subTitle(view.reasonLabel())
                .field("类型", view.caseTypeLabel())
                .field("责任", view.responsibleTeam())
                .field("关联", view.relatedNo())
                .field("创建", view.createdAtLabel())
                .callbackButton("我来处理", CLAIM_BUTTON_KEY, ButtonStyle.PRIMARY);
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }
}
