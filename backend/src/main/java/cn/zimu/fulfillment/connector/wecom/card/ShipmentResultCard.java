package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 发货结果卡（{@code shipped} 域）：闭环的最后一句话。
 *
 * <p><b>为什么从 {@code text_notice} 改成 {@code button_interaction}</b>：与整批确认播报卡同因——
 * {@code text_notice} 强制要求安全的 {@code card_action} 深链，而本部署的公网入口只有明文 HTTP，
 * https-only 的深链规则（正确，不放宽）永远给不出合法基址，这张卡因此渲染必然失败。
 * 交互卡的 {@code card_action} 官方标注可选，从根上不再依赖深链。
 *
 * <p>唯一的按钮是零参数、零业务写的「知道了」：读到这张卡时事情已经发生了，卡上不该有
 * 任何「你还要做什么」的暗示，ack 只表达「我看到了」。没人点时卡面信息依然完整。
 *
 * <p><b>运单号可能还没有</b>，这是常态而不是异常——京东建单成功与分配运单号之间隔着仓库作业，
 * 回填靠轮询。因此卡上显式区分「已建单待分配运单」与「运单已回填」两种事实，
 * 而不是把空运单号渲染成空白让人以为丢了。
 */
public final class ShipmentResultCard {

    public static final String DOMAIN = "shipped";

    public static final String ACKNOWLEDGE_BUTTON_KEY = "acknowledge_shipment_result";

    private ShipmentResultCard() {}

    /**
     * @param orderId        订单主键
     * @param version        订单版本，进 task_id
     * @param sourceChannel  渠道代码
     * @param sourceRef      渠道单号
     * @param receiverName   收货人
     * @param outboundNo     京东出库单号
     * @param trackingNo     运单号；为空表示尚未回填
     * @param carrier        承运商；为空则不上卡
     * @param detailUrl      后台深链
     */
    public record View(
            long orderId,
            long version,
            String sourceChannel,
            String sourceRef,
            String receiverName,
            String outboundNo,
            String trackingNo,
            String carrier,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        boolean tracked = view.trackingNo() != null && !view.trackingNo().isBlank();
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.orderId(), view.version()))
                .title(tracked ? "已发货 · 运单已回填" : "已建单 · 等待分配运单")
                .desc(PreShipConfirmCard.channelLabel(view.sourceChannel()))
                .subTitle(tracked
                        ? "运单号已回填，可回传给渠道"
                        : "京东已收单，运单号由仓库分配后自动回填，无需再点")
                .field("收货人", view.receiverName())
                .field("渠道单", view.sourceRef())
                .field("出库单", view.outboundNo());
        if (tracked) {
            builder.field("运单号", view.trackingNo());
            builder.field("承运", view.carrier());
        }
        builder.callbackButton("知道了", ACKNOWLEDGE_BUTTON_KEY, ButtonStyle.PRIMARY);
        // 深链是可选装饰而非前提：没配 base-url 时整卡照发，只是少一个跳转
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }
}
