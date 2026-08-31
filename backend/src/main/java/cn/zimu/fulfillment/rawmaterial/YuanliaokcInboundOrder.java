package cn.zimu.fulfillment.rawmaterial;

import java.math.BigDecimal;
import java.util.List;

/**
 * 上游入库单（{@code GET/POST /api/inbound-orders} 与 approve 响应共用的 _inbound_out 投影）
 * 的本地映射，字段白名单与上游契约逐一对应，未列字段一律不透传。
 *
 * <p>可空字段（supplierName/notes 与行上的批次号/件数/日期等）缺失是合法业务状态；
 * 必填字段缺失或类型不符按契约漂移拒绝。日期/时间保留上游原文（ISO 字符串），
 * 与 {@link YuanliaokcStockRow#earliestExpiry()} 同一做法——本侧不做时区再解释。
 */
public record YuanliaokcInboundOrder(
        long id,
        String orderNo,
        String supplierName,
        long warehouseId,
        String warehouseName,
        String status,
        String notes,
        String createdAt,
        List<Line> lines) {

    /** 入库单行：quantity_kg 是重量小数（BigDecimal 承载），piece_count 是整数件数。 */
    public record Line(
            long id,
            long materialId,
            String materialName,
            String batchNo,
            String supplierBatchNo,
            Long pieceCount,
            BigDecimal quantityKg,
            String productionDate,
            String expiryDate,
            Long createdBatchId) {}
}
