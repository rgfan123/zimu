/**
 * ECharts 轻量封装：初始化 / setOption / ResizeObserver 自适应 / 销毁。
 * 全量引入 echarts（Demo 阶段优先简单可靠；B5 如需优化体积可换按需引入）。
 *
 * 参数化契约（issue #38）：加载态遮罩（loadingOption）、点击下钻（onChartClick）、
 * 无障碍标签（ariaLabel）均为可选 props；缺省保持极简基线，既有调用方（工作台）零改动，
 * 经营分析的视觉契约（主题化加载遮罩 / 热力点击下钻 / 无障碍）经 props 满足。
 */

import * as echarts from 'echarts';
import { useEffect, useRef } from 'react';
import { saasVisualTokens } from '@/theme/saasTheme';

/**
 * echarts 5.5 未公开 ShowLoadingOption 类型（showLoading 第二参为 cfg?: object），
 * 按 showLoading 实际接受的配置窄化本地定义，供调用方定制加载遮罩。
 */
export interface ChartLoadingOptions {
  /** 加载文案 */
  text?: string;
  /** 旋转指示器颜色 */
  color?: string;
  /** 文案颜色 */
  textColor?: string;
  /** 遮罩颜色（可带透明度，如 #ffffff80） */
  maskColor?: string;
  /** 指示器线宽 */
  lineWidth?: number;
}

export interface ChartProps {
  option: echarts.EChartsOption;
  height?: number | string;
  className?: string;
  loading?: boolean;
  /** 点击图表元素（如热力色块下钻） */
  onChartClick?: (params: echarts.ECElementEvent) => void;
  /** 无障碍标签：传入后容器带 role="img" 与 aria-label */
  ariaLabel?: string;
  /** 加载态视觉定制；未提供时用默认文案与品牌色 */
  loadingOption?: ChartLoadingOptions;
}

export default function Chart({ option, height = 320, className, loading, onChartClick, ariaLabel, loadingOption }: ChartProps) {
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
        color: saasVisualTokens.brand.primary,
        ...loadingOption,
      });
    } else {
      chart.hideLoading();
    }
  }, [loading, loadingOption]);

  return (
    <div
      ref={containerRef}
      className={className}
      role={ariaLabel ? 'img' : undefined}
      aria-label={ariaLabel}
      style={{ width: '100%', height }}
    />
  );
}
