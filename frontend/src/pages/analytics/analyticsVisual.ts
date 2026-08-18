import type { SourceChannel } from '../../api/types';

export type AnalyticsKpiKey = 'orders' | 'qty' | 'pending' | 'exceptions' | 'oos' | 'syncFailed';
export type AnalyticsKpiEmphasis = 'regular' | 'priority' | 'warning' | 'critical';

interface AnalyticsVisualTokenInput {
  data: {
    blue: string;
    cyan: string;
    violet: string;
    blueSoft: string;
    cyanSoft: string;
    violetSoft: string;
  };
  neutral: { 500: string };
  text: { tertiary: string };
  semantic: { warning: string; error: string };
}

export function createAnalyticsVisualSystem(
  tokens: AnalyticsVisualTokenInput,
) {
  const dataPalette = [
    tokens.data.blue,
    tokens.data.cyan,
    tokens.data.violet,
    tokens.data.blueSoft,
    tokens.data.cyanSoft,
    tokens.data.violetSoft,
  ] as const;
  const kpiTones: Record<AnalyticsKpiKey, { emphasis: AnalyticsKpiEmphasis; accent: string }> = {
    orders: { emphasis: 'priority', accent: dataPalette[0] },
    qty: { emphasis: 'priority', accent: dataPalette[1] },
    pending: { emphasis: 'regular', accent: tokens.neutral[500] },
    exceptions: { emphasis: 'critical', accent: tokens.semantic.error },
    oos: { emphasis: 'warning', accent: tokens.semantic.warning },
    syncFailed: { emphasis: 'critical', accent: tokens.semantic.error },
  };

  return {
    kpiTones,
    chartColors: {
      trend: {
        orders: dataPalette[0],
        quantity: dataPalette[1],
      },
      channels: {
        CAISHIXIAN: dataPalette[0],
        JUFUBAO: dataPalette[1],
        FEIXIANG: dataPalette[2],
        WECOM: dataPalette[3],
      } satisfies Record<SourceChannel, string>,
      categories: [...dataPalette],
      status: {
        PENDING_OUTBOUND: tokens.neutral[500],
        AWAIT_TRACKING: dataPalette[0],
        PENDING_SYNC: tokens.semantic.warning,
        SYNC_FAILED: tokens.semantic.error,
      },
      funnel: [
        dataPalette[0],
        dataPalette[3],
        dataPalette[1],
        dataPalette[4],
        dataPalette[2],
        dataPalette[5],
      ],
    },
    states: {
      hoverBorder: dataPalette[3],
      selected: dataPalette[0],
      loading: dataPalette[0],
      empty: tokens.text.tertiary,
      error: tokens.semantic.error,
    },
  } as const;
}

const SERIES_STYLES = [
  { lineType: 'solid', symbol: 'circle' },
  { lineType: 'dashed', symbol: 'diamond' },
  { lineType: 'dotted', symbol: 'rect' },
  { lineType: 'solid', symbol: 'triangle' },
] as const;

export function analyticsSeriesStyle(index: number): (typeof SERIES_STYLES)[number] {
  return SERIES_STYLES[index % SERIES_STYLES.length];
}

export function analyticsHeatmapVisualMap(max: number) {
  return {
    show: false,
    min: 0,
    max,
    inRange: { opacity: [1, 1] },
  };
}
