package cn.zimu.fulfillment.sku;

import java.util.List;

/** 京东件数换算批量导入结果（幂等响应快照与审计摘要）。 */
public record ProviderSkuJdFactorImportResult(
        int acceptedCount, int skippedCount, List<ImportedRow> rows) {

    public record ImportedRow(String providerSkuCode, Integer jdPiecesPerUnit, String status) {}
}
