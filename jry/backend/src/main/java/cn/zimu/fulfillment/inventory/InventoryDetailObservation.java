package cn.zimu.fulfillment.inventory;

import java.time.Instant;

public record InventoryDetailObservation(
        String observationStatus,
        String totalQuantity,
        String availableQuantity,
        String unavailableQuantity,
        String quantityUnit,
        Instant observedAt,
        Long observationAgeSeconds,
        Instant expiresAt,
        String freshnessStatus,
        String sourceType,
        String dataMode) {}
