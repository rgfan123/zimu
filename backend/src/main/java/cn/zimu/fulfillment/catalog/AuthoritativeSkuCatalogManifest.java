package cn.zimu.fulfillment.catalog;

import java.util.List;

/** 由两份权威工作簿严格生成并冻结的京东商品目录。 */
record AuthoritativeSkuCatalogManifest(
        int schemaVersion,
        Source jdSource,
        Source priceSource,
        Expected expected,
        List<ExcludedSheet> excludedSheets,
        List<Item> items) {

    record Source(String fileName, String sheetName, String sha256, int dataRows) {}

    record Expected(
            int uniqueJdCodes,
            int duplicateCodeCount,
            int priceMatchedCount,
            int unpricedCount) {}

    record ExcludedSheet(String sheetName, int nonemptyRows, String reason) {}

    /** 价格相关字段仅为 JSON schema 兼容占位，必须恒为 null。 */
    record Item(
            String jdCode,
            String canonicalName,
            List<String> aliases,
            List<SourceRow> sourceRows,
            String priceMatchName,
            Integer priceSourceRow,
            String purchasePrice,
            String retailPrice,
            List<String> mappingDifferenceCodes) {}

    record SourceRow(
            int row,
            String caishixianName,
            Integer caishixianQuantity,
            String jufubaoName,
            Integer jufubaoQuantity,
            String jdName) {}
}
