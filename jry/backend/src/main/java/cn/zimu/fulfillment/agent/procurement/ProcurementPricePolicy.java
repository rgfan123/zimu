package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.sku.SkuCommercialPrice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 采购比价 Agent 的策略落地（agent-decision-layer 05）：模型输出经
 * {@link #enforce(ProcurementPriceRecommendation)} 确定性归一化。
 *
 * <p>策略（与票一致）：无候选 / 无价格（或价格非 decimal-string SCALE=2）/ 字段缺失 /
 * 低置信度（{@code confidence < }{@link #LOW_CONFIDENCE_THRESHOLD}）→
 * {@code requires_human=true}，且 {@code recommendation} 置空——只保留可复核的事实摘要
 * （candidates/missing_fields/inventory）。候选价格一律经 {@link SkuCommercialPrice} 规范化
 * 为 SCALE=2 decimal-string，格式不合法视为缺价格。模型声明 {@code requires_human=false}
 * 但缺推荐时同样视为字段缺失转人工。
 */
public final class ProcurementPricePolicy {

    /** 低于该置信度视为低置信度，强制转人工。 */
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.6;

    private ProcurementPricePolicy() {}

    public static ProcurementPriceRecommendation enforce(ProcurementPriceRecommendation raw) {
        if (raw == null) {
            return null;
        }
        List<String> missing = new ArrayList<>();
        if (raw.missingFields() != null) {
            raw.missingFields().stream().filter(Objects::nonNull).forEach(field -> addOnce(missing, field));
        }
        if (isBlank(raw.targetSku())) {
            addOnce(missing, "target_sku");
        }
        boolean noCandidates = raw.candidates().isEmpty();
        if (noCandidates) {
            addOnce(missing, "candidates");
        }
        List<Candidate> candidates = normalizePrices(raw.candidates());
        boolean priceMissing = !noCandidates && candidates.stream().anyMatch(ProcurementPricePolicy::invalidCandidate);
        if (priceMissing) {
            addOnce(missing, "price");
        }
        boolean basisMissing = !noCandidates
                && candidates.stream().anyMatch(candidate -> candidate != null && candidate.priceBasis() == null);
        if (basisMissing) {
            addOnce(missing, "price_basis");
        }
        if (raw.inventory() == null) {
            addOnce(missing, "inventory");
        }
        boolean lowConfidence = raw.confidence() < LOW_CONFIDENCE_THRESHOLD;
        boolean requiresHuman =
                raw.requiresHuman() || noCandidates || priceMissing || basisMissing || lowConfidence || !missing.isEmpty();
        if (requiresHuman) {
            // 只给可复核的事实摘要：不给建议，保留候选/库存/缺失项
            return new ProcurementPriceRecommendation(
                    raw.targetSku(),
                    raw.requestedQuantity(),
                    raw.inventory(),
                    candidates,
                    null,
                    missing,
                    raw.confidence(),
                    true);
        }
        if (raw.recommendation() == null) {
            // 声明无需人工却给不出推荐：视为字段缺失，转人工
            addOnce(missing, "recommendation");
            return new ProcurementPriceRecommendation(
                    raw.targetSku(),
                    raw.requestedQuantity(),
                    raw.inventory(),
                    candidates,
                    null,
                    missing,
                    raw.confidence(),
                    true);
        }
        return new ProcurementPriceRecommendation(
                raw.targetSku(),
                raw.requestedQuantity(),
                raw.inventory(),
                candidates,
                raw.recommendation(),
                missing,
                raw.confidence(),
                false);
    }

    /** 候选价格规范化：一律 decimal-string SCALE=2；格式不合法返回 null（视为缺价格）。 */
    private static List<Candidate> normalizePrices(List<Candidate> candidates) {
        List<Candidate> normalized = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                normalized.add(null);
                continue;
            }
            normalized.add(new Candidate(
                    candidate.providerCode(), normalizePrice(candidate.price()), candidate.priceBasis(), candidate.note()));
        }
        return normalized;
    }

    private static String normalizePrice(String price) {
        if (price == null) {
            return null;
        }
        try {
            return SkuCommercialPrice.text(SkuCommercialPrice.parse(price, "price"));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean invalidCandidate(Candidate candidate) {
        return candidate == null || candidate.price() == null || candidate.price().isBlank();
    }

    private static void addOnce(List<String> missing, String field) {
        if (!missing.contains(field)) {
            missing.add(field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
