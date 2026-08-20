import assert from 'node:assert/strict';
import test from 'node:test';
import {
  analyticsHeatmapVisualMap,
  analyticsSeriesStyle,
  createAnalyticsVisualSystem,
} from '../src/pages/analytics/analyticsVisual.ts';
import { saasChartPalette, saasVisualTokens } from '../src/theme/saasTheme.ts';

const visualSystem = createAnalyticsVisualSystem(saasVisualTokens);

test('ordinary analytics metrics stay neutral while priority and exception metrics use stable roles', () => {
  assert.deepEqual(visualSystem.kpiTones.orders, { emphasis: 'priority', accent: saasVisualTokens.data.blue });
  assert.deepEqual(visualSystem.kpiTones.qty, { emphasis: 'priority', accent: saasVisualTokens.data.cyan });
  assert.deepEqual(visualSystem.kpiTones.pending, { emphasis: 'regular', accent: saasVisualTokens.neutral[500] });
  assert.deepEqual(visualSystem.kpiTones.exceptions, { emphasis: 'critical', accent: saasVisualTokens.semantic.error });
  assert.deepEqual(visualSystem.kpiTones.oos, { emphasis: 'warning', accent: saasVisualTokens.semantic.warning });
  assert.deepEqual(visualSystem.kpiTones.syncFailed, { emphasis: 'critical', accent: saasVisualTokens.semantic.error });
});

test('non-semantic chart series use the clear blue teal violet data palette and semantic colors keep stable meaning', () => {
  const semanticColors = new Set(Object.values(saasVisualTokens.semantic));
  const channelColors = Object.values(visualSystem.chartColors.channels);

  assert.deepEqual(channelColors, [
    saasChartPalette.categorical[0],
    saasChartPalette.categorical[1],
    saasChartPalette.categorical[2],
    saasChartPalette.categorical[3],
    saasChartPalette.categorical[4],
    saasChartPalette.categorical[4],
    saasChartPalette.categorical[5],
    saasChartPalette.categorical[0],
  ]);
  assert.ok(channelColors.every((color) => !semanticColors.has(color)));
  assert.deepEqual(visualSystem.chartColors.categories, [...saasChartPalette.categorical]);
  assert.equal(visualSystem.chartColors.funnel.length, 6);
  assert.ok(visualSystem.chartColors.funnel.every((color) => !semanticColors.has(color)));
  assert.equal(visualSystem.chartColors.status.PENDING_SYNC, saasVisualTokens.semantic.warning);
  assert.equal(visualSystem.chartColors.status.SYNC_FAILED, saasVisualTokens.semantic.error);
});

test('repeated hues remain distinguishable without relying on rainbow color alone', () => {
  assert.deepEqual(analyticsSeriesStyle(0), { lineType: 'solid', symbol: 'circle' });
  assert.deepEqual(analyticsSeriesStyle(1), { lineType: 'dashed', symbol: 'diamond' });
  assert.deepEqual(analyticsSeriesStyle(2), { lineType: 'dotted', symbol: 'rect' });
  assert.deepEqual(analyticsSeriesStyle(3), { lineType: 'solid', symbol: 'triangle' });
});

test('analytics interaction, loading, empty and error states resolve to SaaS theme tokens', () => {
  assert.equal(visualSystem.states.hoverBorder, saasVisualTokens.data.blueSoft);
  assert.equal(visualSystem.states.selected, saasVisualTokens.brand.primary);
  assert.equal(visualSystem.states.loading, saasVisualTokens.brand.primary);
  assert.equal(visualSystem.states.empty, saasVisualTokens.text.tertiary);
  assert.equal(visualSystem.states.error, saasVisualTokens.semantic.error);
});

test('analytics heatmap exposes the hidden visual map required by ECharts without recoloring cells', () => {
  assert.deepEqual(analyticsHeatmapVisualMap(48), {
    show: false,
    min: 0,
    max: 48,
    inRange: { opacity: [1, 1] },
  });
});
