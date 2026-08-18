package cn.zimu.fulfillment.customer;

import java.util.List;

/** 京东客户编码批量导入结果（幂等响应快照与审计摘要）。 */
public record CustomerJdCodeImportResult(int acceptedCount, int skippedCount, List<ImportedRow> rows) {

    public record ImportedRow(String customerCode, String jdCustomerCode, String status) {}
}
