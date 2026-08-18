package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 来源 SKU 映射更新输入（至少修改一个业务字段）。 */
public record SourceSkuMappingPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId,
        @Pattern(regexp = Patterns.POSITIVE_DECIMAL_QUANTITY, message = "数量乘数必须为正数且最多三位小数")
                String quantityMultiplier,
        Boolean active) {
}
