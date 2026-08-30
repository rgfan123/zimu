package cn.zimu.fulfillment.sku;

/**
 * SKU 多条件检索筛选器：全部字段为 {@code null} 表示该维度不筛选，多个非空字段之间是“与”。
 *
 * <p>record 避免把四个语义不同的相邻字符串摊平到方法签名，降低条码、SKU 编码和标签错位后
 * 产生静默错答的风险。
 *
 * @param query 商品名、规格、SKU 编码或条码的模糊查询词；数据层负责添加通配符
 * @param providerId 履约方 ID
 * @param barcode 条码，精确匹配
 * @param skuCode SKU 编码，精确匹配
 * @param categoryId 商品品类 ID
 * @param tag 商品标签，JSON 数组元素级精确匹配
 * @param active 启用位；null 表示启用和停用 SKU 都返回
 */
public record SkuSearchFilter(
        String query,
        Long providerId,
        String barcode,
        String skuCode,
        Long categoryId,
        String tag,
        Boolean active) {}
