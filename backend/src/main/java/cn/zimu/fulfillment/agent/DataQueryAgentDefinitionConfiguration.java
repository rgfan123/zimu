package cn.zimu.fulfillment.agent;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 数据查询 Agent（06 票）注册：slug=data-query-agent，只读工具白名单 + 系统提示词。
 *
 * <p>白名单只含 04 票领域只读工具（11 个）与既有 {@code McpReadTools} 的非 PII 投影
 * （消息媒体元数据、解释历史），不含任何写工具，也不含任何客户/收货人 PII 投影工具
 * （草稿/候选/渠道消息原文/复核事项详情等）。运行时绑定由
 * {@link AgentToolBindingFactory} 按白名单强制执行（白名单之外的工具不暴露给模型）。
 *
 * <p>提示词版本固定（data-query-v1），配合 06 票评测集作为 09 票基线；换提示词必须
 * 改版本号并跑评测回归。
 */
@Configuration
public class DataQueryAgentDefinitionConfiguration {

    public static final String SLUG = "data-query-agent";
    public static final String NAME = "数据查询";
    public static final String PROMPT_VERSION = "data-query-v1";
    public static final String MODEL_REF = "app.agent";

    /** 只读白名单：04 票 11 个领域工具 + McpReadTools 非 PII 投影（媒体元数据、解释历史）。 */
    public static final List<String> TOOL_NAMES = List.of(
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

    public static final String SYSTEM_PROMPT = """
            你是数据查询助手（只读）。你只能使用下方提供的只读工具查询数据，绝不执行任何写操作。

            可用工具（均只读）：
            - list_procurement_tickets：采购工单摘要（状态/日期范围/分页）——缺货订单行按 procurement_status=PENDING 统计；
            - get_procurement_ticket：采购工单详情（缺口 remaining_quantity、回执、关联订单行）；
            - list_procurement_receipts：采购工单的全部不可变回执；
            - search_skus：按商品名/规格/SKU 编号模糊检索 SKU（含进货价 purchase_price 与零售价 retail_price）；
            - get_sku：单个 SKU 详情；list_provider_skus：履约方外部编码映射；
            - get_inventory_overview / get_inventory_detail：库存观测；
            - list_products / list_categories / list_fulfillment_providers：主数据；
            - list_interpretations / list_message_media：消息解释历史与媒体元数据。

            规则：
            1. 只调用上述工具；未调用的信息一律不编造，数字必须来自工具返回值。
            2. 信息不全或歧义（未给出具体 SKU 编号、采购工单数字 ticket_id、履约方等）时：
               不猜测参数，输出 clarification_needed 列出需要用户补充的信息，
               sources 保持空数组，requires_human=true。
            3. 涉及客户/收货人/收件人姓名、手机号、地址等 PII：直接 requires_human=true
               并说明需转人工，不得调用任何工具。
            4. 输出严格按给定 JSON schema：answer 是含数字的人话摘要；sources 记录实际调用过的
               工具、关键参数与 row_count；confidence 为 0~1 的置信度；clarification_needed
               无需澄清时为空数组；金额沿用工具返回的 decimal 原值，不得四舍五入丢小数。
            5. 采购工单只能按数字 ticket_id 查询；工单号（如 P-123）无法解析，须请用户提供 ticket_id。
            """;

    @Bean
    AgentDefinition dataQueryAgentDefinition() {
        return AgentDefinition.of(
                SLUG,
                NAME,
                "自然语言只读数据查询：订单/采购/SKU 价格/库存/主数据",
                SYSTEM_PROMPT,
                PROMPT_VERSION,
                MODEL_REF,
                true,
                TOOL_NAMES);
    }
}
