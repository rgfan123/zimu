import { Skeleton } from 'antd';
import type { EChartsOption } from 'echarts';
import { useMemo, type CSSProperties, type ReactNode } from 'react';
import AnalyticsChart from './AnalyticsChart';
import type { AnalyticsKpiEmphasis } from './analyticsVisual';

interface AnalyticsKpiCardProps {
  title: string;
  value?: number | null;
  unit: string;
  accent: string;
  emphasis: AnalyticsKpiEmphasis;
  spark: number[];
  loading: boolean;
  extra: ReactNode;
}

type AccentStyle = CSSProperties & { '--analytics-kpi-accent': string };

const numberFormat = new Intl.NumberFormat('zh-CN');

export default function AnalyticsKpiCard({
  title,
  value,
  unit,
  accent,
  emphasis,
  spark,
  loading,
  extra,
}: AnalyticsKpiCardProps) {
  const option = useMemo<EChartsOption | null>(() => {
    if (!spark.length) return null;
    return {
      animationDuration: 160,
      grid: { left: 0, right: 0, top: 4, bottom: 0 },
      xAxis: { type: 'category', show: false, data: spark.map((_, index) => index) },
      yAxis: { type: 'value', show: false, min: (range: { min: number }) => Math.max(0, range.min * 0.9) },
      tooltip: { show: false },
      series: [{
        type: 'line',
        data: spark,
        smooth: true,
        symbol: 'none',
        lineStyle: { width: emphasis === 'regular' ? 1.25 : 1.75, color: accent },
      }],
    };
  }, [accent, emphasis, spark]);

  return (
    <section
      className={`analytics-kpi-card analytics-kpi-card--${emphasis}`}
      style={{ '--analytics-kpi-accent': accent } as AccentStyle}
      aria-label={`${title}：${value == null ? '暂无数据' : `${numberFormat.format(value)} ${unit}`}`}
    >
      <div className="analytics-kpi-heading">
        <span className="analytics-kpi-label">
          <span className="analytics-kpi-dot" aria-hidden="true" />
          {title}
        </span>
        {extra}
      </div>
      {loading ? (
        <Skeleton active paragraph={false} title={{ width: '58%' }} />
      ) : (
        <div className="analytics-kpi-value-row">
          <strong className="analytics-kpi-value">{value == null ? '—' : numberFormat.format(value)}</strong>
          <span className="analytics-kpi-unit">{unit}</span>
        </div>
      )}
      {option && !loading ? (
        <div className="analytics-kpi-spark">
          <AnalyticsChart option={option} height={34} ariaLabel={`${title}趋势`} />
        </div>
      ) : (
        <div className="analytics-kpi-spark analytics-kpi-spark--empty" aria-hidden="true" />
      )}
    </section>
  );
}
