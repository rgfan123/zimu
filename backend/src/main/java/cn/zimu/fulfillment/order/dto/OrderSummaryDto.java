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
        long completedCount,
        long totalCount,
        String attentionReason,
        Instant createdAt,
        Instant updatedAt,
        long version,
        /** 渠道平台上的真实下单时刻（与 settlement_time 分开）；来源缺失时为 null。 */
        Instant sourceOrderedAt) {
}
