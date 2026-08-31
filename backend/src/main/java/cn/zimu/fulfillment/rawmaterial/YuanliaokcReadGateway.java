package cn.zimu.fulfillment.rawmaterial;

import java.util.List;

/** 原料库存只读网关：实时结存（票 09 取数面）+ 出入库 MCP 的入库单/流水读路径。 */
public interface YuanliaokcReadGateway {

    /**
     * 实时结存（按物料聚合批次）。
     *
     * @param keyword 可选：物料名/编码模糊词；null 或空白 = 全量
     * @throws RawMaterialReadException 四类稳定失败之一
     */
    List<YuanliaokcStockRow> stock(String keyword);

    /**
     * 入库单列表（上游上限 200 行，按 id 倒序）。
     *
     * @param status 可选：上游 SimpleDocStatus 枚举值原样传
     *     （draft/pending_approval/posted/rejected）；null 或空白 = 全部
     * @throws RawMaterialReadException 四类稳定失败之一
     */
    List<YuanliaokcInboundOrder> inboundOrders(String status);

    /**
     * 库存流水（按 id 倒序）。
     *
     * @param materialId 可选：按物料过滤；null = 全部
     * @param transactionType 可选：上游 TransactionType 枚举值原样传；null 或空白 = 全部
     * @param limit 条数（上游侧另有 500 上限）
     * @param offset 偏移
     * @throws RawMaterialReadException 四类稳定失败之一
     */
    List<YuanliaokcStockTransaction> stockTransactions(
            Long materialId, String transactionType, int limit, int offset);
}
