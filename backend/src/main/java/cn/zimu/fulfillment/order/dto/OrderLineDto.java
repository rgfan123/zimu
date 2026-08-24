package cn.zimu.fulfillment.order.dto;

import java.util.List;

/** 订单行详情。 */
public record OrderLineDto(
        String id,
        int lineNo,
        String lineType,
        String bundleId,
        String skuId,
        String skuCode,
        String providerId,
        String productName,
        String specification,
        String unit,
        String sourceQuantity,
        String mappingMultiplier,
        String requestedQuantity,
        String processingStage,
        String exceptionCode,
        List<OrderLineComponentDto> components) {
}
