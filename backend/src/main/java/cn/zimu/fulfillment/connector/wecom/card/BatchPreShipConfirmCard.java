package cn.zimu.fulfillment.connector.wecom.card;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * 整批发货前确认卡（一批一卡）。
 *
 * <p><b>为什么按批不按单</b>：一次导入十几单时逐单弹卡等于让人在手机上连点十几次
 * 「确认发货」，且批次确认本来就是批级动作（SourceImportService.confirm 收整批）。
 * 卡面只放批级汇总——明细在随卡的清单文件里（小批用图片、大批用 Excel，
 * 都从订单行快照渲染），确认的就是清单上那份事实。
 *
 * <p>版本 = 批内订单 lock_version 之和：任何一单被改动，和就变，旧卡点下去
 * 落 VERSION_CONFLICT——与单卡用 orders.lock_version 是同一套断言，只是聚合了。
 */
public final class BatchPreShipConfirmCard {

    public static final String DOMAIN = "preship-batch";

    public static final String CONFIRM_BUTTON_KEY = "preship_batch_confirm";

    public static final String REJECT_BUTTON_KEY = "preship_batch_reject";

    /** 渠道代码 → 人话；与单卡同表，未收录原样显示。 */
    private static final Map<String, String> CHANNEL_LABELS = Map.of(
            "CAISHIXIAN", "彩食鲜",
            "JUFUBAO", "聚福宝",
            "FEIXIANG", "飞象",
            "ZHONGHUI", "中汇",
            "DAZHE", "大者",
            "WANQI", "万齐",
            "WECOM", "企微");

    private BatchPreShipConfirmCard() {}

    /**
     * @param batchId        导入批次主键
     * @param version        批内订单 lock_version 之和（版本断言）
     * @param sourceChannel  渠道代码
     * @param orderCount     批内待确认订单数
     * @param totalQuantity  合计件数（已去零小数）
     * @param receiverBrief  收件人预览（前几位 + 等 N 人；电话与地址不上卡，只进单聊清单）
     * @param detailUrl      后台深链
     */
    public record View(
            long batchId,
            long version,
            String sourceChannel,
            int orderCount,
            String totalQuantity,
            String receiverBrief,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.batchId(), view.version()))
                .title("发货前确认 · " + channelLabel(view.sourceChannel()) + "整批")
                .desc(view.orderCount() + " 单 · 共 " + view.totalQuantity() + " 件")
                // 明细不上卡：字段行 26 字装不下任何一单的完整信息，硬塞只会截断出错误
                .subTitle("逐单明细见随卡清单，核对后一键整批确认")
                .field("渠道", channelLabel(view.sourceChannel()))
                .field("订单数", String.valueOf(view.orderCount()))
                .field("总件数", view.totalQuantity())
                .field("收件人", view.receiverBrief());

        builder.callbackButton("确认整批发货", CONFIRM_BUTTON_KEY, WecomCardBuilder.ButtonStyle.PRIMARY)
                .callbackButton("驳回", REJECT_BUTTON_KEY, WecomCardBuilder.ButtonStyle.DANGER);

        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }

    public static String channelLabel(String code) {
        if (code == null || code.isBlank()) {
            return "-";
        }
        return CHANNEL_LABELS.getOrDefault(code, code);
    }
}
