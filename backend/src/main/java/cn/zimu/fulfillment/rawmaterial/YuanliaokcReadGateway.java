package cn.zimu.fulfillment.rawmaterial;

import java.util.List;

/** 原料库存只读网关：唯一读路径是实时结存（票 09 取数面）。 */
public interface YuanliaokcReadGateway {

    /**
     * 实时结存（按物料聚合批次）。
     *
     * @param keyword 可选：物料名/编码模糊词；null 或空白 = 全量
     * @throws RawMaterialReadException 四类稳定失败之一
     */
    List<YuanliaokcStockRow> stock(String keyword);
}
