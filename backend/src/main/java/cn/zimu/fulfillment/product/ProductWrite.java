package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 商品创建输入。 */
public record ProductWrite(
        @NotBlank(message = "商品编码不能为空") @Size(max = 64, message = "商品编码超长") String productCode,
        @NotBlank(message = "商品名称不能为空") @Size(max = 128, message = "商品名称超长") String productName,
        @NotNull(message = "品类不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        Boolean active) {
}
