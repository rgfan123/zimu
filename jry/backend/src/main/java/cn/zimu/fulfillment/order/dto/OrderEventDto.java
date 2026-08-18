package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.Map;

/** 时间线事件。 */
public record OrderEventDto(
        String id,
        long sequenceNo,
        String eventTypeCode,
        String orderLineId,
        String fulfillmentId,
        String shipmentId,
        String procurementTicketId,
        String operator,
        Map<String, Object> payload,
        Instant createdAt) {
}
