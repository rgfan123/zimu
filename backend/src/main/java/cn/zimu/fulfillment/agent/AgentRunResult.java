package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一次 Agent 运行的结果（04 决策重构：outcome 维度 + 传输层统一 JsonNode；T05 门面富化
 * runId/latency）。
 *
 * <p>与 {@code message/InterpretationResult} 同构：provider/model/promptVersion 随结果保存；
 * {@code output} 为传输层统一 {@link JsonNode} 容器（业务侧按需反序列化为强类型 record）；
 * {@code error} 只取 {@link AgentFailureCode} 稳定枚举，且仅在 outcome ∈ {REJECTED, FAILED}
 * 时非空（构造器强制），模型原始错误文本与 api-key 绝不进入本记录。
 *
 * <p>{@code runId}/{@code latencyMs} 由 Control Plane（Facade）在运行收口时经
 * {@link #withRunMetadata} 富化——一次运行的标识与耗时属控制面事实，领域包装据此回填
 * 业务 run-result 的观测字段。
 */
public record AgentRunResult(
        JsonNode output,
        String provider,
        String model,
        String promptVersion,
        String error,
        AgentOutcome outcome,
        String runId,
        long latencyMs) {

    public AgentRunResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为 null");
        }
        boolean failed = outcome == AgentOutcome.REJECTED || outcome == AgentOutcome.FAILED;
        if (failed != (error != null)) {
            throw new IllegalArgumentException(
                    "REJECTED/FAILED 必须携带 error（AgentFailureCode），其余 outcome 必须为 null");
        }
        runId = runId == null ? "" : runId;
    }

    /** 成功（NEEDS_INPUT 之外的正常收口）。 */
    public static AgentRunResult success(JsonNode output, String provider, String model, String promptVersion) {
        return new AgentRunResult(output, provider, model, promptVersion, null, AgentOutcome.SUCCESS, "", 0);
    }

    /** 澄清/需要输入：不是失败（不污染 FAILED 统计）。 */
    public static AgentRunResult needsInput(JsonNode output, String provider, String model, String promptVersion) {
        return new AgentRunResult(output, provider, model, promptVersion, null, AgentOutcome.NEEDS_INPUT, "", 0);
    }

    /** 守卫/权限/参数拒绝：携带稳定失败码，但不是系统失败。 */
    public static AgentRunResult rejected(String provider, String model, String promptVersion, AgentFailureCode code) {
        return new AgentRunResult(null, provider, model, promptVersion, code.name(), AgentOutcome.REJECTED, "", 0);
    }

    /** 失败：携带稳定失败码（模型调用/输出无效等）。 */
    public static AgentRunResult failure(String provider, String model, String promptVersion, AgentFailureCode code) {
        return new AgentRunResult(null, provider, model, promptVersion, code.name(), AgentOutcome.FAILED, "", 0);
    }

    /** 未配置模型（或运行时整体不可用）时的公共 sentinel 结果：三元组一律 none。 */
    public static AgentRunResult failClosed(AgentFailureCode code) {
        return new AgentRunResult(null, "none", "none", "none", code.name(), AgentOutcome.FAILED, "", 0);
    }

    /** 控制面收口富化：回填 run_id 与实测耗时（结果字段不可变，复制新实例）。 */
    public AgentRunResult withRunMetadata(String runId, long latencyMs) {
        return new AgentRunResult(
                output, provider, model, promptVersion, error, outcome, runId == null ? "" : runId, latencyMs);
    }
}
