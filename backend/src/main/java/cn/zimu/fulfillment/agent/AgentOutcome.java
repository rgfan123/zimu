package cn.zimu.fulfillment.agent;

/**
 * 一次 Agent 运行的结果维度（meta-agent-platform 04 决策）：区分「成功/需要输入/被拒绝/失败」。
 *
 * <p>NEEDS_INPUT（澄清）与 REJECTED（守卫/权限/参数拒绝）不是失败——不再污染
 * {@code agent_runs.status='FAILED'} 与成功率指标；{@code AgentFailureCode} 仅在
 * REJECTED / FAILED 时有值（{@link AgentRunResult} 构造器强制该不变量）。
 */
public enum AgentOutcome {
    SUCCESS,
    NEEDS_INPUT,
    REJECTED,
    FAILED;
}
