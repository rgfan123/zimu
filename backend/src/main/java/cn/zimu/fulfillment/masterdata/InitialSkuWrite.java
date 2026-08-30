package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新商品随附的首个 SKU；商品标识由同一原子命令生成。 */
public record InitialSkuWrite(
        @NotNull(message = "履约方不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "履约方标识符无效") String providerId,
        @NotBlank(message = "规格不能为空") @Size(max = 200, message = "规格超长") String specification,
        @NotBlank(message = "单位不能为空") @Size(max = 32, message = "单位超长") String unit,
        Object netContentValue,
        @Size(max = 16, message = "净含量单位超长") String netContentUnit,
        @Min(value = 1, message = "包装件数必须为正整数") Integer packageCount,
        @Size(max = 32, message = "包装单位超长") String packageUnit,
        @Size(max = 64, message = "条码超长") String barcode,
        Object purchasePrice,
        Object retailPrice,
        Boolean active) {
}
