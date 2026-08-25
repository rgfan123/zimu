/**
 * 订单中心合并页：全部订单 / 待处理 / 异常订单 / 订单追踪 共用一个列表组件，
 * 预设由 pathname 决定（旧直达 URL 兼容），页内 Segmented 切换时导航到对应路径。
 */
import { useLocation, useNavigate } from 'react-router-dom';
import OrderListView, { ORDER_PRESET_DEFS, orderPresetFromPathname } from './OrderListView';

export default function OrdersPage() {
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const preset = orderPresetFromPathname(pathname);

  return (
    <OrderListView
      key={preset}
      preset={preset}
      onPresetChange={(next) => navigate(ORDER_PRESET_DEFS[next].path)}
    />
  );
}
