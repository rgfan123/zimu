/**
 * KPI 数据卡：标题 / 大数字 / 可选迷你趋势 sparkline / 可选角标。
 * 风格跟随原型决策 D（图表优先单屏）：卡片底部压一条平滑面积 sparkline。
 * 视觉基线 saasTheme：文字/表面/阴影走 token，点缀色默认品牌色，仅等待/异常 KPI
 * 由调用方传 semantic.warning / semantic.error。
 *
 * 参数化契约（issue #38）：className/style 接管根节点视觉（经营分析传
 * analytics-kpi-card--{emphasis} 类名与 --analytics-kpi-accent 变量），
 * valueFormatter / ariaLabel / dot / valueSize / spark* 补齐经营分析卡的差异化能力
 * （Intl 千分位、无障碍、强调点、优先级字号、无面积 sparkline 等）；
 * 全部可选，既有调用方（工作台）零改动。
 */

import type { CSSProperties, ReactNode } from 'react';
import { Skeleton, Tooltip, theme } from 'antd';
import type { EChartsOption } from 'echarts';
import { useMemo } from 'react';
import { saasVisualTokens } from '@/theme/saasTheme';
import Chart from './Chart';

export interface KpiCardProps {
  title: string;
  value?: number | string | null;
  unit?: string;
  icon?: ReactNode;
  /** 卡片主色调（数字/图标/sparkline） */
  color?: string;
  /** sparkline 数据点 */
  spark?: number[];
  /** 卡内右上角附加内容（如环比 Tag） */
  extra?: ReactNode;
  loading?: boolean;
  /** 悬浮提示 */
  tooltip?: string;
  /** 根节点类名（经营分析传 analytics-kpi-card analytics-kpi-card--{emphasis}） */
  className?: string;
  /** 根节点样式（经营分析传 --analytics-kpi-accent 等 CSS 变量） */
  style?: CSSProperties;
  /** 无障碍标签（经营分析传「标题：格式化数值 单位」） */
  ariaLabel?: string;
  /** 数值格式化（经营分析用 Intl 千分位） */
  valueFormatter?: (value: number | string | null) => string;
  /** 大数字字号（经营分析 priority 28 / 其余 24） */
  valueSize?: number;
  /** 标题前小圆点（经营分析强调色点） */
  dot?: boolean;
  /** sparkline 高度 */
  sparkHeight?: number;
  /** sparkline 线宽 */
  sparkLineWidth?: number;
  /** sparkline 入场动画时长 ms */
  sparkAnimationDuration?: number;
  /** sparkline 是否绘制面积渐变（经营分析精简线不填面积） */
  sparkArea?: boolean;
  /** sparkline 的无障碍标签（如「{title}趋势」） */
  sparkAriaLabel?: string;
  /** spark 为空且非加载态时渲染占位分隔条（经营分析空趋势占位） */
  showEmptySpark?: boolean;
}

interface SparklineOptions {
  lineWidth?: number;
  animationDuration?: number;
  area?: boolean;
}

function sparklineOption(data: number[], color: string, { lineWidth = 1.5, animationDuration, area = true }: SparklineOptions = {}): EChartsOption {
  return {
    animationDuration,
    grid: { left: 0, right: 0, top: 4, bottom: 0 },
    xAxis: { type: 'category', show: false, data: data.map((_, i) => i) },
    yAxis: { type: 'value', show: false, min: (v: { min: number }) => Math.max(0, v.min * 0.9) },
    tooltip: { show: false },
    series: [
      {
        type: 'line',
        data,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: lineWidth, color },
        ...(area
          ? {
              areaStyle: {
                color: {
                  type: 'linear',
                  x: 0,
                  y: 0,
                  x2: 0,
                  y2: 1,
                  colorStops: [
                    { offset: 0, color: `${color}40` },
                    { offset: 1, color: `${color}08` },
                  ],
                },
              },
            }
          : {}),
      },
    ],
  };
}

export default function KpiCard({
  title,
  value,
  unit,
  icon,
  color = saasVisualTokens.brand.primary,
  spark,
  extra,
  loading,
  tooltip,
  className,
  style,
  ariaLabel,
  valueFormatter,
  valueSize = 28,
  dot,
  sparkHeight = 44,
  sparkLineWidth = 1.5,
  sparkAnimationDuration,
  sparkArea = true,
  sparkAriaLabel,
  showEmptySpark = false,
}: KpiCardProps) {
  const { token } = theme.useToken();
  const option = useMemo(
    () =>
      spark?.length
        ? sparklineOption(spark, color, { lineWidth: sparkLineWidth, animationDuration: sparkAnimationDuration, area: sparkArea })
        : null,
    [spark, color, sparkLineWidth, sparkAnimationDuration, sparkArea],
  );

  const body = (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, minHeight: 22 }}>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, color: token.colorTextSecondary, fontSize: 13, whiteSpace: 'nowrap' }}>
          {dot ? <span aria-hidden="true" style={{ width: 6, height: 6, borderRadius: 999, background: color, flexShrink: 0 }} /> : null}
          {title}
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          {icon ? <span style={{ color, fontSize: 18, display: 'flex' }}>{icon}</span> : null}
          {extra ? <span>{extra}</span> : null}
        </span>
      </div>
      {loading ? (
        <Skeleton active paragraph={false} title={{ width: '60%' }} />
      ) : (
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{ fontSize: valueSize, fontWeight: 600, lineHeight: 1, color: token.colorTextHeading, fontVariantNumeric: 'tabular-nums' }}>
            {valueFormatter ? valueFormatter(value ?? null) : value ?? '—'}
          </span>
          {unit ? <span style={{ fontSize: 13, color: token.colorTextTertiary }}>{unit}</span> : null}
        </div>
      )}
      {option ? (
        <div style={{ height: sparkHeight, margin: '0 -4px -4px' }}>
          <Chart option={option} height={sparkHeight} ariaLabel={sparkAriaLabel} />
        </div>
      ) : showEmptySpark && !loading ? (
        <div aria-hidden="true" style={{ height: sparkHeight, margin: '0 -4px -4px', borderBottom: `1px solid ${token.colorBorderSecondary}` }} />
      ) : null}
    </div>
  );

  const card = (
    <div
      className={className}
      aria-label={ariaLabel}
      style={{
        position: 'relative',
        minWidth: 0,
        boxSizing: 'border-box',
        width: '100%',
        background: token.colorBgContainer,
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: token.borderRadiusLG,
        boxShadow: token.boxShadowTertiary,
        padding: '14px 16px 10px',
        ...style,
      }}
    >
      {body}
    </div>
  );

  return tooltip ? <Tooltip title={tooltip}>{card}</Tooltip> : card;
}
