package cn.zimu.fulfillment.fulfillment;

import java.time.Instant;
import java.util.Map;

/** 履约方回传经过文件/API 边界校验后的统一应用命令。 */
public record ShipmentTrackingCommand(
        Long providerTrackingBatchId,
        long shipmentId,
        long fulfillmentId,
        long orderLineId,
        long orderId,
        String result,
        int shippedQuantity,
        String logisticsCompanyCode,
        String logisticsCompanyName,
        String trackingNumber,
        Instant shippedAt,
        String failureReason,
        Map<String, ?> rawPayload) {}
