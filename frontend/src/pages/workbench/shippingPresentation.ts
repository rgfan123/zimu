/**
 * 今日发货工作台（Issue #107）的纯展示投影：把三平台订单刷新结果投影成渠道卡视图与汇总，
 * 供 ShippingWorkbenchPage 消费，可独立单测，避免把状态语义散落在组件里。
 *
 * 本模块只做「无 React / 无副作用」的投影；不引入 runtime 的 `@/` 依赖（`import type`
 * 会被 strip-types 擦除，`../shared/batchUrl` 走相对路径），保证纯函数测试可直接 import。
 */

import type { ApiErrorBody, ImportRowCounts } from '@/api/types';
import { fileJobUrlForBatch } from '../shared/batchUrl.ts';

export const REFRESH_PLATFORM_CHANNELS = ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG'] as const;
export type RefreshPlatformChannel = (typeof REFRESH_PLATFORM_CHANNELS)[number];

export const CHANNEL_STATUSES = ['OK', 'FAILED', 'SKIPPED'] as const;
export type ChannelStatus = (typeof CHANNEL_STATUSES)[number];

export const FAILED_REFRESH_STATUSES = ['FAILED', 'SKIPPED'] as const;
export type FailedRefreshStatus = (typeof FAILED_REFRESH_STATUSES)[number];

export const CONTRACT_ERROR_STATUS = 'CONTRACT_ERROR' as const;
export type ContractErrorStatus = typeof CONTRACT_ERROR_STATUS;

export const CHANNEL_STATUS_TEXT: ReadonlyMap<ChannelStatus, string> = new Map([
  ['OK', '成功'],
  ['FAILED', '失败'],
  ['SKIPPED', '已跳过'],
]);

const CHANNEL_LABELS: ReadonlyMap<string, string> = new Map([
  ['CAISHIXIAN', '彩食鲜'],
  ['JUFUBAO', '聚福宝'],
  ['FEIXIANG', '飞象'],
]);

const IDENTIFIER_PATTERN = /^[1-9][0-9]*$/;
const REFRESH_CHANNEL_SET: ReadonlySet<string> = new Set(REFRESH_PLATFORM_CHANNELS);
const CHANNEL_STATUS_SET: ReadonlySet<string> = new Set(CHANNEL_STATUSES);
const FAILED_REFRESH_STATUS_SET: ReadonlySet<string> = new Set(FAILED_REFRESH_STATUSES);
const UNKNOWN_CHANNEL_LABEL = '未知渠道';
const CONTRACT_ERROR_STATUS_TEXT = '响应异常';
const CONTRACT_ERROR_MESSAGE = '渠道响应格式异常，请联系管理员';

/** 渠道卡公开说明：只按 business_code + status 封闭映射，永不回传后端 message。 */
export const CHANNEL_PUBLIC_MESSAGES: ReadonlyMap<string, string> = new Map([
  ['CONNECTOR_CAPABILITY_UNAVAILABLE', '该渠道在线拉取尚未接入，本次未拉取'],
  ['CONNECTOR_CONFIG_MISSING', '该渠道连接配置不存在，本次未拉取'],
  ['CONNECTOR_DISABLED', '该渠道已停用，本次未拉取'],
  ['CONNECTOR_CLIENT_MODE_NOT_REAL', '该渠道未处于真实拉取模式，本次未拉取'],
  ['CONNECTOR_TRANSPORT_NOT_API', '该渠道未配置为接口拉取，本次未拉取'],
  ['PLATFORM_PULL_IN_PROGRESS', '该渠道已有拉取任务进行中，本次未重复发起'],
  ['PLATFORM_PULL_CLAIM_CONFLICT', '该渠道拉取状态正在变化，请稍后重试'],
  ['PLATFORM_PULL_CLEANUP_FAILED', '拉取已结束，但临时文件清理不完整，请联系管理员处理'],
  ['SCRIPT_FAILED', '该渠道拉取失败，请稍后重试'],
  ['INTERNAL_ERROR', '该渠道刷新出现内部错误，请稍后重试'],
  ['REFRESH_FAILED', '该渠道刷新失败，请稍后重试'],
  ['SKIPPED', '该渠道已跳过本次拉取'],
]);

