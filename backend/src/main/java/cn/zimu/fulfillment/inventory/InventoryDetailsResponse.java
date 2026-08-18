package cn.zimu.fulfillment.inventory;

import java.time.Instant;
import java.util.List;

public record InventoryDetailsResponse(
        InventoryDetailContext context,
        InventoryDetailObservation observation,
        Instant queryTime,
        String freshnessPolicy,
        List<InventoryDetailCapability> capabilities) {}
