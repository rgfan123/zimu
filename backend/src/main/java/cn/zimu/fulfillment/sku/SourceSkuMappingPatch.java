package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 来源 SKU 映射更新输入（至少修改一个业务字段）。 */
public record SourceSkuMappingPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId,
        @Positive(message = "数量乘数必须为正整数")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer quantityMultiplier,
        Boolean active) {
}
