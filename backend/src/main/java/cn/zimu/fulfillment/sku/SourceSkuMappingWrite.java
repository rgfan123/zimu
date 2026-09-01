package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 来源 SKU 映射创建输入。 */
public record SourceSkuMappingWrite(
        @NotNull(message = "来源渠道不能为空") SourceChannel sourceChannel,
        @NotBlank(message = "来源 SKU 标识不能为空") @Size(max = 128, message = "来源 SKU 标识超长") String sourceSkuRef,
        @Size(max = 255, message = "来源商品名称超长") String sourceSkuName,
        @NotNull(message = "内部 SKU 不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId,
        @NotNull(message = "数量乘数不能为空") @Positive(message = "数量乘数必须为正整数")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer quantityMultiplier,
        Boolean active) {
}
