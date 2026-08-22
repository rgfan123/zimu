/**
 * 作业中心 · 出库信息对账（/workbench/recon，Issue #111）：
 * 复用 /fulfillment/outbound-recon 的三种 query_type/query_value URL 契约与全部七态，
 * 仅在此入口注入「金额对账未纳入本期」的口径横幅，其余展示逻辑零复制。
 *
 * 订单下钻仅在此入口显式 opt-in：internal.summary.order_id 为非空字符串时，
 * 「系统内部事实」整卡变为 /orders/:orderId?return_to=<当前 pathname+search>。
 * 不实现 #112 的 Shipment/运单/审计全证据链。
 */

import { Alert } from 'antd';
import OutboundReconPage from '@/pages/fulfillment/OutboundReconPage';

export default function ReconWorkbenchPage() {
  return (
    <OutboundReconPage
      enableOrderDrilldown
      notice={
        <Alert
          type="info"
          showIcon
          message="金额对账未纳入本期"
          description="当前为数量口径：金额字段显示为 ¥ ——，金额对账将在后续版本纳入。"
        />
      }
    />
  );
}
