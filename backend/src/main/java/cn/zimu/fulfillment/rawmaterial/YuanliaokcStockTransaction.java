package cn.zimu.fulfillment.rawmaterial;

import java.math.BigDecimal;

/**
 * 上游库存流水（{@code GET /api/transactions} TransactionOut 契约）的本地映射。
 *
 * <p>quantityChangeKg 天然可负（出库/报废/冲销为负数），因此不做符号断言；
 * quantityAfterKg 同样只要求是数值——上游按 6 位小数四舍五入维护结存，
 * 把舍入边缘或历史修正误判为契约漂移只会让流水页整体不可用。可空字段
 * （materialName、batchId、batchNo、sourceDocumentType、sourceDocumentId、notes、
 * operatorId）缺失是合法业务状态。
 */
public record YuanliaokcStockTransaction(
        long id,
        long materialId,
        String materialName,
        Long batchId,
        String batchNo,
        String transactionType,
        BigDecimal quantityChangeKg,
        BigDecimal quantityAfterKg,
        String sourceDocumentType,
        Long sourceDocumentId,
        String notes,
        Long operatorId,
        String createdAt) {}
