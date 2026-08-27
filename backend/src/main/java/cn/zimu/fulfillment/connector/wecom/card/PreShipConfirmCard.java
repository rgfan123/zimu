package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * 发货前确认卡：拉单之后、建单之前，把「即将发给京东的样子」推到人眼前等确认。
 *
 * <p>与已有四张卡的根本区别：那四张都是**事后**卡（事情已经发生了，来看一眼），
 * 这张是**事前**卡——点下去才会有外部副作用（京东建单）。所以它必须满足两件事：
 *
 * <ul>
 *   <li><b>信息足以判断真假</b>：光有订单号没用，人要看到收货人、地址、以及
 *       「渠道说要发什么」与「我们打算发给京东什么」两层商品，才能发现映射错了；</li>
 *   <li><b>只进单聊</b>：手机号与详细地址是 PII，群里不能出现。这条由
 *       {@code PreShipConfirmCardSource#route} 在路由层挡死，而不是靠渲染时记得脱敏——
 *       {@code render} 拿不到 Route，指望它自觉是靠不住的。</li>
 * </ul>
 *
 * <p><b>版面预算</b>：{@code horizontal_content_list} 只有 6 行、每行 value 26 字，
 * 而生产真实地址 44~46 字。地址唯一放得下的位置是 {@code sub_title_text}（112 字），
 * 所以地址**不占字段行**，字段行全部让给「谁、什么货」。
 */
public final class PreShipConfirmCard {

    public static final String DOMAIN = "preship";

    /** 回调按钮 key：零参数 + 幂等，版本断言由 task_id 承担。 */
    public static final String CONFIRM_BUTTON_KEY = "preship_confirm";

    public static final String REJECT_BUTTON_KEY = "preship_reject";

    /**
     * 渠道代码 → 人话。卡面上写 {@code FEIXIANG} 等于让读卡的人做一次翻译，
     * 而这张卡的全部价值就在于「一眼看出不对」。未收录的代码原样显示，不猜。
     */
    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "CAISHIXIAN", "彩食鲜",
            "JUFUBAO", "聚福宝",
            "FEIXIANG", "飞象",
            "ZHONGHUI", "中汇",
            "DAZHE", "大者",
            "WANQI", "万齐",
            "WECOM", "企微");

    private PreShipConfirmCard() {}

    /**
     * @param orderId       订单主键
     * @param version       {@code orders.lock_version}，进 task_id 当版本断言
     * @param sourceChannel 渠道代码（原始值，渲染时翻译）
     * @param sourceRef     渠道单号——**用 source_ref 不用 order_no**：
     *                      order_no 是 36 字 UUID，必被截断，而且渠道那边根本不认它
     * @param receiverName  收货人
     * @param receiverPhone 收货电话（PII，仅单聊）
     * @param receiverAddress 收货地址（PII，仅单聊）
     * @param lineCount     订单行数
     * @param totalQuantity 合计件数
     * @param channelGoods  渠道商品名（渠道说要发什么）
     * @param jdGoods       京东商品名 ×数量（我们打算发什么）
     * @param jdGoodsCode   京东商品编码；单行订单时上卡，多行时让位给明细汇总
     * @param detailUrl     后台深链（整卡点击）
     */
    public record View(
            long orderId,
            long version,
            String sourceChannel,
            String sourceRef,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            int lineCount,
            String totalQuantity,
            String channelGoods,
            String jdGoods,
            String jdGoodsCode,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.orderId(), view.version()))
                .title("发货前确认 · " + channelLabel(view.sourceChannel()))
                .desc(view.sourceRef())
                // 地址放这里而不是字段行：字段行 26 字装不下 44 字的真实地址，
                // 而地址截断正是这张卡最不能出的错——截掉的恰恰是门牌号。
                .subTitle(view.receiverAddress())
                .field("收货人", view.receiverName())
                .field("电话", view.receiverPhone())
                .field("渠道品", view.channelGoods())
                .field("京东品", view.jdGoods());

        if (view.lineCount() <= 1) {
            // 单行订单：编码上卡。映射错了的时候，名字可能看着像，编码不会像。
            builder.field("京东码", view.jdGoodsCode());
        } else {
            // 多行订单（礼包最多 12 个组件）：6 行字段装不下明细，
            // 卡面只给可核对的汇总数，逐项明细由随卡的文本消息承载。
            builder.field("明细", view.lineCount() + " 项 共 " + view.totalQuantity() + " 件");
        }

        builder.callbackButton("确认发货", CONFIRM_BUTTON_KEY, ButtonStyle.PRIMARY)
                .callbackButton("驳回", REJECT_BUTTON_KEY, ButtonStyle.DANGER);

        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }

    static String channelLabel(String sourceChannel) {
        if (sourceChannel == null || sourceChannel.isBlank()) {
            return "未标注";
        }
        return CHANNEL_LABELS.getOrDefault(sourceChannel, sourceChannel);
    }
}
