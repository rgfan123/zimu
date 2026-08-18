package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 履约方 SKU 映射创建输入。 */
public record ProviderSkuMappingWrite(
        @NotNull(message = "履约方不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "履约方标识符无效") String providerId,
        @NotNull(message = "内部 SKU 不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId,
        @NotBlank(message = "履约方商品编码不能为空") @Size(max = 128, message = "履约方商品编码超长") String providerSkuCode,
        @Size(min = 1, max = 128, message = "商家 SKU 编码不能为空且不得超长") String merchantSkuCode,
        @Size(max = 255, message = "履约方商品名称超长") String providerSkuName,
        @Pattern(regexp = Patterns.POSITIVE_INTEGER_QUANTITY, message = "京东件数换算必须为正整数件数")
                String jdPiecesPerUnit,
        Boolean active) {
}
