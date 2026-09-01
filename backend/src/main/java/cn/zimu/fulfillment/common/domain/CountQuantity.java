package cn.zimu.fulfillment.common.domain;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * 离散商品数量的边界归一器。
 *
 * <p>JSON 写命令只允许整数 token；文件适配器可以精确兼容 {@code 3.000}
 * 这类数学整数。两条边界都必须拒绝真实小数、非正数和超出 int32 的值。
 */
public final class CountQuantity {

    private CountQuantity() {}

    public static int fromPositiveJsonInteger(BigInteger value) {
        if (value == null) {
            throw invalid(InvalidReason.MISSING, "数量不能为空");
        }
        try {
            int normalized = value.intValueExact();
            if (normalized <= 0) {
                throw invalid(InvalidReason.NON_POSITIVE, "数量必须为正整数");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw invalid(InvalidReason.OUT_OF_RANGE, "数量超出 int32 范围", exception);
        }
    }

    public static int fromNonNegativeJsonInteger(BigInteger value) {
        if (value == null) {
            throw invalid(InvalidReason.MISSING, "数量不能为空");
        }
        try {
            int normalized = value.intValueExact();
            if (normalized < 0) {
                throw invalid(InvalidReason.NON_POSITIVE, "数量不得为负数");
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw invalid(InvalidReason.OUT_OF_RANGE, "数量超出 int32 范围", exception);
        }
    }

    public static int fromPositiveFileValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(InvalidReason.MISSING, "数量不能为空");
        }

        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw invalid(InvalidReason.MALFORMED, "数量格式非法", exception);
        }
        if (parsed.signum() <= 0) {
            throw invalid(InvalidReason.NON_POSITIVE, "数量必须为正整数");
        }
        try {
            return parsed.intValueExact();
        } catch (ArithmeticException exception) {
            InvalidReason reason = parsed.stripTrailingZeros().scale() > 0
                    ? InvalidReason.FRACTIONAL
                    : InvalidReason.OUT_OF_RANGE;
            String message = reason == InvalidReason.FRACTIONAL
                    ? "商品数量必须为整数"
                    : "数量超出 int32 范围";
            throw invalid(reason, message, exception);
        }
    }

    public static int fromNonNegativeFileValue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalid(InvalidReason.MISSING, "数量不能为空");
        }
        final BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw invalid(InvalidReason.MALFORMED, "数量格式非法", exception);
        }
        if (parsed.signum() < 0) {
            throw invalid(InvalidReason.NON_POSITIVE, "数量不得为负数");
        }
        try {
            return parsed.intValueExact();
        } catch (ArithmeticException exception) {
            InvalidReason reason = parsed.stripTrailingZeros().scale() > 0
                    ? InvalidReason.FRACTIONAL
                    : InvalidReason.OUT_OF_RANGE;
            throw invalid(reason, reason == InvalidReason.FRACTIONAL
                    ? "商品数量必须为整数"
                    : "数量超出 int32 范围", exception);
        }
    }

    /**
     * Multiplies two positive item-level counts while keeping the persisted result inside int32.
     * Aggregate totals use int64 elsewhere; a single derived item count must never wrap.
     */
    public static int multiplyPositive(int left, int right) {
        if (left <= 0 || right <= 0) {
            throw invalid(InvalidReason.NON_POSITIVE, "数量乘算因子必须为正整数");
        }
        long product = (long) left * right;
        if (product > Integer.MAX_VALUE) {
            throw invalid(InvalidReason.OUT_OF_RANGE, "数量乘算结果超出 int32 范围");
        }
        return (int) product;
    }

    public enum InvalidReason {
        MISSING,
        MALFORMED,
        NON_POSITIVE,
        FRACTIONAL,
        OUT_OF_RANGE
    }

    public static final class InvalidCountQuantityException extends IllegalArgumentException {
        private final InvalidReason reason;

        private InvalidCountQuantityException(InvalidReason reason, String message) {
            super(message);
            this.reason = reason;
        }

        private InvalidCountQuantityException(InvalidReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public InvalidReason reason() {
            return reason;
        }
    }

    private static InvalidCountQuantityException invalid(InvalidReason reason, String message) {
        return new InvalidCountQuantityException(reason, message);
    }

    private static InvalidCountQuantityException invalid(
            InvalidReason reason, String message, Throwable cause) {
        return new InvalidCountQuantityException(reason, message, cause);
    }
}
