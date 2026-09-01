package cn.zimu.fulfillment.inventory;

import java.time.Instant;

public record InventoryOverviewItem(
        String providerId,
        String providerCode,
        String providerName,
        String providerType,
        String skuId,
        String skuCode,
        String productName,
        String specification,
        String unit,
        String quantityUnit,
        String warehouseCode,
        String observationStatus,
        Integer totalQuantity,
        Integer availableQuantity,
        Integer unavailableQuantity,
        Instant observedAt,
        Long observationAgeSeconds,
        String freshnessStatus,
        String sourceType) {}
