/**
 * Issue #64：复核队列 / 运营提醒两页共用的展示常量（标签、团队选项）。
 * 两页与其抽屉都从这里取标签，避免拆分后出现第二套文案。
 */

import type { OperationalAlertStatus, ReviewCaseStatus } from '@/api/types';

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

export const REASON_LABELS: Record<string, string> = {
  CUSTOMER_MATCH_REQUIRED: '客户映射待确认',
  SKU_MAPPING_REQUIRED: 'SKU 映射待确认',
  SKU_MAPPING_CONFLICT: 'SKU 映射冲突',
  SOURCE_SKU_MAPPING_REQUIRED: '来源 SKU 待确认',
  PROVIDER_SKU_MAPPING_REQUIRED: '履约方 SKU 待确认',
  MAPPING_MULTIPLIER: '数量换算待确认',
  QUANTITY_SCALE: '数量精度待确认',
  CARRIER_MAPPING: '承运商映射待确认',
  MULTI_SHIPMENT_SOURCE_FOLLOWUP: '多批发货来源回传待跟进',
  IMPORT_DATA: '导入数据待修正',
  REVISION_AFTER_EXPORT: '导出后改单待确认',
  SYNC_FAILED: '来源回传失败',
  FULFILLMENT_EXCEPTION: '履约异常',
  WECOM_ORDER_DRAFT: '企业微信订单草稿待确认',
  WECOM_TRACKING_DRAFT: '企业微信运单草稿待确认',
  WECOM_TRACKING_FILE_REVIEW: '企微运单文件处理失败',
  JD_SKU_MAPPING_BLOCKED: '京东 SKU 映射门禁阻断',
  JD_STOCK_BLOCKED: '京东库存不足阻断',
  MULTIPLE_TRACKINGS_FOR_OUTBOUND: '京东多运单待确认',
  JD_TRACKING_CARRIER_MAPPING_REQUIRED: '京东承运商映射待确认',
  JD_TRACKING_TERMINAL_EXCEPTION: '京东运单终态异常待复核',
  WECOM_NEED_REVIEW: '企微消息待人工识别',
  WECOM_ORDER_CHANGE: '企微改单待确认',
  WECOM_ORDER_CANCEL: '企微取消待确认',
};

export const TEAM_OPTIONS = [
  { value: 'CUSTOMER_OPS', label: '客户运营' },
  { value: 'SKU_OPS', label: '商品运营' },
  { value: 'ORDER_OPS', label: '订单运营' },
  { value: 'FULFILLMENT_OPS', label: '履约运营' },
];
export const TEAM_LABELS = Object.fromEntries(TEAM_OPTIONS.map((item) => [item.value, item.label]));
