package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 整批确认完成播报卡（wecom-card-review §F 第三行）。
 *
 * <p>**没有回调按钮**是设计而不是遗漏：动作已经完成了，这张卡是事后播报。
 * 放个回调按钮只会诱导人再点一次——而整批确认是有外部副作用的（京东建单），
 * 「再点一次」正是最不该发生的事。要看详情走整卡跳转。
 *
 * <p>用 {@code text_notice} 而非 {@code button_interaction}：卡型本身就在告诉读者
 * 「这里没有你要做的事」。
 */
public final class BatchConfirmedCard {

    public static final String DOMAIN = "batch";

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
        return WecomCardBuilder
                .textNotice(WecomTaskId.ofVersion(DOMAIN, view.batchId(), view.version()))
                .title("整批确认已完成")
                .desc(view.batchNo())
                .subTitle("确认人 " + view.confirmedBy() + " · 来源 " + view.sourceChannel())
                .field("订单", view.orderCount() + " 单 / " + view.lineCount() + " 行")
                // 两条出库通道分别报数：合并成一个数字就看不出哪条通道没走通
                .field("京东", view.jdShipmentCount() + " 单")
                .field("导出", view.thirdPartyExportCount() + " 单")
                .cardAction(view.detailUrl())
                .build();
    }
}
