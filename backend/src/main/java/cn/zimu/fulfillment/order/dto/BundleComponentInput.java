package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 礼包组件输入。 */
public record BundleComponentInput(
        @Size(max = 64, message = "SKU 编码超长") String skuCode,
        @Size(max = 128, message = "来源 SKU 标识超长") String sourceSkuRef,
        @NotBlank(message = "组件名称不能为空") @Size(max = 255, message = "组件名称超长") String productName,
        @NotBlank(message = "组件规格不能为空") @Size(max = 255, message = "组件规格超长") String specification,
        @NotBlank(message = "组件单位不能为空") @Size(max = 32, message = "组件单位超长") String unit,
        @NotNull(message = "单份用量不能为空") @Positive(message = "单份用量必须为正整数")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class)
                Integer quantityPerBundle) {

    public BundleComponentInput {
        if (quantityPerBundle == null || quantityPerBundle <= 0) {
            throw new IllegalArgumentException("单份用量必须为正整数");
        }
    }
}
