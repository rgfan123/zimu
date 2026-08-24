package cn.zimu.fulfillment.agent.procurement;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 采购比价 Agent 的结构化输出（agent-decision-layer 05，01 票扩展）。
 *
 * <p>AI Service 严格 schema：模型输出由 AiServices 以本记录的 JSON Schema 约束并反序列化；
 * 任何不满足该结构的模型响应都会被拒绝（AGENT_OUTPUT_INVALID）。字段名经
 * {@link JsonProperty} 固定为票 schema 的 snake_case，并兼容
 * {@link JsonAlias}（LangChain4j 文本指令以 Java 字段名 camelCase 引导模型，两种命名
 * 都能解析）。价格一律是 decimal-string 且 SCALE=2（对齐 04 票 {@code McpDomainReadTools}
 * 的 {@code SkuCommercialPrice} 语义）；{@code price_basis} 只取 {@link PriceBasis}
 * 两种取值。{@code requires_human=true} 时 {@code recommendation} 恒为 null
 * （只给出可复核的事实摘要，不给建议）。
 *
 * <p>不可比候选（01 票）：{@code candidates} 只含<b>可比</b>候选（参与推荐与 UI 的
 * 「可比候选」组），被剔除的候选整体移入 {@code excludedCandidates}（「被剔除候选」组），
 * 每项携带 {@link ExclusionReason} 理由标签与可读说明——剔除 = 降级展示，不是删除。
 * 推荐只在可比候选中产生；可比候选为空时 {@code requires_human=true}，不硬推。
 */
public record ProcurementPriceRecommendation(
        @JsonProperty("target_sku") @JsonAlias("targetSku") String targetSku,
        @JsonProperty("requested_quantity") @JsonAlias("requestedQuantity") String requestedQuantity,
        @JsonProperty("inventory") Inventory inventory,
        @JsonProperty("candidates") List<Candidate> candidates,
        @JsonProperty("excluded_candidates") @JsonAlias("excludedCandidates") List<ExcludedCandidate> excludedCandidates,
        @JsonProperty("recommendation") Recommendation recommendation,
        @JsonProperty("missing_fields") @JsonAlias("missingFields") List<String> missingFields,
        @JsonProperty("confidence") double confidence,
        @JsonProperty("requires_human") @JsonAlias("requiresHuman") boolean requiresHuman) {

    public ProcurementPriceRecommendation {
        missingFields = missingFields == null ? List.of() : List.copyOf(missingFields);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        excludedCandidates = excludedCandidates == null ? List.of() : List.copyOf(excludedCandidates);
    }

    /** 库存上下文：可用量与缺口，decimal-string（SCALE=2）；无观测时整体为 null。 */
    public record Inventory(
            @JsonProperty("available") String available, @JsonProperty("shortage") String shortage) {}

    /** 一个可比比价候选；price 为 decimal-string（SCALE=2），priceBasis 只取两种取值。 */
    public record Candidate(
            @JsonProperty("provider_code") @JsonAlias("providerCode") String providerCode,
            @JsonProperty("price") String price,
            @JsonProperty("price_basis") @JsonAlias("priceBasis") PriceBasis priceBasis,
            @JsonProperty("note") String note) {}

    /** 一个被剔除的候选（01 票）：与理由标签一并返回，绝不静默消失。 */
    public record ExcludedCandidate(
            @JsonProperty("provider_code") @JsonAlias("providerCode") String providerCode,
            @JsonProperty("price") String price,
            @JsonProperty("price_basis") @JsonAlias("priceBasis") PriceBasis priceBasis,
            @JsonProperty("note") String note,
            @JsonProperty("exclusion_reason") @JsonAlias("exclusionReason") ExclusionReason exclusionReason,
            @JsonProperty("exclusion_reason_detail") @JsonAlias("exclusionReasonDetail") String exclusionReasonDetail) {}

    /** 推荐（requires_human=true 时为 null，只给可复核事实）。 */
    public record Recommendation(
            @JsonProperty("provider_code") @JsonAlias("providerCode") String providerCode,
            @JsonProperty("reason") String reason) {}

    /** 候选价格依据：SKU 主数据进货价 / 履约方映射价格。 */
    public enum PriceBasis {
        sku_commercial_price,
        provider_sku
    }

    /** 不可比候选的剔除理由标签（01 票）：价格离群 / 价格缺失 / 映射失效。 */
    public enum ExclusionReason {
        /** 与同组候选的中位数偏离超过设定倍数（{@link ProcurementPricePolicy#PRICE_OUTLIER_MULTIPLE}）。 */
        price_outlier,
        /** 候选没有可用价格（未定价或价格格式非法），不可参与比价。 */
        price_missing,
        /** 候选来自已停用或过期的履约方 SKU 映射。 */
        mapping_stale
    }
}
