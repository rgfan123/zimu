package cn.zimu.fulfillment.agent;

/**
 * Agent 运行时对外可见的稳定失败码。
 *
 * <p>模型或 SDK 的原始错误文本属于敏感运行证据，不得进入 {@link AgentRunResult} 之外的
 * 可观测面（公共 API / 日志 / 审计）。失败结果只保留下列有限枚举。
 *
 * <p>02 票追加 {@link #AGENT_NOT_FOUND} / {@link #AGENT_DISABLED}（注册表拒绝路径）；
 * 拒绝路径与失败路径一样返回 {@link AgentRunResult#failClosed(AgentFailureCode)}
 * 的 none/none/none 三元组并留 AGENT 审计。
 */
public enum AgentFailureCode {
    AGENT_MODEL_NOT_CONFIGURED,
    AGENT_MODEL_CALL_FAILED,
    AGENT_OUTPUT_INVALID,
    /** 注册表中不存在该 agent_slug。 */
    AGENT_NOT_FOUND,
    /** Agent 已注册但未启用（enabled=false），运行时拒绝执行。 */
    AGENT_DISABLED,
    /** 运行期守卫拒绝（05 决策默认链 [PII 拒绝]）：输入涉 PII，outcome=REJECTED 转人工。 */
    PII_GUARDED;
}
