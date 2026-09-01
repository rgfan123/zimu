package cn.zimu.fulfillment.agent.procurement;

import java.util.List;

/**
 * 采购比价 Agent（05 票）固定评测集（09 票基线种子，版本 {@value #VERSION}）：内嵌代码
 * fixture，由 05 票 {@code ProcurementPriceEvalTest} 与本评测包 {@code AgentEvalScorer}
 * （09 票跑分器）共同只读引用，避免双份维护漂移。
 *
 * <p>12 例：正常比价（ticket 输入）、正常比价（sku 输入、无数量）、无候选、缺价格、
 * 低置信度+字段缺失、schema 不符（负例）、camelCase 模型输出兼容，以及 01 票新增
 * 5 例覆盖三类不可比候选剔除：价格离群（保留可比候选自动决策）、全部映射失效转人工、
 * 映射失效（保留可比候选自动决策）、价格缺失候选剔除（整体转人工）、推荐落在被剔除候选
 * 上转人工。
 *
 * <p>评测集不可增删改（换例即换版本号）：新增场景请另立 {@code procurement-eval-v4}，
 * 并同步 {@code AgentEvalBaselineTest} 基线数字。
 */
public final class ProcurementPriceEvalFixture {

    private ProcurementPriceEvalFixture() {}

    /** 评测集版本标识（09 票基线门禁断言该版本）。 */
    public static final String VERSION = "procurement-eval-v3";

    public record EvalCase(
            String id,
            String inputJson,
            String modelOutput,
            boolean expectRequiresHuman,
            String expectMissingContain) {}

    public static final List<EvalCase> CASES = List.of(
            new EvalCase(
                    "happy-path-ticket",
                    "{\"procurement_ticket_id\":\"9001\",\"quantity\":2}",
                    "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":2,"
                            + "\"inventory\":{\"available\":0,\"shortage\":2},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},"
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":{\"provider_code\":\"P001\","
                            + "\"reason\":\"最低价且来自主数据进货价\"},"
                            + "\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}",
                    false,
                    null),
            new EvalCase(
                    "happy-path-sku-no-quantity",
                    "{\"sku_id\":\"1001\"}",
                    "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"provider_code\":\"P003\",\"price\":\"8.50\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":{\"provider_code\":\"P003\",\"reason\":\"唯一候选\"},"
                            + "\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}",
                    false,
                    null),
            new EvalCase(
                    "no-candidates",
                    "{\"procurement_ticket_id\":\"9002\",\"quantity\":1}",
                    "{\"target_sku\":\"SKU-2001\",\"requested_quantity\":1,"
                            + "\"inventory\":{\"available\":0,\"shortage\":1},"
                            + "\"candidates\":[],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":null,"
                            + "\"missing_fields\":[],\"confidence\":0.8,\"requires_human\":false}",
                    true,
                    "candidates"),
            new EvalCase(
                    "missing-price",
                    "{\"procurement_ticket_id\":\"9003\"}",
                    "{\"target_sku\":\"SKU-3001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":0,\"shortage\":3},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":null,"
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"未定价\"}],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},"
                            + "\"missing_fields\":[],\"confidence\":0.7,\"requires_human\":false}",
                    true,
                    "price"),
            new EvalCase(
                    "low-confidence-and-missing-fields",
                    "{\"procurement_ticket_id\":\"9004\",\"quantity\":4}",
                    "{\"target_sku\":\"SKU-4001\",\"requested_quantity\":4,"
                            + "\"inventory\":{\"available\":0,\"shortage\":4},"
                            + "\"candidates\":[{\"provider_code\":\"P002\",\"price\":\"20.10\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"外部映射无本地名\"}],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":{\"provider_code\":\"P002\",\"reason\":\"x\"},"
                            + "\"missing_fields\":[\"provider_sku_name\"],\"confidence\":0.2,"
                            + "\"requires_human\":false}",
                    true,
                    "provider_sku_name"),
            new EvalCase(
                    "happy-path-camelcase-model-output",
                    "{\"sku_id\":\"1001\"}",
                    "{\"targetSku\":\"SKU-1001\",\"requestedQuantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"providerCode\":\"P003\",\"price\":\"8.50\","
                            + "\"priceBasis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],"
                            + "\"excludedCandidates\":[],"
                            + "\"recommendation\":{\"providerCode\":\"P003\",\"reason\":\"唯一候选\"},"
                            + "\"missingFields\":[],\"confidence\":0.85,\"requiresHuman\":false}",
                    false,
                    null),
            new EvalCase(
                    "schema-invalid-output",
                    "{\"sku_id\":\"1002\"}",
                    "这不是符合 schema 的 JSON",
                    true,
                    null),
            // ---- 01 票：不可比候选三规则（价格离群 / 价格缺失 / 映射失效） ----
            new EvalCase(
                    "outlier-candidate-excluded",
                    "{\"sku_id\":\"1001\"}",
                    "{\"target_sku\":\"SKU-5001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},"
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"},"
                            + "{\"provider_code\":\"P003\",\"price\":\"45.67\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"渠道报价异常高\"}],"
                            + "\"excluded_candidates\":[],"
                            + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"最低价且可比\"},"
                            + "\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}",
                    false,
                    null),
            new EvalCase(
                    "all-mapping-stale-forces-human",
                    "{\"sku_id\":\"1003\"}",
                    "{\"target_sku\":\"SKU-6001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":0,\"shortage\":6},"
                            + "\"candidates\":[],"
                            + "\"excluded_candidates\":["
                            + "{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"映射已停用\","
                            + "\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已停用\"},"
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"映射已过期\","
                            + "\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已过期\"}],"
                            + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},"
                            + "\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}",
                    true,
                    "candidates"),
            new EvalCase(
                    "mapping-stale-candidate-excluded",
                    "{\"sku_id\":\"1001\"}",
                    "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],"
                            + "\"excluded_candidates\":["
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射已停用\","
                            + "\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已停用\"}],"
                            + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"唯一可比候选\"},"
                            + "\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}",
                    false,
                    null),
            new EvalCase(
                    "price-missing-candidate-excluded-forces-human",
                    "{\"sku_id\":\"1004\"}",
                    "{\"target_sku\":\"SKU-7001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},"
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],"
                            + "\"excluded_candidates\":["
                            + "{\"provider_code\":\"P003\",\"price\":null,"
                            + "\"price_basis\":\"provider_sku\",\"note\":\"未定价\","
                            + "\"exclusion_reason\":\"price_missing\",\"exclusion_reason_detail\":\"无可用价格\"}],"
                            + "\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},"
                            + "\"missing_fields\":[],\"confidence\":0.8,\"requires_human\":false}",
                    true,
                    "price"),
            new EvalCase(
                    "recommendation-on-excluded-candidate-forces-human",
                    "{\"sku_id\":\"1005\"}",
                    "{\"target_sku\":\"SKU-8001\",\"requested_quantity\":null,"
                            + "\"inventory\":{\"available\":5,\"shortage\":0},"
                            + "\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\","
                            + "\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},"
                            + "{\"provider_code\":\"P002\",\"price\":\"12.90\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],"
                            + "\"excluded_candidates\":["
                            + "{\"provider_code\":\"P003\",\"price\":\"45.67\","
                            + "\"price_basis\":\"provider_sku\",\"note\":\"渠道报价异常高\","
                            + "\"exclusion_reason\":\"price_outlier\",\"exclusion_reason_detail\":\"偏离中位数\"}],"
                            + "\"recommendation\":{\"provider_code\":\"P003\",\"reason\":\"x\"},"
                            + "\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}",
                    true,
                    "recommendation"));
}
