package cn.zimu.fulfillment.sku;

import java.time.Instant;

/** SKU 只读详情投影：含商品与履约方归属；价格已按 {@link SkuCommercialPrice} SCALE=2 规范化。 */
public record SkuDetail(
        String id,
        String skuCode,
        String productId,
        String productCode,
        String productName,
        String categoryId,
        String specification,
        String unit,
        String barcode,
        String purchasePrice,
        String retailPrice,
        boolean active,
        String providerId,
        String providerCode,
        String providerName,
        String providerType,
        SkuFulfillmentReadiness readiness,
        Instant createdAt,
        Instant updatedAt) {
}
