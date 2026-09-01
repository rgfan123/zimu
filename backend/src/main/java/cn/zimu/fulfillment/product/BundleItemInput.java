package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 静态礼包组件创建输入。 */
public record BundleItemInput(
        @NotNull(message = "组件 SKU 不能为空")
                @Pattern(regexp = Patterns.IDENTIFIER, message = "组件 SKU 标识符无效")
                String skuId,
        @NotNull(message = "单份用量不能为空") @Positive(message = "单份用量必须为正整数")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer quantityPerBundle,
        @Size(max = 64, message = "EMG 编码快照超长") String emgCodeSnapshot,
        @Size(max = 255, message = "内配原文超长") String sourceTextSnapshot) {}
