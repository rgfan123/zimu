/**
 * 异常订单：默认按「订单状态 = 履约异常」筛选；缺货 / 采购待处理 / 回传失败等分支可切换状态查看。
 */
import OrderListView from './OrderListView';

export default function ExceptionOrdersPage() {
  return (
    <OrderListView
      title="异常订单"
      defaultFilters={{ order_status: 'FULFILLMENT_EXCEPTION' }}
      tip="异常订单包含履约异常 / 缺货 / 采购待处理 / 回传失败 / 待复核等分支，可在筛选栏切换订单状态查看。"
    />
  );
}
