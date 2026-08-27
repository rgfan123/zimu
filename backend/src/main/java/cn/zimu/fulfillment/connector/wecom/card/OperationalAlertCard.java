package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 运营告警卡（wecom-card-review §F 第二行）。
 *
 * <p>「知道了」是这套卡里最干净的回调按钮：{@code OperationalAlertService.acknowledge}
 * 已有 IdempotentResult + lock_version，零参数、幂等、并发安全，与协议要求天然吻合。
 *
 * <p>严重度以文案而非颜色承载。评审文档提到 {@code desc_color}，但该字段在
 * button_interaction 上的可用性未经实测；写错的字段会被企微整条丢弃，而文案不会。
 * 等实测确认后再补颜色，先保证信息一定能到人眼前。
 */
public final class OperationalAlertCard {

    public static final String DOMAIN = "alert";
    public static final String ACKNOWLEDGE_BUTTON_KEY = "acknowledge_alert";

    private OperationalAlertCard() {}

    /** 严重度：与 operational_alerts.severity 的 RED/YELLOW 对齐。 */
    public enum Severity {
        RED("🔴 紧急"),
        YELLOW("🟡 注意");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param alertId      operational_alerts.id
     * @param lockVersion  乐观锁版本，进 task_id 作版本断言
     * @param alertNo      稳定业务号
     * @param severity     严重度
     * @param message      告警正文（进 sub_title，超长自动截断）
     * @param relatedNo    关联 shipment / order 业务号；无则 null
     * @param businessCode detail.business_code；无则 null
     * @param detailUrl    后台深链
     */
    public record View(
            long alertId,
            long lockVersion,
            String alertNo,
            Severity severity,
            String message,
            String relatedNo,
            String businessCode,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.alertId(), view.lockVersion()))
                .title("运营告警")
                .desc(view.alertNo())
                .subTitle(view.message())
                .field("级别", view.severity().label())
                .field("关联", view.relatedNo())
                .field("代码", view.businessCode())
                .callbackButton("知道了", ACKNOWLEDGE_BUTTON_KEY, ButtonStyle.PRIMARY);
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }
}