export function channelPublicMessage(status: string, businessCode?: string | null): string | null {
  if (status === 'OK') return null;
  if (typeof businessCode === 'string') {
    const mapped = CHANNEL_PUBLIC_MESSAGES.get(businessCode);
    if (typeof mapped === 'string') return mapped;
  }
  return status === 'SKIPPED' ? '该渠道已跳过本次拉取' : '该渠道刷新失败，请稍后重试';
}

export interface FailedRefreshChannel {
  channel: RefreshPlatformChannel;
  status: FailedRefreshStatus;
  business_code?: string;
}

interface ShippingChannelViewBase {
  channel: string;
  /** 三平台封闭标签；未知/原型键为「未知渠道」，从不索引全局 CHANNEL_LABELS */
  label: string;
  /** 后端 business_code；卡片文案只按封闭映射生成 */
  businessCode: string | null;
}

export interface ValidShippingChannelView extends ShippingChannelViewBase {
  status: ChannelStatus;
  statusText: string;
  validContract: true;
  /** 已生成导入批次的批次号（彩食鲜/飞象） */
  batchNo: string | null;
  /** 已生成导入批次的批次 ID；有值时整卡可点击跳文件作业页 */
  batchId: string | null;
  /** 由 business_code + status 封闭映射生成的公开说明，不是后端 message */
  message: string | null;
  rowCounts: ImportRowCounts | null;
  /** 聚福宝 JSON 直连拉取订单数（缺收货人字段，仅报告未入库） */
  orderCount: number | null;
  /** 一等状态：仅聚福宝 OK 且无批次、有 order_count 时成立（含 0） */
  reportOnly: boolean;
  /** 整卡 React Router 落点；无真实落点为 null */
  destination: string | null;
}

export interface InvalidShippingChannelView extends ShippingChannelViewBase {
  status: ContractErrorStatus;
  statusText: typeof CONTRACT_ERROR_STATUS_TEXT;
  validContract: false;
  batchNo: null;
  batchId: null;
  message: typeof CONTRACT_ERROR_MESSAGE;
  rowCounts: null;
  orderCount: null;
  reportOnly: false;
  destination: null;
}

export type ShippingChannelView = ValidShippingChannelView | InvalidShippingChannelView;

export interface ShippingSummary {
  /** 生成导入批次的渠道数（有 batch_id 或 batch_no） */
  batchCount: number;
  /** 全部渠道 row_counts.total 之和 */
  totalRows: number;
  /** 仅报告未入库的订单数之和 */
  reportedOrders: number;
  /** FAILED 状态的渠道数 */
  failedCount: number;
  /** SKIPPED 状态的合法渠道数；>0 时不得宣称「三平台已同步完成 / 没有新订单」 */
  skippedCount: number;
  /** 非法合同渠道数；>0 时页面必须显示页级错误，不得宣称「三平台已同步完成」 */
  contractErrorCount: number;
  /** 是否真的有新订单（入库或仅报告）；false 且 failed/skipped/contractError 都 0 才显示「没有新订单」 */
  hasNewOrders: boolean;
}

function asRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {};
}

export function presentShippingChannel(channel: unknown): ShippingChannelView {
  const rec = asRecord(channel);
  const channelName = typeof rec.channel === 'string' ? rec.channel : '';
  const statusName = typeof rec.status === 'string' ? rec.status : '';
  const validContract = REFRESH_CHANNEL_SET.has(channelName) && CHANNEL_STATUS_SET.has(statusName);
  const businessCode = typeof rec.business_code === 'string' ? rec.business_code : null;
  const label = refreshChannelLabel(channelName);

  if (!validContract) {
    return {
      channel: channelName || 'UNKNOWN',
      status: CONTRACT_ERROR_STATUS,
      statusText: CONTRACT_ERROR_STATUS_TEXT,
      label,
      validContract: false,
      batchNo: null,
      batchId: null,
      businessCode,
      message: CONTRACT_ERROR_MESSAGE,
      rowCounts: null,
      orderCount: null,
      reportOnly: false,
      destination: null,
    };
  }

  const status = statusName as ChannelStatus;
  const batchId = identifier(rec.batch_id);
  const batchNo = typeof rec.batch_no === 'string' ? rec.batch_no : null;
  const orderCount = nonNegativeInteger(rec.order_count);

  return {
    channel: channelName || 'UNKNOWN',
    status,
    statusText: CHANNEL_STATUS_TEXT.get(status) ?? CONTRACT_ERROR_STATUS_TEXT,
    label,
    validContract: true,
    batchNo,
    batchId,
    businessCode,
    message: channelPublicMessage(status, businessCode),
    rowCounts: importRowCounts(rec.row_counts),
    orderCount,
    reportOnly: isReportOnlyChannel(rec),
    destination: batchId ? fileJobUrlForBatch(batchId) : null,
  };
}

