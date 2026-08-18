package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 来源 SKU 映射创建输入。 */
public record SourceSkuMappingWrite(
        @NotNull(message = "来源渠道不能为空") SourceChannel sourceChannel,
        @NotBlank(message = "来源 SKU 标识不能为空") @Size(max = 128, message = "来源 SKU 标识超长") String sourceSkuRef,
        @Size(max = 255, message = "来源商品名称超长") String sourceSkuName,
        @NotNull(message = "内部 SKU 不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId,
        @NotBlank(message = "数量乘数不能为空")
                @Pattern(regexp = Patterns.POSITIVE_DECIMAL_QUANTITY, message = "数量乘数必须为正数且最多三位小数")
                String quantityMultiplier,
        Boolean active) {
}
