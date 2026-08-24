package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.math.BigDecimal;

/** 完整内部 Shipment 来源回传事实；收货信息只在即时检查结果中展示，不写审计 payload。 */
public record SourceSyncFacts(
        long shipmentId,
        long orderId,
        SourceChannel sourceChannel,
        String sourceRef,
        String sourceLineRef,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        BigDecimal orderedSourceQuantity,
        BigDecimal shippedSourceQuantity,
        BigDecimal internalShippedQuantity,
        String fulfillmentOutcome,
        String carrierCode,
        String carrierName,
        String carrierOutputValue,
        String trackingNumber) {}
