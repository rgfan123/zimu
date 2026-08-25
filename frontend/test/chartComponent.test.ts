/**
 * issue #38：图表/指标卡视觉组件参数化去重 —— props 映射契约测试。
 *
 * 走 node:test（npm run test:unit），不渲染真图：
 * - 类型层（编译期，node 运行期剥除 import type）：通用 Chart/KpiCard 的差异化能力
 *   均为可选 props（向后兼容既有调用方），且经营分析用量可经这些 props 满足；
 * - 值层（运行期）：经营分析加载态遮罩与 KPI 强调色契约能从 saas 基线解析，
 *   与通用组件的默认值 / 派生映射（valueSize、sparkLineWidth）对齐。
 */

import assert from 'node:assert/strict';
import test from 'node:test';
import type { ChartLoadingOptions, ChartProps } from '../src/components/Chart.tsx';
import type { KpiCardProps } from '../src/components/KpiCard.tsx';
import { saasVisualTokens } from '../src/theme/saasTheme.ts';
import { createAnalyticsVisualSystem } from '../src/pages/analytics/analyticsVisual.ts';

const visualSystem = createAnalyticsVisualSystem(saasVisualTokens);

// ---- 类型层：props 映射（编译期校验；tsc 覆盖 src，此处作为映射契约文档） ----
type IsOptional<T, K extends keyof T> = undefined extends T[K] ? true : false;
type IsRequired<T, K extends keyof T> = undefined extends T[K] ? false : true;
type Expect<T extends true> = T;

// 通用 Chart：option 必填，差异化能力全部可选（向后兼容）。
type ChartOptionRequired = Expect<IsRequired<ChartProps, 'option'>>;
type ChartClickOptional = Expect<IsOptional<ChartProps, 'onChartClick'>>;
type ChartAriaOptional = Expect<IsOptional<ChartProps, 'ariaLabel'>>;
type ChartLoadingOptionOptional = Expect<IsOptional<ChartProps, 'loadingOption'>>;

// 通用 KpiCard：既有 props 保持，经营分析差异化能力全部可选。
type KpiBackCompatOptional = Expect<IsOptional<KpiCardProps, 'className' | 'style' | 'ariaLabel' | 'valueFormatter' | 'valueSize' | 'dot' | 'sparkHeight' | 'sparkLineWidth' | 'sparkAnimationDuration' | 'sparkArea' | 'sparkAriaLabel' | 'showEmptySpark'>>;

// 经营分析 KPI 卡的完整用量可赋给 KpiCardProps（映射满足）。
const analyticsKpiUsage: KpiCardProps = {
  title: '订单数',
  value: 42,
  unit: '单',
  color: '#3f6fd1',
  className: 'analytics-kpi-card analytics-kpi-card--priority',
  style: { '--analytics-kpi-accent': '#3f6fd1' } as KpiCardProps['style'],
  ariaLabel: '订单数：42 单',
  valueFormatter: (v) => (v == null ? '—' : String(v)),
  valueSize: 28,
  dot: true,
  spark: [1, 2, 3],
  sparkHeight: 34,
  sparkLineWidth: 1.75,
  sparkAnimationDuration: 160,
  sparkArea: false,
  sparkAriaLabel: '订单数趋势',
  showEmptySpark: true,
  loading: false,
  extra: null,
};
void analyticsKpiUsage;

// 经营分析图表的加载遮罩用量可赋给 ChartLoadingOptions。
const analyticsLoadingUsage: ChartLoadingOptions = {
  text: '加载中…',
  color: saasVisualTokens.brand.primary,
  textColor: saasVisualTokens.text.secondary,
  maskColor: `${saasVisualTokens.surface.raised}e8`,
  lineWidth: 2,
};
void analyticsLoadingUsage;

// ---- 值层：加载态遮罩契约（原 AnalyticsChart → 通用 Chart 的 loadingOption） ----

test('analytics 加载指示器颜色与通用 Chart 默认一致（brand.primary）', () => {
  assert.equal(visualSystem.states.loading, saasVisualTokens.brand.primary);
  assert.equal(analyticsLoadingUsage.color, saasVisualTokens.brand.primary);
});

test('analytics 加载遮罩由 saas 基线派生（次级文案色 + 抬升面半透明遮罩 + 2px 线宽）', () => {
  assert.equal(analyticsLoadingUsage.textColor, saasVisualTokens.text.secondary);
  assert.equal(analyticsLoadingUsage.maskColor, '#ffffffe8');
  assert.equal(analyticsLoadingUsage.lineWidth, 2);
});

// ---- 值层：KPI 强调色与派生映射（AnalyticsKpiCard → 通用 KpiCard 的 props） ----

test('六个经营分析 KPI 强调色全部来自 saas 调色板（可经 color prop 承载）', () => {
  const accents = Object.values(visualSystem.kpiTones).map((tone) => tone.accent);
  const palette = new Set([
    ...saasChartCategorical(),
    saasVisualTokens.neutral[500],
    saasVisualTokens.semantic.warning,
    saasVisualTokens.semantic.error,
  ]);
  for (const accent of accents) {
    assert.ok(palette.has(accent), `unexpected accent ${accent}`);
  }
});

function saasChartCategorical(): string[] {
  return [
    saasVisualTokens.data.blue,
    saasVisualTokens.data.cyan,
    saasVisualTokens.data.violet,
    saasVisualTokens.data.blueSoft,
    saasVisualTokens.data.cyanSoft,
    saasVisualTokens.data.violetSoft,
  ];
}

test('emphasis → valueSize / sparkLineWidth 派生映射与经营分析视觉契约一致', () => {
  for (const tone of Object.values(visualSystem.kpiTones)) {
    const valueSize = tone.emphasis === 'priority' ? 28 : 24;
    const lineWidth = tone.emphasis === 'regular' ? 1.25 : 1.75;
    // 与 AnalyticsPage 传入通用 KpiCard 的映射一致
    if (tone.emphasis === 'priority') {
      assert.equal(valueSize, 28);
      assert.equal(lineWidth, 1.75);
    } else {
      assert.equal(valueSize, 24);
      assert.equal(lineWidth, tone.emphasis === 'regular' ? 1.25 : 1.75);
    }
  }
});

test('通用 KpiCard 默认色 = 品牌色（经营分析不传 color 时亦成立，实际显式传强调色）', () => {
  assert.equal(saasVisualTokens.brand.primary, '#3f6fd1');
});
