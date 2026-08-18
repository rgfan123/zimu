package cn.zimu.fulfillment.sku;

/** 履约方 SKU 映射只读详情：含内部 SKU 与履约方编码投影；只暴露已知外部编码键。 */
public record ProviderSkuDetail(
        String id,
        String providerId,
        String providerCode,
        String providerName,
        String skuId,
        String skuCode,
        String providerSkuCode,
        String merchantSkuCode,
        boolean active,
        String providerSkuName,
        String jdPiecesPerUnit) {
}
