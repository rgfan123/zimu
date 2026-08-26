/**
 * Issue #64：复核队列 / 运营提醒两页共用的展示常量（标签、团队选项）。
 * 两页与其抽屉都从这里取标签，避免拆分后出现第二套文案。
 * 复核原因标签与调度台共用 constants/labels 的 REASON_LABELS 单表（UIUX-03 #137）。
 */

import type { OperationalAlertStatus, ReviewCaseStatus } from '@/api/types';

// 复核原因标签与调度台共用同一张表（UIUX-03 #137：统一口径，禁止第二套文案）。
// 相对路径导入以兼容 node --test 直跑（@/ 别名仅 Vite 解析）。
export { REASON_LABELS } from '../../constants/reasonLabels.ts';

export const REVIEW_STATUS_LABELS: Record<ReviewCaseStatus, string> = {
  OPEN: '待处理',
  RESOLVED: '已解决',
  DISMISSED: '已关闭',
};

export const ALERT_STATUS_LABELS: Record<OperationalAlertStatus, string> = {
  OPEN: '待确认',
  ACKNOWLEDGED: '已知晓',
  RESOLVED: '已恢复',
};

export const TEAM_OPTIONS = [
  { value: 'CUSTOMER_OPS', label: '客户运营' },
  { value: 'SKU_OPS', label: '商品运营' },
  { value: 'ORDER_OPS', label: '订单运营' },
  { value: 'FULFILLMENT_OPS', label: '履约运营' },
];
export const TEAM_LABELS = Object.fromEntries(TEAM_OPTIONS.map((item) => [item.value, item.label]));
