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
     * 按物料分类取结存。成品与原料同住上游一套物料档案，靠 {@code category} 分野
     * （成品固定 "成品"）；过滤交给上游，本地不筛，避免分页语义与上游漂移。
     *
     * @param onlyInStock false 时连零库存行一并返回——「还有没有货」的答案可能是 0
     */
    List<YuanliaokcStockRow> stock(String keyword, String category, boolean onlyInStock);

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
