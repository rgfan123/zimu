package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.List;

/** 订单聚合详情。 */
public record OrderDetailDto(
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
        long version,
        Receiver receiver,
        Settlement settlement,
        String remark,
        List<OrderLineDto> lines,
        List<ReviewCaseDto> reviewCases) {
}
