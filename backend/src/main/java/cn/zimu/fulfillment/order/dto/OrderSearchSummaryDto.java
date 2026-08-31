package cn.zimu.fulfillment.order.dto;

import java.time.Instant;

/**
 * 订单检索摘要（MCP {@code search_orders} 专用投影）：订单头 + 行数/总件数聚合 +
 * 最近一个 Shipment 的发货进度与运单摘要。不含收货人电话/详细地址（PII 边界，
 * 见 {@code check_shipment_source_sync} 先例）。
 */
public record OrderSearchSummaryDto(
        String id,
        String orderNo,
        /** 来源渠道技术键（如 JUFUBAO）；中文显示名由 MCP 投影层转换。 */
        String sourceChannel,
        String sourceRef,
        String receiverName,
        String orderStatus,
        /** 渠道平台上的真实下单时刻；来源缺失时为 null。 */
        Instant sourceOrderedAt,
        Instant settlementTime,
        int lineCount,
        /** 全部订单行 requested_quantity 之和（件数，整数）。 */
        long totalQuantity,
        boolean hasShipment,
        /** 最近一个 Shipment 的状态；无 Shipment 时为 null。 */
        String shipmentStatus,
        /** 最近一个 Shipment 的运单号；无运单时为 null。 */
        String trackingNumber,
        /** 最近一个 Shipment 的承运商名称；无运单时为 null。 */
        String carrierName) {
}
