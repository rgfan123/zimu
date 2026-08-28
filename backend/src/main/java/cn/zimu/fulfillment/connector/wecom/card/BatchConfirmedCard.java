package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 整批确认完成播报卡（wecom-card-review §F 第三行）。
 *
 * <p><b>为什么从 {@code text_notice} 改成 {@code button_interaction}</b>：
 * {@code text_notice} 协议强制要求一个安全的 {@code card_action} 深链，而本部署的公网入口
 * 只有明文 HTTP、不支持 TLS，{@code CardDeepLinks} 的 https-only 规则（正确，不放宽）
 * 因此永远给不出合法基址。结果是这张卡在生产里渲染必然失败、一张也发不出去。
 * {@code button_interaction} 的 {@code card_action} 官方标注可选，从根上不再需要深链。
 *
 * <p><b>按钮是「知道了」而不是任何会重做的动作</b>：整批确认有外部副作用（京东建单），
 * 「再点一次」正是最不该发生的事。这个按钮零参数、零业务写、天然幂等，只表达
 * 「我看到了」，并借仓库既有的整卡替换机制把自己换成 style=4 的灰态回执。
 *
 * <p>没人点按钮时卡面信息依然完整——它首先是一张播报卡，ack 是可选的。
 */
public final class BatchConfirmedCard {

    public static final String DOMAIN = "batch";

    public static final String ACKNOWLEDGE_BUTTON_KEY = "acknowledge_batch_confirmed";

    private BatchConfirmedCard() {}

    /**
     * @param batchId          批次主键
     * @param version          批次版本，进 task_id
     * @param batchNo          稳定业务号
     * @param sourceChannel    来源渠道
     * @param orderCount       订单数
     * @param lineCount        行数
     * @param jdShipmentCount  京东 SDK 建单数
     * @param thirdPartyExportCount 第三方导出数
     * @param confirmedBy      确认人（人工主体，必须可追溯）
     * @param detailUrl        后台深链
     */
    public record View(
            long batchId,
            long version,
            String batchNo,
            String sourceChannel,
            int orderCount,
            int lineCount,
            int jdShipmentCount,
            int thirdPartyExportCount,
            String confirmedBy,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.batchId(), view.version()))
                .title("整批确认已完成")
                .desc(view.batchNo())
                .subTitle("确认人 " + view.confirmedBy() + " · 来源 " + view.sourceChannel())
                .field("订单", view.orderCount() + " 单 / " + view.lineCount() + " 行")
                // 两条出库通道分别报数：合并成一个数字就看不出哪条通道没走通
                .field("京东", view.jdShipmentCount() + " 单")
                .field("导出", view.thirdPartyExportCount() + " 单")
                .callbackButton("知道了", ACKNOWLEDGE_BUTTON_KEY, ButtonStyle.PRIMARY);
        // 深链是可选装饰而非前提：没配 base-url 时整卡照发，只是少一个跳转
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }
}
