package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExcludedCandidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExclusionReason;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Inventory;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.PriceBasis;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 05 + 01 — 采购比价策略落地（agent-decision-layer 05，01 票扩展）：确定性归一化断言——
 * 无候选/无价格/字段缺失/低置信度全部 requires_human=true，且 recommendation 置空
 * （只给可复核事实）；价格统一 decimal-string SCALE=2；模型声明无需人工却缺推荐时转人工。
 * 01 票新增不可比候选三规则断言：价格离群（中位数倍数）、价格缺失（整体转人工）、
 * 映射失效（采信模型声明），以及「推荐只在可比候选中产生」。
 */
class ProcurementPricePolicyTest {

    @Test
    void happyPathKeepsRequiresHumanFalseAndNormalizesPrices() {
        ProcurementPriceRecommendation raw = raw(
                "SKU-1001",
                "2",
                new Inventory("0", "2"),
                List.of(
                        new Candidate("P001", "12.3", PriceBasis.sku_commercial_price, "主数据进货价"),
                        new Candidate("P002", "12.90", PriceBasis.provider_sku, "履约方映射")),
                new Recommendation("P001", "价格最低且可信"),
                0.9,
                false,
                List.of());

        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(raw);

        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.missingFields()).isEmpty();
        assertThat(enforced.excludedCandidates()).isEmpty();
        assertThat(enforced.recommendation()).isEqualTo(new Recommendation("P001", "价格最低且可信"));
        // 价格规范化：12.3 → 12.30（SCALE=2）
        assertThat(enforced.candidates().get(0).price()).isEqualTo("12.30");
        assertThat(enforced.candidates().get(1).price()).isEqualTo("12.90");
    }

    @Test
    void noCandidatesForcesRequiresHumanAndDropsRecommendation() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"), List.of(), new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("candidates");
        assertThat(enforced.recommendation()).isNull();
        assertThat(enforced.candidates()).isEmpty();
        assertThat(enforced.excludedCandidates()).isEmpty();
    }

    @Test
    void missingPriceForcesRequiresHumanAndMovesCandidateToExcluded() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", null, PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price");
        assertThat(enforced.recommendation()).isNull();
        // 被剔除候选与理由一并返回，绝不静默消失
        assertThat(enforced.candidates()).isEmpty();
        assertThat(enforced.excludedCandidates()).hasSize(1);
        assertThat(enforced.excludedCandidates().get(0).providerCode()).isEqualTo("P001");
        assertThat(enforced.excludedCandidates().get(0).exclusionReason()).isEqualTo(ExclusionReason.price_missing);
        assertThat(enforced.excludedCandidates().get(0).exclusionReasonDetail()).isNotBlank();
    }

    @Test
    void invalidPriceScaleForcesRequiresHumanAsMissingPrice() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.345", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        // 12.345 不满足 SCALE=2：视为缺价格，候选移入被剔除组，转人工
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price");
        assertThat(enforced.candidates()).isEmpty();
        assertThat(enforced.excludedCandidates().get(0).exclusionReason())
                .isEqualTo(ExclusionReason.price_missing);
        assertThat(enforced.recommendation()).isNull();
    }

    @Test
    void missingPriceBasisForcesRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", null, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price_basis");
    }

    @Test
    void lowConfidenceForcesRequiresHumanAndDropsRecommendation() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.3, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.recommendation()).isNull();
        assertThat(enforced.confidence()).isEqualTo(0.3);
    }

    @Test
    void modelClaimedMissingFieldsForceRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of("provider_sku_name")));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("provider_sku_name");
        assertThat(enforced.recommendation()).isNull();
    }

    @Test
    void missingTargetSkuForcesRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw(null, "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("target_sku");
    }

    @Test
    void missingInventoryForcesRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", null,
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("inventory");
    }

    @Test
    void modelClaimedNoHumanButNoRecommendationForcesHumanWithMissingField() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        null, 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("recommendation");
        assertThat(enforced.recommendation()).isNull();
    }

    @Test
    void modelClaimedRequiresHumanStripsRecommendationButKeepsFacts() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, true, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.recommendation()).isNull();
        // 可复核事实保留
        assertThat(enforced.candidates()).hasSize(1);
        assertThat(enforced.candidates().get(0).price()).isEqualTo("12.34");
        assertThat(enforced.targetSku()).isEqualTo("SKU-1001");
        assertThat(enforced.inventory()).isNotNull();
    }

    @Test
    void missingFieldsAreImmutableAndNullSafe() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw(null, null, null, List.of(), null, 0.1, false, null));

        assertThat(enforced.missingFields()).contains("target_sku", "candidates", "inventory");
        assertThat(enforced.missingFields()).isUnmodifiable();
    }

    @Test
    void thresholdBoundaryIsInclusiveForAutoDecision() {
        // confidence == threshold 不视为低置信度（>= 阈值可自动决策）
        ProcurementPriceRecommendation ok = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), ProcurementPricePolicy.LOW_CONFIDENCE_THRESHOLD, false, List.of()));
        assertThat(ok.requiresHuman()).isFalse();

        // 低于阈值 0.01 转人工
        ProcurementPriceRecommendation human = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), ProcurementPricePolicy.LOW_CONFIDENCE_THRESHOLD - 0.01, false, List.of()));
        assertThat(human.requiresHuman()).isTrue();
    }

    // ------------------------------------------------------------------
    // 01 票：不可比候选三规则
    // ------------------------------------------------------------------

    @Test
    void priceOutlierCandidateIsExcludedAndRecommendationOnlyAmongComparable() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, "主数据进货价"),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, "履约方映射"),
                                new Candidate("P003", "45.67", PriceBasis.provider_sku, "渠道报价异常高")),
                        new Recommendation("P001", "最低价且可比"),
                        0.9,
                        false,
                        List.of()));

        // 中位数 12.90：45.67 > 12.90×2 判离群，剔除并带理由；推荐只在可比候选中
        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.candidates()).extracting(Candidate::providerCode).containsExactly("P001", "P002");
        assertThat(enforced.excludedCandidates()).hasSize(1);
        ExcludedCandidate excluded = enforced.excludedCandidates().get(0);
        assertThat(excluded.providerCode()).isEqualTo("P003");
        assertThat(excluded.price()).isEqualTo("45.67");
        assertThat(excluded.exclusionReason()).isEqualTo(ExclusionReason.price_outlier);
        assertThat(excluded.exclusionReasonDetail()).contains("12.90", "45.67");
        assertThat(enforced.recommendation().providerCode()).isEqualTo("P001");
    }

    @Test
    void lowSidedPriceOutlierIsExcludedToo() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "5.00", PriceBasis.sku_commercial_price, null),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, null),
                                new Candidate("P003", "13.20", PriceBasis.provider_sku, null)),
                        new Recommendation("P002", "最低价且可比"),
                        0.9,
                        false,
                        List.of()));

        // 中位数 12.90：5.00 < 12.90÷2 判离群（低价离群）
        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.candidates()).extracting(Candidate::providerCode).containsExactly("P002", "P003");
        assertThat(enforced.excludedCandidates()).extracting(ExcludedCandidate::providerCode).containsExactly("P001");
        assertThat(enforced.excludedCandidates().get(0).exclusionReason())
                .isEqualTo(ExclusionReason.price_outlier);
    }

    @Test
    void outlierDetectionNeedsAtLeastThreePricedCandidates() {
        // 只有 2 例有价格：无中位数统计意义，全部保留可比，不做离群剔除
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "10.00", PriceBasis.sku_commercial_price, null),
                                new Candidate("P002", "50.00", PriceBasis.provider_sku, null)),
                        new Recommendation("P001", "x"),
                        0.9,
                        false,
                        List.of()));

        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.candidates()).hasSize(2);
        assertThat(enforced.excludedCandidates()).isEmpty();
    }

    @Test
    void mappingStaleCandidateIsExcludedAndKeptVisible() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, "主数据进货价")),
                        List.of(new ExcludedCandidate(
                                "P002", "12.90", PriceBasis.provider_sku, "履约方映射已停用",
                                ExclusionReason.mapping_stale, "映射已停用")),
                        new Recommendation("P001", "唯一可比候选"),
                        0.85,
                        false,
                        List.of()));

        // 映射失效候选被剔除（采信模型声明），可比候选保留且可自动决策
        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.candidates()).extracting(Candidate::providerCode).containsExactly("P001");
        assertThat(enforced.excludedCandidates()).hasSize(1);
        ExcludedCandidate excluded = enforced.excludedCandidates().get(0);
        assertThat(excluded.providerCode()).isEqualTo("P002");
        assertThat(excluded.exclusionReason()).isEqualTo(ExclusionReason.mapping_stale);
        assertThat(excluded.exclusionReasonDetail()).isNotBlank();
    }

    @Test
    void allCandidatesMappingStaleForcesRequiresHumanWithoutHardRecommendation() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("0", "1"),
                        List.of(),
                        List.of(
                                new ExcludedCandidate(
                                        "P001", "12.34", PriceBasis.provider_sku, null,
                                        ExclusionReason.mapping_stale, "映射已停用"),
                                new ExcludedCandidate(
                                        "P002", "12.90", PriceBasis.provider_sku, null,
                                        ExclusionReason.mapping_stale, "映射已过期")),
                        new Recommendation("P001", "x"),
                        0.85,
                        false,
                        List.of()));

        // 可比候选为空：转人工而不是硬推一个
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("candidates");
        assertThat(enforced.recommendation()).isNull();
        assertThat(enforced.candidates()).isEmpty();
        assertThat(enforced.excludedCandidates()).hasSize(2);
    }

    @Test
    void priceMissingCandidateForcesRequiresHumanEvenWhenComparableExists() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, null)),
                        List.of(new ExcludedCandidate(
                                "P003", null, PriceBasis.provider_sku, "未定价",
                                ExclusionReason.price_missing, "无可用价格")),
                        new Recommendation("P001", "x"),
                        0.8,
                        false,
                        List.of()));

        // 缺价是数据缺口：整体转人工（01 票前提假设 2），被剔除候选与理由保留
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price");
        assertThat(enforced.recommendation()).isNull();
        assertThat(enforced.candidates()).hasSize(2);
        assertThat(enforced.excludedCandidates()).hasSize(1);
        assertThat(enforced.excludedCandidates().get(0).exclusionReason())
                .isEqualTo(ExclusionReason.price_missing);
    }

    @Test
    void modelDeclaredPriceOutlierIsRecomputedAndNotTrustedBlindly() {
        // 模型把 P003 声明为 price_outlier；策略重算：三例 [12.34, 12.90, 45.67] 的中位数
        // 12.90，45.67 > 12.90×2 → 维持剔除（策略是确定性真源，声明与重算一致）
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, null)),
                        List.of(new ExcludedCandidate(
                                "P003", "45.67", PriceBasis.provider_sku, "渠道报价异常高",
                                ExclusionReason.price_outlier, "偏离中位数")),
                        new Recommendation("P001", "最低价且可比"),
                        0.9,
                        false,
                        List.of()));

        assertThat(enforced.requiresHuman()).isFalse();
        assertThat(enforced.candidates()).extracting(Candidate::providerCode).containsExactly("P001", "P002");
        assertThat(enforced.excludedCandidates())
                .extracting(ExcludedCandidate::providerCode)
                .containsExactly("P003");
        assertThat(enforced.excludedCandidates().get(0).exclusionReason())
                .isEqualTo(ExclusionReason.price_outlier);
    }

    @Test
    void recommendationOnExcludedCandidateForcesRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(
                                new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null),
                                new Candidate("P002", "12.90", PriceBasis.provider_sku, null)),
                        List.of(new ExcludedCandidate(
                                "P003", "45.67", PriceBasis.provider_sku, "渠道报价异常高",
                                ExclusionReason.price_outlier, "偏离中位数")),
                        new Recommendation("P003", "x"),
                        0.9,
                        false,
                        List.of()));

        // 推荐落在被剔除候选上：推荐只在可比候选中产生，视为字段缺失转人工
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("recommendation");
        assertThat(enforced.recommendation()).isNull();
    }

    @Test
    void outlierMultipleIsConfigurableAndMustExceedOne() {
        // 3 例价格 [12.34, 12.90, 45.67]，中位数 12.90
        ProcurementPriceRecommendation raw = raw(
                "SKU-1001",
                "1",
                new Inventory("5", "0"),
                List.of(
                        new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null),
                        new Candidate("P002", "12.90", PriceBasis.provider_sku, null),
                        new Candidate("P003", "45.67", PriceBasis.provider_sku, null)),
                new Recommendation("P001", "x"),
                0.9,
                false,
                List.of());

        // 倍数放大到 4：45.67 < 12.90×4，不再是离群 → 全部可比
        ProcurementPriceRecommendation lenient = ProcurementPricePolicy.enforce(raw, 4.0);
        assertThat(lenient.requiresHuman()).isFalse();
        assertThat(lenient.candidates()).hasSize(3);
        assertThat(lenient.excludedCandidates()).isEmpty();

        // 倍数必须 > 1.0
        assertThatThrownBy(() -> ProcurementPricePolicy.enforce(raw, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void excludedCandidatesAreNormalizedToScaleTwoPrices() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "1", new Inventory("5", "0"),
                        List.of(new Candidate("P001", "12.34", PriceBasis.sku_commercial_price, null)),
                        List.of(new ExcludedCandidate(
                                "P002", "12.9", PriceBasis.provider_sku, null,
                                ExclusionReason.mapping_stale, "映射已停用")),
                        new Recommendation("P001", "唯一可比候选"),
                        0.85,
                        false,
                        List.of()));

        assertThat(enforced.excludedCandidates().get(0).price()).isEqualTo("12.90");
    }

    private static ProcurementPriceRecommendation raw(
            String targetSku,
            String requestedQuantity,
            Inventory inventory,
            List<Candidate> candidates,
            Recommendation recommendation,
            double confidence,
            boolean requiresHuman,
            List<String> missingFields) {
        return raw(targetSku, requestedQuantity, inventory, candidates, List.of(), recommendation, confidence, requiresHuman, missingFields);
    }

    private static ProcurementPriceRecommendation raw(
            String targetSku,
            String requestedQuantity,
            Inventory inventory,
            List<Candidate> candidates,
            List<ExcludedCandidate> excludedCandidates,
            Recommendation recommendation,
            double confidence,
            boolean requiresHuman,
            List<String> missingFields) {
        return new ProcurementPriceRecommendation(
                targetSku,
                requestedQuantity,
                inventory,
                candidates,
                excludedCandidates,
                recommendation,
                missingFields,
                confidence,
                requiresHuman);
    }
}
