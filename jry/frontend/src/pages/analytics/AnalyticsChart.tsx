/**
 * 数据中台 ECharts 封装：统一主题化的加载态，并按需提供点击下钻。
 * 共享 Chart 仍服务其他页面；本组件仅承担 analytics 的视觉契约。
 */

import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { saasVisualTokens } from '../../theme/saasTheme';
import { analyticsVisualSystem } from './analyticsTheme';

export interface AnalyticsChartProps {
  option: echarts.EChartsOption;
  height?: number | string;
  loading?: boolean;
  onChartClick?: (params: echarts.ECElementEvent) => void;
  ariaLabel?: string;
}

export default function AnalyticsChart({ option, height = 236, loading, onChartClick, ariaLabel = '数据图表' }: AnalyticsChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);
  const clickRef = useRef(onChartClick);
  clickRef.current = onChartClick;

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = echarts.init(el);
    chartRef.current = chart;
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(el);
    chart.on('click', (params) => clickRef.current?.(params));
    return () => {
      observer.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true });
  }, [option]);

  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    if (loading) {
      chart.showLoading('default', {
        text: '加载中…',
        color: analyticsVisualSystem.states.loading,
        textColor: saasVisualTokens.text.secondary,
        maskColor: `${saasVisualTokens.surface.raised}e8`,
        lineWidth: 2,
      });
    } else {
      chart.hideLoading();
    }
  }, [loading]);

  return <div ref={containerRef} role="img" aria-label={ariaLabel} style={{ width: '100%', height }} />;
}
