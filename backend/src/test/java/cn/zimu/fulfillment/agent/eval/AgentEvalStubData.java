package cn.zimu.fulfillment.agent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * 评测 stub 模型的可编程输出（T03 数据驱动化后保留的 canned 层）：按用例 {@code input}
 * 脚本化模型的固定响应。用例真源（input/expected）在 DB（{@code agent_eval_cases}，V33 播种），
 * 本类只承载「stub 模型返回什么」这一测试基建事实——与 {@code AgentEvalScorer} 的 canned
 * MCP 注册表同性质，不是用例数据源。
 *
 * <p>采购比价：input 为结构化 JSON，脚本化最终结构化输出（7 例，与 V33 种子 input 语义对应；
 * 其中两例 input 相同但输出形态不同——snake_case 与 camelCase 兼容——本类按 input 返回
 * snake_case 形态，camelCase 解析兼容由 {@code ProcurementPriceEvalTest} 用真实 runtime 覆盖）。
 * 查找按 JSON 语义等价（字段序无关），与 jsonb 规范化后的 DB input 匹配稳定。
 */
public final class AgentEvalStubData {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEvalStubData() {}

    /** 采购比价 7 例：input JSON → stub 最终输出（与 V33 种子 input 逐字对应）。 */
    private static final Map<String, String> PROCUREMENT_MODEL_OUTPUT_BY_INPUT = Map.of(
            "{\"procurement_ticket_id\":\"9001\",\"quantity\":\"2\"}",
            "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":\"2\","
                    + "\"inventory\":{\"available\":\"0\",\"shortage\":\"2\"},"
                    + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                    + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},"
                    + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                    + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],"
                    + "\"recommendation\":{\"provider_code\":\"P001\","
                    + "\"reason\":\"最低价且来自主数据进货价\"},"
                    + "\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}",
            "{\"sku_id\":\"1001\"}",
            "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":null,"
                    + "\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},"
                    + "\"candidates\":[{\"provider_code\":\"P003\",\"price\":\"8.50\","
                    + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],"
                    + "\"recommendation\":{\"provider_code\":\"P003\",\"reason\":\"唯一候选\"},"
                    + "\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}",
            "{\"procurement_ticket_id\":\"9002\",\"quantity\":\"1\"}",
            "{\"target_sku\":\"SKU-2001\",\"requested_quantity\":\"1\","
                    + "\"inventory\":{\"available\":\"0\",\"shortage\":\"1\"},"
                    + "\"candidates\":[],"
                    + "\"recommendation\":null,"
                    + "\"missing_fields\":[],\"confidence\":0.8,\"requires_human\":false}",
            "{\"procurement_ticket_id\":\"9003\"}",
            "{\"target_sku\":\"SKU-3001\",\"requested_quantity\":null,"
                    + "\"inventory\":{\"available\":\"0\",\"shortage\":\"3\"},"
                    + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":null,"
                    + "\"price_basis\":\"sku_commercial_price\",\"note\":\"未定价\"}],"
                    + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},"
                    + "\"missing_fields\":[],\"confidence\":0.7,\"requires_human\":false}",
            "{\"procurement_ticket_id\":\"9004\",\"quantity\":\"4\"}",
            "{\"target_sku\":\"SKU-4001\",\"requested_quantity\":\"4\","
                    + "\"inventory\":{\"available\":\"0\",\"shortage\":\"4\"},"
                    + "\"candidates\":[{\"provider_code\":\"P002\",\"price\":\"20.10\","
                    + "\"price_basis\":\"provider_sku\",\"note\":\"外部映射无本地名\"}],"
                    + "\"recommendation\":{\"provider_code\":\"P002\",\"reason\":\"x\"},"
                    + "\"missing_fields\":[\"provider_sku_name\"],\"confidence\":0.2,"
                    + "\"requires_human\":false}",
            "{\"sku_id\":\"1002\"}",
            "这不是符合 schema 的 JSON");

    /** 按 input 返回采购比价 stub 最终输出（JSON 语义等价匹配）；未注册的 input 视为评测数据漂移，fail-fast。 */
    public static String procurementModelOutput(String inputJson) {
        try {
            JsonNode input = MAPPER.readTree(inputJson);
            for (Map.Entry<String, String> entry : PROCUREMENT_MODEL_OUTPUT_BY_INPUT.entrySet()) {
                if (MAPPER.readTree(entry.getKey()).equals(input)) {
                    return entry.getValue();
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("stub 输入解析失败: " + inputJson, ex);
        }
        throw new IllegalStateException(
                "stub 未注册的采购比价评测输入（评测数据与 V33 种子漂移）: " + inputJson);
    }
}
