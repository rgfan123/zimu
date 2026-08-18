package cn.zimu.fulfillment.order.dto;

import java.time.Instant;

/** 订单列表摘要。 */
public record OrderSummaryDto(
        String id,
        String orderNo,
        String sourceChannel,
        String sourceRef,
        String customerId,
        String customerName,
        String receiverName,
        String orderStatus,
        String processingStage,
        String processingHealth,
        int completedCount,
        int totalCount,
        String attentionReason,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
