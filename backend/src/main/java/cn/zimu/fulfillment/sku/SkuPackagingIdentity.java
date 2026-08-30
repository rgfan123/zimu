package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SKU 的结构化净含量与包装身份；四个字段要么全部缺省，要么完整出现。 */
public record SkuPackagingIdentity(
        BigDecimal netContentValue,
        String netContentUnit,
        Integer packageCount,
        String packageUnit) {

    private static final Pattern DISPLAY_SPECIFICATION = Pattern.compile(
            "^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(kg|g|ml|l|件)\\s*"
                    + "(?:(?:[/／])\\s*([^\\s]+)|(?:[*xX×]\\s*([1-9][0-9]*)\\s*([^\\s]+)?))?\\s*$",
            Pattern.CASE_INSENSITIVE);

    public static SkuPackagingIdentity optional(
            Object rawValue, String rawUnit, Integer rawCount, String rawPackageUnit) {
        boolean any = rawValue != null || rawUnit != null || rawCount != null || rawPackageUnit != null;
        if (!any) return null;
        if (rawValue == null || isBlank(rawUnit) || rawCount == null || isBlank(rawPackageUnit)) {
            throw invalid("net_content_value", "净含量与包装字段必须完整填写");
        }
        if (!(rawValue instanceof String text)) {
            throw invalid("net_content_value", "净含量必须使用 decimal string");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text.trim());
        } catch (NumberFormatException exception) {
            throw invalid("net_content_value", "净含量必须是正数，最多三位小数");
        }
        if (value.signum() <= 0 || value.scale() > 3 || value.precision() - value.scale() > 15) {
            throw invalid("net_content_value", "净含量必须是正数，最多十五位整数和三位小数");
        }
        if (rawCount <= 0) {
            throw invalid("package_count", "包装件数必须为正整数");
        }
        return new SkuPackagingIdentity(
                value,
                normalizeContentUnit(rawUnit),
                rawCount,
                rawPackageUnit.trim());
    }

    public static SkuPackagingIdentity required(
            Object rawValue, String rawUnit, Integer rawCount, String rawPackageUnit) {
        SkuPackagingIdentity identity = optional(rawValue, rawUnit, rawCount, rawPackageUnit);
        if (identity == null) {
            throw invalid("net_content_value", "净含量与包装字段必须完整填写或全部清空");
        }
        return identity;
    }

    public void applyTo(Sku sku) {
        sku.setNetContentValue(netContentValue);
        sku.setNetContentUnit(netContentUnit);
        sku.setPackageCount(packageCount);
        sku.setPackageUnit(packageUnit);
    }

    public static void clearFrom(Sku sku) {
        sku.setNetContentValue(null);
        sku.setNetContentUnit(null);
        sku.setPackageCount(null);
        sku.setPackageUnit(null);
    }

    public static SkuPackagingIdentity from(Sku sku) {
        return optional(
                decimalText(sku.getNetContentValue()),
                sku.getNetContentUnit(),
                sku.getPackageCount(),
                sku.getPackageUnit());
    }

    /** 只校验能确定解析的常见规格；自由文本留给后续数据质量复核，避免误判。 */
    public void validateDisplaySpecification(String specification) {
        if (specification == null) return;
        Matcher matcher = DISPLAY_SPECIFICATION.matcher(specification);
        if (!matcher.matches()) return;

        BigDecimal displayedValue = new BigDecimal(matcher.group(1));
        String displayedUnit = normalizeContentUnit(matcher.group(2));
        int displayedCount = matcher.group(4) == null ? 1 : Integer.parseInt(matcher.group(4));
        String displayedPackageUnit = matcher.group(3) != null ? matcher.group(3) : matcher.group(5);

        if (!sameContent(displayedValue, displayedUnit, netContentValue, netContentUnit)
                || displayedCount != packageCount
                || (displayedPackageUnit != null && !displayedPackageUnit.equals(packageUnit))) {
            throw invalid("specification", "规格展示与净含量或包装字段不一致");
        }
    }

    public static String decimalText(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String normalizeContentUnit(String value) {
        String normalized = value.trim();
        return normalized.chars().allMatch(character -> character < 128)
                ? normalized.toLowerCase(Locale.ROOT)
                : normalized;
    }

    private static boolean sameContent(
            BigDecimal leftValue, String leftUnit, BigDecimal rightValue, String rightUnit) {
        Measurement left = measurement(leftValue, leftUnit);
        Measurement right = measurement(rightValue, rightUnit);
        return left.kind().equals(right.kind()) && left.baseValue().compareTo(right.baseValue()) == 0;
    }

    private static Measurement measurement(BigDecimal value, String unit) {
        return switch (normalizeContentUnit(unit)) {
            case "kg" -> new Measurement("mass", value.multiply(BigDecimal.valueOf(1000)));
            case "g" -> new Measurement("mass", value);
            case "l" -> new Measurement("volume", value.multiply(BigDecimal.valueOf(1000)));
            case "ml" -> new Measurement("volume", value);
            default -> new Measurement("count:" + normalizeContentUnit(unit), value);
        };
    }

    private record Measurement(String kind, BigDecimal baseValue) {}

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(
                400,
                "INVALID_SKU_IDENTITY",
                "SKU 结构化身份无效",
                List.of(new FieldErrorItem(field, "Pattern", message)),
                Map.of());
    }
}
