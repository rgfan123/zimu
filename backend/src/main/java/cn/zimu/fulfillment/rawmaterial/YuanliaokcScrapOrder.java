package cn.zimu.fulfillment.rawmaterial;

import java.math.BigDecimal;

/**
 * 上游报废单（{@code POST /api/scrap-orders} 与 approve 响应共用的 _scrap_out 投影）的本地映射。
 *
 * <p>字段白名单照上游契约：id/order_no/batch_id/batch_no/material_name/piece_count/
 * quantity_kg/reason/status/created_at；未列字段不透传。pieceCount 可空是合法业务状态。
 */
public record YuanliaokcScrapOrder(
        long id,
        String orderNo,
        long batchId,
        String batchNo,
        String materialName,
        Long pieceCount,
        BigDecimal quantityKg,
        String reason,
        String status,
        String createdAt) {}
