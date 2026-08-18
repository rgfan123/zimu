/**
 * 数据中台 —— 单屏 bento（原型决策 D，2026-08-11 定稿）。
 *
 * 落地要点：
 *  - /analytics 单路由单屏，12 栅格 bento，不再拆四页；
 *  - 顶部全局筛选条：日期粒度（今日/7/30/自定义）+ 渠道多选 + 订单数↔实发量双口径开关；
 *    选中渠道同时约束订单、商品、履约积压、漏斗等统计；
 *  - 每张卡片两副面孔：默认图表，右上角 icon 切文字版明细（?txt=trend,funnel,...）；
 *  - 七张图卡（纯看数；需人工介入清单已移入工作台）；
 *  - 下钻：点热力色块 / 渠道占比条 → 右侧抽屉（渠道 KPI+商品构成；单元格 → 映射面板 + SKU 上钻）；
 *  - 双口径硬要求：实发量 = 乘数换算后 Canonical SKU 件数（契约 §4.7），不统计来源包装数/礼包份数/重量。
 */

import { useMemo, useState, type CSSProperties, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  DatePicker,
  Drawer,
  Empty,
  Progress,
  Segmented,
  Skeleton,
  Table,
  Tag,
  Typography,
} from 'antd';
import { BarChartOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { EChartsOption } from 'echarts';
import * as echarts from 'echarts';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import { CHANNEL_LABELS } from '@/constants/labels';
import type { SourceChannel } from '@/api/types';
import { saasVisualTokens } from '../../theme/saasTheme';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import {
  CHANNELS,
  CHANNEL_HEX,
  STATUS_HEX,
  STATUS_KEYS,
  STATUS_LABELS,
  type AnalyticsData,
  type MetricKey,
  type RangeKey,
} from './analyticsTypes';
import { useAnalyticsData, type AnalyticsFilters } from './useAnalyticsData';
import {
  backlogOption,
  categoryColor,
  channelStackOption,
  donutOption,
  funnelOption,
  heatOption,
  topProductsOption,
  trendOption,
} from './chartOptions';
import AnalyticsChart from './AnalyticsChart';
import AnalyticsKpiCard from './AnalyticsKpiCard';
import { parseChannels, serializeChannels } from './analyticsFilters';
import { analyticsCssVariables, analyticsVisualSystem } from './analyticsTheme';
import './analytics.css';

const BLUE = analyticsVisualSystem.chartColors.trend.orders;
const TEAL = analyticsVisualSystem.chartColors.trend.quantity;

const RANGE_LABEL: Record<RangeKey, string> = { today: '今日', '7d': '近 7 天', '30d': '近 30 天', custom: '自定义' };
type MarkerStyle = CSSProperties & { '--analytics-marker-color': string };

function markerStyle(color: string): MarkerStyle {
  return { '--analytics-marker-color': color };
}

const nf = new Intl.NumberFormat('zh-CN');

function fmtNum(v: number | undefined | null): string {
  return v === undefined || v === null || !Number.isFinite(v) ? '—' : nf.format(v);
}

/** 环比标签：up 对「越多越好」指标是绿色；invert 后对积压/异常类「越多越差」。 */
function DeltaTag({ cur, prev, invert }: { cur?: number; prev?: number; invert?: boolean }) {
  const c = cur ?? 0;
  const p = prev ?? 0;
  if (p <= 0) return <Tag className="analytics-delta">—</Tag>;
  const pct = ((c - p) / p) * 100;
  const up = pct >= 0;
  const good = invert ? !up : up;
  return (
    <Tag className={`analytics-delta analytics-delta--${good ? 'good' : 'bad'}`}>
      {up ? '↑' : '↓'} {Math.abs(pct).toFixed(1)}%
    </Tag>
  );
}

/** 卡片两副面孔的通用框架：标题 + 副标题 + 图例 + 图↔文字切换 icon。 */
interface BentoCardProps {
  id: string;
  title: string;
  sub?: ReactNode;
  legend?: ReactNode;
  span: number;
  chart: ReactNode;
  text: ReactNode;
  txtSet: Set<string>;
  onToggle: (id: string) => void;
}

function BentoCard({ id, title, sub, legend, span, chart, text, txtSet, onToggle }: BentoCardProps) {
  const on = txtSet.has(id);
  return (
    <div className="analytics-panel-shell" style={{ gridColumn: `span ${span}` }}>
      <section className="analytics-panel">
        <div className="analytics-panel-header">
          <span className="analytics-panel-title">{title}</span>
          {sub ? (
            <span className="analytics-card-subtitle">{sub}</span>
          ) : null}
          <div className="analytics-panel-actions-spacer" />
          {!on && legend ? legend : null}
          <Button
            type="text"
            size="small"
            icon={on ? <BarChartOutlined /> : <TableOutlined />}
            onClick={() => onToggle(id)}
            title={on ? '回到图表' : '查看文字版明细'}
            className={`analytics-panel-toggle${on ? ' is-selected' : ''}`}
          />
        </div>
        <div className="analytics-panel-body">{on ? text : chart}</div>
      </section>
    </div>
  );
}

function MiniTable<T extends object>({ columns, dataSource, rowKey, empty }: { columns: ColumnsType<T>; dataSource: T[]; rowKey: keyof T & string; empty?: string }) {
  return (
    <Table<T>
      size="small"
      columns={columns}
      dataSource={dataSource}
      rowKey={rowKey}
      pagination={false}
      scroll={{ x: 620 }}
      locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={empty ?? '暂无数据'} /> }}
    />
  );
}

