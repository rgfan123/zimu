package cn.zimu.fulfillment.rawmaterial;

import java.math.BigDecimal;

/**
 * 上游 {@code GET /api/stock} 一行结存的本地映射（yuanliaokc StockRow 契约）。
 *
 * <p>kg 结存是重量事实，天然带小数——商品数量整数纪律（V99）不适用于此；
 * 用 BigDecimal 承载并以 decimal-string 出 JSON，杜绝浮点串扰。
 * 可空字段（category/spec/pieceCount/earliestExpiry）缺失是合法业务状态；
 * 必填字段缺失或类型不符一律按契约漂移拒绝，不做默认值补齐。
 */
public record YuanliaokcStockRow(
        long materialId,
        String materialCode,
        String materialName,
        String category,
        String spec,
        String unit,
        Long pieceCount,
        BigDecimal currentKg,
        BigDecimal availableKg,
        BigDecimal frozenKg,
        long batchCount,
        String earliestExpiry,
        String status) {}
