/**
 * 数据中台取数与聚合 hook。
 * 契约 §4.7：三个 analytics 端点均只接受单值 source_channel/provider_id，
 * 故渠道多选由页面按渠道并发请求后合并（endpoints.ts 同注释）。
 *
 * 窗口策略（对齐原型 D）：
 *   - agg 窗口 = 选中粒度（今日 / 7 / 30 / 自定义）——漏斗、占比、热力、KPI 聚合；
 *   - series 窗口 = 今日粒度时回看近 14 天，其余等于 agg 窗口——趋势/构成/积压/sparkline；
 *   - prev 窗口 = 与 agg 等长的前一窗口——KPI 环比。
 * 后端返回带 metric_date 的按天行时逐日图表可用；仅返回聚合行时退化为单周期口径
 * （types.ts ChannelMetric 注释同约定）。
 */

import { useCallback, useMemo } from 'react';
import dayjs from 'dayjs';
import { analyticsApi } from '@/api/endpoints';
import type { ChannelMetric, FulfillmentMetric, ProductMetric, SourceChannel } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import {
  STATUS_KEYS,
  type ChannelAgg,
  type DayPoint,
  type ProductRow,
  type RangeKey,
  type StatusKey,
} from './analyticsTypes';
import { assembleAnalyticsData, buildFulfillmentFunnel, type AnalyticsWindowSnapshot } from './analyticsTransform';

export interface AnalyticsFilters {
  range: RangeKey;
  start?: string;
  end?: string;
  channels: SourceChannel[];
}

const FMT = 'YYYY-MM-DD';
const fmt = (d: dayjs.Dayjs) => d.format(FMT);

/** 字符串十进制数量 → number（契约：数量为十进制字符串，避免 BIGINT 精度丢失）。 */
function num(v: string | number | undefined | null): number {
  if (v === undefined || v === null || v === '') return 0;
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n : 0;
}

function emptyAgg(): ChannelAgg {
  return { orders: 0, qty: 0, lines: 0, exceptions: 0, oos: 0, syncFailed: 0 };
}

function emptyStatus(): Record<StatusKey, number> {
  return { PENDING_OUTBOUND: 0, AWAIT_TRACKING: 0, PENDING_SYNC: 0, SYNC_FAILED: 0 };
}

function emptyDay(date: string): DayPoint {
  return {
    date,
    label: dayjs(date).format('MM-DD'),
    orders: 0,
    qty: 0,
    lines: 0,
    exceptions: 0,
    oos: 0,
    syncFailed: 0,
    byChannel: {},
    status: emptyStatus(),
  };
}

/** 计算三个窗口（agg / series / prev）。 */
function windows(range: RangeKey, start?: string, end?: string) {
  const today = dayjs();
  let aggFrom: dayjs.Dayjs;
  let aggTo: dayjs.Dayjs;
  if (range === 'custom' && start && end) {
    aggFrom = dayjs(start);
    aggTo = dayjs(end);
  } else if (range === '7d') {
    aggFrom = today.subtract(6, 'day');
    aggTo = today;
  } else if (range === '30d') {
    aggFrom = today.subtract(29, 'day');
    aggTo = today;
  } else {
    aggFrom = today;
    aggTo = today;
  }
  const seriesFrom = range === 'today' ? today.subtract(13, 'day') : aggFrom;
  const len = aggTo.diff(aggFrom, 'day') + 1;
  const prevTo = aggFrom.subtract(1, 'day');
  const prevFrom = prevTo.subtract(len - 1, 'day');
  return {
    agg: { from: fmt(aggFrom), to: fmt(aggTo) },
    series: { from: fmt(seriesFrom), to: fmt(aggTo) },
    prev: { from: fmt(prevFrom), to: fmt(prevTo) },
  };
}

function daysBetween(from: string, to: string): string[] {
  const out: string[] = [];
  let d = dayjs(from);
  const end = dayjs(to);
  let guard = 0;
  while (!d.isAfter(end) && guard < 400) {
    out.push(fmt(d));
    d = d.add(1, 'day');
    guard++;
  }
  return out;
}

