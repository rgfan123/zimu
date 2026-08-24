package cn.zimu.fulfillment.agent;

/**
 * 定义域异步任务执行失败（meta-agent-platform-impl 11）：携带稳定错误码（与
 * {@code agent_runs.error_type} 同一枚举空间，供 T12 轮询面直接呈现）。任务 Worker
 * 捕获本异常 → 任务行收口 FAILED + 运行行收口 FAILED（error_type=code），不重试
 * （maxAttempts=1：业务失败重试无意义，重试语义由客户端按幂等契约重发）。
 */
public class AgentDefinitionTaskFailure extends RuntimeException {

    private final String code;

    public AgentDefinitionTaskFailure(String code, String message) {
        super(message);
        this.code = code;
    }

    /** 稳定错误码（agent_runs.error_type 同空间：AGENT_GATE_BLOCKED / AGENT_CONFLICT / …）。 */
    public String code() {
        return code;
    }
}
