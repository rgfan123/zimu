package cn.zimu.fulfillment.agent;

/**
 * 一次 Agent 运行的结果。
 *
 * <p>与 {@code message/InterpretationResult} 同构：provider/model/promptVersion 随结果保存；
 * error 非空表示运行失败（此时 output 为 null），error 只取 {@link AgentFailureCode} 稳定枚举，
 * 模型原始错误文本与 api-key 绝不进入本记录。未配置时一律投影为 none/none/none。
 */
public record AgentRunResult(
        AgentStructuredOutput output,
        String provider,
        String model,
        String promptVersion,
        String error) {

    /** 未配置模型（或运行时整体不可用）时的公共 sentinel 结果：三元组一律 none。 */
    public static AgentRunResult failClosed(AgentFailureCode code) {
        return new AgentRunResult(null, "none", "none", "none", code.name());
    }
}
