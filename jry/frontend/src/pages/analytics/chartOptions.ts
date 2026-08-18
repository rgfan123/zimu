/**
 * 数据中台图表 option 构建（ECharts 5，决策 D 图表清单一一对应）。
 * 纯函数：输入聚合数据 → EChartsOption；数据为空时返回 null 由卡片渲染空态。
 */

import type { EChartsOption } from 'echarts';
import type { SourceChannel } from '@/api/types';
import { CHANNEL_LABELS } from '@/constants/labels';
import { saasVisualTokens } from '../../theme/saasTheme';
import {
  CHANNEL_HEX,
  STATUS_HEX,
  STATUS_KEYS,
  STATUS_LABELS,
  type DayPoint,
  type FunnelStage,
  type MetricKey,
  type ProductRow,
} from './analyticsTypes';
import { analyticsVisualSystem } from './analyticsTheme';
import { analyticsHeatmapVisualMap, analyticsSeriesStyle } from './analyticsVisual';

const ORDER_COLOR = analyticsVisualSystem.chartColors.trend.orders;
const QUANTITY_COLOR = analyticsVisualSystem.chartColors.trend.quantity;
const AXIS = saasVisualTokens.text.tertiary;
const SPLIT = saasVisualTokens.neutral[100];
const LINE = saasVisualTokens.neutral[300];
const SURFACE = saasVisualTokens.surface.raised;
const CHART_FRAME = {
  animationDuration: 160,
  animationDurationUpdate: 160,
  animationEasing: 'cubicOut' as const,
  textStyle: { color: saasVisualTokens.text.secondary },
};

/** 品类 → 颜色（稳定调色板按出现顺序取色；品类未知时回退灰色）。 */
const CATEGORY_PALETTE = analyticsVisualSystem.chartColors.categories;

export function categoryColor(rows: ProductRow[]): Record<string, string> {
  const map: Record<string, string> = {};
  let i = 0;
  for (const r of rows) {
    if (r.category && !(r.category in map)) {
      map[r.category] = CATEGORY_PALETTE[i % CATEGORY_PALETTE.length];
      i++;
    }
  }
  return map;
}

function baseAxes(labels: string[]) {
  return {
    xAxis: {
      type: 'category' as const,
      boundaryGap: false,
      data: labels,
      axisLine: { lineStyle: { color: LINE } },
      axisTick: { show: false },
      axisLabel: { color: AXIS },
    },
    yAxis: {
      type: 'value' as const,
      minInterval: 1,
      splitLine: { lineStyle: { color: SPLIT } },
      axisLabel: { color: AXIS },
    },
    grid: { left: 8, right: 14, top: 28, bottom: 0, containLabel: true },
  };
}

/** ① 订单 / 发货趋势：平滑面积 + 渐变 + 末点高亮。 */
export function trendOption(days: DayPoint[]): EChartsOption | null {
  if (!days.length) return null;
  const labels = days.map((d) => d.label);
  const last = days.length - 1;
  const mk = (values: number[], color: string) =>
    values.map((v, i) => ({
      value: v,
      symbolSize: i === last ? 9 : 5,
      itemStyle: i === last ? { color, borderColor: SURFACE, borderWidth: 2 } : undefined,
      label:
        i === last
          ? { show: true, formatter: String(v), position: 'top' as const, color, fontWeight: 600 as const, fontSize: 11 }
          : undefined,
    }));
  return {
    ...CHART_FRAME,
    color: [ORDER_COLOR, QUANTITY_COLOR],
    tooltip: { trigger: 'axis', valueFormatter: (v) => `${v}` },
    // 图例由卡片头部自定义渲染（中文），关闭 ECharts 内建图例避免重复
    legend: { show: false },
    ...baseAxes(labels),
    series: [
      {
        name: '订单数',
        type: 'line',
        data: mk(days.map((d) => d.orders), ORDER_COLOR),
        smooth: true,
        symbol: analyticsSeriesStyle(0).symbol,
        lineStyle: { width: 2, type: analyticsSeriesStyle(0).lineType },
        emphasis: { focus: 'series' },
      },
      {
        name: '实际发货量',
        type: 'line',
        data: mk(days.map((d) => d.qty), QUANTITY_COLOR),
        smooth: true,
        symbol: analyticsSeriesStyle(1).symbol,
        lineStyle: { width: 2, type: analyticsSeriesStyle(1).lineType },
        emphasis: { focus: 'series' },
      },
    ],
  };
}

