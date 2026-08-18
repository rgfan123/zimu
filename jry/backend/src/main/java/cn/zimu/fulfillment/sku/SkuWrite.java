package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** SKU 创建输入。 */
public record SkuWrite(
        @NotNull(message = "履约方不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "履约方标识符无效") String providerId,
        @NotNull(message = "商品不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "商品标识符无效") String productId,
        @NotBlank(message = "规格不能为空") @Size(max = 200, message = "规格超长") String specification,
        @NotBlank(message = "单位不能为空") @Size(max = 32, message = "单位超长") String unit,
        @Size(max = 64, message = "条码超长") String barcode,
        Object purchasePrice,
        Object retailPrice,
        Boolean active) {
}
