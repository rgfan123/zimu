/**
 * 运营域（工作台 / 订单 / 履约 / 仪表盘）共享语义状态映射。
 *
 * 只输出两类颜色：
 *  1. antd 语义 Tag 预设（default / processing / success / warning / error）——
 *     它们跟随 saasTheme token 降饱和，不产生高饱和彩虹色；
 *  2. 由 saasVisualTokens 派生的分类点缀色（来源渠道等普通分类，走品牌/数据色阶）。
 *
 * 禁止在此使用 antd 具名预设色（blue / green / gold / red / purple / cyan /
 * geekblue / orange / volcano）——具名预设固定高饱和、不随主题变化。
 */

import { saasVisualTokens } from '../../theme/saasTheme';
import type {
  ExportUsageStatus,
  FulfillmentOutcome,
  OrderStatus,
  ProcessingStage,
  ShipmentStatus,
  ShippingProgress,
  SourceChannel,
} from '../../api/types';

export type SemanticTagColor = 'default' | 'processing' | 'success' | 'warning' | 'error';

/** 订单状态：进行中→processing（品牌信息色），完成→success，等待人工→warning，异常→error，终态中性→default。 */
export const ORDER_STATUS_SEMANTIC: Record<OrderStatus, SemanticTagColor> = {
  RECEIVED: 'processing',
  VALIDATED: 'processing',
  SKU_MAPPED: 'processing',
  FULFILLING: 'processing',
  SHIPPED: 'success',
  SYNCED: 'success',
  CLOSED: 'default',
  NEED_REVIEW: 'warning',
  OUT_OF_STOCK: 'warning',
  PROCUREMENT_PENDING: 'warning',
  FULFILLMENT_EXCEPTION: 'error',
  SYNC_FAILED: 'error',
  CANCELLED: 'default',
};

/** 处理阶段：与订单状态同一语义骨架，未开始/进行中走中性或品牌，异常/完成显式表达。 */
export const PROCESSING_STAGE_SEMANTIC: Record<ProcessingStage, SemanticTagColor> = {
  NEED_REVIEW: 'warning',
  READY_TO_EXPORT: 'processing',
  PROCUREMENT_IN_PROGRESS: 'processing',
  WAITING_PROVIDER: 'processing',
  TRACKING_RECEIVED: 'processing',
  RETURN_FILE_READY: 'processing',
  COMPLETED: 'success',
  EXCEPTION: 'error',
};

export const SHIPMENT_STATUS_SEMANTIC: Record<ShipmentStatus, SemanticTagColor> = {
  CREATED: 'processing',
  SHIPPED: 'success',
  FAILED: 'error',
  DELIVERED: 'success',
};

/** 来源渠道是普通分类而非状态：品牌/数据色阶的稳定点缀色，不使用彩虹。 */
export const CHANNEL_ACCENT: Record<SourceChannel, string> = {
  CAISHIXIAN: saasVisualTokens.data.blue,
  JUFUBAO: saasVisualTokens.data.cyan,
  FEIXIANG: saasVisualTokens.data.violet,
  WECOM: saasVisualTokens.data.blueSoft,
};

export function severitySemantic(severity: string): SemanticTagColor {
  return severity === 'RED' ? 'error' : 'warning';
}

export function reviewCaseStatusSemantic(status: string): SemanticTagColor {
  if (status === 'OPEN') return 'warning';
  if (status === 'RESOLVED') return 'success';
  return 'default';
}

export function operationalAlertStatusSemantic(status: string): SemanticTagColor {
  if (status === 'OPEN') return 'warning';
  return 'success';
}

export const SHIPPING_PROGRESS_SEMANTIC: Record<ShippingProgress, SemanticTagColor> = {
  NOT_SHIPPED: 'default',
  PARTIALLY_SHIPPED: 'warning',
  SHIPPED: 'success',
};

export const FULFILLMENT_OUTCOME_SEMANTIC: Record<FulfillmentOutcome, SemanticTagColor> = {
  IN_PROGRESS: 'processing',
  FULLY_FULFILLED: 'success',
  PARTIALLY_FULFILLED: 'warning',
  CANCELLED: 'default',
};

export const EXPORT_USAGE_SEMANTIC: Record<ExportUsageStatus, SemanticTagColor> = {
  GENERATED_NOT_DOWNLOADED: 'default',
  DOWNLOADED_WAITING_RETURN: 'warning',
  RETURNED: 'success',
  RETURN_OVERDUE: 'error',
};

/** 导入明细行状态：ACCEPTED / NEED_REVIEW / REJECTED → 语义三态。 */
export function importRowStatusSemantic(status: string): SemanticTagColor {
  if (status === 'ACCEPTED') return 'success';
  if (status === 'REJECTED') return 'error';
  if (status === 'NEED_REVIEW') return 'warning';
  return 'default';
}

/** 京东连接就绪度：真实就绪→success；真实未就绪是需要关注的等待→warning；模拟/未知→中性。 */
export function jdConnectionSemantic(liveReady: boolean, mode?: string): SemanticTagColor {
  if (mode === 'REAL') return liveReady ? 'success' : 'warning';
  return 'default';
}

/** 只读工具说明用品牌信息色（processing 跟随 colorInfo token），不占用语义异常色。 */
export const READ_ONLY_TAG_COLOR: SemanticTagColor = 'processing';

/** 京东查询页的工具分类说明：普通分类，中性呈现。 */
export const TOOL_CATEGORY_TAG_COLOR: SemanticTagColor = 'default';

/** 待介入类 KPI / 计数的强调色：等待→warning，真实异常→error（保持三通道可辨识）。 */
export const ATTENTION_COLORS = {
  waiting: saasVisualTokens.semantic.warning,
  severe: saasVisualTokens.semantic.error,
} as const;
