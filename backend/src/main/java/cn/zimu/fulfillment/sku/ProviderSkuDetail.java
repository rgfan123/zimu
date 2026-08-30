package cn.zimu.fulfillment.sku;

/** 履约方 SKU 映射只读详情：scope 明确区分真实外码与 TP 内部自路由。 */
public record ProviderSkuDetail(
        String id,
        String providerId,
        String providerCode,
        String providerName,
        String skuId,
        String skuCode,
        String providerSkuCode,
        ProviderSkuCodeScope providerSkuCodeScope,
        String merchantSkuCode,
        boolean active,
        String providerSkuName,
        String jdPiecesPerUnit) {
}
