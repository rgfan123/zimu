package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.agent.AgentInputFormat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 测试夹具：与 V33 种子（{@code app.agent_definitions}，meta-agent-platform-impl 01）
 * 身份字段与工具白名单一致的 Agent 定义。T02 删除代码定义后，单元测试（无 DB）需要定义时
 * 统一从这里取，避免各测试内联重复清单造成漂移。
 *
 * <p>注意口径：{@link #dataQueryDefinition()} / {@link #procurementDefinition()} 的
 * slug/name/description/prompt_version/model_ref/enabled/tool_whitelist 与 V33 种子一致，
 * 但 <b>system_prompt 为截断节选</b>（单元测试不驱动真实模型，只需断言所需的关键约束词）；
 * 完整提示词真源在 DB 种子，不在本类重复。种子 ↔ 库的完整性由
 * {@code AgentPlatformSeedVerbatimTest} 的 DB 断言兜底；改种子必须同步本类的清单字段。
 *
 * <p>集成测试在 DB 真源上注册测试 Agent 用 {@link #upsertActiveDefinition}（先删同 slug
 * 再插，幂等），避免多处内联重复 INSERT SQL。
 */
public final class AgentSeedFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return AgentDefinition.ofActiveV1(
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

    /** 与当前 active procurement-price-agent 定义一致的测试夹具（system_prompt 取关键约束）。 */
    public static AgentDefinition procurementDefinition() {
        return AgentDefinition.of(
                "procurement-price-agent",
                "采购比价 Agent",
                "针对采购工单/SKU 汇总进货价、履约方映射与库存上下文，输出结构化比价建议；不可比候选降级展示并说明理由；低置信度或信息不全时转人工。",
                "你是采购比价 Agent（只读，绝不触发任何写操作）。你的职责：针对采购工单或 SKU 汇总进货价、"
                        + "履约方映射与库存上下文，输出结构化比价建议。不可比候选必须携带剔除理由返回。",
                "procurement-price-v2",
                "app.agent",
                true,
                PROCUREMENT_TOOL_NAMES,
                1,
                AgentStatus.ACTIVE,
                "system",
                java.time.OffsetDateTime.now(),
                false,
                java.util.List.of(),
                null,
                AgentInputFormat.STRUCTURED_JSON);
    }

    /** 以给定定义构造持有器（测试便捷构造，等价 {@code new AgentRegistryHolder(new AgentRegistry(...))}）。 */
    public static AgentRegistryHolder holderOf(AgentDefinition... definitions) {
        return new AgentRegistryHolder(new AgentRegistry(List.of(definitions)));
    }

    /**
     * 在 DB 真源（{@code app.agent_definitions}）上幂等注册一个 active 定义（先删同 slug 再插）：
     * 集成测试注册测试 Agent 用，避免各处内联重复 INSERT SQL。注意：会删除同 slug 的既有行
     * （含历史版本），不适合需要保留版本链的「草稿确认」流程模拟（后者用显式 UPDATE+INSERT）。
     */
    public static void upsertActiveDefinition(JdbcTemplate jdbc, AgentDefinition definition) {
        jdbc.update("DELETE FROM app.agent_definitions WHERE agent_slug = ?", definition.agentSlug());
        jdbc.update(
                "INSERT INTO app.agent_definitions ("
                        + "agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, activated_by, activated_at, allow_write, "
                        + "guard_exemptions, output_schema, tool_whitelist) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', 'test', CURRENT_TIMESTAMP, "
                        + "?, '[]'::jsonb, NULL, ?::jsonb)",
                definition.agentSlug(),
                definition.name(),
                definition.description(),
                definition.systemPrompt(),
                definition.promptVersion(),
                definition.modelRef(),
                definition.enabled(),
                definition.version(),
                definition.allowWrite(),
                toJsonArray(definition.toolNames()));
    }

    private static String toJsonArray(List<String> values) {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("工具白名单序列化失败: " + values, ex);
        }
    }
}
