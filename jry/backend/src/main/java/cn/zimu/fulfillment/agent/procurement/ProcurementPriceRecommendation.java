package cn.zimu.fulfillment.agent.procurement;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 采购比价 Agent 的结构化输出（agent-decision-layer 05）。
 *
 * <p>AI Service 严格 schema：模型输出由 AiServices 以本记录的 JSON Schema 约束并反序列化；
 * 任何不满足该结构的模型响应都会被拒绝（AGENT_OUTPUT_INVALID）。字段名经
 * {@link JsonProperty} 固定为票 schema 的 snake_case，并兼容
 * {@link JsonAlias}（LangChain4j 文本指令以 Java 字段名 camelCase 引导模型，两种命名
 * 都能解析）。价格一律是 decimal-string 且 SCALE=2（对齐 04 票 {@code McpDomainReadTools}
 * 的 {@code SkuCommercialPrice} 语义）；{@code price_basis} 只取 {@link PriceBasis}
 * 两种取值。{@code requires_human=true} 时 {@code recommendation} 恒为 null
 * （只给出可复核的事实摘要，不给建议）。
 */
public record ProcurementPriceRecommendation(
        @JsonProperty("target_sku") @JsonAlias("targetSku") String targetSku,
        @JsonProperty("requested_quantity") @JsonAlias("requestedQuantity") String requestedQuantity,
        @JsonProperty("inventory") Inventory inventory,
        @JsonProperty("candidates") List<Candidate> candidates,
        @JsonProperty("recommendation") Recommendation recommendation,
        @JsonProperty("missing_fields") @JsonAlias("missingFields") List<String> missingFields,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("requires_human") @JsonAlias("requiresHuman") boolean requiresHuman) {

    public ProcurementPriceRecommendation {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** 库存上下文：可用量与缺口，decimal-string（SCALE=2）；无观测时整体为 null。 */
    public record Inventory(
            @JsonProperty("available") String available, @JsonProperty("shortage") String shortage) {}

    /** 一个比价候选；price 为 decimal-string（SCALE=2），priceBasis 只取两种取值。 */
    public record Candidate(
            @JsonProperty("provider_code") @JsonAlias("providerCode") String providerCode,
            @JsonProperty("price") String price,
            @JsonProperty("price_basis") @JsonAlias("priceBasis") PriceBasis priceBasis,
            @JsonProperty("note") String note) {}

    /** 推荐（requires_human=true 时为 null，只给可复核事实）。 */
    public record Recommendation(
            @JsonProperty("provider_code") @JsonAlias("providerCode") String providerCode,
            @JsonProperty("reason") String reason) {}

    /** 候选价格依据：SKU 主数据进货价 / 履约方映射价格。 */
    public enum PriceBasis {
        sku_commercial_price,
        provider_sku
    }
}
