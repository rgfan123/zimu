/**
 * 状态枚举 → 中文标签 / 颜色 映射（PRD §18 事件清单 + CONTEXT.md 状态维度 + openapi 枚举）。
 * 颜色只使用 antd 语义 Tag 预设（success/processing/error/warning/default，随 saasTheme token
 * 降饱和）；来源渠道等普通分类走 pages/shared/semanticStatus 的品牌色阶点缀，不使用具名彩虹色。
 */

import type { FulfillmentProvider, OrderStatus, ProcessingHealth, ProcessingStage, ShipmentStatus, SourceChannel } from '@/api/types';
import {
  ORDER_STATUS_SEMANTIC,
  PROCESSING_STAGE_SEMANTIC,
  SHIPMENT_STATUS_SEMANTIC,
} from '@/pages/shared/semanticStatus';

export const CHANNEL_LABELS: Record<SourceChannel, string> = {
  CAISHIXIAN: '彩食鲜',
  JUFUBAO: '聚福宝',
  FEIXIANG: '飞象',
  ZHONGHUI: '中汇',
  WECOM: '企业微信',
};

/** 来源渠道分类点缀色见 pages/shared/semanticStatus 的 CHANNEL_ACCENT（品牌/数据色阶）。 */

export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  RECEIVED: '已接收',
  VALIDATED: '已校验',
  SKU_MAPPED: '已映射 SKU',
  FULFILLING: '履约中',
  SHIPPED: '已发货',
  SYNCED: '已回传',
  CLOSED: '已关闭',
  NEED_REVIEW: '待复核',
  OUT_OF_STOCK: '缺货',
  PROCUREMENT_PENDING: '采购待处理',
  FULFILLMENT_EXCEPTION: '履约异常',
  SYNC_FAILED: '回传失败',
  CANCELLED: '已取消',
};

export const ORDER_STATUS_COLORS: Record<OrderStatus, string> = ORDER_STATUS_SEMANTIC;

export const PROCESSING_STAGE_COLORS: Record<ProcessingStage, string> = PROCESSING_STAGE_SEMANTIC;

export const PROCESSING_STAGE_LABELS: Record<ProcessingStage, string> = {
  NEED_REVIEW: '待复核',
  READY_TO_EXPORT: '待生成发货表',
  PROCUREMENT_IN_PROGRESS: '采购中',
  WAITING_PROVIDER: '等待履约方',
  TRACKING_RECEIVED: '已取得运单',
  RETURN_FILE_READY: '待生成回填表',
  COMPLETED: '已完成',
  EXCEPTION: '异常',
};

export const PROCESSING_HEALTH_LABELS: Record<ProcessingHealth, string> = {
  GREEN: '健康',
  BLUE: '处理中',
  YELLOW: '待关注',
  RED: '需介入',
};

export const PROCESSING_HEALTH_COLORS: Record<ProcessingHealth, string> = {
  GREEN: 'success',
  BLUE: 'processing',
  YELLOW: 'warning',
  RED: 'error',
};

export const SHIPMENT_STATUS_LABELS: Record<ShipmentStatus, string> = {
  CREATED: '已创建',
  SHIPPED: '已发货',
  FAILED: '失败',
  DELIVERED: '已送达',
};

export const SHIPMENT_STATUS_COLORS: Record<ShipmentStatus, string> = SHIPMENT_STATUS_SEMANTIC;

/** 履约方类型 → 中文（JD_WAREHOUSE=京东 / THIRD_PARTY=第三方），全站统一口径。 */
export const PROVIDER_TYPE_LABELS: Record<FulfillmentProvider['provider_type'], string> = {
  JD_WAREHOUSE: '京东',
  THIRD_PARTY: '第三方',
};

/** 工作台 attention.reason_code 常见值 → 中文；未知码使用稳定业务提示。 */
export const REASON_LABELS: Record<string, string> = {
  NEED_REVIEW: '待复核',
  CUSTOMER_UNMATCHED: '客户未匹配',
  SKU_UNMAPPED: 'SKU 未映射',
  OUT_OF_STOCK: '缺货',
  PROCUREMENT_PENDING: '采购待处理',
  PROCUREMENT_FAILED: '采购失败',
  FULFILLMENT_EXCEPTION: '履约异常',
  JD_SUBMIT_FAILED: '京东提交失败',
  SYNC_FAILED: '回传失败',
  TRACKING_OVERDUE: '运单超时未回',
  RETURN_OVERDUE: '回填超时',
  JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED: '京东建单预检未通过',
  JD_SKU_MAPPING_BLOCKED: '京东商品映射未通过',
  JD_STOCK_BLOCKED: '京东库存判定未通过',
  JD_TRACKING_CONFLICT: '京东运单冲突',
  MULTI_SHIPMENT_FOLLOWUP: '多批次发货待跟进',
};

/**
 * 未登记的原因码回退为「未分类原因」而非状态类措辞——
 * 曾用「待人工复核」，与复核状态混淆：已 RESOLVED 的事项也会显示成待处理。
 */
export function reasonLabel(code: string): string {
  return REASON_LABELS[code] ?? '未分类原因';
}

// ---------- 订单事件（PRD §18） ----------

export type EventTone = 'blue' | 'green' | 'gold' | 'red' | 'gray';

export interface EventMeta {
  label: string;
  tone: EventTone;
}

export const ORDER_EVENT_META: Record<string, EventMeta> = {
  ORDER_RECEIVED: { label: '订单已接收', tone: 'blue' },
  ORDER_UPDATED: { label: '订单信息已更新', tone: 'blue' },
  SKU_MAPPED: { label: 'SKU 映射完成', tone: 'blue' },
  JD_STOCK_CHECKED: { label: '京东库存已校验', tone: 'blue' },
  JD_OUTBOUND_SUBMITTED: { label: '京东出库单已提交', tone: 'gold' },
  JD_OUTBOUND_ACCEPTED: { label: '京东出库单已受理', tone: 'blue' },
  JD_SHIPPED: { label: '京东已发货', tone: 'green' },
  SHIPMENT_CREATED: { label: '发货单已创建', tone: 'blue' },
  TRACKING_RECEIVED: { label: '运单已取得', tone: 'green' },
  PROCUREMENT_REQUESTED: { label: '采购已申请', tone: 'gold' },
  PROCUREMENT_COMPLETED: { label: '采购已完成', tone: 'green' },
  SOURCE_SYNCED: { label: '来源渠道已回传', tone: 'green' },
  // 异常类（防御性补充，后端可能按需扩展事件码）
  FULFILLMENT_EXCEPTION: { label: '履约异常', tone: 'red' },
  SYNC_FAILED: { label: '回传失败', tone: 'red' },
};

export const ORDER_EVENT_FALLBACK: EventMeta = { label: '订单事件', tone: 'gray' };

/** 事件 payload 常见键 → 中文（未知键原样展示）。 */
export const EVENT_PAYLOAD_LABELS: Record<string, string> = {
  sku_code: 'SKU',
  product_name: '商品',
  specification: '规格',
  quantity: '数量',
  requested_quantity: '申请数量',
  shipped_quantity: '发货数量',
  available_quantity: '可用数量',
  outbound_order_no: '出库单号',
  shipment_no: '发货单号',
  tracking_number: '运单号',
  logistics_company: '物流公司',
  logistics_company_code: '物流公司编码',
  provider_code: '履约方',
  provider_name: '履约方',
  source_ref: '来源单号',
  source_channel: '来源渠道',
  carrier: '承运商',
  reason: '原因',
  message: '说明',
  change_reason: '变更原因',
  revision_no: '修订号',
};