/** ② 履约漏斗：梯形逐段 + 环节通过率。 */
export function funnelOption(stages: FunnelStage[]): EChartsOption | null {
  const visible = stages.filter((s) => s.value > 0);
  if (!visible.length) return null;
  return {
    ...CHART_FRAME,
    tooltip: {
      trigger: 'item',
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (p: any) =>
        `${p.name}<br/>${p.value} 履约单${p.data.passPct != null ? `<br/>通过率 ${p.data.passPct}%` : ''}`,
    },
    series: [
      {
        name: '履约漏斗',
        type: 'funnel',
        left: '2%',
        top: 6,
        bottom: 6,
        width: '86%',
        minSize: '12%',
        maxSize: '100%',
        sort: 'descending',
        gap: 3,
        label: {
          show: true,
          position: 'inside',
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (p: any) =>
            p.data.passPct != null ? `${p.name} ${p.value} · ${p.data.passPct}%` : `${p.name} ${p.value}`,
          color: saasVisualTokens.text.inverse,
          fontSize: 12,
        },
        itemStyle: { borderColor: SURFACE, borderWidth: 1 },
        data: visible.map((s, i) => ({
          ...s,
          itemStyle: { color: analyticsVisualSystem.chartColors.funnel[i % analyticsVisualSystem.chartColors.funnel.length] },
          label: { color: i % 2 === 0 ? saasVisualTokens.text.inverse : saasVisualTokens.text.primary },
        })),
      },
    ],
  } as EChartsOption;
}

/** ③ 渠道构成（按天堆叠面积）/ ⑦ 积压构成（堆叠面积，不含已出库）共用。 */
export interface StackSeries {
  key: string;
  name: string;
  color: string;
  get: (d: DayPoint) => number;
}

export function stackedAreaOption(days: DayPoint[], series: StackSeries[]): EChartsOption | null {
  if (!days.length) return null;
  return {
    ...CHART_FRAME,
    tooltip: { trigger: 'axis' },
    // 图例由卡片头部自定义渲染，关闭 ECharts 内建图例
    legend: { show: false },
    ...baseAxes(days.map((d) => d.label)),
    series: series.map((s, index) => {
      const style = analyticsSeriesStyle(index);
      return {
        name: s.name,
        type: 'line',
        stack: 'total',
        smooth: true,
        symbol: style.symbol,
        showSymbol: false,
        lineStyle: { width: 1.5, color: s.color, type: style.lineType },
        areaStyle: { color: `${s.color}24` },
        emphasis: { focus: 'series' as const },
        data: days.map((d) => s.get(d)),
      };
    }),
  };
}

/** 渠道构成（口径随双口径开关联动）。 */
export function channelStackOption(days: DayPoint[], channels: SourceChannel[], metric: MetricKey): EChartsOption | null {
  return stackedAreaOption(
    days,
    channels.map((ch) => ({
      key: ch,
      name: CHANNEL_LABELS[ch],
      color: CHANNEL_HEX[ch],
      get: (d) => d.byChannel[ch]?.[metric] ?? 0,
    })),
  );
}

/** 积压构成（决策 D 坑 2：不含已出库）。 */
export function backlogOption(days: DayPoint[]): EChartsOption | null {
  return stackedAreaOption(
    days,
    STATUS_KEYS.map((k) => ({
      key: k,
      name: STATUS_LABELS[k],
      color: STATUS_HEX[k],
      get: (d) => d.status[k],
    })),
  );
}

