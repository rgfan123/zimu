package cn.zimu.fulfillment.fulfillment;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 整个 Shipment 的原子物流接受命令：多个明细共享唯一 Tracking。 */
public record ShipmentTrackingBatchCommand(
        Long providerTrackingBatchId,
        long shipmentId,
        long orderId,
        List<Item> items,
        String logisticsCompanyCode,
        String logisticsCompanyName,
        String trackingNumber,
        Instant shippedAt,
        Map<String, ?> rawPayload,
        String changeReason) {

    public ShipmentTrackingBatchCommand {
        items = List.copyOf(items);
        rawPayload = rawPayload == null ? Map.of() : Map.copyOf(rawPayload);
    }

    public record Item(long fulfillmentId, long orderLineId, int shippedQuantity) {
    }
}
