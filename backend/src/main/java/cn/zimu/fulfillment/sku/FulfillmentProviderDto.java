package cn.zimu.fulfillment.sku;

import java.util.Map;

/** 履约方 DTO；jd_config 为京东标识状态投影（pin 只标记存在性），非京东履约方为空 map。 */
public record FulfillmentProviderDto(
        String id,
        String providerCode,
        String providerName,
        String providerType,
        int trackingSlaMinutes,
        boolean active,
        long version,
        Map<String, Object> jdConfig,
        String wecomGroupChatId) {
}
