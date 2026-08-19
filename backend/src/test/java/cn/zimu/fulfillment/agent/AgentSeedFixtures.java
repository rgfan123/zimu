package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 测试夹具：与 V33 种子（{@code app.agent_definitions}，meta-agent-platform-impl 01）逐字
 * 一致的 Agent 定义与工具白名单。T02 删除代码定义后，单元测试（无 DB）需要定义时统一
 * 从这里取，避免各测试内联重复清单造成漂移。
 *
 * <p>这些清单必须与 V33 迁移种子一致（种子 ↔ 库的完整性由
 * {@code AgentPlatformSeedVerbatimTest} 的 DB 断言兜底）；改种子必须同步本类。
 */
public final class AgentSeedFixtures {

    private AgentSeedFixtures() {}

    /** data-query-agent 白名单（V33 种子 tool_whitelist，13 个只读工具）。 */
    public static final List<String> DATA_QUERY_TOOL_NAMES = List.of(
            "list_procurement_tickets",
            "get_procurement_ticket",
            "list_procurement_receipts",
            "search_skus",
            "get_sku",
            "list_provider_skus",
            "get_inventory_overview",
            "get_inventory_detail",
            "list_products",
            "list_categories",
            "list_fulfillment_providers",
            "list_interpretations",
            "list_message_media");

    /** procurement-price-agent 白名单（V33 种子 tool_whitelist，11 个只读工具）。 */
    public static final List<String> PROCUREMENT_TOOL_NAMES = List.of(
            "list_procurement_tickets",
            "get_procurement_ticket",
            "list_procurement_receipts",
            "search_skus",
            "get_sku",
            "list_provider_skus",
            "get_inventory_overview",
            "get_inventory_detail",
            "list_products",
            "list_categories",
            "list_fulfillment_providers");

    /** 与 V33 种子 data-query-agent（version=1, active）一致的定义（system_prompt 取种子开头与关键约束）。 */
    public static AgentDefinition dataQueryDefinition() {
        return AgentDefinition.of(
                "data-query-agent",
                "数据查询",
                "自然语言只读数据查询：订单/采购/SKU 价格/库存/主数据",
                "你是数据查询助手（只读）。输出严格按给定 JSON schema：answer 是含数字的人话摘要；"
                        + "clarification_needed 无需澄清时为空数组。",
                "data-query-v1",
                "app.agent",
                true,
                DATA_QUERY_TOOL_NAMES);
    }

    /** 与 V33 种子 procurement-price-agent（version=1, active）一致的定义（system_prompt 取种子开头）。 */
    public static AgentDefinition procurementDefinition() {
        return AgentDefinition.of(
                "procurement-price-agent",
                "采购比价 Agent",
                "针对采购工单/SKU 汇总进货价、履约方映射与库存上下文，输出结构化比价建议；低置信度或信息不全时转人工。",
                "你是采购比价 Agent（只读，绝不触发任何写操作）。你的职责：针对采购工单或 SKU 汇总进货价、"
                        + "履约方映射与库存上下文，输出结构化比价建议。",
                "procurement-price-v1",
                "app.agent",
                true,
                PROCUREMENT_TOOL_NAMES);
    }
}