/** ChannelMetric 行 → 该渠道某天的双口径值。 */
function channelPoint(row: ChannelMetric): ChannelAgg {
  return {
    orders: row.order_count ?? 0,
    qty: num(row.actual_shipped_quantity ?? row.canonical_quantity ?? row.shipped_quantity),
    lines: row.order_line_count ?? 0,
    exceptions: row.exception_order_count ?? 0,
    oos: row.out_of_stock_order_count ?? 0,
    syncFailed: row.sync_failed_count ?? 0,
  };
}

/** 渠道×日期指标 → 按天聚合 map（聚合行（无 metric_date）落到 date_to）。 */
function channelDays(rows: ChannelMetric[], dateTo: string): Map<string, ChannelAgg> {
  const map = new Map<string, ChannelAgg>();
  for (const row of rows) {
    const key = row.metric_date ?? dateTo;
    if (!map.has(key)) map.set(key, emptyAgg());
    const cur = map.get(key)!;
    const p = channelPoint(row);
    cur.orders += p.orders;
    cur.qty += p.qty;
    cur.lines += p.lines;
    cur.exceptions += p.exceptions;
    cur.oos += p.oos;
    cur.syncFailed += p.syncFailed;
  }
  return map;
}

/** 履约指标 → 按天积压状态 map。 */
function statusDays(rows: FulfillmentMetric[], dateTo: string): Map<string, Record<StatusKey, number>> {
  const map = new Map<string, Record<StatusKey, number>>();
  for (const row of rows) {
    const key = row.metric_date ?? dateTo;
    if (!map.has(key)) map.set(key, emptyStatus());
    const cur = map.get(key)!;
    cur.PENDING_OUTBOUND += row.awaiting_shipment_count ?? 0;
    cur.AWAIT_TRACKING += row.awaiting_tracking_count ?? 0;
    cur.PENDING_SYNC += row.awaiting_sync_count ?? 0;
    cur.SYNC_FAILED += row.sync_failed_count ?? 0;
  }
  return map;
}

function sumStatus(days: DayPoint[]): Record<StatusKey, number> {
  const out = emptyStatus();
  for (const d of days) for (const k of STATUS_KEYS) out[k] += d.status[k];
  return out;
}

/** 商品×渠道×日期 → 按商品/SKU 聚合行（渠道拆列）。 */
interface RowAcc extends Omit<ProductRow, 'skus'> {
  skus: Map<string, { sku_id: string; sku_code: string; sku_name: string; qty: number }>;
}

function productRows(rows: ProductMetric[], channels: SourceChannel[], aggFrom: string, aggTo: string): ProductRow[] {
  const byKey = new Map<string, RowAcc>();
  const ensure = (row: ProductMetric): RowAcc => {
    const key = row.product_id || row.sku_id || `${row.product_code ?? ''}:${row.sku_code ?? ''}`;
    let rec = byKey.get(key);
    if (!rec) {
      const skus = new Map<string, { sku_id: string; sku_code: string; sku_name: string; qty: number }>();
      if (row.sku_id) {
        skus.set(row.sku_id, { sku_id: row.sku_id, sku_code: row.sku_code ?? '', sku_name: row.sku_name ?? '', qty: 0 });
      }
      rec = {
        key,
        isProduct: Boolean(row.product_id),
        label: row.product_name ?? row.sku_name ?? row.sku_code ?? key,
        category: row.category_name ?? '',
        sku_code: row.sku_code,
        channel: Object.fromEntries(channels.map((c) => [c, 0])),
        sourceMappings: Object.fromEntries(channels.map((c) => [c, []])),
        jdSkuCodes: [],
        total: 0,
        skus,
      };
      byKey.set(key, rec);
    }
    return rec;
  };
  for (const row of rows) {
    if (row.metric_date && (row.metric_date < aggFrom || row.metric_date > aggTo)) continue;
    const ch = row.source_channel;
    if (!ch || !channels.includes(ch)) continue;
    const rec = ensure(row);
    const qty = num(row.actual_shipped_quantity ?? row.canonical_quantity ?? row.shipped_quantity);
    rec.channel[ch] = (rec.channel[ch] ?? 0) + qty;
    rec.total += qty;
    const mappings = rec.sourceMappings[ch] ?? (rec.sourceMappings[ch] = []);
    for (const mapping of row.source_mappings ?? []) {
      if (!mappings.some((item) => item.source_sku_ref === mapping.source_sku_ref)) mappings.push(mapping);
    }
    for (const code of row.jd_sku_codes ?? []) {
      if (!rec.jdSkuCodes.includes(code)) rec.jdSkuCodes.push(code);
    }
    if (row.sku_id && rec.skus.has(row.sku_id)) {
      const s = rec.skus.get(row.sku_id)!;
      s.qty += qty;
      s.sku_name = row.sku_name ?? s.sku_name;
      s.sku_code = row.sku_code ?? s.sku_code;
    }
  }
  return [...byKey.values()]
    .map((r) => ({ ...r, skus: [...r.skus.values()] }))
    .sort((a, b) => b.total - a.total);
}

