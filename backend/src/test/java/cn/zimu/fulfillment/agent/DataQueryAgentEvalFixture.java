package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 数据查询 Agent（06 票）固定评测集：内嵌代码 fixture，作为 09 票评测基线的种子。
 *
 * <p>覆盖：
 * <ul>
 *   <li>票内示例评测查询 4 条（{@code Q_7D_OUT_OF_STOCK} 可答；其余 3 条为占位/歧义，
 *       按策略进入澄清路径）；</li>
 *   <li>可答的落地变体（具体 SKU 编号、数字 ticket_id），用于「答案数字与数据库事实核对」；</li>
 *   <li>PII 拒绝路径（客户/收货人 PII → 转人工）。</li>
 * </ul>
 *
 * <p>预期工具序列与数字事实断言由 {@code DataQueryAgentServiceIntegrationTest} 以
 * 真实注册表 + Testcontainers 数据库事实执行；本类只声明查询集与分类，保持可复用
 * （09 票跑分器只读引用本类，版本 {@value #VERSION}）。
 */
public final class DataQueryAgentEvalFixture {

    private DataQueryAgentEvalFixture() {}

    /** 评测集版本标识（09 票基线门禁断言该版本）。 */
    public static final String VERSION = "data-query-eval-v1";

    /** 可答：最近 7 天缺货订单行数 → list_procurement_tickets(status=PENDING, 日期范围)。 */
    public static final String Q_7D_OUT_OF_STOCK = "最近 7 天有多少缺货的订单行";

    /** 歧义（票内示例）：SKU 编号为占位符 → 澄清，不猜参数。 */
    public static final String Q_SKU_PLACEHOLDER = "SKU-xxx 的进货价和零售价是多少";

    /** 歧义（票内示例）：工单号 P-123 无法解析为数字 ticket_id → 澄清。 */
    public static final String Q_TICKET_NO_PLACEHOLDER = "采购工单 P-123 还差多少数量";

    /** 歧义（票内示例）：未指明履约方 → 澄清。 */
    public static final String Q_PROVIDER_AMBIGUOUS = "某履约方本月共接收多少运单回执";

    /** 可答变体：具体 SKU 编号 → search_skus，价格与数据库事实核对。 */
    public static final String Q_SKU_CONCRETE = "SKU-EVAL-000001 的进货价和零售价是多少";

    /** 可答变体：数字 ticket_id → get_procurement_ticket，缺口与数据库事实核对。 */
    public static final String Q_TICKET_CONCRETE = "采购工单 9005 还差多少数量";

    /** PII 拒绝路径：客户/收货人 PII → 转人工，不发起模型与工具调用。 */
    public static final String Q_PII_RECEIVER = "查一下客户张三的收货地址";

    /** 全部评测查询（保持声明顺序）。 */
    public static final List<String> ALL_QUERIES = List.of(
            Q_7D_OUT_OF_STOCK,
            Q_SKU_PLACEHOLDER,
            Q_TICKET_NO_PLACEHOLDER,
            Q_PROVIDER_AMBIGUOUS,
            Q_SKU_CONCRETE,
            Q_TICKET_CONCRETE,
            Q_PII_RECEIVER);

    /** 预期：工具调用 + 数字答案（与数据库事实核对）。 */
    public static final List<String> EXPECT_ANSWER =
            List.of(Q_7D_OUT_OF_STOCK, Q_SKU_CONCRETE, Q_TICKET_CONCRETE);

    /** 预期：澄清路径（clarification_needed 非空，零工具调用，零模型调用）。 */
    public static final List<String> EXPECT_CLARIFICATION =
            List.of(Q_SKU_PLACEHOLDER, Q_TICKET_NO_PLACEHOLDER, Q_PROVIDER_AMBIGUOUS);

    /** 预期：PII 转人工（requires_human=true，零工具调用，零模型调用）。 */
    public static final List<String> EXPECT_PII_TRANSFER = List.of(Q_PII_RECEIVER);

    /** 可答查询的预期工具选择（工具选择正确率断言）。 */
    public static String expectedTool(String question) {
        return switch (question) {
            case Q_7D_OUT_OF_STOCK -> "list_procurement_tickets";
            case Q_SKU_CONCRETE -> "search_skus";
            case Q_TICKET_CONCRETE -> "get_procurement_ticket";
            default -> throw new IllegalArgumentException("可答评测查询未定义工具预期: " + question);
        };
    }
}
