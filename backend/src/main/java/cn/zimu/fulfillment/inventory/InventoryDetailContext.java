package cn.zimu.fulfillment.inventory;

public record InventoryDetailContext(
        String providerId,
        String providerCode,
        String providerName,
        String providerType,
        String skuId,
        String skuCode,
        String productName,
        String specification,
        String unit,
        String providerSkuCode,
        String warehouseCode) {}
