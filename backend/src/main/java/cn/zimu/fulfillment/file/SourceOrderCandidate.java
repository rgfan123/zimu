package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import java.math.BigDecimal;
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
        AuditActorType actor,
        List<SourceMappingSnapshot> sourceMappings) {

    SourceOrderCandidate {
        rows = List.copyOf(rows);
        sourceMappings = sourceMappings == null ? List.of() : List.copyOf(sourceMappings);
    }

    SourceOrderCandidate(
            String candidateKey,
            CanonicalOrderInput order,
            List<CandidateRow> rows,
            String createIdempotencyKey,
            AuditActorType actor) {
        this(candidateKey, order, rows, createIdempotencyKey, actor, List.of());
    }

    record CandidateRow(long rawImportRowId, int partitionCount) {
        CandidateRow {
            if (rawImportRowId <= 0 || partitionCount <= 0) {
                throw new IllegalArgumentException("来源候选行标识与分片数必须为正数");
            }
        }
    }

    /** 上传时观察到的来源映射事实；确认时必须与当前长期映射一致。 */
    record SourceMappingSnapshot(
            int itemIndex,
            String sourceSkuRef,
            Long skuId,
            String skuCode,
            BigDecimal quantityMultiplier) {
        SourceMappingSnapshot {
            if (itemIndex < 0 || sourceSkuRef == null || sourceSkuRef.isBlank()) {
                throw new IllegalArgumentException("来源映射快照缺少商品行位置或来源编码");
            }
        }
    }
}
