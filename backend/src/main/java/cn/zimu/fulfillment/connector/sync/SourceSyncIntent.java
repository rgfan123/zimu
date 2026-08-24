package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.connector.SourceShipmentResult;

/** 已提交的外部写意图；不含完整 receiver，避免形成第二份 PII 持久快照。 */
public record SourceSyncIntent(
        long shipmentId,
        long orderId,
        String intentKey,
        String platformIntentKey,
        String checkHash,
        String artifactHash,
        String sourceLineRef,
        String carrierCode,
        String trackingNumber,
        long version,
        SourceShipmentResult result) {}
