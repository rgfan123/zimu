package cn.zimu.fulfillment.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台默认 Agent 守卫链（05 决策）：运行期守卫是行为约束——模型调用前对输入判定，
 * 与权限（07 票，工具调用时强制）互不替代。
 *
 * <p>默认链 = [PII 拒绝]：输入含客户/收货人/手机号/地址等 PII 模式 → 门面以
 * outcome=REJECTED（{@code PII_GUARDED}）转人工、不进模型；{@code guard_exemptions}
 * 声明后跳过（默认空 = 守卫生效）。歧义澄清是领域行为（{@link DataQueryAgentGuard}
 * 保留为该校验器实现），不在平台默认链内。
 *
 * <p>范围：守卫只对门面驱动（{@code AgentRuntimeFacade.invoke}）的运行生效；D 路径
 * （意图识别/消息解释，{@code IntentRecognitionAgentBridge}）输入即业务消息内容本身
 * （PII 是业务载荷而非查询），不在默认守卫范围内。
 *
 * <p>判定是关键词子串匹配的确定性启发式（05 认可既有口径，保守且可能有近邻词误报，
 * 如「IP 地址」命中「地址」）：单一实现（{@link DataQueryAgentGuard#piiProblems}
 * 委托本类），领域守卫先行短路与平台默认链兜底共用同一口径；误报面偏大时以
 * {@code guard_exemptions} 豁免并由领域守卫承接。
 */
public final class AgentGuard {

    /** PII 关键词：命中即转人工（平台默认链 [PII 拒绝]）。 */
    private static final List<String> PII_KEYWORDS = List.of(
            "客户", "收货人", "收件人", "手机", "电话", "姓名", "地址", "身份证");

    private AgentGuard() {}

    /** PII 命中返回命中原因（逐条）；未命中返回空列表。null/空白输入视为无命中。 */
    public static List<String> piiProblems(String input) {
        List<String> hits = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return hits;
        }
        for (String keyword : PII_KEYWORDS) {
            if (input.contains(keyword)) {
                hits.add("输入涉及客户/收货人 PII（" + keyword + "），Agent 无 PII 工具，须转人工");
            }
        }
        return hits;
    }

    /**
     * 豁免判定：{@code definition.guard_exemptions} 含该守卫枚举名即豁免（默认空 = 生效）。
     * definition 为 null（未注册）按不豁免处理（守卫按 fail-closed 语义执行）。
     */
    public static boolean exempt(AgentDefinition definition, AgentGuardExemption exemption) {
        return definition != null && definition.guardExemptions().contains(exemption.name());
    }
}
