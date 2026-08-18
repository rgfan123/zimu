package cn.zimu.fulfillment.inventory;

import java.util.List;

public record InventoryOverviewResponse(
        List<InventoryOverviewItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages,
        InventoryCoverage coverage) {}
