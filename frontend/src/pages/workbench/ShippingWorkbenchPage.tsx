/**
 * 今日发货工作台（Issue #107 同步动线 + #108 骨架先行，ADR 0005/0006）：
 * 第一屏即数据——七指标（真数）、八段链路、复核分组预览、运营告警一次全部在位；
 * 拼不出的口径就地「暂无汇总」诚实态，绝不伪造。计数单一来源 useShippingSummary（#119 接缝）。
 * 同步动线（POST refresh、逐渠道诚实结果）原样保留在 ShippingSyncResults，契约由既有测试锁定。
 */

import { useMemo, useRef, useState } from 'react';
import { Button } from 'antd';
import dayjs from 'dayjs';
import { CloudSyncOutlined, FileExcelOutlined } from '@ant-design/icons';
import { Link, useNavigate } from 'react-router-dom';
import { ordersApi, platformOrdersApi } from '@/api/endpoints';
import { useAsync } from '@/hooks/useAsync';
import { errorMessage } from '@/api/client';
import { formatDateTime } from '@/format/dateTime';
import { extractBlockerCases, mergeBlockers, groupBlockers, PREVIEW_BLOCKED_REASON, type BlockerCase } from './blockerGrouping';
import { extractStockBlockerCases, mergeStockBlockers, STOCK_BLOCKED_REASON, type StockBlockerCase } from './stockBlockerCases';
import { orderIdsToLoad, presentStockBlockerCard } from './reviewCardPresentation';
import { toOrderFacts } from './orderFacts';
import { JdBlockerFixDrawer } from './JdBlockerFixDrawer';
import { presentAwaitingTracking } from './awaitingTracking';
import { readStoredWorkbenchRole } from '@/workbenchRole';
import { reviewTeamForRole } from '@/components/layout/useRailBadges';
import { alertsQueueUrl, reviewsQueueUrl } from '../shared/reviewQueueUrl';
import ShippingSyncResults, { type SyncState } from './ShippingSyncResults';
import PendingConfirmationSection from './PendingConfirmationSection';
import { useShippingSummary } from './useShippingSummary';
import { groupReviewPreview, JD_GATE_ZERO_COPY, presentAlertRows } from './shippingSkeleton';
import './workbench.css';

const PLACEHOLDER = '暂无汇总';
const REVIEW_PREVIEW_SIZE = 50;
/** 逐单拉订单详情的上限：第一屏不该为了小字副行打出几十个请求；超出的卡片退回诚实态。 */
const ORDER_FACTS_LIMIT = 20;
const SHIPPING_SYNC_SESSION_KEY = 'zimu.shipping-workbench.latest-platform-pull';
const PULL_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function formatCount(value: number | null): string {
  return value === null ? '—' : String(value);
}

