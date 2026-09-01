package cn.zimu.fulfillment.fulfillment;

/**
 * 京东云仓建出库单（addSoOrder）命令（Shipment 边界）。
 *
 * <p>请求完全由 Shipment 及其全部 ShipmentItems 派生，命令本身不携带业务字段；
 * 幂等摘要只表达稳定的 Shipment 命令身份。派生请求事实由持久化写意图另行冻结并校验，
 * 使外部结果未决时同一幂等键仍能按原事实进入 query-only 对账，而不能再次建单。
 */
public record ShipmentJdOutboundCommand() {
}
