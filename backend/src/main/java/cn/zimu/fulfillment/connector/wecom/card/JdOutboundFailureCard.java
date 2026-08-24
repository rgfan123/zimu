package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 京东出库失败卡（wecom-card-review §F 第四行）。
 *
 * <p>本卡唯一的关键设计：**把 {@code retryable} 直接映射成「重试」按钮的有无**。
 * 代码里已经算好了这个判定（{@code !"RECONCILIATION_REQUIRED".equals(businessCode)}），
 * 卡面就该照着它决定按钮，而不是一律放个重试按钮、等人点了才报「此单不可重试」。
 * 不可重试时只给「去对账」——那才是此时唯一有意义的下一步。
 */
public final class JdOutboundFailureCard {

    public static final String DOMAIN = "jd-outbound";
    public static final String RETRY_BUTTON_KEY = "retry_jd_outbound";

    /** 需人工对账的稳定码：命中即不可重试（与 ShipmentJdOutboundService 的判定同源）。 */
    public static final String RECONCILIATION_REQUIRED = "RECONCILIATION_REQUIRED";

    private JdOutboundFailureCard() {}

    /**
     * @param shipmentId     发货单主键
     * @param version        发货单版本，进 task_id
     * @param erpDeliveryNo  稳定业务号
     * @param failurePhase   失败阶段
     * @param businessCode   稳定失败码
     * @param retryCount     已重试次数
     * @param reconUrl       对账台深链（不可重试时唯一去处，必填）
     */
    public record View(
            long shipmentId,
            long version,
            String erpDeliveryNo,
            String failurePhase,
            String businessCode,
            int retryCount,
            String reconUrl) {}

    /** 与服务端同源的可重试判定，卡面按钮由它决定。 */
    public static boolean retryable(String businessCode) {
        return !RECONCILIATION_REQUIRED.equals(businessCode);
    }

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.shipmentId(), view.version()))
                .title("京东出库失败")
                .desc(view.erpDeliveryNo())
                .subTitle(view.failurePhase() + " · " + view.businessCode())
                .field("阶段", view.failurePhase())
                .field("代码", view.businessCode())
                .field("重试", view.retryCount() + " 次");
        // 不可重试时不给重试按钮：让人点了才报错，是把系统已知的事实藏起来
        if (retryable(view.businessCode())) {
            builder.callbackButton("重试建单", RETRY_BUTTON_KEY, ButtonStyle.PRIMARY);
        }
        if (view.reconUrl() != null && !view.reconUrl().isBlank()) {
            builder.jumpButton("去对账", view.reconUrl(), ButtonStyle.SECONDARY)
                    .cardAction(view.reconUrl());
        }
        return builder.build();
    }
}
