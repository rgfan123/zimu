package cn.zimu.fulfillment.inventory;

import java.time.Instant;

public record InventoryDetailObservation(
        String observationStatus,
        Integer totalQuantity,
        Integer availableQuantity,
        Integer unavailableQuantity,
        String quantityUnit,
        Instant observedAt,
        Long observationAgeSeconds,
        Instant expiresAt,
        String freshnessStatus,
        String sourceType,
        String dataMode) {}