function legendDot(color: string, label: string) {
  return (
    <span key={label} className="analytics-legend-item">
      <span className="analytics-legend-dot" style={markerStyle(color)} />
      {label}
    </span>
  );
}

const channelLegend = (channels: SourceChannel[]) => (
  <span className="analytics-legend">
    {channels.map((ch) => legendDot(CHANNEL_HEX[ch], CHANNEL_LABELS[ch]))}
  </span>
);

const statusLegend = () => (
  <span className="analytics-legend">
    {STATUS_KEYS.map((k) => legendDot(STATUS_HEX[k], STATUS_LABELS[k]))}
  </span>
);

/** 右侧下钻抽屉（点热力色块 / 渠道占比条打开）。 */
function DrillDrawer({ data, drill, rangeLabel, onClose }: { data: AnalyticsData | null; drill: string | null; rangeLabel: string; onClose: () => void }) {
  const parts = (drill ?? '').split(':');
  const kind = parts[0];
  const ch = parts[1] as SourceChannel | undefined;
  const key = parts[2];
  const open = (kind === 'ch' || kind === 'cell') && Boolean(ch);

  let title = '';
  let body: ReactNode = null;

  const statRow = (label: string, value: ReactNode) => (
    <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', padding: '7px 0', borderBottom: `1px dashed ${saasVisualTokens.neutral[100]}`, fontSize: 13 }}>
      <span style={{ color: saasVisualTokens.text.secondary }}>{label}</span>
      <span style={{ fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>{value}</span>
    </div>
  );

  if (kind === 'ch' && ch && data) {
    const agg = data.byChannel[ch];
    title = `${CHANNEL_LABELS[ch]} · ${rangeLabel}`;
    const prods = data.byProduct
      .map((r) => ({ label: r.label, value: r.channel[ch] ?? 0 }))
      .filter((p) => p.value > 0)
      .sort((a, b) => b.value - a.value);
    const max = Math.max(1, ...prods.map((p) => p.value));
    body = (
      <div>
        <div style={{ marginBottom: 16 }}>
          {statRow('订单数', `${fmtNum(agg?.orders)} 单`)}
          {statRow('实际发货量', `${fmtNum(agg?.qty)} 件`)}
          {statRow('异常', `${fmtNum(agg?.exceptions)} 单`)}
          {statRow('缺货', `${fmtNum(agg?.oos)} 行`)}
          {statRow('回传失败', `${fmtNum(agg?.syncFailed)} 次`)}
        </div>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          商品构成（实发件数）
        </Typography.Text>
        <div style={{ marginTop: 10 }}>
          {prods.length ? (
            prods.slice(0, 8).map((p) => (
              <div key={p.label} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 7 }}>
                <span style={{ width: 132, fontSize: 12, color: saasVisualTokens.text.secondary, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={p.label}>
                  {p.label}
                </span>
                <div style={{ flex: 1 }}>
                  <Progress percent={Math.round((p.value / max) * 100)} showInfo={false} strokeColor={CHANNEL_HEX[ch]} size="small" />
                </div>
                <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>{fmtNum(p.value)}</span>
              </div>
            ))
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该渠道无商品数据" />
          )}
        </div>
      </div>
    );
  } else if (kind === 'cell' && ch && key && data) {
    const row = data.byProduct.find((r) => r.key === key);
    if (row) {
      const qty = row.channel[ch] ?? 0;
      const chTotal = data.byChannel[ch]?.qty ?? 0;
      title = `${CHANNEL_LABELS[ch]} × ${row.label}`;
      const skuRows = row.skus.filter((s) => s.qty > 0);
      const mappings = row.sourceMappings[ch] ?? [];
      body = (
        <div>
          <div style={{ marginBottom: 16 }}>
            {statRow('实际发货量', `${fmtNum(qty)} 件`)}
            {statRow('占该渠道比', chTotal > 0 ? `${((qty / chTotal) * 100).toFixed(1)}%` : '—')}
            {statRow('占该商品比', row.total > 0 ? `${((qty / row.total) * 100).toFixed(1)}%` : '—')}
          </div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            映射
          </Typography.Text>
          <table style={{ width: '100%', marginTop: 8, fontSize: 13, borderCollapse: 'collapse' }}>
            <tbody>
              <tr>
                <td style={{ padding: '6px 0', color: saasVisualTokens.text.tertiary }}>京东 SKU</td>
                <td style={{ padding: '6px 0', fontVariantNumeric: 'tabular-nums' }}>
                  {row.jdSkuCodes.length ? row.jdSkuCodes.join('、') : '—'}
                </td>
              </tr>
              <tr>
                <td style={{ padding: '6px 0', color: saasVisualTokens.text.tertiary }}>{CHANNEL_LABELS[ch]}商品名</td>
                <td style={{ padding: '6px 0' }}>
                  {mappings.map((mapping) => mapping.source_product_name).filter(Boolean).join('、') || row.label}
                </td>
              </tr>
              <tr>
                <td style={{ padding: '6px 0', color: saasVisualTokens.text.tertiary }}>包装数量</td>
                <td style={{ padding: '6px 0' }}>
                  {mappings.length
                    ? mappings.map((mapping) => `${mapping.source_sku_ref} × ${mapping.quantity_multiplier ?? '未配置'}`).join('；')
                    : '—'}
                </td>
              </tr>
            </tbody>
          </table>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 10, marginBottom: 0 }}>
            口径提示：来源行为定制礼包时，分析指标已按订单组件快照展开；BOM 以订单详情中的礼包组件快照为准。
          </Typography.Paragraph>
          {row.isProduct && skuRows.length > 1 ? (
            <div style={{ marginTop: 18 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                上钻 · Internal SKU（{row.label}）
              </Typography.Text>
              <div style={{ marginTop: 10 }}>
                {skuRows.slice(0, 8).map((s) => (
                  <div key={s.sku_id} style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 7 }}>
                    <ProductIdentity className="analytics-product-identity" name={s.sku_name} code={s.sku_code} />
                    <span style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>{fmtNum(s.qty)}</span>
                  </div>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      );
    }
  }

  return (
    <Drawer title={title || '明细'} open={open} onClose={onClose} width={440} styles={{ body: { padding: '14px 18px' } }}>
      {body ?? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无明细" />}
    </Drawer>
  );
}

export default function AnalyticsPage() {
  const [params, setParams] = useSearchParams();
  const [drill, setDrill] = useState<string | null>(null);

  const rawRange = params.get('range') ?? '';
  const range: RangeKey = ['today', '7d', '30d', 'custom'].includes(rawRange) ? (rawRange as RangeKey) : 'today';
  const start = params.get('start') ?? undefined;
  const end = params.get('end') ?? undefined;
  const channels = useMemo(() => parseChannels(params.get('ch')), [params]);
  const metric: MetricKey = params.get('metric') === 'qty' ? 'qty' : 'orders';
  const channelsKey = channels.join(',');
  const txtSet = useMemo(() => new Set((params.get('txt') ?? '').split(',').filter(Boolean)), [params]);

  const updateParams = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) {
      if (v === null || v === '') next.delete(k);
      else next.set(k, v);
    }
    setParams(next, { replace: true });
  };

  const onRangeChange = (r: RangeKey) => {
    if (r === 'custom') {
      const to = dayjs();
      const from = to.subtract(6, 'day');
      updateParams({ range: r, start: from.format('YYYY-MM-DD'), end: to.format('YYYY-MM-DD') });
    } else {
      updateParams({ range: r, start: null, end: null });
    }
  };

  const toggleTxt = (id: string) => {
    const next = new Set(txtSet);
    if (next.has(id)) next.delete(id);
    else next.add(id);
    updateParams({ txt: next.size ? [...next].join(',') : null });
  };

  const filters: AnalyticsFilters = useMemo(
    () => ({ range, start, end, channels }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [range, start, end, channelsKey],
  );

  const { data, loading, error, reload } = useAnalyticsData(filters);

  const metricLabel = metric === 'qty' ? '实际发货量' : '订单数';
  const colors = useMemo(() => categoryColor(data?.byProduct ?? []), [data?.byProduct]);
  const seriesDays = data?.seriesDays ?? [];
  const products = data?.byProduct ?? [];

  const kpis = [
    { key: 'orders', title: '订单数', unit: '单', tone: analyticsVisualSystem.kpiTones.orders, value: data?.totals.orders, prev: data?.prev.totals.orders, invert: false, spark: seriesDays.map((d) => d.orders) },
    { key: 'qty', title: '实际发货量', unit: '件', tone: analyticsVisualSystem.kpiTones.qty, value: data?.totals.qty, prev: data?.prev.totals.qty, invert: false, spark: seriesDays.map((d) => d.qty) },
    { key: 'pending', title: '待出库', unit: '单', tone: analyticsVisualSystem.kpiTones.pending, value: data?.statusTotals.PENDING_OUTBOUND, prev: data?.prev.statusTotals.PENDING_OUTBOUND, invert: true, spark: seriesDays.map((d) => d.status.PENDING_OUTBOUND) },
    { key: 'exceptions', title: '异常', unit: '单', tone: analyticsVisualSystem.kpiTones.exceptions, value: data?.totals.exceptions, prev: data?.prev.totals.exceptions, invert: true, spark: seriesDays.map((d) => d.exceptions) },
    { key: 'oos', title: '缺货', unit: '行', tone: analyticsVisualSystem.kpiTones.oos, value: data?.totals.oos, prev: data?.prev.totals.oos, invert: true, spark: seriesDays.map((d) => d.oos) },
    { key: 'syncFailed', title: '回传失败', unit: '单', tone: analyticsVisualSystem.kpiTones.syncFailed, value: data?.totals.syncFailed, prev: data?.prev.totals.syncFailed, invert: true, spark: seriesDays.map((d) => d.syncFailed) },
  ];

  const trendOpt = useMemo(() => trendOption(seriesDays), [seriesDays]);
  const funnelOpt = useMemo(() => funnelOption(data?.funnel ?? []), [data?.funnel]);
  const chstackOpt = useMemo(() => channelStackOption(seriesDays, channels, metric), [seriesDays, channels, metric]);
  const shareOpt = useMemo(
    () => donutOption(channels.map((ch) => ({ name: CHANNEL_LABELS[ch], value: data?.byChannel[ch]?.[metric] ?? 0, color: CHANNEL_HEX[ch] }))),
    [channels, metric, data?.byChannel],
  );
  const heatOpt = useMemo(() => heatOption(products, channels), [products, channels]);
  const topOpt = useMemo(() => topProductsOption(products, colors), [products, colors]);
  const backlogOpt = useMemo(() => backlogOption(seriesDays), [seriesDays]);

  const shareTotal = channels.reduce((a, ch) => a + (data?.byChannel[ch]?.[metric] ?? 0), 0);

  /** 图表面孔：有 option 出图（加载态遮罩）；无 option 且加载中出骨架；否则空态。 */
  const chartOf = (opt: EChartsOption | null) =>
    opt ? (
      <AnalyticsChart option={opt} height={236} loading={loading} />
    ) : loading ? (
      <Skeleton active paragraph={{ rows: 4 }} />
    ) : (
      <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
    );

  const onHeatClick = (params: echarts.ECElementEvent) => {
    const [x, y] = params.value as number[];
    const row = products[x];
    const ch = channels[y];
    if (row && ch) setDrill(`cell:${ch}:${row.key}`);
  };

  return (
    <div className="analytics-page" style={analyticsCssVariables}>
      {/* 全局筛选条（决策 D：一处改动全屏联动，状态入 URL） */}
      <div className="analytics-filterbar">
        <span className="analytics-filter-title">数据看板</span>
        <Segmented
          size="small"
          value={range}
          onChange={(v) => onRangeChange(v as RangeKey)}
          options={[
            { label: '今日', value: 'today' },
            { label: '近 7 天', value: '7d' },
            { label: '近 30 天', value: '30d' },
            { label: '自定义', value: 'custom' },
          ]}
        />
        {range === 'custom' ? (
          <DatePicker.RangePicker
            size="small"
            allowClear={false}
            value={start && end ? [dayjs(start), dayjs(end)] : null}
            onChange={(dates) => {
              if (dates?.[0] && dates?.[1]) {
                updateParams({ start: dates[0].format('YYYY-MM-DD'), end: dates[1].format('YYYY-MM-DD') });
              }
            }}
          />
        ) : null}
        <span className="analytics-filter-label">渠道</span>
        <div role="group" aria-label="渠道筛选" className="analytics-channel-filter">
          {CHANNELS.map((channel) => {
            const selected = channels.includes(channel);
            return (
              <Button
                key={channel}
                size="small"
                aria-pressed={selected}
                className={`analytics-channel-button${selected ? ' is-selected' : ''}`}
                onClick={() => {
                  if (selected && channels.length === 1) return;
                  const next = CHANNELS.filter((item) => item === channel ? !selected : channels.includes(item));
                  updateParams({ ch: serializeChannels(next) });
                }}
              >
                <span
                  aria-hidden="true"
                  className="analytics-channel-dot"
                  style={markerStyle(selected ? CHANNEL_HEX[channel] : saasVisualTokens.neutral[300])}
                />
                {CHANNEL_LABELS[channel]}
              </Button>
            );
          })}
        </div>
        <span className="analytics-filter-label">口径</span>
        <Segmented
          size="small"
          value={metric}
          onChange={(v) => updateParams({ metric: v as MetricKey })}
          options={[
            { label: '订单数', value: 'orders' },
            { label: '实际发货量', value: 'qty' },
          ]}
        />
        <div style={{ flex: 1 }} />
        {loading ? (
          <span className="analytics-loading-copy">加载中…</span>
        ) : null}
      </div>

      {error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="数据中台加载失败"
          description={errorMessage(error)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
              重试
            </Button>
          }
        />
      ) : null}

      <div className="analytics-dashboard-grid">
        {/* KPI 六卡（决策 D：卡底 sparkline + 环比） */}
        <div className="analytics-overview-grid" style={{ gridColumn: 'span 12' }}>
          {kpis.map((k) => (
            <AnalyticsKpiCard
              key={k.key}
              title={k.title}
              value={k.value}
              unit={k.unit}
              accent={k.tone.accent}
              emphasis={k.tone.emphasis}
              spark={k.spark}
              loading={loading}
              extra={<DeltaTag cur={k.value} prev={k.prev} invert={k.invert} />}
            />
          ))}
        </div>

        {/* ① 订单 / 发货趋势：平滑面积 + 渐变 + 末点高亮 */}
        <BentoCard
          id="trend"
          span={8}
          title="订单 / 发货趋势"
          sub={`${seriesDays.length ? `${seriesDays[0].label} ~ ${seriesDays[seriesDays.length - 1].label}` : ''} · ${channels.map((c) => CHANNEL_LABELS[c]).join('、')}`}
          legend={
            <span className="analytics-legend">
              {legendDot(BLUE, '订单数')}
              {legendDot(TEAL, '实际发货量')}
            </span>
          }
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={chartOf(trendOpt)}
          text={
            <MiniTable<{ date: string; orders: number; qty: number; lines: number }>
              rowKey="date"
              empty="暂无趋势数据"
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '订单数', dataIndex: 'orders', align: 'right', render: fmtNum },
                { title: '实际发货量', dataIndex: 'qty', align: 'right', render: fmtNum },
                { title: 'Order Line', dataIndex: 'lines', align: 'right', render: fmtNum },
              ]}
              dataSource={[...seriesDays].reverse()}
            />
          }
        />

        {/* ② 履约漏斗（funnel + 环节通过率） */}
        <BentoCard
          id="funnel"
          span={4}
          title="履约漏斗"
          sub={`${RANGE_LABEL[range]} · 履约单`}
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={
            <div>
              {chartOf(funnelOpt)}
              <div className="analytics-chart-note">
                右侧百分比 = 相对上一环节通过率；已出库量走本漏斗与 KPI，不进积压图
              </div>
            </div>
          }
          text={
            <MiniTable<{ name: string; value: number; passPct?: number }>
              rowKey="name"
              empty="暂无漏斗数据"
              columns={[
                { title: '环节', dataIndex: 'name' },
                { title: '履约单', dataIndex: 'value', align: 'right', render: fmtNum },
                { title: '通过率', align: 'right', render: (_, r) => (r.passPct != null ? `${r.passPct}%` : '—') },
              ]}
              dataSource={data?.funnel ?? []}
            />
          }
        />

        {/* ③ 渠道构成（按天堆叠面积，口径随双口径开关联动） */}
        <BentoCard
          id="chstack"
          span={7}
          title="渠道构成"
          sub={`按天堆叠 · ${metricLabel}`}
          legend={channelLegend(channels)}
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={chartOf(chstackOpt)}
          text={
            <MiniTable<{ date: string; channels: string; total: number }>
              rowKey="date"
              empty="暂无渠道数据"
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '渠道明细', dataIndex: 'channels' },
                { title: '合计', dataIndex: 'total', align: 'right', render: fmtNum },
              ]}
              dataSource={[...seriesDays].reverse().map((d) => ({
                date: d.date,
                channels: channels.map((ch) => `${CHANNEL_LABELS[ch]} ${fmtNum(d.byChannel[ch]?.[metric])}`).join(' / '),
                total: channels.reduce((a, ch) => a + (d.byChannel[ch]?.[metric] ?? 0), 0),
              }))}
            />
          }
        />

        {/* ④ 渠道占比（甜甜圈 + 占比条，点条下钻） */}
        <BentoCard
          id="share"
          span={5}
          title="渠道占比"
          sub={`${metricLabel} · 点条下钻`}
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={
            <div>
              {chartOf(shareOpt)}
              <div style={{ marginTop: 8 }}>
                {channels.map((ch) => {
                  const v = data?.byChannel[ch]?.[metric] ?? 0;
                  const pct = shareTotal > 0 ? (v / shareTotal) * 100 : 0;
                  return (
                    <button
                      type="button"
                      key={ch}
                      onClick={() => setDrill(`ch:${ch}`)}
                      className="analytics-share-row"
                      title="点击查看渠道明细"
                    >
                      <span className="analytics-share-label">
                        <span className="analytics-channel-dot" style={markerStyle(CHANNEL_HEX[ch])} />
                        {CHANNEL_LABELS[ch]}
                      </span>
                      <div className="analytics-share-track">
                        <div className="analytics-share-fill" style={{ ...markerStyle(CHANNEL_HEX[ch]), width: `${pct}%` }} />
                      </div>
                      <span className="analytics-share-value">{pct.toFixed(1)}%</span>
                    </button>
                  );
                })}
              </div>
            </div>
          }
          text={
            <MiniTable<{ ch: string; orders: number; qty: number; pct: string }>
              rowKey="ch"
              empty="暂无渠道数据"
              columns={[
                { title: '渠道', dataIndex: 'ch', render: (v: SourceChannel) => CHANNEL_LABELS[v] },
                { title: '订单数', dataIndex: 'orders', align: 'right', render: fmtNum },
                { title: '实际发货量', dataIndex: 'qty', align: 'right', render: fmtNum },
                { title: '占比', dataIndex: 'pct', align: 'right' },
              ]}
              dataSource={channels.map((ch) => ({
                ch,
                orders: data?.byChannel[ch]?.orders ?? 0,
                qty: data?.byChannel[ch]?.qty ?? 0,
                pct: shareTotal > 0 ? `${((data?.byChannel[ch]?.[metric] ?? 0) / shareTotal * 100).toFixed(1)}%` : '—',
              }))}
            />
          }
        />

        {/* ⑤ 渠道 × 商品热力矩阵（按各渠道自身归一，点色块下钻） */}
        <BentoCard
          id="heat"
          span={7}
          title="渠道 × 商品 热力图"
          sub="实际发货量 · 底色按各渠道自身归一 · 点色块下钻"
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={
            heatOpt ? (
              <AnalyticsChart option={heatOpt} height={236} loading={loading} onChartClick={onHeatClick} ariaLabel="渠道与商品实际发货量热力图" />
            ) : loading ? (
              <Skeleton active paragraph={{ rows: 4 }} />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
            )
          }
          text={
            <MiniTable<{ key: string; label: string; category: string; channel: string; total: number }>
              rowKey="key"
              empty="暂无商品数据"
              columns={[
                { title: '商品', dataIndex: 'label', ellipsis: true },
                { title: '品类', dataIndex: 'category', width: 90 },
                { title: '渠道明细', dataIndex: 'channel' },
                { title: '合计', dataIndex: 'total', align: 'right', render: fmtNum },
              ]}
              dataSource={products.slice(0, 30).map((r) => ({
                key: r.key,
                label: r.label,
                category: r.category,
                channel: channels.map((ch) => `${CHANNEL_LABELS[ch]} ${fmtNum(r.channel[ch])}`).join(' / '),
                total: r.total,
              }))}
            />
          }
        />

        {/* ⑥ Top 商品（横向条，按品类着色） */}
        <BentoCard
          id="top"
          span={5}
          title="Top 商品"
          sub="实际发货量 · 按品类着色"
          legend={
            <span className="analytics-legend">
              {Object.entries(colors).map(([cat, col]) => legendDot(col, cat))}
            </span>
          }
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={chartOf(topOpt)}
          text={
            <MiniTable<{ key: string; label: string; category: string; total: number; pct: string }>
              rowKey="key"
              empty="暂无商品数据"
              columns={[
                { title: '商品', dataIndex: 'label', ellipsis: true },
                { title: '品类', dataIndex: 'category', width: 80 },
                { title: '实际发货量', dataIndex: 'total', align: 'right', render: fmtNum },
                { title: '占比', dataIndex: 'pct', align: 'right', width: 70 },
              ]}
              dataSource={products.slice(0, 20).map((r) => ({
                key: r.key,
                label: r.label,
                category: r.category,
                total: r.total,
                pct: (data?.totals.qty ?? 0) > 0 ? `${((r.total / (data?.totals.qty ?? 1)) * 100).toFixed(1)}%` : '—',
              }))}
            />
          }
        />

        {/* ⑦ 积压构成（堆叠面积，不含已出库——已出库量走漏斗与 KPI） */}
        <BentoCard
          id="backlog"
          span={12}
          title="积压构成"
          sub="按天堆叠 · 不含已出库（已出库量级会压扁其余状态，拆出）"
          legend={statusLegend()}
          txtSet={txtSet}
          onToggle={toggleTxt}
          chart={chartOf(backlogOpt)}
          text={
            <MiniTable<{ date: string; cells: string; total: number }>
              rowKey="date"
              empty="暂无积压数据"
              columns={[
                { title: '日期', dataIndex: 'date' },
                { title: '状态明细', dataIndex: 'cells' },
                { title: '积压合计', dataIndex: 'total', align: 'right', render: fmtNum },
              ]}
              dataSource={[...seriesDays].reverse().map((d) => ({
                date: d.date,
                cells: STATUS_KEYS.map((k) => `${STATUS_LABELS[k]} ${fmtNum(d.status[k])}`).join(' / '),
                total: STATUS_KEYS.reduce((a, k) => a + d.status[k], 0),
              }))}
            />
          }
        />
      </div>

      <span className="analytics-note">
        口径：实际发货量 = 来源包装乘数换算后的 Canonical SKU 实发件数（礼包展开组件），不统计来源包装数 / 礼包份数 / 重量（契约 §4.7）。
      </span>

      <DrillDrawer data={data} drill={drill} rangeLabel={RANGE_LABEL[range]} onClose={() => setDrill(null)} />
    </div>
  );
}