function refreshChannelLabel(channel: string): string {
  return CHANNEL_LABELS.get(channel) ?? UNKNOWN_CHANNEL_LABEL;
}

function identifier(value: unknown): string | null {
  return typeof value === 'string' && IDENTIFIER_PATTERN.test(value) ? value : null;
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && Number.isFinite(value) && value >= 0
    ? value
    : null;
}

function importRowCounts(value: unknown): ImportRowCounts | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
  const counts = value as Record<string, unknown>;
  const total = nonNegativeInteger(counts.total);
  const accepted = nonNegativeInteger(counts.accepted);
  const needReview = nonNegativeInteger(counts.need_review);
  const rejected = nonNegativeInteger(counts.rejected);
  if (total == null || accepted == null || needReview == null || rejected == null) return null;
  return { total, accepted, need_review: needReview, rejected };
}

function isBlank(value: unknown): boolean {
  return value == null || value === '';
}

function isReportOnlyChannel(channel: Record<string, unknown>): boolean {
  return channel.channel === 'JUFUBAO'
    && channel.status === 'OK'
    && isBlank(channel.batch_id)
    && isBlank(channel.batch_no)
    && nonNegativeInteger(channel.order_count) != null;
}

export function summarizeShippingResult(result: { channels: readonly unknown[] }): ShippingSummary {
  const views = result.channels.map(presentShippingChannel);
  const validViews = views.filter((view) => view.validContract);
  const batchCount = validViews.filter((view) => Boolean(view.batchId || view.batchNo)).length;
  const totalRows = validViews.reduce((sum, view) => sum + (view.rowCounts?.total ?? 0), 0);
  const reportedOrders = validViews.reduce((sum, view) => {
    const count = view.reportOnly ? view.orderCount : null;
    return count == null ? sum : sum + count;
  }, 0);
  const failedCount = validViews.filter((view) => view.status === 'FAILED').length;
  const skippedCount = validViews.filter((view) => view.status === 'SKIPPED').length;

  return {
    batchCount,
    totalRows,
    reportedOrders,
    failedCount,
    skippedCount,
    contractErrorCount: views.length - validViews.length,
    hasNewOrders: batchCount > 0 || totalRows > 0 || reportedOrders > 0,
  };
}

/**
 * 真实后端在无任何 OK 时抛 502 business_code=PLATFORM_REFRESH_ALL_FAILED，
 * 逐渠道数组位于 ApiError.body.details.channels。只接受三平台 FAILED/SKIPPED，
 * 返回新建窄对象；未知/畸形 details 返回 null，页面只显示通用错误。
 */
export function failedRefreshChannels(error: { status: number; body: ApiErrorBody }): FailedRefreshChannel[] | null {
  if (error.status !== 502) return null;
  if (error.body.business_code !== 'PLATFORM_REFRESH_ALL_FAILED') return null;
  const channels = error.body.details?.channels;
  if (!Array.isArray(channels) || channels.length === 0) return null;

  const narrowed: FailedRefreshChannel[] = [];
  for (const item of channels) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
    const rec = item as Record<string, unknown>;
    if (typeof rec.channel !== 'string' || !REFRESH_CHANNEL_SET.has(rec.channel)) return null;
    if (typeof rec.status !== 'string' || !FAILED_REFRESH_STATUS_SET.has(rec.status)) return null;
    if (rec.business_code !== undefined && typeof rec.business_code !== 'string') return null;
    if (rec.message !== undefined && typeof rec.message !== 'string') return null;
    const next: FailedRefreshChannel = {
      channel: rec.channel as RefreshPlatformChannel,
      status: rec.status as FailedRefreshStatus,
    };
    if (typeof rec.business_code === 'string') next.business_code = rec.business_code;
    narrowed.push(next);
  }
  return narrowed;
}
