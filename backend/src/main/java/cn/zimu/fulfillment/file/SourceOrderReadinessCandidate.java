package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.order.dto.OrderItemInput;
import java.util.List;

/** 已成单来源批次保留的最小 SKU 门禁快照；不复制客户、收货人或结算信息。 */
record SourceOrderReadinessCandidate(
        String candidateKey,
        List<CandidateRow> rows,
        List<OrderItemInput> items,
        List<SourceOrderCandidate.SourceMappingSnapshot> sourceMappings) {

    SourceOrderReadinessCandidate {
        rows = List.copyOf(rows);
        items = List.copyOf(items);
        sourceMappings = sourceMappings == null ? List.of() : List.copyOf(sourceMappings);
    }

    static SourceOrderReadinessCandidate from(SourceOrderCandidate candidate) {
        return new SourceOrderReadinessCandidate(
                candidate.candidateKey(),
                candidate.rows().stream()
                        .map(row -> new CandidateRow(row.rawImportRowId(), row.partitionCount()))
                        .toList(),
                candidate.order().items(),
                candidate.sourceMappings());
    }

    record CandidateRow(long rawImportRowId, int partitionCount) {
        CandidateRow {
            if (rawImportRowId <= 0 || partitionCount <= 0) {
                throw new IllegalArgumentException("来源门禁快照的行标识与分片数必须为正数");
            }
        }
    }
}
