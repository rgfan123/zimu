package cn.zimu.fulfillment.operator;

/**
 * 责任团队解析状态（Issue #89）。
 *
 * <ul>
 *   <li>{@link #PUSHABLE}：全部 active 成员已绑定企微 userid，可全员推送；</li>
 *   <li>{@link #PARTIALLY_BOUND}：部分成员未绑定，推送前必须显式处理未绑定名单；</li>
 *   <li>{@link #ALL_UNBOUND}：有 active 成员但全部未绑定；</li>
 *   <li>{@link #NO_MEMBERS}：该团队暂无 active 人员。</li>
 * </ul>
 */
public enum OperatorResolutionStatus {
    PUSHABLE,
    PARTIALLY_BOUND,
    ALL_UNBOUND,
    NO_MEMBERS
}