/** 单渠道取完整复核队列，避免首屏分页截断统计。 */
async function fetchWindow(
  channels: SourceChannel[],
  from: string,
  to: string,
  withProducts: boolean,
): Promise<AnalyticsWindowSnapshot> {
  const [channelLists, productLists, fulfillmentLists] = await Promise.all([
    Promise.all(channels.map((ch) => analyticsApi.channels({ date_from: from, date_to: to, source_channel: ch }))),
    withProducts
      ? Promise.all(channels.map((ch) => analyticsApi.products({ date_from: from, date_to: to, source_channel: ch })))
      : Promise.resolve([]),
    Promise.all(channels.map((ch) => analyticsApi.fulfillments({ date_from: from, date_to: to, source_channel: ch }))),
  ]);
  const fulfillmentRows = fulfillmentLists.flat();

  const dates = daysBetween(from, to);
  const days = dates.map(emptyDay);
  const byChannel: Record<string, ChannelAgg> = {};
  const totals = emptyAgg();

  channels.forEach((ch, i) => {
    const byDate = channelDays(channelLists[i], to);
    const agg = emptyAgg();
    for (const [date, p] of byDate) {
      const day = days.find((d) => d.date === date);
      if (day) {
        day.byChannel[ch] = { orders: p.orders, qty: p.qty };
        day.orders += p.orders;
        day.qty += p.qty;
        day.lines += p.lines;
        day.exceptions += p.exceptions;
        day.oos += p.oos;
        day.syncFailed += p.syncFailed;
      }
      agg.orders += p.orders;
      agg.qty += p.qty;
      agg.lines += p.lines;
      agg.exceptions += p.exceptions;
      agg.oos += p.oos;
      agg.syncFailed += p.syncFailed;
    }
    byChannel[ch] = agg;
    totals.orders += agg.orders;
    totals.qty += agg.qty;
    totals.lines += agg.lines;
    totals.exceptions += agg.exceptions;
    totals.oos += agg.oos;
    totals.syncFailed += agg.syncFailed;
  });

  const statusByDate = statusDays(fulfillmentRows, to);
  for (const [date, st] of statusByDate) {
    const day = days.find((d) => d.date === date);
    if (day) for (const k of STATUS_KEYS) day.status[k] = st[k];
  }
  const statusTotals = sumStatus(days);

  const byProduct = withProducts
    ? productRows(productLists.flat(), channels, from, to)
    : [];

  return { days, totals, byChannel, byProduct, statusTotals, funnel: buildFulfillmentFunnel(fulfillmentRows, statusTotals) };
}

export function useAnalyticsData(filters: AnalyticsFilters) {
  const { range, start, end, channels } = filters;
  const channelsKey = channels.join(',');
  const fetcher = useCallback(async () => {
    const w = windows(range, start, end);
    const seriesPromise = fetchWindow(channels, w.series.from, w.series.to, true);
    const aggregatePromise =
      w.agg.from === w.series.from && w.agg.to === w.series.to
        ? seriesPromise
        : fetchWindow(channels, w.agg.from, w.agg.to, true);
    const [series, aggregate, prev] = await Promise.all([
      seriesPromise,
      aggregatePromise,
      fetchWindow(channels, w.prev.from, w.prev.to, false),
    ]);
    return assembleAnalyticsData(series, aggregate, prev);
  }, [range, start, end, channelsKey]); // eslint-disable-line react-hooks/exhaustive-deps

  const { data, loading, error, reload } = useAsync(fetcher, [range, start, end, channelsKey]);
  return useMemo(() => ({ data, loading, error, reload }), [data, loading, error, reload]);
}
