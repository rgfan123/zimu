/**
 * 发货台骨架的纯投影（Issue #108）：复核预览分组与告警行，无 React、fail-closed。
 * 分组按 reason_code（spec D21），标签只从 queuePresentation.REASON_LABELS 取；
 * 京东门禁类 0 项时保留可见（原型约束：0 不等于不存在）。
 */

import { REASON_LABELS } from './queuePresentation';
import { attentionCardUrl, reviewsQueueUrl } from '../shared/reviewQueueUrl';

export interface ReviewGroupView {
  reasonCode: string;
  label: string;
  count: number;
  url: string;
}

/** 京东门禁/接口异常类原因码：0 项时合并为一个保留组。 */
export const JD_GATE_REASON_CODES: readonly string[] = [
  'JD_SKU_MAPPING_BLOCKED',
  'JD_STOCK_BLOCKED',
  'MULTIPLE_TRACKINGS_FOR_OUTBOUND',
  'JD_TRACKING_CARRIER_MAPPING_REQUIRED',
  'JD_TRACKING_TERMINAL_EXCEPTION',
];

export const JD_GATE_ZERO_COPY = '今天没有。这一类保留是因为它出现时必须第一时间看见。';

/**
 * 按 reason_code 分组（计数降序，同数按标签稳定排序）；未知码原样显示不丢弃（D21）。
 * 若当前预览中没有任何京东门禁类事项，追加一个 0 项保留组。
 */
export function groupReviewPreview(items: unknown[], team: string | null): ReviewGroupView[] {
  const counter = new Map<string, number>();
  for (const item of items) {
    if (typeof item !== 'object' || item === null) continue;
    const code = (item as { reason_code?: unknown }).reason_code;
    if (typeof code !== 'string' || !code) continue;
    counter.set(code, (counter.get(code) ?? 0) + 1);
  }

  const groups: ReviewGroupView[] = [...counter.entries()]
    .map(([reasonCode, count]) => ({
      reasonCode,
      label: REASON_LABELS[reasonCode] ?? reasonCode,
      count,
      url: appendTeam(attentionCardUrl(reasonCode), team),
    }))
    .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label, 'zh'));

  const hasJdGate = groups.some((group) => JD_GATE_REASON_CODES.includes(group.reasonCode));
  if (!hasJdGate) {
    groups.push({
      reasonCode: 'JD_GATE_ZERO',
      label: '京东门禁 / 接口异常',
      count: 0,
      url: appendTeam(reviewsQueueUrl({ status: 'OPEN' }), team),
    });
  }
  return groups;
}

/** 分组跳转与收件箱预筛同口径：有岗位团队时把 responsible_team 一并带上。 */
function appendTeam(url: string, team: string | null): string {
  if (!team) return url;
  // attentionCardUrl 可能指向提醒路由（无团队维度），只对复核路由追加。
  if (!url.startsWith('/workbench/reviews')) return url;
  const [path, query = ''] = url.split('?');
  const params = new URLSearchParams(query);
  params.set('responsible_team', team);
  return `${path}?${params.toString()}`;
}

export interface AlertRowView {
  id: string;
  alertType: string;
  createdAt: string | null;
}

/** 告警行 fail-closed 投影：畸形行丢弃，不让一条坏数据崩整个区块。 */
export function presentAlertRows(items: unknown[]): AlertRowView[] {
  const rows: AlertRowView[] = [];
  for (const item of items) {
    if (typeof item !== 'object' || item === null) continue;
    const record = item as { id?: unknown; alert_type?: unknown; created_at?: unknown };
    if (typeof record.id !== 'string' && typeof record.id !== 'number') continue;
    if (typeof record.alert_type !== 'string' || !record.alert_type) continue;
    rows.push({
      id: String(record.id),
      alertType: record.alert_type,
      createdAt: typeof record.created_at === 'string' ? record.created_at : null,
    });
  }
  return rows;
}
