/**
 * 今日发货工作台（Issue #107）的纯展示投影：把三平台订单刷新结果投影成渠道卡视图与汇总，
 * 供 ShippingWorkbenchPage 消费，可独立单测，避免把状态语义散落在组件里。
 *
 * 本模块只做「无 React / 无副作用」的投影；不引入 runtime 的 `@/` 依赖（`import type`
 * 会被 strip-types 擦除，`../shared/batchUrl` 走相对路径），保证纯函数测试可直接 import。
 */

import type { ImportRowCounts, PlatformOrderRefreshResult, SourceChannel } from '@/api/types';
import { fileJobUrlForBatch } from '../shared/batchUrl.ts';

export type ChannelStatus = 'OK' | 'FAILED' | 'SKIPPED';

export const CHANNEL_STATUS_TEXT: Record<ChannelStatus, string> = {
  OK: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
};

/**
 * 契约边界（Issue #107）：DTO 不暴露 last_pull_at 或剩余拉取次数，只展示本次结果。
 * 严禁伪造「今日剩 N 次」之类的假配额。
 */
export const QUOTA_UNAVAILABLE_TEXT = '当前接口未暴露剩余拉取额度';

export interface ShippingChannelView {
  channel: SourceChannel;
  status: ChannelStatus;
  statusText: string;
  /** 已生成导入批次的批次号（彩食鲜/飞象） */
  batchNo: string | null;
  /** 已生成导入批次的批次 ID；有值时整卡可点击跳文件作业页 */
  batchId: string | null;
  message: string | null;
  rowCounts: ImportRowCounts | null;
  /** 聚福宝 JSON 直连拉取订单数（缺收货人字段，仅报告未入库） */
  orderCount: number | null;
  /** 一等状态：仅报告未入库（有 order_count 且无 batch） */
  reportOnly: boolean;
  /** 整卡 React Router 落点；无真实落点为 null */
  destination: string | null;
}

export interface ShippingSummary {
  /** 生成导入批次的渠道数（有 batch_id 或 batch_no） */
  batchCount: number;
  /** 全部渠道 row_counts.total 之和 */
  totalRows: number;
  /** 仅报告未入库的订单数之和 */
  reportedOrders: number;
  /** FAILED 状态的渠道数 */
  failedCount: number;
  /** 是否真的有新订单（入库或仅报告）；false 且 failedCount=0 才显示「没有新订单」 */
  hasNewOrders: boolean;
}

export function presentShippingChannel(
  channel: PlatformOrderRefreshResult['channels'][number],
): ShippingChannelView {
  const batchId = channel.batch_id ?? null;
  const batchNo = channel.batch_no ?? null;
  const orderCount = channel.order_count ?? null;
  const reportOnly = channel.status === 'OK' && batchId === null && orderCount != null;

  return {
    channel: channel.channel,
    status: channel.status,
    statusText: CHANNEL_STATUS_TEXT[channel.status],
    batchNo,
    batchId,
    message: channel.message ?? null,
    rowCounts: channel.row_counts ?? null,
    orderCount,
    reportOnly,
    destination: batchId ? fileJobUrlForBatch(batchId) : null,
  };
}

export function summarizeShippingResult(result: PlatformOrderRefreshResult): ShippingSummary {
  const batchCount = result.channels.filter((c) => c.batch_id || c.batch_no).length;
  const totalRows = result.channels.reduce((sum, c) => sum + (c.row_counts?.total ?? 0), 0);
  const reportedOrders = result.channels.reduce((sum, c) => sum + (c.order_count ?? 0), 0);
  const failedCount = result.channels.filter((c) => c.status === 'FAILED').length;

  return {
    batchCount,
    totalRows,
    reportedOrders,
    failedCount,
    hasNewOrders: batchCount > 0 || totalRows > 0 || reportedOrders > 0,
  };
}
