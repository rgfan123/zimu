package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder.ButtonStyle;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 定时拉取 + 自动发货的运行播报卡。
 *
 * <p>只在「这次运行有需要人处理的事」时发（{@code problem_count > 0}）——
 * 每天两次准点报「一切正常」，两周之后就没人再看这张卡了，而那正是出事的那天。
 *
 * <p><b>为什么这张卡可以进群</b>：卡面只有渠道名、受控词表里的原因码、批次号与计数，
 * 没有收件人姓名、手机号或地址。{@code preship}/{@code preship-batch}/{@code shipped}
 * 三张卡硬过滤 SINGLE 是因为卡面与随卡清单带收件信息；本卡不带，因此按
 * {@code alert}/{@code batch} 的做法走普通路由（默认 GROUP）。
 * 边界由 {@code ScheduledPullReportCardSource} 的投影保证，不是靠约定——
 * 那里逐字段挑选，自由文本（拉取的 {@code message}、blocker 的 {@code message}）
 * 一律不取。
 *
 * <p><b>为什么按钮是「知道了」</b>：这次运行已经结束，货该发的已经发了。
 * 卡上任何「重试」按钮都意味着在一张可能被多人看到的群卡上放一个会花真钱的动作。
 * 补救动作留在后台，那里有人的身份与授权。
 *
 * <p>卡型是 {@code button_interaction} 而非 {@code text_notice}：后者协议强制要求安全的
 * {@code card_action} 深链，而本部署公网入口只有明文 HTTP，深链必然给不出来，
 * 渲染必然失败（见 {@code CardDeepLinks} 的说明）。
 */
public final class ScheduledPullReportCard {

    /** 域名只允许 {@code [a-z][a-z-]*}——下划线是 task_id 的分隔符，冒号会被企微拒收。 */
    public static final String DOMAIN = "scheduled-pull";

    public static final String ACKNOWLEDGE_BUTTON_KEY = "acknowledge_scheduled_pull";

    private ScheduledPullReportCard() {}

    /**
     * @param runId          scheduled_pull_runs.id
     * @param lockVersion    乐观锁版本，进 task_id 作版本断言；收口时 +1，故一次运行只发一张卡
     * @param runKey         {@code yyyy-MM-dd:SLOT:CHANNEL}，稳定业务号；渠道就在里面
     * @param slotLabel      时段中文（早班 / 晚班 / 手动触发）。不写具体时刻：V85 起每个渠道
     *                       各自设时间，写死「早上 09:00」对配了别的时间的渠道就是假话
     * @param pullChannels   本次拉取的渠道数
     * @param pullFailed     拉取失败的渠道数
     * @param shippedBatches 自动确认发货的批次数
     * @param blockedBatches 有阻断行、未自动确认的批次数
     * @param reasonSummary  归类后的原因摘要（受控词表拼成，无自由文本）
     * @param jdSummary      京东侧问题的短摘要，进字段行；无则空串
     * @param detailUrl      后台深链；未配 base-url 时为 null
     */
    public record View(
            long runId,
            long lockVersion,
            String runKey,
            String slotLabel,
            int pullChannels,
            int pullFailed,
            int shippedBatches,
            int blockedBatches,
            String reasonSummary,
            String jdSummary,
            String detailUrl) {}

    public static ObjectNode render(View view) {
        WecomCardBuilder builder = WecomCardBuilder
                .buttonInteraction(WecomTaskId.ofVersion(DOMAIN, view.runId(), view.lockVersion()))
                .title("定时拉取发货待处理")
                .desc(view.runKey())
                // 归类摘要放 sub_title（112 字上限）而不是字段行（26 字）：
                // 「缺货」和「映射没配」必须一眼分得开，塞进 26 字必然被截成看不懂的半句。
                .subTitle(view.reasonSummary())
                .field("时段", view.slotLabel())
                .field("拉取", pullText(view))
                .field("已发", view.shippedBatches() + " 批")
                .field("待办", view.blockedBatches() == 0 ? null : view.blockedBatches() + " 批有阻断行")
                .field("京东", blankToNull(view.jdSummary()))
                .callbackButton("知道了", ACKNOWLEDGE_BUTTON_KEY, ButtonStyle.PRIMARY);
        // 深链是可选装饰：没配 base-url 时整卡照发，只是少一个跳转。
        if (view.detailUrl() != null && !view.detailUrl().isBlank()) {
            builder.cardAction(view.detailUrl());
        }
        return builder.build();
    }

    /** 拉取一行：成功数与失败数都写出来。只报失败数会让「三个渠道全没跑」看起来像「零失败」。 */
    private static String pullText(View view) {
        if (view.pullChannels() == 0) {
            return "未执行";
        }
        return view.pullFailed() == 0
                ? view.pullChannels() + " 渠道全部成功"
                : "失败 " + view.pullFailed() + " / 共 " + view.pullChannels();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
