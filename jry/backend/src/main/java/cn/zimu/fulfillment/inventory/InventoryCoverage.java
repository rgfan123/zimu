package cn.zimu.fulfillment.inventory;

import java.time.Instant;

public record InventoryCoverage(
        long providerCount,
        long observedProviderCount,
        long skuCount,
        long observedSkuCount,
        long warehouseCount,
        Instant latestObservedAt,
        long staleCount,
        Instant oldestObservedAt,
        boolean partial,
        String freshnessPolicy) {}
