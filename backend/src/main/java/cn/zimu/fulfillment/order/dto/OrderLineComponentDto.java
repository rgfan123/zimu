package cn.zimu.fulfillment.order.dto;

/** 礼包组件详情。 */
public record OrderLineComponentDto(
        String id,
        String skuId,
        String productName,
        String specification,
        String unit,
        int quantityPerBundle,
        int totalQuantity) {
}
