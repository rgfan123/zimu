/**
 * 出库信息内外事实并排（Ticket 01）的纯展示逻辑：京东侧状态、逐字段差异状态的
 * 呈现口径统一在这里，页面组件只做渲染。纯函数便于 node:test 直接验证。
 */

import type {
  OutboundReconJdStatus,
  OutboundReconQueryType,
  OutboundReconRowState,
  OutboundReconView,
} from '../../api/types.ts';

export type AlertTone = 'success' | 'warning' | 'error' | 'info';

export interface ReconJdStatusPresentation {
  tone: AlertTone;
  title: string;
  description: string;
}

/** 京东侧整体状态 → 横幅呈现；UNAVAILABLE 明确是「未取到」而不是空值。 */
export function jdStatusPresentation(
  status: OutboundReconJdStatus,
  message?: string | null,
): ReconJdStatusPresentation {
  switch (status) {
    case 'OK':
      return {
        tone: 'success',
        title: '京东侧已返回出库记录',
        description: '两侧事实并排展示，不一致的字段已高亮并说明差异。',
      };
    case 'NOT_FOUND':
      return {
        tone: 'warning',
        title: '京东侧没有这笔出库记录',
        description: '京东查询成功但未返回对应出库；系统内部事实照常展示，请人工核对是否已建单或单号有误。',
      };
    case 'UNAVAILABLE':
      return {
        tone: 'error',
        title: '京东侧未取到（查询失败或超时）',
        description: message
          ? `${message}；这不是「字段为空」，系统内部事实照常展示，请稍后重试或联系管理员排查京东连接。`
          : '京东查询失败或超时，系统内部事实照常展示；这不是「字段为空」，请稍后重试或联系管理员排查京东连接。',
      };
  }
}

export interface ReconRowPresentation {
  tone: 'success' | 'error' | 'warning' | 'default';
  label: string;
}

/** 逐字段差异状态 → 标记口径；JD_UNAVAILABLE / JD_NOT_FOUND 与「字段为空」明确区分。 */
export function rowStatePresentation(state: OutboundReconRowState): ReconRowPresentation {
  switch (state) {
    case 'MATCH':
      return { tone: 'success', label: '一致' };
    case 'MISMATCH':
      return { tone: 'error', label: '不一致' };
    case 'INTERNAL_ONLY':
      return { tone: 'warning', label: '仅内部有' };
    case 'JD_ONLY':
      return { tone: 'warning', label: '仅京东有' };
    case 'EMPTY':
      return { tone: 'default', label: '两侧均为空' };
    case 'JD_UNAVAILABLE':
      return { tone: 'error', label: '京东未取到' };
    case 'JD_NOT_FOUND':
      return { tone: 'warning', label: '京东无记录' };
  }
}

export function queryTypeLabel(type: OutboundReconQueryType): string {
  switch (type) {
    case 'OUTBOUND_ORDER_NO':
      return '系统出库单号';
    case 'JD_DELIVERY_NO':
      return '京东单号';
    case 'ORDER_NO':
      return '订单号';
  }
}

/** 差异行判定：只有 MATCH 与 EMPTY 不算差异。 */
export function isDiffRow(state: OutboundReconRowState): boolean {
  return state !== 'MATCH' && state !== 'EMPTY';
}

export interface ReconSummary {
  totalRows: number;
  matched: number;
  mismatched: number;
  internalOnly: number;
  jdOnly: number;
  jdStatus: OutboundReconJdStatus;
}

export function reconSummary(view: OutboundReconView): ReconSummary {
  const rows = view.comparisons;
  return {
    totalRows: rows.length,
    matched: view.matched_count,
    mismatched: view.mismatch_count,
    internalOnly: rows.filter((row) => row.state === 'INTERNAL_ONLY').length,
    jdOnly: rows.filter((row) => row.state === 'JD_ONLY').length,
    jdStatus: view.jd.status,
  };
}

/** 单元格文本：null/空数组显示「—」；数组拼成可读摘要。 */
export function cellText(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value.trim() === '' ? '—' : value;
  if (Array.isArray(value)) return value.length === 0 ? '—' : value.map((item) => String(item)).join('、');
  return String(value);
}

const ORDER_ID_PATTERN = /^[1-9][0-9]*$/;

/** 保守投影：internal.summary.order_id trim 后必须符合 OpenAPI Identifier 才视为可证订单身份。 */
export function internalOrderId(summary: Record<string, unknown> | null | undefined): string | null {
  const orderId = summary?.order_id;
  if (typeof orderId !== 'string') return null;
  const trimmed = orderId.trim();
  return ORDER_ID_PATTERN.test(trimmed) ? trimmed : null;
}
