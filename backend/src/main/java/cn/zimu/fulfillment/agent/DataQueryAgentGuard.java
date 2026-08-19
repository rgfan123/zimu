package cn.zimu.fulfillment.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 数据查询 Agent（06 票）的确定性策略门：PII 拒绝与歧义澄清。
 *
 * <p>两层兜底，使「歧义不猜参数 / PII 转人工」不依赖模型自觉：
 * <ol>
 *   <li>问题级（模型调用前）：PII 关键词命中 → 转人工（判定口径委托平台默认守卫链
 *       {@link AgentGuard}，单一实现）；占位/歧义标记（{@code SKU-xxx}、工单号
 *       {@code P-123}、{@code 某履约方}）命中 → 澄清；</li>
 *   <li>工具参数级（模型仍猜占位参数时）：{@link #toolArgumentProblem} 命中即拒绝该次
 *       工具调用并回传 {@code CLARIFICATION_REQUIRED}，模型据此转入澄清路径。</li>
 * </ol>
 *
 * <p>标记判定是领域启发式：覆盖本系统 SKU 编号（{@code SKU-<CODE>-000000}）与采购工单
 * （数字 ticket_id）的真实格式，避免把真实标识误判为占位。歧义澄清是领域行为
 * （05 决策不进平台默认链），保留为本校验器实现。
 */
public final class DataQueryAgentGuard {

    /** 独立成词的 x 占位序列（SKU-xxx / xxx）；后随完整编号段（-000000）时不视为占位。 */
    private static final Pattern SKU_PLACEHOLDER = Pattern.compile("(?i)(?<![a-z0-9])x{2,}(?!-?[0-9]{6})");

    /** 采购工单号样式（P-123）：工具只接受数字 ticket_id，工单号无法解析。 */
    private static final Pattern TICKET_NO_PATTERN = Pattern.compile("(?i)\\bP-[0-9]+\\b");

    /** 未指明的实体（某履约方/某客户/某某/某个等）。 */
    private static final Pattern AMBIGUOUS_ENTITY =
            Pattern.compile("某履约方|某供应商|某客户|某某|某个|某些|随便|大概");

    /** 工具参数级占位/歧义标记（模型仍猜参数时的兜底）。 */
    private static final Pattern ARGUMENT_PLACEHOLDER =
            Pattern.compile("(?i)xxx|某某|\\b某\\b|占位|placeholder|待定");

    private DataQueryAgentGuard() {}

    /** PII 命中返回命中原因（逐条，口径委托平台默认守卫链 {@link AgentGuard}）；未命中返回空列表。 */
    public static List<String> piiProblems(String question) {
        return AgentGuard.piiProblems(question);
    }

    /** 歧义/占位命中返回需要澄清的原因（逐条）；未命中返回空列表。 */
    public static List<String> ambiguityProblems(String question) {
        List<String> hits = new ArrayList<>();
        if (question == null || question.isBlank()) {
            return hits;
        }
        if (SKU_PLACEHOLDER.matcher(question).find()) {
            hits.add("SKU 编号为占位符（如 SKU-xxx），请提供具体 SKU 编号（如 SKU-EVAL-000001）");
        }
        if (TICKET_NO_PATTERN.matcher(question).find()) {
            hits.add("采购工单号（如 P-123）无法解析为工具所需的数字工单 ID，请提供具体 ticket_id");
        }
        if (AMBIGUOUS_ENTITY.matcher(question).find()) {
            hits.add("未指明具体履约方/实体，请提供履约方 ID 或名称");
        }
        return hits;
    }

    /**
     * 工具参数级兜底：任一参数值为占位/歧义标记时返回拒绝原因；否则返回 null。
     *
     * <p>命中后该次工具调用不执行，返回 {@code CLARIFICATION_REQUIRED}，模型须转入澄清路径。
     */
    public static String toolArgumentProblem(Map<String, Object> arguments) {
        if (arguments == null) {
            return null;
        }
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String text && ARGUMENT_PLACEHOLDER.matcher(text).find()) {
                return "参数 " + entry.getKey() + " 为占位/歧义值（" + text
                        + "），禁止猜测参数，请向用户要求具体值";
            }
        }
        return null;
    }
}
