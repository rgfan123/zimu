package cn.zimu.fulfillment.agent;

import java.time.Duration;

/**
 * 单次 Agent 运行的收敛预算。预算限制的是模型/工具动作与总墙钟时间，不把业务正确性
 * 绑定到任意固定轮数；调用方可为交互与异步任务选择不同预算。
 */
public record AgentExecutionBudget(
        int maxModelCalls,
        int maxToolCalls,
        Duration maxDuration,
        int maxRepeatedToolCalls) {

    public AgentExecutionBudget {
        if (maxModelCalls < 1) {
            throw new IllegalArgumentException("maxModelCalls 必须大于 0");
        }
        if (maxToolCalls < 0) {
            throw new IllegalArgumentException("maxToolCalls 不能小于 0");
        }
        if (maxDuration == null || maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException("maxDuration 必须大于 0");
        }
        if (maxRepeatedToolCalls < 1) {
            throw new IllegalArgumentException("maxRepeatedToolCalls 必须大于 0");
        }
    }

    boolean deadlineExceeded(long startedNanos) {
        return System.nanoTime() - startedNanos >= maxDuration.toNanos();
    }
}
