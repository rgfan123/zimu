/**
 * 状态枚举 → 中文标签 / 颜色 映射（PRD §18 事件清单 + CONTEXT.md 状态维度 + openapi 枚举）。
 * 颜色只使用 antd 语义 Tag 预设（success/processing/error/warning/default，随 saasTheme token
 * 降饱和）；来源渠道等普通分类走 pages/shared/semanticStatus 的品牌色阶点缀，不使用具名彩虹色。
 */

import type { FulfillmentProvider, OrderStatus, ProcessingHealth, ProcessingStage, ShipmentStatus, SourceChannel } from '@/api/types';
import type { ChannelMessageType } from '@/api/types';
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
  WANGQI: '大者',
  DAZHE: '大者',
  WANQI: '万齐',
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

// 复核原因/告警类型标签：单表见 reasonLabels.ts（UIUX-03 #137，对账测试保证缺译即失败）。
export { REASON_LABELS, reasonLabel } from './reasonLabels.ts';

/** 企业微信消息类型 → 中文（全站统一，禁止中英混排）。 */
export const MESSAGE_TYPE_LABELS: Record<ChannelMessageType, string> = {
  text: '文字',
  mixed: '图文',
  image: '图片',
  voice: '语音',
  file: '文件',
  video: '视频',
};

/** 结账方式（SettlementMethod）→ 中文；未知值回退原码。 */
export const SETTLEMENT_METHOD_LABELS: Record<string, string> = {
  UNSPECIFIED: '未指定',
  MONTHLY: '月结',
  IMMEDIATE: '现结',
  CREDIT_TERM: '账期',
  PREPAID: '预付款',
  COD: '货到付款',
  OTHER: '其他',
};

/** 履约方类型 → 中文（JD_WAREHOUSE=京东 / THIRD_PARTY=第三方），全站统一口径。 */
export const PROVIDER_TYPE_LABELS: Record<FulfillmentProvider['provider_type'], string> = {
  JD_WAREHOUSE: '京东',
  THIRD_PARTY: '第三方',
};

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
