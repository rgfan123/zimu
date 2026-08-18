package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.Map;

public record OperationalAlertDto(
        String id,
        String alertNo,
        String alertType,
        String severity,
        String status,
        String orderId,
        String orderLineId,
        String fulfillmentId,
        String shipmentId,
        String message,
        Map<String, Object> detail,
        String acknowledgedBy,
        Instant acknowledgedAt,
        Instant resolvedAt,
        long version,
        Instant createdAt) {}
