package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import java.util.List;

/**
 * 来源批次在正式订单创建前保存的确定性候选快照。
 *
 * <p>一个候选对应一个 CanonicalOrderInput；rows 保存每条来源原始行将展开成多少条
 * 订单行，使门禁解除后仍可原样恢复 raw row → order line 血缘。
 */
record SourceOrderCandidate(
        String candidateKey,
        CanonicalOrderInput order,
        List<CandidateRow> rows,
        String createIdempotencyKey,
        AuditActorType actor) {

    SourceOrderCandidate {
        rows = List.copyOf(rows);
    }

    record CandidateRow(long rawImportRowId, int partitionCount) {
        CandidateRow {
            if (rawImportRowId <= 0 || partitionCount <= 0) {
                throw new IllegalArgumentException("来源候选行标识与分片数必须为正数");
            }
        }
    }
}
