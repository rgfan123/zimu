package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** SKU 进货价/零售价的 decimal-string 边界及标准化。 */
public final class SkuCommercialPrice {

    private static final int SCALE = 2;
    private static final String MESSAGE = "价格必须是非负 decimal string，最多十二位整数和两位小数";

    private SkuCommercialPrice() {}

    /** null 表示未定价；数值 JSON token 不会被隐式转为字符串。 */
    public static BigDecimal parse(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof String text) || !text.matches(Patterns.COMMERCIAL_PRICE)) {
            throw invalid(field);
        }
        try {
            return new BigDecimal(text).setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(field);
        }
    }

    public static String text(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.UNNECESSARY).toPlainString();
    }

    private static BusinessException invalid(String field) {
        return new BusinessException(
                400,
                "INVALID_COMMERCIAL_PRICE",
                "SKU 价格格式无效",
                List.of(new FieldErrorItem(field, "Pattern", MESSAGE)),
                Map.of());
    }
}
