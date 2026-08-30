package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 永久来源商品标识必须能跨订单复用，不能使用草稿、订单、纠正或行级血缘编号。 */
public final class SourceSkuRefPolicy {

    private static final List<Pattern> NON_REUSABLE_PATTERNS = List.of(
            Pattern.compile("^WECOM-DRAFT-.+$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^CORR-SKU-.+$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^OD-\\d+-\\d+(?:-L\\d+)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^ORD-[0-9A-F]{32}(?:-L\\d+)?$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^(?:ORDER-)?LINE-\\d+$", Pattern.CASE_INSENSITIVE));

    private SourceSkuRefPolicy() {}

    public static void requireReusable(String sourceSkuRef) {
        String normalized = sourceSkuRef == null ? "" : sourceSkuRef.trim();
        if (NON_REUSABLE_PATTERNS.stream().noneMatch(pattern -> pattern.matcher(normalized).matches())) {
            return;
        }
        throw new BusinessException(
                422,
                "NON_REUSABLE_SOURCE_SKU_REF",
                "一次性草稿、订单、纠正或订单行编号不能作为永久来源商品标识",
                List.of(),
                Map.of("source_sku_ref", normalized));
    }
}
