/**
 * 今日采购工作台（Issue #110，ADR 0005/0010，spec #120）：
 * 第一屏是建议与工单数据，全部真数：指标与工单表来自 procurement-tickets；
 * 建议区直接调用已注册的比价 Agent（POST /procurement-price-agent/compare，只读运行，
 * 复用 agent console 的运行时与留痕），展示可比候选 / 被剔除候选与理由 / 推荐 / 模型留痕。
 * 建议永不创建工单、参考价永不预填（ADR 0010 / 比价价≠订单价）。
 */

import { Link } from 'react-router-dom';
import { useEffect, useMemo, useState } from 'react';
import { Button } from 'antd';
import dayjs from 'dayjs';
import { formatDateTime } from '@/format/dateTime';
import { errorMessage } from '@/api/client';
import { procurementApi, procurementPriceAgentApi } from '@/api/endpoints';
import type { ProcurementStatus, ProcurementTicket } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { presentSuggestion, type SuggestionCardView } from './procurementSuggestion';
import ProcurementSuggestionCard from './ProcurementSuggestionCard';
import './workbench.css';

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

/** 一次为若干缺货工单跑比价 Agent（只读）；每张卡独立成败，一单失败不拖垮整屏。 */
function useSuggestionRunner() {
  const [running, setRunning] = useState(false);
  const [views, setViews] = useState<SuggestionCardView[] | null>(null);
  const [failed, setFailed] = useState(0);

  const run = async (tickets: ProcurementTicket[]) => {
    setRunning(true);
    setFailed(0);
    const settled = await Promise.allSettled(
      tickets.map(async (ticket) => {
        const result = await procurementPriceAgentApi.compare({ procurement_ticket_id: ticket.id });
        return presentSuggestion({ id: ticket.id, ticket_no: ticket.ticket_no }, result);
      }),
    );
    const ok: SuggestionCardView[] = [];
    let failures = 0;
    for (const outcome of settled) {
      if (outcome.status === 'fulfilled') ok.push(outcome.value);
      else failures += 1;
    }
    setViews(ok);
    setFailed(failures);
    setRunning(false);
  };

  return { running, views, failed, run };
}

export default function ProcurementWorkbenchPage() {
  const counts = useProcurementCounts();
  const tickets = useAsync(() => procurementApi.list({ page: 0, size: 8 }), []);
  const rows = useMemo(() => tickets.data?.items ?? [], [tickets.data]);
  const total = tickets.data?.total_elements ?? null;
  const suggestions = useSuggestionRunner();
  /** 只为「还缺货」的工单比价：已完成/已取消的没有采购决策可做。 */
  const openTickets = useMemo(
    () => rows.filter((ticket) => ticket.status === 'PENDING' || ticket.status === 'PARTIAL' || ticket.status === 'FAILED'),
    [rows],
  );

  const metrics = [
    {
      key: 'suggest',
      cls: 'e',
      label: '待我确认建议',
      value: suggestions.views ? suggestions.views.filter((view) => !view.errorCode).length : null,
      note: suggestions.views ? '本次运行 · 均需人工确认' : '点「为缺货工单比价」运行',
    },
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

      <section className="zs-sec" id="zs-suggest">
        <div className="zs-card">
          <div className="zs-hd">
            <h3>比价建议</h3>
            <span className="zs-tag m">procurement-price-agent</span>
            <div className="zs-r">
              <Button
                type="primary"
                size="small"
                loading={suggestions.running}
                disabled={openTickets.length === 0}
                onClick={() => suggestions.run(openTickets)}
              >
                为缺货工单比价（{openTickets.length}）
              </Button>
            </div>
          </div>
          <div className="zs-bd">
            {suggestions.running ? (
              <div className="zs-hint">正在为 {openTickets.length} 张缺货工单运行比价 Agent…</div>
            ) : suggestions.views === null ? (
              <div className="zs-alert i">
                <span className="zs-ico">i</span>
                <div className="zs-b">
                  <b>点上方按钮为缺货工单跑一次比价</b>
                  <p>
                    Agent 只读运行：给出可比候选、被剔除候选与理由、推荐与留痕，全部标记「需人工确认」。
                    它不创建工单、不改任何价格——工单由履约缺货产生，成交价电话确认后由人手填。
                  </p>
                </div>
              </div>
            ) : suggestions.views.length === 0 ? (
              <div className="zs-hint">本次没有产生建议（{suggestions.failed} 张工单运行失败）。</div>
            ) : (
              <>
                {suggestions.failed > 0 ? (
                  <div className="zs-hint" style={{ marginBottom: 10 }}>
                    {suggestions.failed} 张工单比价请求失败，已跳过；其余结果如下。
                  </div>
                ) : null}
                <div className="zs-pgrid">
                  {suggestions.views.map((view) => (
                    <ProcurementSuggestionCard key={view.ticketId} view={view} />
                  ))}
                </div>
              </>
            )}
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
                        <td>{formatDateTime(ticket.created_at)}</td>
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
