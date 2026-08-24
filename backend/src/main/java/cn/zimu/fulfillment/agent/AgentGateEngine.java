package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * Agent 定义门禁引擎（05 决策；meta-agent-platform-impl 08 票）：草稿不可确认的条件判定。
 *
 * <p>以可复用引擎形式提供（接口抽象，非内嵌）：T10 写工具静态门禁与 T11 确认前全量复跑
 * 共用同一实现（{@link DefaultAgentGateEngine}）；判定口径遵循 05——六项阻断（结构完整性 /
 * 工具白名单合法性 / 只读不变式 / output_schema 可解析 / 凭据扫描 / 越权指令扫描）+
 * PII 警告（不阻断，确认流程高亮）。
 */
public interface AgentGateEngine {

    /** 评估一份 Agent 定义：返回阻断项（草稿不可确认）与 PII 警告项（仅高亮）。 */
    AgentGateReport evaluate(AgentDefinition definition);
}
