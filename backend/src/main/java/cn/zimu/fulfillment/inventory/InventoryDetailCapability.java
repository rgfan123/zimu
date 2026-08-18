package cn.zimu.fulfillment.inventory;

import java.util.List;

public record InventoryDetailCapability(
        String group,
        String label,
        String integrationStatus,
        String runtimeMode,
        String sourceType,
        String explanation,
        List<InventoryDetailTool> tools) {}
