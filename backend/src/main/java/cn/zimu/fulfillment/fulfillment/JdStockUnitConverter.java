package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import java.util.Map;

/**
 * 京东库存判定单位换算：系统离散数量 → 京东整数件数。
 *
 * <p>换算系数「1 系统单位 = N 件」按 SKU 存放在 provider_skus.external_codes 的保留键
 * {@value #FACTOR_CONFIG_KEY}。换算系数与两侧数量都必须是正整数，禁止任何取整。
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
    public record OutboundFactorValidation(OutboundFactorStatus status, Integer factor) {
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
    static final int DEFAULT_FACTOR = 1;

    private JdStockUnitConverter() {}

    /** 需求件数 = 系统数量 × 整数系数。 */
    static long requiredPieces(long systemQuantity, int piecesPerUnit) {
        return Math.multiplyExact(systemQuantity, (long) piecesPerUnit);
    }

    /**
     * 建单精确件数 = 系统数量 × 系数；结果必须为正整数（不含小数），否则返回 null 由调用方阻断。
     * 系统对建单数量既不四舍五入也不向上取整（spec: planQuantity must be an exact positive integer
     * after conversion）。
     */
    public static Long exactPiecesOrNull(Long systemQuantity, Integer piecesPerUnit) {
        if (systemQuantity == null || piecesPerUnit == null || systemQuantity <= 0 || piecesPerUnit <= 0) {
            return null;
        }
        try {
            return Math.multiplyExact(systemQuantity, piecesPerUnit.longValue());
        } catch (ArithmeticException exception) {
            return null;
        }
    }

    /**
     * 从 external_codes 解析换算系数；未配置时返回默认 1.000。
     * 配置存在但非法（非正数 / 不可解析）时返回 null，由调用方拒绝判定。
     */
    static Integer factorOrNull(Map<String, Object> externalCodes) {
        Object raw = externalCodes == null ? null : externalCodes.get(FACTOR_CONFIG_KEY);
        if (raw == null) {
            return DEFAULT_FACTOR;
        }
        return toPositiveCount(raw);
    }

    /**
     * 只解析显式配置的换算系数。缺少配置时返回 null，由业务用例根据单位决定是否允许‘件’=1；
     * 非‘件’单位不得借用 {@link #factorOrNull(Map)} 的历史默认值静默放行。
     */
    public static Integer explicitFactorOrNull(Map<String, Object> externalCodes) {
        Object raw = externalCodes == null ? null : externalCodes.get(FACTOR_CONFIG_KEY);
        if (raw == null) {
            return null;
        }
        return toPositiveCount(raw);
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
        Object raw = externalCodes.get(FACTOR_CONFIG_KEY);
        try {
            int factor = CountQuantity.fromPositiveFileValue(String.valueOf(raw));
            return new OutboundFactorValidation(OutboundFactorStatus.EXPLICIT_VALID, factor);
        } catch (InvalidCountQuantityException exception) {
            return new OutboundFactorValidation(
                    exception.reason() == CountQuantity.InvalidReason.FRACTIONAL
                            ? OutboundFactorStatus.NON_INTEGER
                            : OutboundFactorStatus.INVALID,
                    null);
        }
    }

    private static Integer toPositiveCount(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return CountQuantity.fromPositiveFileValue(String.valueOf(value));
        } catch (InvalidCountQuantityException ignored) {
            return null;
        }
    }
}
