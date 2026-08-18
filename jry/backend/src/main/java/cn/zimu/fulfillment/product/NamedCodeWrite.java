package cn.zimu.fulfillment.product;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 品类创建输入。 */
public record NamedCodeWrite(
        @NotBlank(message = "编码不能为空") @Size(max = 64, message = "编码超长") String code,
        @NotBlank(message = "名称不能为空") @Size(max = 128, message = "名称超长") String name,
        Boolean active) {
}
