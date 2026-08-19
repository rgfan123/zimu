package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 门禁评估结果（05 决策）：{@code blockers} 为阻断项（草稿不可确认，任一非空即失败）；
 * {@code piiWarnings} 为 PII 警告项（示例数据含手机号等可能是合理内容，不阻断，确认
 * 流程高亮）。列表防御性拷贝为不可变。
 */
public record AgentGateReport(List<String> blockers, List<String> piiWarnings) {

    public AgentGateReport {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        piiWarnings = piiWarnings == null ? List.of() : List.copyOf(piiWarnings);
    }

    /** 全部阻断项为空即通过。 */
    public boolean passed() {
        return blockers.isEmpty();
    }

    public static AgentGateReport blocked(List<String> blockers, List<String> piiWarnings) {
        return new AgentGateReport(blockers, piiWarnings);
    }
}
