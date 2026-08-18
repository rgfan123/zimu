/**
 * ECharts 轻量封装：初始化 / setOption / ResizeObserver 自适应 / 销毁。
 * 全量引入 echarts（Demo 阶段优先简单可靠；B5 如需优化体积可换按需引入）。
 */

import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { saasVisualTokens } from '@/theme/saasTheme';

export interface ChartProps {
  option: echarts.EChartsOption;
  height?: number | string;
  className?: string;
  loading?: boolean;
}

export default function Chart({ option, height = 320, className, loading }: ChartProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<echarts.ECharts | null>(null);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const chart = echarts.init(el);
    chartRef.current = chart;
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(el);
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
    if (loading) chart.showLoading('default', { text: '加载中…', color: saasVisualTokens.brand.primary });
    else chart.hideLoading();
  }, [loading]);

  return <div ref={containerRef} className={className} style={{ width: '100%', height }} />;
}
