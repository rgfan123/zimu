package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.Map;

/** 订单版本快照。 */
public record OrderVersionDto(
        long versionNo,
        String sourceVersion,
        String changeReason,
        String triggeredBy,
        Map<String, Object> snapshot,
        Instant createdAt) {
}
