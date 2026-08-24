package cn.zimu.fulfillment.agent.dto;

/**
 * Agent 列表行的运行状态投影（12 票消费方要求 1；agent-console 设计 §4）。
 *
 * <p>{@code status}（版本生命周期）与 {@code enabled}（运维启停）正交，本枚举是两者
 * 组合后的「列表显示」语义：active+enabled=运行中；active+disabled=已停用；
 * 无 active 版本（只有草稿或全部 retired）=无生效版本。
 */
public enum AgentListState {
    RUNNING,
    DISABLED,
    NO_ACTIVE_VERSION
}
