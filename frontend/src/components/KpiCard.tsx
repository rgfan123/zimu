/**
 * KPI 数据卡：标题 / 大数字 / 可选迷你趋势 sparkline / 可选角标。
 * 风格跟随原型决策 D（图表优先单屏）：卡片底部压一条平滑面积 sparkline。
 * 视觉基线 saasTheme：文字/表面/阴影走 token，点缀色默认品牌色，仅等待/异常 KPI
 * 由调用方传 semantic.warning / semantic.error。
 */

import type { ReactNode } from 'react';
import { Card, Skeleton, Tooltip, theme } from 'antd';
import type { EChartsOption } from 'echarts';
import { useMemo } from 'react';
import { Link } from 'react-router-dom';
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
  /** 数字可点击时指向的目标 URL（工作台「待人工介入」KPI/attention 卡直达对应列表）。 */
  valueHref?: string;
}

function sparklineOption(data: number[], color: string): EChartsOption {
  return {
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
        lineStyle: { width: 1.5, color },
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
      },
    ],
  };
}

export default function KpiCard({ title, value, unit, icon, color = saasVisualTokens.brand.primary, spark, extra, loading, tooltip, valueHref }: KpiCardProps) {
  const { token } = theme.useToken();
  const option = useMemo(() => (spark?.length ? sparklineOption(spark, color) : null), [spark, color]);

  const valueStyle: React.CSSProperties = {
    fontSize: 28,
    fontWeight: 600,
    lineHeight: 1,
    color: token.colorTextHeading,
    fontVariantNumeric: 'tabular-nums',
  };

  const body = (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span style={{ color: token.colorTextSecondary, fontSize: 13 }}>{title}</span>
        {icon ? <span style={{ color, fontSize: 18, display: 'flex' }}>{icon}</span> : null}
      </div>
      {loading ? (
        <Skeleton active paragraph={false} title={{ width: '60%' }} />
      ) : (
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          {valueHref ? (
            <Link to={valueHref} style={valueStyle} title={`前往处理：${title}`}>
              {value ?? '—'}
            </Link>
          ) : (
            <span style={valueStyle}>{value ?? '—'}</span>
          )}
          {unit ? <span style={{ fontSize: 13, color: token.colorTextTertiary }}>{unit}</span> : null}
          {extra ? <span style={{ marginLeft: 'auto' }}>{extra}</span> : null}
        </div>
      )}
      {option ? (
        <div style={{ height: 44, margin: '0 -4px -4px' }}>
          <Chart option={option} height={44} />
        </div>
      ) : null}
    </div>
  );

  const card = (
    <Card size="small" styles={{ body: { padding: '14px 16px 10px' } }}>
      {body}
    </Card>
  );

  return tooltip ? <Tooltip title={tooltip}>{card}</Tooltip> : card;
}
