/**
 * 订单追踪：默认展示已发货订单，进入详情可查看分批 Shipment 与运单轨迹。
 */
import OrderListView from './OrderListView';

export default function OrderTrackingPage() {
  return (
    <OrderListView
      defaultFilters={{ order_status: 'SHIPPED' }}
      tip="订单追踪默认展示「已发货」订单；点击订单号进入详情页查看分批发货明细与运单信息。"
    />
  );
}
