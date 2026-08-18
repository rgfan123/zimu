import type { FulfillmentMetric } from '../../api/types';
import type {
  AnalyticsData,
  ChannelAgg,
  DayPoint,
  FunnelStage,
  ProductRow,
  StatusKey,
} from './analyticsTypes';

/** 单个统计窗口的完整快照；保持请求与页面装配之间的转换可独立回归。 */
export interface AnalyticsWindowSnapshot {
  days: DayPoint[];
  totals: ChannelAgg;
  byChannel: Record<string, ChannelAgg>;
  byProduct: ProductRow[];
  statusTotals: Record<StatusKey, number>;
  funnel: FunnelStage[];
}

/** 履约漏斗以真实 Tracking 记录作为末段，不把已同步记录误当作“未取得运单”。 */
export function buildFulfillmentFunnel(
  rows: FulfillmentMetric[],
  status: Record<StatusKey, number>,
): FunnelStage[] {
  const fulfillmentCount = rows.reduce((a, r) => a + (r.fulfillment_count ?? 0), 0);
  const outOfStock = rows.reduce((a, r) => a + (r.out_of_stock_fulfillment_count ?? 0), 0);
  const shipped = rows.reduce((a, r) => a + (r.shipped_shipment_count ?? 0), 0);
  const trackingReceived = rows.reduce((a, r) => a + (r.tracking_received_count ?? 0), 0);
  const synced = rows.reduce((a, r) => a + (r.synced_count ?? 0), 0);
  const values = [
    fulfillmentCount,
    Math.max(0, fulfillmentCount - outOfStock),
    Math.max(0, fulfillmentCount - outOfStock - status.PENDING_OUTBOUND),
    Math.max(0, Math.min(fulfillmentCount - outOfStock - status.PENDING_OUTBOUND, shipped)),
    Math.max(0, Math.min(shipped, trackingReceived)),
    Math.max(0, Math.min(trackingReceived, synced)),
  ];
  const names = ['履约创建', '库存校验通过', '京东已受理', '已出库', '已取得运单', '已回传'];
  return names.map((name, index) => ({
    name,
    value: values[index],
    passPct: index > 0 && values[index - 1] > 0
      ? Math.round((values[index] / values[index - 1]) * 1000) / 10
      : undefined,
  }));
}

/** 趋势使用较长序列窗口；所有 KPI 与构成严格使用用户选中的聚合窗口。 */
export function assembleAnalyticsData(
  series: AnalyticsWindowSnapshot,
  aggregate: AnalyticsWindowSnapshot,
  previous: AnalyticsWindowSnapshot,
): AnalyticsData {
  return {
    aggDays: aggregate.days,
    seriesDays: series.days,
    totals: aggregate.totals,
    byChannel: aggregate.byChannel,
    byProduct: aggregate.byProduct,
    statusTotals: aggregate.statusTotals,
    funnel: aggregate.funnel,
    prev: { totals: previous.totals, statusTotals: previous.statusTotals },
  };
}
