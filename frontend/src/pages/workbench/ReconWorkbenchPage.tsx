/**
 * 作业中心 · 出库信息对账（/workbench/recon，Issue #111）：
 * 复用 /fulfillment/outbound-recon 的三种 query_type/query_value URL 契约与全部七态，
 * 仅在此入口注入「金额对账未纳入本期」的口径横幅，其余展示逻辑零复制。
 *
 * 详情跳转本轮明确不做：OutboundReconView DTO 只暴露业务订单号 order_no，没有可证映射到
 * 内部 /orders/:id 的 order_id，渲染 Link 就是假点击；证据链留待 #112 补齐后再接
 * React Router Link 到 /orders/:id 并以 return_to 保留当前查询。
 */

import { Alert } from 'antd';
import OutboundReconPage from '@/pages/fulfillment/OutboundReconPage';

export default function ReconWorkbenchPage() {
  return (
    <OutboundReconPage
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
