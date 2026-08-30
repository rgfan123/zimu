package cn.zimu.fulfillment.connector;

/**
 * 渠道拉取时间表的读投影（契约 snake_case：{@code pull_schedule}）。
 *
 * <p><b>永远有值</b>：没配过的渠道回显的是**实际生效**的全局默认（09:00 / 18:00 全开、推企微），
 * 不是 null 也不是空对象。界面据此直接渲染，运营看到的就是它今天真实会怎么跑——
 * 让「没配置」在界面上表现成一个空白框，正是「以为关了其实在跑 / 以为在跑其实关了」的来源。
 *
 * <p>{@code schedulable} 区分「这个渠道参与定时拉取吗」：中汇、大者、万齐、企微不参与，
 * 界面不给它们出时间卡片，后端也不会因为有人往 config 里塞了 pull_schedule 就开始拉。
 *
 * @param schedulable   本渠道是否参与定时拉取
 * @param configured    是否已显式配置过（false 表示回显的是全局默认）
 * @param morning       早班
 * @param evening       晚班
 * @param notifyWecom   拉取后是否推企微
 */
public record ConnectorPullScheduleView(
        boolean schedulable,
        boolean configured,
        Slot morning,
        Slot evening,
        boolean notifyWecom) {

    /**
     * @param enabled 这一档是否启用
     * @param at      HH:mm（Asia/Shanghai）
     */
    public record Slot(boolean enabled, String at) {}
}
