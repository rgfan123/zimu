package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 履约方 SKU 映射更新输入（至少修改一个业务字段）。 */
public record ProviderSkuMappingPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Size(min = 1, max = 128, message = "履约方商品编码不能为空且不得超长") String providerSkuCode,
        @Size(min = 1, max = 128, message = "商家 SKU 编码不能为空且不得超长") String merchantSkuCode,
        @Size(max = 255, message = "履约方商品名称超长") String providerSkuName,
        @Min(value = 1, message = "京东件数换算必须为正整数件数")
                @JsonDeserialize(using = PositiveCountQuantityDeserializer.class) Integer jdPiecesPerUnit,
        Boolean active) {
}
