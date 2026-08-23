/**
 * 对账工作台（/workbench/recon，Issue #111 + v-fin 骨架，ADR 0005）：
 * 月度骨架（账期切换、金额诚实告警、五指标、分平台数量对账表）+ 既有单笔点查
 * （三种 query_type URL 契约与七态展示零复制，经 OutboundReconPage prelude 插槽复用）。
 * 金额列显示 ¥ ——（D15 设计决定：数量口径成立、金额数据层缺失，宁可显示空也不编数）；
 * 按平台的内外差异归集端点未接入，差异明细区如实占位，单笔差异用下方点查。
 */

import { useMemo, useState } from 'react';
import { Alert, Select } from 'antd';
import dayjs from 'dayjs';
import OutboundReconPage from '@/pages/fulfillment/OutboundReconPage';
import { errorMessage } from '@/api/client';
import { useAsync } from '@/hooks/useAsync';
import { aggregateChannelMetrics, RECON_CHANNEL_LABELS } from './reconSkeleton';
import './workbench.css';

const MONTH_COUNT = 6;

function monthOptions() {
  return Array.from({ length: MONTH_COUNT }, (_, index) => {
    const month = dayjs().subtract(index, 'month');
    return { value: month.format('YYYY-MM'), label: month.format('YYYY 年 M 月') };
  });
}

function MonthlyReconSkeleton() {
  const [month, setMonth] = useState(() => dayjs().format('YYYY-MM'));
  const range = useMemo(() => {
    const start = dayjs(`${month}-01`);
    return { from: start.format('YYYY-MM-DD'), to: start.endOf('month').format('YYYY-MM-DD') };
  }, [month]);

  const channels = useAsync(async () => {
    const params = new URLSearchParams({ date_from: range.from, date_to: range.to });
    const response = await fetch(`/api/v1/analytics/channels?${params.toString()}`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) throw new Error(`分平台数据加载失败（${response.status}）`);
    const body = await response.json();
    return Array.isArray(body) ? body : [];
  }, [range.from, range.to]);

  const { rows, totals } = useMemo(() => aggregateChannelMetrics(channels.data ?? []), [channels.data]);

  const metrics = [
    { key: 'orders', cls: 'b', label: '平台下单', value: String(totals.orderCount), note: '来源订单数' },
    { key: 'canonical', cls: '', label: '来源份数', value: totals.canonicalQuantity, note: '平台口径' },
    { key: 'shipped', cls: '', label: '已发份数', value: totals.shippedQuantity, note: '来源份数口径' },
    { key: 'actual', cls: '', label: '实际发货件数', value: totals.actualShippedQuantity ?? '—', note: '×包装乘数后' },
    { key: 'amount', cls: 'w', label: '金额对账', value: '——', note: '数据层缺失' },
  ];

  return (
    <div>
      <div className="zs-ph">
        <h1>对账工作台</h1>
        <div className="zs-ph-actions">
          <Select value={month} onChange={setMonth} options={monthOptions()} style={{ width: 140 }} />
        </div>
      </div>

      <section className="zs-sec">
        <div className="zs-alert w">
          <span className="zs-ico">!</span>
          <div className="zs-b">
            <b>金额列现在是空的，这不是加载失败</b>
            <p>
              数据库 <span className="zs-mono">orders</span> 有结算方式与结算时间但没有金额字段，
              <span className="zs-mono">order_lines</span> 也没有。数量对账今天就能做，金额对账要先补结算数据层——
              宁可显示 <span className="zs-mono">¥ ——</span> 也不编一个数出来。
            </p>
          </div>
        </div>
      </section>

      <section className="zs-sec">
        <h3 className="zs-eyebrow">本账期的五个数</h3>
        <div className="zs-stats">
          {metrics.map((metric) => (
            <div key={metric.key} className={`zs-st ${metric.cls}`.trim()}>
              <div className="zs-k">
                <i className="zs-d" />
                {metric.label}
              </div>
              <div className="zs-v">{channels.loading ? '…' : metric.value}</div>
              <div className="zs-n">{metric.note}</div>
            </div>
          ))}
        </div>
      </section>

      <section className="zs-sec">
        <div className="zs-card">
          <div className="zs-hd">
            <h3>分平台对账</h3>
            <span className="zs-tag m">{range.from} ~ {range.to}</span>
          </div>
          <div className="zs-bd zs-flush">
            {channels.loading ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>正在加载分平台数据…</div>
            ) : channels.error ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>
                分平台数据加载失败：{errorMessage(channels.error)}
              </div>
            ) : rows.length === 0 ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>本账期没有渠道数据。</div>
            ) : (
              <div className="zs-tw">
                <table className="zs-tbl">
                  <thead>
                    <tr>
                      <th>来源平台</th>
                      <th className="num">平台下单<br /><span className="zs-muted">订单数</span></th>
                      <th className="num">来源份数</th>
                      <th className="num">已发<br /><span className="zs-muted">来源份数</span></th>
                      <th className="num">实际发货<br /><span className="zs-muted">件</span></th>
                      <th className="num">异常</th>
                      <th className="num">金额</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row) => (
                      <tr key={row.channel}>
                        <td>{RECON_CHANNEL_LABELS[row.channel] ?? row.channel}</td>
                        <td className="num">{row.orderCount}</td>
                        <td className="num">{row.canonicalQuantity}</td>
                        <td className="num">{row.shippedQuantity}</td>
                        <td className="num">{row.actualShippedQuantity ?? '—'}</td>
                        <td className="num">{row.exceptionCount ?? '—'}</td>
                        <td className="num na">¥ ——</td>
                      </tr>
                    ))}
                  </tbody>
                  <tfoot>
                    <tr>
                      <td>合计</td>
                      <td className="num">{totals.orderCount}</td>
                      <td className="num">{totals.canonicalQuantity}</td>
                      <td className="num">{totals.shippedQuantity}</td>
                      <td className="num">{totals.actualShippedQuantity ?? '—'}</td>
                      <td className="num">{totals.exceptionCount ?? '—'}</td>
                      <td className="num na">¥ ——</td>
                    </tr>
                  </tfoot>
                </table>
              </div>
            )}
          </div>
        </div>
      </section>

      <section className="zs-sec">
        <div className="zs-alert i">
          <span className="zs-ico">i</span>
          <div className="zs-b">
            <b>按平台的差异归集尚未接入</b>
            <p>内外事实差异的平台级汇总端点未提供；单笔出库用下方点查核对（七态逐字段判定），差异汇总随后端聚合端点点亮。</p>
          </div>
        </div>
      </section>
    </div>
  );
}

export default function ReconWorkbenchPage() {
  return (
    <OutboundReconPage
      hideHeader
      enableOrderDrilldown
      notice={
        <Alert
          type="info"
          showIcon
          message="金额对账未纳入本期"
          description="当前为数量口径：金额字段显示为 ¥ ——，金额对账将在后续版本纳入。"
        />
      }
      prelude={<MonthlyReconSkeleton />}
    />
  );
}
