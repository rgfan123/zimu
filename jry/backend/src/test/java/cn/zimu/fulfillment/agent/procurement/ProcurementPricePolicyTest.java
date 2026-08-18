package cn.zimu.fulfillment.agent.procurement;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Inventory;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.PriceBasis;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Recommendation;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 05 — 采购比价策略落地（agent-decision-layer 05）：确定性归一化断言——
 * 无候选/无价格/字段缺失/低置信度全部 requires_human=true，且 recommendation 置空
 * （只给可复核事实）；价格统一 decimal-string SCALE=2；模型声明无需人工却缺推荐时转人工。
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
    }

    @Test
    void missingPriceForcesRequiresHuman() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", null, PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price");
        assertThat(enforced.recommendation()).isNull();
    }

    @Test
    void invalidPriceScaleForcesRequiresHumanAsMissingPrice() {
        ProcurementPriceRecommendation enforced = ProcurementPricePolicy.enforce(
                raw("SKU-1001", "2", new Inventory("0", "2"),
                        List.of(new Candidate("P001", "12.345", PriceBasis.sku_commercial_price, null)),
                        new Recommendation("P001", "x"), 0.9, false, List.of()));

        // 12.345 不满足 SCALE=2：视为缺价格，候选价格被清空，转人工
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.missingFields()).contains("price");
        assertThat(enforced.candidates().get(0).price()).isNull();
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

    private static ProcurementPriceRecommendation raw(
            String targetSku,
            String requestedQuantity,
            Inventory inventory,
            List<Candidate> candidates,
            Recommendation recommendation,
            double confidence,
            boolean requiresHuman,
            List<String> missingFields) {
        return new ProcurementPriceRecommendation(
                targetSku, requestedQuantity, inventory, candidates, recommendation, missingFields, confidence, requiresHuman);
    }
}
