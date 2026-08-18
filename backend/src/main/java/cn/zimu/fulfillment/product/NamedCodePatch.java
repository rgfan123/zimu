package cn.zimu.fulfillment.product;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 品类更新输入（至少修改一个业务字段）。 */
public record NamedCodePatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Size(max = 128, message = "名称超长") String name,
        Boolean active) {
}
