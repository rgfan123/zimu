/**
 * 今日采购工作台（Issue #110，ADR 0005/0010，spec #120）：
 * 第一屏是建议与工单数据。建议数据层（价格采集/剔除极值/落库）由后端施工中——
 * 建议区按 ADR 0001 保留位置并如实说明；工单指标与工单表今天就是真数。
 * 建议永不创建工单、参考价永不预填（ADR 0010 / 比价价≠订单价）。
 */

import { Link } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import { procurementApi } from '@/api/endpoints';
import type { ProcurementStatus, ProcurementTicket } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import './workbench.css';

const PLACEHOLDER = '暂无汇总';

const STATUS_TAG_CLASS: Record<ProcurementStatus, string> = {
  PENDING: '',
  PARTIAL: 'warn',
  SUCCESS: 'ok',
  FAILED: 'err',
  CANCELLED: '',
};

async function countOf(params: URLSearchParams): Promise<number | null> {
  try {
    const response = await fetch(`/api/v1/procurement-tickets?${params.toString()}`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) return null;
    const body: { total_elements?: unknown } = await response.json();
    return typeof body.total_elements === 'number' ? body.total_elements : null;
  } catch {
    return null;
  }
}

interface ProcurementCounts {
  pending: number | null;
  partial: number | null;
  createdToday: number | null;
}

function useProcurementCounts(): ProcurementCounts {
  const [counts, setCounts] = useState<ProcurementCounts>({ pending: null, partial: null, createdToday: null });
  useEffect(() => {
    let cancelled = false;
    const today = dayjs().format('YYYY-MM-DD');
    Promise.all([
      countOf(new URLSearchParams({ status: 'PENDING', page: '0', size: '1' })),
      countOf(new URLSearchParams({ status: 'PARTIAL', page: '0', size: '1' })),
      countOf(new URLSearchParams({ date_from: today, date_to: today, page: '0', size: '1' })),
    ]).then(([pending, partial, createdToday]) => {
      if (!cancelled) setCounts({ pending, partial, createdToday });
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return counts;
}

function formatCount(value: number | null): string {
  return value === null ? '—' : String(value);
}

export default function ProcurementWorkbenchPage() {
  const counts = useProcurementCounts();
  const tickets = useAsync(() => procurementApi.list({ page: 0, size: 8 }), []);
  const rows = useMemo(() => tickets.data?.items ?? [], [tickets.data]);
  const total = tickets.data?.total_elements ?? null;

  const metrics = [
    { key: 'suggest', cls: 'e', label: '待我确认建议', value: null, note: `${PLACEHOLDER} · 建议数据层未接入` },
    { key: 'pending', cls: 'b', label: '待处理工单', value: counts.pending, note: '等首次回执' },
    { key: 'partial', cls: 'w', label: '部分到货', value: counts.partial, note: '余量在途' },
    { key: 'today', cls: '', label: '今日新增工单', value: counts.createdToday, note: '缺货触发' },
  ];

  return (
    <div>
      <div className="zs-ph">
        <h1>今日采购工作台</h1>
        <div className="zs-ph-actions">
          <Link to="/procurement/price-compare">采购比价工具</Link>
        </div>
      </div>

      <section className="zs-sec">
        <h3 className="zs-eyebrow">今天的四个数</h3>
        <div className="zs-stats">
          {metrics.map((metric) => (
            <div key={metric.key} className={`zs-st ${metric.cls}`.trim()}>
              <div className="zs-k">
                <i className="zs-d" />
                {metric.label}
              </div>
              <div className="zs-v">{formatCount(metric.value)}</div>
              <div className="zs-n">{metric.note}</div>
            </div>
          ))}
        </div>
      </section>

      <section className="zs-sec">
        <div className="zs-card">
          <div className="zs-hd">
            <h3>今日建议</h3>
            <span className="zs-tag m">procurement-price-agent</span>
          </div>
          <div className="zs-bd">
            <div className="zs-alert i">
              <span className="zs-ico">i</span>
              <div className="zs-b">
                <b>价格建议数据层尚未接入</b>
                <p>
                  比价 Agent 已注册（只读、强制人工确认），价格采集与建议落库由后端施工中。
                  接入后此处每日给出剔除最高最低后的中间价建议，附被剔除报盘与理由；
                  建议只能被人采纳——不自动创建工单、参考价不进任何价格字段。
                </p>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="zs-sec" id="zs-tickets">
        <div className="zs-card">
          <div className="zs-hd">
            <h3>我发起的采购工单</h3>
            {total !== null ? <span className="zs-tag">{total} 单</span> : null}
            <div className="zs-r">
              <Link to="/procurement/tickets">采购协同</Link>
            </div>
          </div>
          <div className="zs-bd zs-flush">
            {tickets.loading ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>正在加载工单…</div>
            ) : tickets.error ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>工单加载失败：{errorMessage(tickets.error)}</div>
            ) : rows.length === 0 ? (
              <div className="zs-hint" style={{ padding: '12px 16px' }}>
                当前没有采购工单——工单由履约缺货自动创建，不由建议或预算创建。
              </div>
            ) : (
              <div className="zs-tw">
                <table className="zs-tbl">
                  <thead>
                    <tr>
                      <th>工单号</th>
                      <th>条目</th>
                      <th className="num">请求</th>
                      <th className="num">已到</th>
                      <th className="num">剩余</th>
                      <th>状态</th>
                      <th>创建时间</th>
                      <th />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((ticket: ProcurementTicket) => (
                      <tr key={ticket.id}>
                        <td className="zs-mono">{ticket.ticket_no}</td>
                        <td>{ticket.items.length} 个 SKU</td>
                        {/* 数量为十进制字符串：原样展示，不做 Number 运算（防精度丢失） */}
                        <td className="num">{ticket.requested_quantity}</td>
                        <td className="num">{ticket.fulfilled_quantity}</td>
                        <td className="num">{ticket.remaining_quantity}</td>
                        <td>
                          <span className={`zs-tag ${STATUS_TAG_CLASS[ticket.status] ?? ''}`.trim()}>{ticket.status}</span>
                        </td>
                        <td>{dayjs(ticket.created_at).format('MM-DD HH:mm')}</td>
                        <td>
                          <Link to="/procurement/tickets">处理</Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
