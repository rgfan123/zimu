package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import java.time.Instant;

/**
 * 订单检索条件（MCP {@code search_orders} 专用）：与 {@link OrderListQuery} 分离，因为模糊匹配
 * 目标不同——这里匹配渠道单号/收件人姓名，供业务人员按「某某那单」检索，而非管理台的
 * 订单号/来源单号/客户名检索。
 */
public record OrderSearchQuery(
        /** 模糊查询词：命中渠道单号（source_ref）或收件人姓名（receiver_name）。 */
        String query,
        SourceChannel sourceChannel,
        OrderStatus orderStatus,
        /** 按「下单或创建日期」过滤：优先取渠道下单时刻（source_ordered_at），缺失时退回创建时刻。 */
        Instant dateFrom,
        Instant dateTo,
        int page,
        int size) {
}
