package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentFailureCode;

/**
 * 一次采购比价 Agent 运行的结果（agent-decision-layer 05）。
 *
 * <p>与 {@code AgentRunResult} 同构：provider/model/promptVersion 随结果保存；error 非空表示
 * 运行失败（此时 recommendation 为 null），error 只取 {@link AgentFailureCode} 稳定枚举，
 * 模型原始错误文本与 api-key 绝不进入本记录。
 */
public record ProcurementPriceRunResult(
        ProcurementPriceRecommendation recommendation,
        String provider,
        String model,
        String promptVersion,
        String error) {

    /** 未配置模型（或运行时整体不可用）时的公共 sentinel 结果：三元组一律 none。 */
    public static ProcurementPriceRunResult failClosed(AgentFailureCode code) {
        return new ProcurementPriceRunResult(null, "none", "none", "none", code.name());
    }
}
