package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import cn.zimu.fulfillment.order.domain.ProcessingHealth;
import cn.zimu.fulfillment.order.domain.ProcessingStage;
import java.time.Instant;
import java.util.List;

/** 订单列表查询条件。 */
public record OrderListQuery(
        int page,
        int size,
        Instant dateFrom,
        Instant dateTo,
        SourceChannel sourceChannel,
        OrderStatus orderStatus,
        ProcessingStage processingStage,
        ProcessingHealth processingHealth,
        Long providerId,
        String query,
        List<String> sorts) {
}
