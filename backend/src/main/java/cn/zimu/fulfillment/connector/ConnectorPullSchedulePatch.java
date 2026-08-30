package cn.zimu.fulfillment.connector;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 渠道拉取时间表的写入体（契约 snake_case：{@code pull_schedule}）。
 *
 * <p><b>整体替换而不是逐字段 patch</b>：这是一张卡片上一次性提交的五个字段，界面每次都把
 * 五个都发过来。做成部分 patch 的话，「关掉早班」和「没提到早班」在报文里长得一模一样
 * （都可能是 null），而这两者的后果完全相反——本特性的空值纪律是「读不到就按默认拉」，
 * 于是一次漏发的字段会静悄悄地把用户刚关掉的档位重新打开。整体替换让「关」永远是显式的。
 *
 * <p>时间只收 {@code HH:mm}：界面给的是时间选择框，不给用户填 cron（本票已定），
 * 也不需要秒级精度——触发本身就是每分钟一跳。
 */
public record ConnectorPullSchedulePatch(
        @NotNull @Valid Slot morning,
        @NotNull @Valid Slot evening,
        @NotNull Boolean notifyWecom) {

    public record Slot(
            @NotNull Boolean enabled,
            @NotNull @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "时间必须是 HH:mm")
                    String at) {}
}
