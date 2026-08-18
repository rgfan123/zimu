package cn.zimu.fulfillment.fulfillment;

/**
 * 京东云仓建出库单（addSoOrder）命令（Shipment 边界）。
 *
 * <p>请求完全由 Shipment 及其全部 ShipmentItems 派生，命令本身不携带业务字段；
 * 幂等键 + 请求哈希保证同一 Shipment 重复提交只产生一张京东出库单，请求事实变化时返回冲突。
 */
public record ShipmentJdOutboundCommand() {
}