function jumpTo(anchor: string) {
  const target = document.querySelector(anchor);
  if (target && typeof target.scrollIntoView === 'function') {
    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

function safeSyncSnapshot(result: Awaited<ReturnType<typeof platformOrdersApi.refresh>>) {
  const channels = (Array.isArray(result.channels) ? result.channels : []).map((item) => {
    const candidate: unknown = item;
    const channel = candidate && typeof candidate === 'object' && !Array.isArray(candidate)
      ? candidate as Record<string, unknown>
      : {};
    const safe: Record<string, unknown> = {};
    for (const key of ['channel', 'status', 'business_code', 'batch_no', 'batch_id'] as const) {
      if (typeof channel[key] === 'string') safe[key] = channel[key];
    }
    if (typeof channel.order_count === 'number' && Number.isFinite(channel.order_count)) {
      safe.order_count = channel.order_count;
    }
    if (channel.row_counts && typeof channel.row_counts === 'object' && !Array.isArray(channel.row_counts)) {
      const counts = channel.row_counts as Record<string, unknown>;
      const safeCounts: Record<string, number> = {};
      for (const key of ['total', 'accepted', 'need_review', 'rejected'] as const) {
        if (typeof counts[key] === 'number' && Number.isFinite(counts[key])) safeCounts[key] = counts[key];
      }
      safe.row_counts = safeCounts;
    }
    return safe;
  });
  return {
    channels,
    ...(typeof result.date_begin === 'string' && PULL_DATE_PATTERN.test(result.date_begin)
      ? { date_begin: result.date_begin }
      : {}),
    ...(typeof result.date_end === 'string' && PULL_DATE_PATTERN.test(result.date_end)
      ? { date_end: result.date_end }
      : {}),
  } as Awaited<ReturnType<typeof platformOrdersApi.refresh>>;
}

function restoredSyncState(): SyncState {
  try {
    const stored = window.sessionStorage.getItem(SHIPPING_SYNC_SESSION_KEY);
    if (!stored) return { phase: 'idle' };
    const parsed = JSON.parse(stored) as { result?: unknown };
    if (!parsed || typeof parsed !== 'object' || !parsed.result || typeof parsed.result !== 'object') {
      return { phase: 'idle' };
    }
    const result = parsed.result as Awaited<ReturnType<typeof platformOrdersApi.refresh>>;
    if (!Array.isArray(result.channels)) return { phase: 'idle' };
    return { phase: 'success', result: safeSyncSnapshot(result) };
  } catch {
    return { phase: 'idle' };
  }
}

function storeSyncSnapshot(result: Awaited<ReturnType<typeof platformOrdersApi.refresh>>) {
  try {
    window.sessionStorage.setItem(
      SHIPPING_SYNC_SESSION_KEY,
      JSON.stringify({ result: safeSyncSnapshot(result) }),
    );
  } catch {
    // sessionStorage 不可用时不阻断本次同步；后端 audit 仍是权威留痕。
  }
}

function clearSyncSnapshot() {
  try {
    window.sessionStorage.removeItem(SHIPPING_SYNC_SESSION_KEY);
  } catch {
    // sessionStorage 不可用时仍允许发起本次同步。
  }
}

export default function ShippingWorkbenchPage() {
  const [state, setState] = useState<SyncState>(restoredSyncState);
  // #115：原生 disabled 落到 DOM 前仍可能收到同一事件循环内的第二次触发；ref 是最后一道前端重入门禁。
  // 跨浏览器/跨实例的权威并发边界在后端 PlatformPullSingleFlight（advisory lock）。
  const syncInFlight = useRef(false);
  const navigate = useNavigate();
  const team = reviewTeamForRole(readStoredWorkbenchRole());
  const counts = useShippingSummary(team, state.phase === 'success' ? state.result : null);

  const sync = async () => {
    if (syncInFlight.current) return;
    syncInFlight.current = true;
    // 开始新一次同步时旧快照已不再是“最新结果”；若本次失败，不得跨路由恢复旧成功态。
    clearSyncSnapshot();
    setState({ phase: 'loading' });
    try {
      // 拉取窗口显式收窄到「今天」：后端默认回溯 30 天，彩食鲜侧导出任务会跑到网关超时
      // （实测 30 天 >256s 超时，1 天 9s、7 天 7s）。「今日发货工作台」的语义本就是当天。
      const today = dayjs().format('YYYY-MM-DD');
      const result = await platformOrdersApi.refresh({ date_begin: today, date_end: today });
      setState({ phase: 'success', result });
      storeSyncSnapshot(result);
    } catch (error) {
      setState({ phase: 'error', error });
    } finally {
      syncInFlight.current = false;
    }
  };

  const syncing = state.phase === 'loading';
  const metrics = [
    { key: 'installed', cls: 'b click', label: '已入库订单', value: counts.installedToday, note: '今日新建', jump: '#zs-pipe' },
    {
      key: 'reported',
      cls: 'w',
      label: '仅报告未入库',
      value: counts.reportedNotImported,
      note: counts.reportedNotImported != null ? '本次同步' : `${PLACEHOLDER} · 见本次同步结果`,
      jump: null,
    },
    { key: 'review', cls: 'e click', label: '待我人工复核', value: counts.reviewOpen, note: '阻断整批确认', jump: '#zs-review' },
    { key: 'ready', cls: 'click', label: '待发货', value: counts.readyToExport, note: '已确认待出库', jump: '#zs-pipe' },
    { key: 'waiting', cls: '', label: '发货中', value: counts.waitingProvider, note: '等运单回填', jump: null },
    { key: 'shipped', cls: 's', label: '已发货已回填', value: counts.shippedToday, note: '今日建单口径', jump: null },
    { key: 'backfill-failed', cls: 'e click', label: '回填失败', value: null, note: `${PLACEHOLDER}（全局清单未接入）`, jump: '#zs-fail' },
  ];

  const segments = [
    {
      name: '1 平台拉取',
      value: counts.platformPullChannels,
      status: counts.platformPullSuccesses == null ? PLACEHOLDER : `${counts.platformPullSuccesses} 成功`,
      cls: counts.platformPullSuccesses == null ? 'wait' : '',
    },
    {
      name: '2 落导入批次',
      value: counts.importedBatches,
      status: counts.importedRows == null ? PLACEHOLDER : `${counts.importedRows} 行`,
      cls: counts.importedRows == null ? 'wait' : '',
    },
    {
      name: '3 SKU / 客户识别',
      value: counts.needReview,
      status: counts.needReview === null ? '' : counts.needReview > 0 ? `${counts.needReview} 待人工` : '无待人工',
      cls: counts.needReview !== null && counts.needReview > 0 ? 'err' : '',
    },
    { name: '4 整批确认', value: counts.readyToExport, status: '等整批确认', cls: '' },
    { name: '5 京东出库提交', value: null, status: PLACEHOLDER, cls: 'wait' },
    { name: '6 运单回填', value: counts.waitingProvider, status: '等运单回传', cls: '' },
    { name: '7 来源回填表', value: counts.trackingReceived, status: '待生成', cls: '' },
    { name: '8 回传来源平台', value: counts.returnFileReady, status: '待回传', cls: '' },
  ];

  return (
    <div>
      {/* 页头即动作行（ADR 0005）：标题左、同步动作右，没有独立的按钮卡，没有闲置提示 */}
      <div className="zs-ph">
        <h1>今日发货工作台</h1>
        <div className="zs-ph-actions">
          <Button type="primary" icon={<CloudSyncOutlined />} loading={syncing} disabled={syncing} onClick={sync}>
            开始今日订单同步
          </Button>
          {/* 单一 <a>（Button href），不 Link 包 Button：保留真实 href 与键盘可达性，点击经路由导航。 */}
          <Button
            icon={<FileExcelOutlined />}
            href="/fulfillment/sales-outbound"
            onClick={(event) => {
              event.preventDefault();
              navigate('/fulfillment/sales-outbound');
            }}
          >
            手动导入 Excel
          </Button>
        </div>
      </div>

      {state.phase !== 'idle' ? (
        <div className="zs-sec">
          <ShippingSyncResults state={state} onRetry={sync} />
        </div>
      ) : null}

      {/* 七指标（#108）：数字全中性，状态点 + 左边框；可点卡滚动到对应区 */}
      <section className="zs-sec">
        <h3 className="zs-eyebrow">今天的七个数</h3>
        <div className="zs-stats">
          {metrics.map((metric) => (
            <div
              key={metric.key}
              className={`zs-st ${metric.cls}`.trim()}
              {...(metric.jump
                ? {
                    role: 'button',
                    tabIndex: 0,
                    onClick: () => jumpTo(metric.jump),
                    onKeyDown: (event: { key: string }) => {
                      if (event.key === 'Enter' || event.key === ' ') jumpTo(metric.jump);
                    },
                  }
                : {})}
            >
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

      {/* 八段链路（#108）：可得段位真数，不可得段位诚实占位 */}
      <section className="zs-sec" id="zs-pipe">
        <div className="zs-card">
          <div className="zs-hd">
            <h3>八段链路 · 今天卡在哪一段</h3>
          </div>
          <div className="zs-bd">
            <div className="zs-pipe">
              {segments.map((segment) => (
                <div key={segment.name} className={`zs-pstep ${segment.cls}`.trim()}>
                  <div className="zs-n">{segment.name}</div>
                  <div className="zs-v">{formatCount(segment.value)}</div>
                  <div className="zs-s">{segment.status}</div>
                </div>
              ))}
            </div>
            <div className="zs-legend">
              <span><i style={{ background: 'var(--zs-success)' }} />完成</span>
              <span><i style={{ background: 'var(--zs-brand)' }} />系统处理中</span>
              <span><i style={{ background: 'var(--zs-warning)' }} />等人 / 等外部</span>
              <span><i style={{ background: 'var(--zs-error)' }} />异常需介入</span>
              <span><i style={{ background: 'var(--zs-border)' }} />未开始 / 暂无汇总</span>
            </div>
          </div>
        </div>
      </section>

      <PendingConfirmationSection />
      <ReviewPreviewSection team={team} reviewOpen={counts.reviewOpen} />
      <AwaitingTrackingSection />
      <AlertsSection />
    </div>
  );
}

function ReviewPreviewSection({ team, reviewOpen }: { team: string | null; reviewOpen: number | null }) {
  const preview = useAsync(async () => {
    const params = new URLSearchParams({ status: 'OPEN', page: '0', size: String(REVIEW_PREVIEW_SIZE) });
    if (team) params.set('responsible_team', team);
    const response = await fetch(`/api/v1/review-cases?${params.toString()}`, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`复核清单加载失败（${response.status}）`);
    return (await response.json()) as { items?: unknown[]; total_elements?: number };
  }, [team]);

  const groups = useMemo(() => groupReviewPreview(preview.data?.items ?? [], team), [preview.data, team]);
  const total = preview.data?.total_elements ?? null;
  const partial = total !== null && total > REVIEW_PREVIEW_SIZE;

  // 结构化阻塞项就在这批事项的 DTO 里（review_cases.detail.blockers），
  // 此前被 groupReviewPreview 数完 reason_code 就丢掉。不额外发请求。
  const blockerCases = useMemo(
    () => extractBlockerCases(preview.data?.items ?? []),
    [preview.data],
  );
  // 京东库存/映射阻断（JD_STOCK_BLOCKED）：同源同法，detail.blockers 早已带着逐条商品
  // 身份，此前只有「去处理」链接，现在跟 PREVIEW_BLOCKED_REASON 一样接就地处置抽屉。
  const stockBlockerCases = useMemo(
    () => extractStockBlockerCases(preview.data?.items ?? []),
    [preview.data],
  );
  const [fixing, setFixing] = useState<BlockerCase | null>(null);
  const [fixingStock, setFixingStock] = useState<StockBlockerCase | null>(null);

  // 卡片要显示的「平台商品名 + 收货人」两样都不在复核事项里（前者事项带的是我方内部名，
  // 后者根本不带，且 raw_cells 里的姓名/电话已被导入时脱敏），只能按 order_id 去订单详情取。
  // 去重后逐单拉；单条失败不拖垮整屏，卡片自动退回「收货人信息待加载」的诚实态。
  const stockOrderIds = useMemo(() => orderIdsToLoad(stockBlockerCases), [stockBlockerCases]);
  const stockOrderKey = stockOrderIds.join(',');
  const orderFacts = useAsync(async () => {
    const loaded = await Promise.all(
      stockOrderIds.slice(0, ORDER_FACTS_LIMIT).map(async (orderId) => {
        try {
          return [orderId, toOrderFacts(await ordersApi.detail(orderId))] as const;
        } catch {
          return null;
        }
      }),
    );
    return Object.fromEntries(loaded.filter((entry): entry is NonNullable<typeof entry> => entry !== null));
  }, [stockOrderKey]);
  const factsFor = (orderId: string | null) => (orderId ? orderFacts.data?.[orderId] ?? null : null);

  return (
    <section className="zs-sec" id="zs-review">
      <div className="zs-card">
        <div className="zs-hd">
          <h3>待我人工复核</h3>
          {reviewOpen !== null && reviewOpen > 0 ? <span className="zs-tag err">{reviewOpen} 项阻断整批确认</span> : null}
          <div className="zs-r">
            <Link to={reviewsQueueUrl({ status: 'OPEN', team: team ?? undefined })}>去收件箱处理</Link>
          </div>
        </div>
        <div className="zs-bd zs-flush">
          {preview.loading ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>正在加载复核清单…</div>
          ) : preview.error ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>
              复核清单加载失败：{errorMessage(preview.error)}
            </div>
          ) : (
            <div className="zs-rq" style={{ padding: 12 }}>
              {partial ? (
                <div className="zs-hint">按前 {REVIEW_PREVIEW_SIZE} 条分组统计，共 {total} 条，完整清单见收件箱。</div>
              ) : null}
              {groups.map((group, index) => (
                <details key={group.reasonCode} className="zs-rqg" open={index === 0 && group.count > 0}>
                  <summary>
                    {group.label}
                    <span className="zs-c">{group.count} 项</span>
                  </summary>
                  {group.reasonCode === PREVIEW_BLOCKED_REASON && blockerCases.length > 0 ? (
                    blockerCases.map((blockerCase) => (
                      <div className="zs-rqi" key={blockerCase.caseId}>
                        <div className="zs-w">
                          <div className="zs-l1">
                            {blockerCase.caseNo ?? `事项 ${blockerCase.caseId}`}
                            <span className="zs-c">{blockerCase.blockers.length} 项</span>
                          </div>
                          <div className="zs-l2">
                            {groupBlockers(blockerCase.blockers)
                              .map((g) => `${g.label} ${g.items.length} 项`)
                              .join(' · ')}
                          </div>
                        </div>
                        <div className="zs-a">
                          <button type="button" className="zs-lnk" onClick={() => setFixing(blockerCase)}>
                            就地处置
                          </button>
                        </div>
                      </div>
                    ))
                  ) : group.reasonCode === STOCK_BLOCKED_REASON && stockBlockerCases.length > 0 ? (
                    stockBlockerCases.map((stockCase) => {
                      // 主行「平台商品名 + 具体原因」、副行收货人，RC 编码退进 title 属性——
                      // 它对人没有信息量，但排查时还得能对上单（见 reviewCardPresentation 头注释）。
                      const view = presentStockBlockerCard(stockCase, factsFor(stockCase.orderId));
                      const body = (
                        <>
                          <div className="zs-l1">
                            {view.title}
                            <span className="zs-c">{view.blockerCount} 项</span>
                          </div>
                          <div className="zs-l2">{view.subtitle}</div>
                        </>
                      );
                      return (
                        <div className="zs-rqi" key={view.caseId} title={view.caseNo ?? undefined}>
                          <div className="zs-w">
                            {/* 整块文案就是通往该订单的链接；没有关联订单时不造假链接，退成纯文本。 */}
                            {view.orderHref ? (
                              <Link className="zs-rql" to={view.orderHref}>{body}</Link>
                            ) : (
                              body
                            )}
                          </div>
                          <div className="zs-a">
                            <button type="button" className="zs-lnk pri" onClick={() => setFixingStock(stockCase)}>
                              就地处置
                            </button>
                          </div>
                        </div>
                      );
                    })
                  ) : (
                    <div className="zs-rqi">
                      <div className="zs-w">
                        {group.count === 0 ? (
                          <div className="zs-l2 zs-muted">{JD_GATE_ZERO_COPY}</div>
                        ) : (
                          <div className="zs-l2">同类事项 {group.count} 项，在收件箱按此类型预筛后逐条处理。</div>
                        )}
                      </div>
                      {group.count > 0 ? (
                        <div className="zs-a">
                          <Link to={group.url} className="zs-lnk">去处理</Link>
                        </div>
                      ) : null}
                    </div>
                  )}
                </details>
              ))}
            </div>
          )}
        </div>
      </div>
      <JdBlockerFixDrawer
        open={fixing !== null || fixingStock !== null}
        shipmentId={fixing?.shipmentId ?? fixingStock?.shipmentId ?? null}
        blockers={fixing ? mergeBlockers([fixing]) : []}
        stockBlockers={fixingStock ? mergeStockBlockers([fixingStock]) : undefined}
        orderId={fixingStock?.orderId ?? null}
        onClose={() => { setFixing(null); setFixingStock(null); }}
        onResolved={() => preview.reload?.()}
      />
    </section>
  );
}

/**
 * 已发出待回单：回答「这单在等什么」。
 *
 * 京东与第三方等的东西完全不同——前者是系统按 60s 一轮自动查询京东
 * （ShipmentJdTrackingPoller），后者是**等人回传运单文件**，系统只能催。
 * 混在一栏里会让人以为第三方那几单也在自动推进，所以分两组、各自写明在等谁。
 */
function AwaitingTrackingSection() {
  const data = useAsync(async () => {
    const [shipmentsRes, providersRes] = await Promise.all([
      fetch('/api/v1/shipments?shipment_status=CREATED&size=50', { headers: { Accept: 'application/json' } }),
      fetch('/api/v1/fulfillment-providers', { headers: { Accept: 'application/json' } }),
    ]);
    if (!shipmentsRes.ok) throw new Error(`发货批次加载失败（${shipmentsRes.status}）`);
    if (!providersRes.ok) throw new Error(`履约方加载失败（${providersRes.status}）`);
    const shipments = (await shipmentsRes.json()) as { items?: unknown[] };
    const providers = (await providersRes.json()) as Array<{ id?: unknown; provider_type?: unknown }>;
    const typeById = new Map<string, string>();
    for (const provider of providers) {
      if (typeof provider?.id === 'string' && typeof provider?.provider_type === 'string') {
        typeById.set(provider.id, provider.provider_type);
      }
    }
    return presentAwaitingTracking(shipments.items ?? [], typeById);
  }, []);

  const view = data.data;

  return (
    <section className="zs-sec" id="zs-awaiting">
      <div className="zs-card">
        <div className="zs-hd">
          <h3>已发出待回单</h3>
          {view && view.total > 0 ? <span className="zs-tag">{view.total} 单</span> : null}
        </div>
        <div className="zs-bd zs-flush">
          {data.loading ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>正在加载…</div>
          ) : data.error ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>加载失败：{errorMessage(data.error)}</div>
          ) : !view || view.total === 0 ? (
            <div className="zs-hint" style={{ padding: '12px 16px' }}>当前没有等待回单的批次。</div>
          ) : (
            <div className="zs-rq" style={{ padding: 12 }}>
              <details className="zs-rqg" open={view.jd.length > 0}>
                <summary>
                  京东 SDK · 系统自动查询
                  <span className="zs-c">{view.jd.length} 单</span>
                </summary>
                {view.jd.length === 0 ? (
                  <div className="zs-rqi"><div className="zs-w"><div className="zs-l2 zs-muted">今天没有。</div></div></div>
                ) : view.jd.map((row) => (
                  <div className="zs-rqi" key={row.shipmentId}>
                    <div className="zs-w">
                      <div className="zs-l1">{row.shipmentNo}</div>
                      <div className="zs-l2">
                        出库号 {row.erpDeliveryNo ?? '——'}
                        {' · '}已查询 {row.attempts} 次
                        {row.lastQueryAt ? ` · 上次 ${formatDateTime(row.lastQueryAt)}` : ' · 尚未查询'}
                      </div>
                    </div>
                  </div>
                ))}
              </details>
              <details className="zs-rqg" open={view.thirdParty.length > 0}>
                <summary>
                  第三方 · 等对方回传运单
                  <span className="zs-c">{view.thirdParty.length} 单</span>
                </summary>
                {view.thirdParty.length === 0 ? (
                  <div className="zs-rqi"><div className="zs-w"><div className="zs-l2 zs-muted">今天没有。</div></div></div>
                ) : view.thirdParty.map((row) => (
                  <div className="zs-rqi" key={row.shipmentId}>
                    <div className="zs-w">
                      <div className="zs-l1">{row.shipmentNo}</div>
                      <div className="zs-l2">已导出发货清单，等对方回传运单文件——系统不会自动获取。</div>
                    </div>
                  </div>
                ))}
              </details>
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

function AlertsSection() {
  const alerts = useAsync(async () => {
    const params = new URLSearchParams({ status: 'OPEN', page: '0', size: '5' });
    const response = await fetch(`/api/v1/operational-alerts?${params.toString()}`, { headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`告警加载失败（${response.status}）`);
    return (await response.json()) as { items?: unknown[]; total_elements?: number };
  }, []);

  const rows = useMemo(() => presentAlertRows(alerts.data?.items ?? []), [alerts.data]);
  const total = alerts.data?.total_elements ?? null;

  return (
    <section className="zs-sec" id="zs-fail">
      <div className="zs-card">
        <div className="zs-hd">
          <h3>运营告警</h3>
          {total !== null && total > 0 ? <span className="zs-tag warn">{total} 条待处理</span> : null}
          <div className="zs-r">
            <Link to={alertsQueueUrl()}>去提醒中心</Link>
          </div>
        </div>
        <div className="zs-bd">
          {alerts.loading ? (
            <div className="zs-hint">正在加载告警…</div>
          ) : alerts.error ? (
            <div className="zs-hint">告警加载失败：{errorMessage(alerts.error)}</div>
          ) : rows.length === 0 ? (
            <div className="zs-alert i">
              <span className="zs-ico">i</span>
              <div className="zs-b">
                <b>当前没有打开的运营告警</b>
              </div>
            </div>
          ) : (
            <div className="zs-stack">
              {rows.map((row) => (
                <div key={row.id} className="zs-alert w">
                  <span className="zs-ico">◷</span>
                  <div className="zs-b">
                    <b>
                      <span className="zs-mono">{row.alertType}</span>
                    </b>
                    {row.createdAt ? <p>创建于 {row.createdAt}</p> : null}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}
