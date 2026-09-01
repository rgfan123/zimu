package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 渠道礼包显式映射创建输入；一期一个来源单位恒等于一份礼包。 */
public record SourceBundleMappingWrite(
        @NotNull(message = "来源渠道不能为空") SourceChannel sourceChannel,
        @NotBlank(message = "来源礼包编号不能为空")
                @Size(max = 128, message = "来源礼包编号超长")
                String sourceBundleRef,
        @Size(max = 255, message = "来源礼包名称超长") String sourceBundleName,
        @Size(max = 128, message = "来源条码超长") String sourceBarcode,
        @Min(value = 1, message = "礼包来源包装乘数一期必须为 1")
                @Max(value = 1, message = "礼包来源包装乘数一期必须为 1")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer quantityMultiplier,
        @NotNull(message = "礼包不能为空")
                @Pattern(regexp = Patterns.IDENTIFIER, message = "礼包标识符无效")
                String bundleId,
        Boolean active) {}
