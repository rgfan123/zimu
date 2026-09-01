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
        long completedCount,
        long totalCount,
        String attentionReason,
        Instant createdAt,
        Instant updatedAt,
        long version,
        /** 渠道平台上的真实下单时刻（与 settlement 分开）；来源缺失时为 null。 */
        Instant sourceOrderedAt,
        Receiver receiver,
        Settlement settlement,
        String remark,
        List<OrderLineDto> lines,
        List<ReviewCaseDto> reviewCases) {
}
