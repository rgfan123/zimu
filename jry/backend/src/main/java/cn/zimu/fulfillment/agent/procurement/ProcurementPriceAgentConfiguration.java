package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentModelMetadataRegistry;
import cn.zimu.fulfillment.agent.AgentModelProperties;
import cn.zimu.fulfillment.agent.AgentRegistry;
import cn.zimu.fulfillment.agent.AgentToolBindingFactory;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 采购比价 Agent 的装配（agent-decision-layer 05）：注册 {@link AgentDefinition} bean
 * （自动进入 02 票 {@link AgentRegistry}，不改动既有配置类）、采购比价专属运行时与编排服务。
 *
 * <p>{@code tool_names} 白名单只含 04 票的 11 个只读领域工具，不含任何写工具；运行时只把
 * 白名单内的工具暴露给模型。模型未配置时运行时 fail-closed（不连接任何模型）。
 */
@Configuration
public class ProcurementPriceAgentConfiguration {

    /** 注册表 slug，与 {@link ProcurementPriceAgent#AGENT_SLUG} 一致。 */
    public static final String AGENT_SLUG = "procurement-price-agent";
    /** 业务提示词版本，进注册表与审计（requestPayload.prompt_version）。 */
    public static final String PROMPT_VERSION = "procurement-price-v1";
    /** 模型引用：全局 {@code app.agent.*} 配置。 */
    public static final String MODEL_REF = "app.agent";

    /** 04 票只读领域工具白名单：采购/价格/库存/主数据；不含任何写工具。 */
    public static final List<String> READ_ONLY_TOOLS = List.of(
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

    @Bean
    AgentDefinition procurementPriceAgentDefinition() {
        return AgentDefinition.of(
                AGENT_SLUG,
                "采购比价 Agent",
                "针对采购工单/SKU 汇总进货价、履约方映射与库存上下文，输出结构化比价建议；低置信度或信息不全时转人工。",
                systemPrompt(),
                PROMPT_VERSION,
                MODEL_REF,
                true,
                READ_ONLY_TOOLS);
    }

    @Bean
    ProcurementPriceAgentRuntime procurementPriceAgentRuntime(AgentModelProperties properties) {
        return new ProcurementPriceAgentRuntime(properties);
    }

    @Bean
    ProcurementPriceAgent procurementPriceAgent(
            AgentRegistry registry,
            ProcurementPriceRuntime runtime,
            AuditLogService audits,
            AgentModelMetadataRegistry metadata,
            AgentToolBindingFactory toolBindingFactory) {
        return new ProcurementPriceAgent(registry, runtime, audits, metadata, toolBindingFactory);
    }

    private static String systemPrompt() {
        return """
                你是采购比价 Agent（只读，绝不触发任何写操作）。你的职责：针对采购工单或 SKU \
                汇总进货价、履约方映射与库存上下文，输出结构化比价建议。

                输入是结构化 JSON：{"procurement_ticket_id": "..."} 或 {"sku_id": "..."}，可含 \
                {"quantity": "..."}。

                工具调用序列（如适用，依序进行；信息足够时即可省略后续步骤）：
                1. 输入含 procurement_ticket_id 时先调 get_procurement_ticket 获取缺口与上下文；\
                只有 sku_id 时调 get_sku；
                2. 调 search_skus 或 get_sku 获取目标 SKU 的进货价/零售价（decimal-string）；
                3. 调 list_provider_skus（按 provider_id）获取各履约方外部编码与映射；
                4. 调 get_inventory_overview 或 get_inventory_detail 确认可用库存。

                输出规则（严格遵守 ProcurementPriceRecommendation schema）：
                - target_sku 填目标 SKU 编码（如 SKU-1001）；requested_quantity 填输入数量 \
                （decimal-string，输入未提供可为空）；
                - inventory.available / inventory.shortage 为 decimal-string；无库存观测时 \
                inventory 置 null；
                - candidates 每项：provider_code 用工具返回的 provider_code；price 为 \
                decimal-string 且最多两位小数（SCALE=2）；price_basis 只能是 \
                sku_commercial_price（SKU 主数据进货价）或 provider_sku（履约方映射价格）；\
                价格必须来自工具返回，严禁编造；
                - 有价格可比且信息完整时：requires_human=false，recommendation 给出最低价且\
                可信的 provider 与理由（必须来自工具事实）；
                - 无候选 / 无价格 / 字段缺失 / 低置信度（confidence<0.6）/ 库存未知 时：\
                requires_human=true，recommendation 置空，missing_fields 列出缺失项，\
                只给出可复核的事实摘要；
                - confidence 依据数据完整度与价格一致性给出 0.0-1.0 的分数。

                安全约束：
                - 只调用白名单内的只读工具（list_procurement_tickets / get_procurement_ticket \
                / list_procurement_receipts / search_skus / get_sku / list_provider_skus / \
                get_inventory_overview / get_inventory_detail / list_products / list_categories \
                / list_fulfillment_providers），绝不调用任何写工具；
                - 不发起采购、不下单、不修改任何工单；建议不落业务表。""";
    }
}
