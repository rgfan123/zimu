package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 商品更新输入（至少修改一个业务字段）。 */
public record ProductPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Size(max = 128, message = "商品名称超长") String productName,
        @Pattern(regexp = Patterns.IDENTIFIER, message = "品类标识符无效") String categoryId,
        Boolean active) {
}
