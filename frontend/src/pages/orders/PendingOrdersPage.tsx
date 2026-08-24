/**
 * 待处理订单：默认按「处理阶段 = 待复核」筛选，可切换其他筛选条件。
 */
import OrderListView from './OrderListView';

export default function PendingOrdersPage() {
  return (
    <OrderListView
      title="待处理"
      defaultFilters={{ processing_stage: 'NEED_REVIEW' }}
      tip="待处理订单 = 需要人工介入的订单行（默认按处理阶段「待复核」筛选，可切换为其他阶段/健康度）。"
    />
  );
}
