package cn.zimu.fulfillment.fulfillment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 京东库存判定单位换算：系统数量（order_lines.unit_snapshot 单位，NUMERIC(18,3)）→ 京东件数（整数件）。
 *
 * <p>换算系数「1 系统单位 = N 件」按 SKU 存放在 provider_skus.external_codes 的保留键
 * {@value #FACTOR_CONFIG_KEY}（JSON 数值，如 0.500 表示 1 盒 = 0.5 件）；未配置时默认 1.000。
 * 需求件数一律向上取整：京东按整件履约，任何非零尾数都须占用 1 件库存，避免低估需求造成超卖误判。
 */
public final class JdStockUnitConverter {

    public enum OutboundFactorStatus {
        DEFAULT_ONE,
        EXPLICIT_VALID,
        MISSING,
        INVALID,
        NON_INTEGER
    }

    /** 京东真实出库使用的单位换算判定；readiness 与建单必须复用同一结果。 */
    public record OutboundFactorValidation(OutboundFactorStatus status, BigDecimal factor) {
        public boolean valid() {
            return status == OutboundFactorStatus.DEFAULT_ONE
                    || status == OutboundFactorStatus.EXPLICIT_VALID;
        }
    }

    /** 采购工单与缺口数量使用的京东库存单位。 */
    public static final String PIECES_UNIT = "件";

    /** provider_skus.external_codes 中「每系统单位对应京东件数」的保留键。 */
    public static final String FACTOR_CONFIG_KEY = "jd_pieces_per_unit";

    /** 未配置换算系数时的默认值：1 系统单位 = 1 件。 */
    static final BigDecimal DEFAULT_FACTOR = new BigDecimal("1.000");

    private JdStockUnitConverter() {}

    /** 需求件数 = ceil(系统数量 × 系数)，向上取整到整数件。
     *
     * <p>仅用于库存判定等保守估算场景；建单请求不得取整（见 {@link #exactPiecesOrNull}）。
     */
    static BigDecimal requiredPieces(BigDecimal systemQuantity, BigDecimal piecesPerUnit) {
        return systemQuantity.multiply(piecesPerUnit).setScale(0, RoundingMode.CEILING);
    }

    /**
     * 建单精确件数 = 系统数量 × 系数；结果必须为正整数（不含小数），否则返回 null 由调用方阻断。
     * 系统对建单数量既不四舍五入也不向上取整（spec: planQuantity must be an exact positive integer
     * after conversion）。
     */
    public static BigDecimal exactPiecesOrNull(BigDecimal systemQuantity, BigDecimal piecesPerUnit) {
        if (systemQuantity == null || piecesPerUnit == null) {
            return null;
        }
        BigDecimal pieces = systemQuantity.multiply(piecesPerUnit);
        if (pieces.signum() <= 0) {
            return null;
        }
        if (pieces.stripTrailingZeros().scale() > 0) {
            return null;
        }
        return pieces.setScale(0);
    }

    /**
     * 从 external_codes 解析换算系数；未配置时返回默认 1.000。
     * 配置存在但非法（非正数 / 不可解析）时返回 null，由调用方拒绝判定。
     */
    static BigDecimal factorOrNull(Map<String, Object> externalCodes) {
        Object raw = externalCodes == null ? null : externalCodes.get(FACTOR_CONFIG_KEY);
        if (raw == null) {
            return DEFAULT_FACTOR;
        }
        BigDecimal factor = toDecimal(raw);
        return factor != null && factor.signum() > 0 ? factor : null;
    }

    /**
     * 只解析显式配置的换算系数。缺少配置时返回 null，由业务用例根据单位决定是否允许‘件’=1；
     * 非‘件’单位不得借用 {@link #factorOrNull(Map)} 的历史默认值静默放行。
     */
    public static BigDecimal explicitFactorOrNull(Map<String, Object> externalCodes) {
        Object raw = externalCodes == null ? null : externalCodes.get(FACTOR_CONFIG_KEY);
        if (raw == null) {
            return null;
        }
        BigDecimal factor = toDecimal(raw);
        return factor != null && factor.signum() > 0 ? factor : null;
    }

    /**
     * 校验京东建单的单位换算：未显式配置时只有“件”可按 1 放行；显式配置必须为正整数。
     */
    public static OutboundFactorValidation validateOutboundFactor(
            String systemUnit, Map<String, Object> externalCodes) {
        if (externalCodes == null || !externalCodes.containsKey(FACTOR_CONFIG_KEY)) {
            return PIECES_UNIT.equals(systemUnit)
                    ? new OutboundFactorValidation(OutboundFactorStatus.DEFAULT_ONE, DEFAULT_FACTOR)
                    : new OutboundFactorValidation(OutboundFactorStatus.MISSING, null);
        }
        BigDecimal factor = explicitFactorOrNull(externalCodes);
        if (factor == null) {
            return new OutboundFactorValidation(OutboundFactorStatus.INVALID, null);
        }
        if (factor.stripTrailingZeros().scale() > 0) {
            return new OutboundFactorValidation(OutboundFactorStatus.NON_INTEGER, factor);
        }
        return new OutboundFactorValidation(OutboundFactorStatus.EXPLICIT_VALID, factor);
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
