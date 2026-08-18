package cn.zimu.fulfillment.sku;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** 履约方更新输入（provider_code 不可变）。 */
public record FulfillmentProviderPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Size(min = 1, message = "履约方名称不能为空") String providerName,
        Integer trackingSlaMinutes,
        Boolean active,
        Map<String, Object> config) {
}