/** ④ 渠道占比：甜甜圈。 */
export function donutOption(items: { name: string; value: number; color: string }[]): EChartsOption | null {
  if (!items.some((i) => i.value > 0)) return null;
  return {
    ...CHART_FRAME,
    tooltip: { trigger: 'item', formatter: '{b}<br/>{c}（{d}%）' },
    series: [
      {
        type: 'pie',
        radius: ['58%', '78%'],
        center: ['50%', '46%'],
        avoidLabelOverlap: true,
        itemStyle: { borderColor: SURFACE, borderWidth: 2, borderRadius: 4 },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 15, fontWeight: 600, color: saasVisualTokens.text.primary },
          itemStyle: { borderColor: analyticsVisualSystem.states.hoverBorder, borderWidth: 2 },
        },
        data: items.map((i) => ({ name: i.name, value: i.value, itemStyle: { color: i.color } })),
      },
    ],
  };
}

/** ⑤ 渠道 × 商品热力矩阵：**按各渠道自身归一**（决策 D 坑 1，禁止全局归一）。 */
export function heatOption(products: ProductRow[], channels: SourceChannel[]): EChartsOption | null {
  if (!products.length || !channels.length) return null;
  const colMax: Record<string, number> = {};
  for (const ch of channels) {
    colMax[ch] = Math.max(1, ...products.map((r) => r.channel[ch] ?? 0));
  }
  const maxValue = Math.max(1, ...products.flatMap((row) => channels.map((channel) => row.channel[channel] ?? 0)));
  const alpha = (ratio: number) => Math.round(8 + Math.min(1, ratio) * 70).toString(16).padStart(2, '0');
  const data: { value: [number, number, number]; itemStyle: { color: string; borderRadius: number } }[] = products.flatMap((row, x) =>
    channels.map((ch, y) => {
      const v = row.channel[ch] ?? 0;
      return {
        value: [x, y, v],
        itemStyle: { color: `${CHANNEL_HEX[ch]}${alpha(v / colMax[ch])}`, borderRadius: 4 },
      };
    }),
  );
  return {
    ...CHART_FRAME,
    visualMap: analyticsHeatmapVisualMap(maxValue),
    tooltip: {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      formatter: (p: any) => {
        const [x, y, v] = p.value as [number, number, number];
        return `${channels[y]} × ${products[x].label}<br/>实发 ${v} 件`;
      },
    },
    grid: { left: 8, right: 16, top: 10, bottom: 62, containLabel: true },
    xAxis: {
      type: 'category',
      data: products.map((r) => r.label),
      axisLabel: { rotate: 32, width: 110, overflow: 'truncate', color: AXIS },
      axisLine: { lineStyle: { color: LINE } },
      axisTick: { show: false },
      splitArea: { show: false },
    },
    yAxis: {
      type: 'category',
      data: channels,
      axisLabel: { color: AXIS },
      axisLine: { lineStyle: { color: LINE } },
      axisTick: { show: false },
    },
    series: [{
      type: 'heatmap',
      data,
      emphasis: { itemStyle: { borderColor: analyticsVisualSystem.states.selected, borderWidth: 1.5 } },
    }],
  } as EChartsOption;
}

/** ⑥ Top 商品：横向条，按品类着色。 */
export function topProductsOption(rows: ProductRow[], colors: Record<string, string>): EChartsOption | null {
  const top = rows.slice(0, 6).reverse(); // yAxis inverse → 大值在上
  if (!top.length) return null;
  return {
    ...CHART_FRAME,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 30, top: 6, bottom: 0, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: SPLIT } }, axisLabel: { color: AXIS } },
    yAxis: {
      type: 'category',
      data: top.map((r) => (r.label.length > 12 ? `${r.label.slice(0, 12)}…` : r.label)),
      axisLine: { lineStyle: { color: LINE } },
      axisTick: { show: false },
      axisLabel: { color: AXIS },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 18,
        data: top.map((r) => ({
          value: r.total,
          itemStyle: { color: colors[r.category] ?? saasVisualTokens.neutral[500], borderRadius: [0, 5, 5, 0] },
        })),
        label: { show: true, position: 'right', color: AXIS, fontSize: 11 },
      },
    ],
  };
}
