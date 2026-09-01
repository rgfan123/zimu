package cn.zimu.fulfillment.mcp;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人类确认闸（2026-09-01 需求：防 Agent 误发）。
 *
 * <p>受闸的是**货物真实移动**那一步：京东出库、原料入账/出账审批。这些动作一旦触发就在
 * 物理世界留下痕迹（货出仓、账入库），不可逆，也不是「系统内单据」——所以闸装在这里，
 * 而不是装在草稿成单、手工建单、履约路由这类只动系统内记录的中间步骤上。
 *
 * <p>闸的语义只有一句：**必须是用户本人在对话里输入的「确认」二字**。工具 schema 把
 * {@link #PARAMETER} 声明为必填并写死中文指令（{@link #DESCRIPTION}），执行层再用
 * {@link #requireConfirmed(Map)} 复核字面值——精确等值比较，只容忍首尾空白，
 * {@code 确认。}/{@code 确认了}/{@code ok}/{@code yes} 一律拒。校验只有这一份，
 * 三个受闸工具跨两个 provider 共用，不各写各的。
 *
 * <p>为什么校验器**返回剥离后的入参**而不是只返回 boolean：用户输入不该顺着载荷流下去。
 * 同一个动作用户确认两次，不该因为一次输入了「确认」、一次输入了「 确认 」就变成两个不同的
 * 幂等请求；下游命令与幂等注册表拿到的载荷必须与没有这道闸时**逐字节相同**。调用方一律用
 * 返回值继续解析参数，闸就成了结构性的，而不是靠每个工具记得别把这个键传下去。
 *
 * <p>审计只留 {@link #AUDIT_FIELD}{@code =true} 这一事实，不落用户输入明文。
 */
public final class McpHumanConfirmation {

    /** 受闸工具的必填参数名。 */
    public static final String PARAMETER = "human_confirmation";

    /** 唯一放行值：用户亲自输入的「确认」二字。 */
    public static final String EXPECTED_VALUE = "确认";

    /** 稳定错误码（422）：Agent 据此判定「回去向用户索要确认」，而不是改参数重试。 */
    public static final String ERROR_CODE = "HUMAN_CONFIRMATION_REQUIRED";

    /** 审计载荷里代表「人类确认过」的字段；不落用户输入明文。 */
    public static final String AUDIT_FIELD = "human_confirmed";

    /** 参数描述：直接写给模型看的操作指令，措辞不得弱化。 */
    public static final String DESCRIPTION =
            "人类确认闸：必须先在对话中向用户复述即将执行的动作，等用户亲自输入『确认』二字后，"
                    + "把用户输入原样传入本参数；用户未输入不得调用本工具，不得代填";

    private static final String ERROR_MESSAGE =
            "本工具受人类确认闸保护：请先在对话中向用户复述即将执行的动作，等用户亲自输入『确认』二字，"
                    + "再把该输入原样填入参数 " + PARAMETER + " 后重试；不得代填、不得改写、不得用"
                    + "「ok」「yes」「确认。」等近似值替代。";

    private McpHumanConfirmation() {}

    /** 受闸工具 schema 里的 {@value #PARAMETER} 属性；描述由本类唯一持有，不在各工具复制。 */
    public static ObjectNode property() {
        return McpToolRegistry.stringProperty(DESCRIPTION);
    }

    /**
     * 校验人类确认并剥离该参数。
     *
     * @return 不含 {@link #PARAMETER} 的入参副本；调用方**必须**用它继续解析参数，
     *     以保证用户输入不进入下游命令与幂等载荷
     * @throws BusinessException 422 {@value #ERROR_CODE}——缺参数、类型不对、
     *     或字面值（strip 后）不等于「确认」
     */
    public static Map<String, Object> requireConfirmed(Map<String, Object> args) {
        Object value = args == null ? null : args.get(PARAMETER);
        // strip() 而不是 trim()：全角空格（U+3000）在中文输入法下极常见，
        // 它是空白而不是内容，不该让用户因为输入法多带一个空格就被闸住。
        if (!(value instanceof String text) || !EXPECTED_VALUE.equals(text.strip())) {
            throw BusinessException.unprocessable(ERROR_CODE, ERROR_MESSAGE);
        }
        Map<String, Object> stripped = new LinkedHashMap<>(args);
        stripped.remove(PARAMETER);
        return Collections.unmodifiableMap(stripped);
    }
}
