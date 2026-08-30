package cn.zimu.fulfillment.masterdata;

import java.util.List;

/** MCP 商品档案查询的领域投影：保留全部 47 列业务数据，不暴露 Excel/快照存储元数据。 */
public record ProductArchiveSummary(
        String productName,
        String brand,
        String specificationG,
        String barcode,
        String meatType,
        String material,
        String status,
        boolean linked,
        String skuCode,
        String skuId,
        List<CostingField> costing) {

    /** 身份列 A..G 之外的成本列：仅保留业务列头与原值，顺序与源表一致。 */
    public record CostingField(String name, String value) {}
}
