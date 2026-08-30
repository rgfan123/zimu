package cn.zimu.fulfillment.catalog;

import java.util.List;

/** 管理端权威目录导入结果与幂等响应快照；审计日志只保留不含商品价格明细的摘要。 */
public record AuthoritativeSkuCatalogImportReport(
        String manifestSha256,
        String jdSourceSha256,
        String priceSourceSha256,
        int catalogRowCount,
        int uniqueJdCodeCount,
        int duplicateCodeCount,
        int priceMatchedCount,
        int unpricedCount,
        int createdProducts,
        int reusedProducts,
        int createdSkus,
        int reusedSkus,
        int updatedSkus,
        int createdProviderSkus,
        int reusedProviderSkus,
        int updatedProviderSkus,
        List<DuplicateCode> duplicateCodes,
        List<PricedItem> pricedItems,
        List<UnpricedItem> unpricedItems,
        List<MappingDifference> mappingDifferences,
        List<ExcludedSheet> excludedSheets) {

    public record DuplicateCode(String jdCode, List<Integer> sourceRows, List<String> jdNames) {}

    public record PricedItem(
            String jdCode,
            String canonicalName,
            List<Integer> sourceRows,
            String priceMatchName,
            int priceSourceRow) {}

    public record UnpricedItem(String jdCode, String canonicalName, List<Integer> sourceRows) {}

    public record MappingDifference(
            String jdCode, String canonicalName, List<Integer> sourceRows, List<String> differenceCodes) {}

    public record ExcludedSheet(String sheetName, int nonemptyRows, String reason) {}
}
