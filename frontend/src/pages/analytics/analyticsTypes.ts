/**
 * 数据中台（决策 D 单屏 bento）共享类型与常量。
 * 口径严格按契约 §4.7 / CONTEXT.md ActualShippedQuantity：
 *   实发量 = 来源包装乘数换算后的 Canonical SKU 件数，礼包展开组件；
 *   不统计来源包装数 / 礼包份数 / 重量。
 */

import type { SourceChannel } from '@/api/types';
import { analyticsVisualSystem } from './analyticsTheme';

export type RangeKey = 'today' | '7d' | '30d' | 'custom';
export type MetricKey = 'orders' | 'qty';

export const CHANNELS: SourceChannel[] = ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WECOM'];

/** 渠道是非语义数据系列，统一取自 SaaS 品牌色与中性色阶。 */
export const CHANNEL_HEX: Record<SourceChannel, string> = analyticsVisualSystem.chartColors.channels;

/** 积压构成 = 除「已出库」外的履约状态（决策 D 坑 2：已出库量级会压扁其余四态，拆图）。 */
export const STATUS_KEYS = ['PENDING_OUTBOUND', 'AWAIT_TRACKING', 'PENDING_SYNC', 'SYNC_FAILED'] as const;
export type StatusKey = (typeof STATUS_KEYS)[number];

export const STATUS_LABELS: Record<StatusKey, string> = {
  PENDING_OUTBOUND: '待出库',
  AWAIT_TRACKING: '待取得运单',
  PENDING_SYNC: '待回传',
  SYNC_FAILED: '回传失败',
};

export const STATUS_HEX: Record<StatusKey, string> = analyticsVisualSystem.chartColors.status;

/** 单个渠道在某天的双口径值。 */
export interface ChannelDaily {
  orders: number;
  qty: number;
}

/** 某天汇总：双口径 + 渠道分解 + 积压状态分解。 */
export interface DayPoint {
  date: string;
  /** 图表轴标签（MM-DD） */
  label: string;
  orders: number;
  qty: number;
  lines: number;
  exceptions: number;
  oos: number;
  syncFailed: number;
  byChannel: Record<string, ChannelDaily>;
  status: Record<StatusKey, number>;
}

/** 渠道聚合行（选中粒度窗口内）。 */
export interface ChannelAgg extends ChannelDaily {
  lines: number;
  exceptions: number;
  oos: number;
  syncFailed: number;
}

/** 上钻用 SKU 摘要。 */
export interface SkuBrief {
  sku_id: string;
  sku_code: string;
  sku_name: string;
  qty: number;
}

export interface SourceMappingBrief {
  source_sku_ref: string;
  source_product_name?: string;
  source_specification?: string;
  quantity_multiplier?: string | number;
}

/** 热力图 / Top 商品行：按商品聚合（可上钻 SKU），无 product_id 时退化为 SKU 行。 */
export interface ProductRow {
  key: string;
  /** true = 按商品聚合（抽屉展示 SKU 上钻列表）；false = 本身已是 SKU */
  isProduct: boolean;
  label: string;
  category: string;
  /** 京东 SKU 编码（isProduct=false 时为本行；isProduct=true 时为首个 SKU） */
  sku_code?: string;
  /** channel -> 实发件数 */
  channel: Record<string, number>;
  /** channel -> 已维护的来源 SKU/包装映射。 */
  sourceMappings: Record<string, SourceMappingBrief[]>;
  /** 当前 Canonical SKU 对应的京东仓 SKU 编码。 */
  jdSkuCodes: string[];
  total: number;
  /** 上钻列表（isProduct=true 时 >1 项） */
  skus: SkuBrief[];
}

export interface FunnelStage {
  name: string;
  value: number;
  /** 相对上一环节通过率（%）；首环节为 undefined */
  passPct?: number;
}

export interface AnalyticsData {
  /** 选中粒度窗口（占比 / 漏斗 / 热力 / KPI 聚合用） */
  aggDays: DayPoint[];
  /** 序列窗口（趋势 / 渠道构成 / 积压 / sparkline 用；今日粒度时回看近 14 天） */
  seriesDays: DayPoint[];
  totals: ChannelAgg;
  byChannel: Record<string, ChannelAgg>;
  byProduct: ProductRow[];
  statusTotals: Record<StatusKey, number>;
  /** 履约漏斗（履约创建 → 库存校验通过 → 京东已受理 → 已出库 → 已取得运单） */
  funnel: FunnelStage[];
  /** 上一等长窗口（环比） */
  prev: { totals: ChannelAgg; statusTotals: Record<StatusKey, number> };
}
