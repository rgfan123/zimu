package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.Candidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExcludedCandidate;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceRecommendation.ExclusionReason;
import cn.zimu.fulfillment.sku.SkuCommercialPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 采购比价 Agent 的策略落地（agent-decision-layer 05，01 票扩展）：模型输出经
 * {@link #enforce(ProcurementPriceRecommendation)} 确定性归一化。
 *
 * <p>01 票新增「不可比候选」三规则（并集，各带独立理由标签 {@link ExclusionReason}）：
 * <ol>
 *   <li><b>价格离群</b>（{@code price_outlier}）：与同组候选<b>中位数</b>偏离超过
 *       {@link #PRICE_OUTLIER_MULTIPLE} 倍（价格 &gt; 中位数×倍数 或 价格×倍数 &lt; 中位数）；
 *       只有候选池（有价格且映射有效）≥ {@link #OUTLIER_MIN_POOL_SIZE} 时才判离群——两三例
 *       无统计学意义，全部保留可比；</li>
 *   <li><b>价格缺失</b>（{@code price_missing}）：候选没有可用价格（null/空白/非 SCALE=2），
 *       不可参与比价——同时保持「整体转人工」（缺价是数据缺口，不允许在信息不全时自动推荐）；</li>
 *   <li><b>映射失效</b>（{@code mapping_stale}）：候选来自已停用或过期的履约方 SKU 映射，
 *       该事实只有模型在工具结果（{@code list_provider_skus} 的 {@code active=false}）里能看到，
 *       策略采信模型声明并保留其可读说明。</li>
 * </ol>
 *
 * <p>策略（与票一致，沿用既有语义）：无候选（可比候选为空）/ 字段缺失 / 低置信度
 * （{@code confidence < }{@link #LOW_CONFIDENCE_THRESHOLD}）→ {@code requires_human=true}，
 * 且 {@code recommendation} 置空——只保留可复核的事实摘要
 * （candidates/excluded_candidates/missing_fields/inventory）。候选价格一律经
 * {@link SkuCommercialPrice} 规范化为 SCALE=2 decimal-string，格式不合法视为缺价格。
 * 模型声明 {@code requires_human=false} 但缺推荐、或推荐落在被剔除候选上
 * （推荐只在可比候选中产生）时同样视为字段缺失转人工。
 *
 * <p>离群判定为策略的确定性重算（不采信模型的 {@code price_outlier}/{@code price_missing}
 * 声明，避免模型误剔正确候选）；模型只负责把「映射失效」候选放进
 * {@code excluded_candidates} 并声明 {@code mapping_stale}。倍数经
 * {@link #enforce(ProcurementPriceRecommendation, double)} 可配置（默认
 * {@link #PRICE_OUTLIER_MULTIPLE}=2.0，见常量 javadoc 的依据），由
 * {@code app.agent.procurement-price.outlier-multiple} 覆盖。
 */
public final class ProcurementPricePolicy {

    /** 低于该置信度视为低置信度，强制转人工。 */
    public static final double LOW_CONFIDENCE_THRESHOLD = 0.6;

    /**
     * 价格离群判定倍数（默认 2.0）：候选价格 &gt; 组中位数×2 或 &lt; 组中位数÷2 即剔除。
     *
     * <p>依据：比价候选价格一般落在同 SKU 的进货价附近；超过中位数的 2 倍（或不足一半）
     * 已远离正常区间（如同 SKU 竞品价差通常 &lt;±50%），且 2 倍阈值把「明显异常」与
     * 「正常价差」分开——太低（如 1.2 倍）会把正常促销价差误剔，太高（如 3 倍）会漏掉
     * 明显的录入错误/渠道差异报价。倍数要求 &gt; 1.0，且仅当候选池 ≥ 3 例时启用
     * （见 {@link #OUTLIER_MIN_POOL_SIZE}）。
     */
    public static final double PRICE_OUTLIER_MULTIPLE = 2.0;

    /** 候选池至少 3 例才做离群判定：1~2 例无中位数统计意义，全部保留可比。 */
    public static final int OUTLIER_MIN_POOL_SIZE = 3;

    private ProcurementPricePolicy() {}

    /** 按默认离群倍数（{@link #PRICE_OUTLIER_MULTIPLE}）归一化。 */
    public static ProcurementPriceRecommendation enforce(ProcurementPriceRecommendation raw) {
        return enforce(raw, PRICE_OUTLIER_MULTIPLE);
    }

    /**
     * 按给定离群倍数归一化（配置项：默认 {@link #PRICE_OUTLIER_MULTIPLE}，
     * 由 {@code app.agent.procurement-price.outlier-multiple} 注入）。
     */
    public static ProcurementPriceRecommendation enforce(ProcurementPriceRecommendation raw, double outlierMultiple) {
        if (raw == null) {
            return null;
        }
        if (outlierMultiple <= 1.0) {
            throw new IllegalArgumentException("离群倍数必须大于 1.0，实际: " + outlierMultiple);
        }
        List<String> missing = new ArrayList<>();
        if (raw.missingFields() != null) {
            raw.missingFields().stream().filter(Objects::nonNull).forEach(field -> addOnce(missing, field));
        }
        if (isBlank(raw.targetSku())) {
            addOnce(missing, "target_sku");
        }

        // 1) 收集全部候选（可比声明 + 被剔除声明），统一规范化价格
        List<Classified> classified = classify(raw, outlierMultiple);

        // 2) 按理由标签分桶：可比候选 / 被剔除候选
        List<Candidate> comparable = new ArrayList<>();
        List<ExcludedCandidate> excluded = new ArrayList<>();
        for (Classified item : classified) {
            if (item.reason() == null) {
                comparable.add(item.asCandidate());
            } else {
                excluded.add(item.asExcluded());
            }
        }

        // 3) 缺失项与转人工判定
        boolean noCandidates = comparable.isEmpty();
        if (noCandidates) {
            addOnce(missing, "candidates");
        }
        boolean anyPriceMissing = excluded.stream()
                .anyMatch(candidate -> candidate.exclusionReason() == ExclusionReason.price_missing);
        if (anyPriceMissing) {
            // 缺价是数据缺口：保持既有「整体转人工」语义（01 票前提假设 2）
            addOnce(missing, "price");
        }
        boolean basisMissing = classified.stream()
                .anyMatch(item -> item.candidate().priceBasis() == null);
        if (basisMissing) {
            addOnce(missing, "price_basis");
        }
        if (raw.inventory() == null) {
            addOnce(missing, "inventory");
        }
        boolean lowConfidence = raw.confidence() < LOW_CONFIDENCE_THRESHOLD;
        boolean requiresHuman = raw.requiresHuman()
                || noCandidates
                || anyPriceMissing
                || basisMissing
                || lowConfidence
                || !missing.isEmpty();
        if (requiresHuman) {
            // 只给可复核的事实摘要：不给建议，保留候选/被剔除候选/库存/缺失项
            return new ProcurementPriceRecommendation(
                    raw.targetSku(),
                    raw.requestedQuantity(),
                    raw.inventory(),
                    comparable,
                    excluded,
                    null,
                    missing,
                    raw.confidence(),
                    true);
        }
        ProcurementPriceRecommendation.Recommendation recommendation = raw.recommendation();
        if (recommendation == null || !isComparableProvider(comparable, recommendation.providerCode())) {
            // 声明无需人工却给不出推荐，或推荐落在被剔除候选上（推荐只在可比候选中产生）：
            // 视为字段缺失，转人工
            addOnce(missing, "recommendation");
            return new ProcurementPriceRecommendation(
                    raw.targetSku(),
                    raw.requestedQuantity(),
                    raw.inventory(),
                    comparable,
                    excluded,
                    null,
                    missing,
                    raw.confidence(),
                    true);
        }
        return new ProcurementPriceRecommendation(
                raw.targetSku(),
                raw.requestedQuantity(),
                raw.inventory(),
                comparable,
                excluded,
                recommendation,
                missing,
                raw.confidence(),
                false);
    }

    // ------------------------------------------------------------------
    // 分类：三规则并集（price_outlier / price_missing / mapping_stale）
    // ------------------------------------------------------------------

    private static List<Classified> classify(ProcurementPriceRecommendation raw, double outlierMultiple) {
        List<Classified> classified = new ArrayList<>();
        // 模型声明的可比候选：先按声明顺序处理，理由由策略重算
        for (Candidate candidate : raw.candidates()) {
            classified.add(classifyOne(candidate, null));
        }
        // 模型声明的被剔除候选：采信 mapping_stale（策略无法从候选本身得知映射状态），
        // price_outlier / price_missing 由策略重算，声明不一致时回到可比
        for (ExcludedCandidate candidate : raw.excludedCandidates()) {
            ExclusionReason declared = candidate.exclusionReason();
            classified.add(classifyOne(
                    new Candidate(
                            candidate.providerCode(),
                            candidate.price(),
                            candidate.priceBasis(),
                            candidate.note()),
                    declared == ExclusionReason.mapping_stale ? declared : null));
        }
        // 离群判定：以「有价格且映射有效」的候选池中位数重算
        List<Classified> pricedPool = classified.stream()
                .filter(item -> item.reason() == null)
                .toList();
        if (pricedPool.size() >= OUTLIER_MIN_POOL_SIZE) {
            BigDecimal median = median(pricedPool.stream()
                    .map(Classified::price)
                    .toList());
            if (median != null && median.signum() > 0) {
                for (Classified item : classified) {
                    if (item.reason() != null || item.price() == null) {
                        continue;
                    }
                    if (isOutlier(item.price(), median, outlierMultiple)) {
                        item.exclude(
                                ExclusionReason.price_outlier,
                                String.format(
                                        "与同组候选中位数偏离超过 %.1f 倍（中位数 %s，该候选价格 %s）",
                                        outlierMultiple,
                                        SkuCommercialPrice.text(median),
                                        item.candidate().price()));
                    }
                }
            }
        }
        return classified;
    }

    private static Classified classifyOne(Candidate candidate, ExclusionReason declared) {
        if (candidate == null) {
            // 空候选视同无可用价格：剔除并转人工（与既有 invalidCandidate 语义一致）
            return new Classified(
                    new Candidate(null, null, null, null),
                    ExclusionReason.price_missing,
                    "候选为空（无可用价格），不可参与比价");
        }
        BigDecimal price = normalizePrice(candidate.price());
        if (price == null) {
            // 无可用价格（null/空白/非 SCALE=2）：不可参与比价
            return new Classified(
                    new Candidate(candidate.providerCode(), null, candidate.priceBasis(), candidate.note()),
                    ExclusionReason.price_missing,
                    "无可用价格（未定价或价格缺失），不可参与比价");
        }
        if (declared == ExclusionReason.mapping_stale) {
            return new Classified(
                    new Candidate(candidate.providerCode(), SkuCommercialPrice.text(price), candidate.priceBasis(), candidate.note()),
                    ExclusionReason.mapping_stale,
                    "履约方 SKU 映射已停用或过期（工具返回 active=false）");
        }
        return new Classified(
                new Candidate(
                        candidate.providerCode(),
                        SkuCommercialPrice.text(price),
                        candidate.priceBasis(),
                        candidate.note()),
                null,
                null);
    }

    /** 候选价格规范化：一律 decimal-string SCALE=2；格式不合法返回 null（视为缺价格）。 */
    private static BigDecimal normalizePrice(String price) {
        if (price == null) {
            return null;
        }
        try {
            return SkuCommercialPrice.parse(price, "price");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isOutlier(BigDecimal price, BigDecimal median, double multiple) {
        BigDecimal mult = BigDecimal.valueOf(multiple);
        // 对称判据：price > median×multiple 或 price×multiple < median
        return price.compareTo(median.multiply(mult)) > 0
                || price.multiply(mult).compareTo(median) < 0;
    }

    /** 中位数：奇数取中间值；偶数取中间两值平均（SCALE=2，HALF_UP）。 */
    private static BigDecimal median(List<BigDecimal> prices) {
        List<BigDecimal> sorted = new ArrayList<>(prices);
        sorted.sort(Comparator.naturalOrder());
        int size = sorted.size();
        if (size == 0) {
            return null;
        }
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1)
                .add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static boolean isComparableProvider(List<Candidate> comparable, String providerCode) {
        if (providerCode == null) {
            return false;
        }
        return comparable.stream().anyMatch(candidate -> providerCode.equals(candidate.providerCode()));
    }

    private static void addOnce(List<String> missing, String field) {
        if (!missing.contains(field)) {
            missing.add(field);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 分类中间态：候选 + 策略重算后的理由标签与可读说明。 */
    private static final class Classified {

        private final Candidate candidate;
        private ExclusionReason reason;
        private String detail;

        private Classified(Candidate candidate, ExclusionReason reason, String detail) {
            this.candidate = candidate;
            this.reason = reason;
            this.detail = detail;
        }

        private Candidate candidate() {
            return candidate;
        }

        private ExclusionReason reason() {
            return reason;
        }

        private BigDecimal price() {
            try {
                return SkuCommercialPrice.parse(candidate.price(), "price");
            } catch (RuntimeException ex) {
                return null;
            }
        }

        private void exclude(ExclusionReason reason, String detail) {
            this.reason = reason;
            this.detail = detail;
        }

        private Candidate asCandidate() {
            return candidate;
        }

        private ExcludedCandidate asExcluded() {
            return new ExcludedCandidate(
                    candidate.providerCode(),
                    candidate.price(),
                    candidate.priceBasis(),
                    candidate.note(),
                    reason,
                    detail);
        }
    }
}
