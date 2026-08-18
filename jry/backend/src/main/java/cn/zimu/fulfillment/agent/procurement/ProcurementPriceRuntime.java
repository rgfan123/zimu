package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentTaskRequest;

/**
 * 采购比价 Agent 的模型接缝（agent-decision-layer 05）：按采购比价专属 schema
 * （{@link ProcurementPriceRecommendation}）结构化输出的一次运行。
 *
 * <p>唯一实现 {@link ProcurementPriceAgentRuntime}；接口化便于服务编排层 mock 测试
 * （与 01 票 {@code AgentRuntime} 接缝同构）。未配置模型时实现 fail-closed，
 * 不连接任何模型。
 */
public interface ProcurementPriceRuntime {

    ProcurementPriceRunResult run(AgentTaskRequest request);
}
