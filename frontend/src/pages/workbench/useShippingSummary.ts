/**
 * 发货台骨架计数（ADR 0006 第一阶段）：既有列表接口 size=1 只取 total_elements 拼真数。
 * 全部计数收在本 hook —— #119 聚合端点落地后只改本文件（数据源切换），骨架与交互零改动。
 * 口径清单与跳转同筛选契约见 workflow 研究映射表 + docs/adr/0006。
 * 复核计数按岗位团队过滤（与侧栏徽标 useRailBadges 同口径）；拼不出的口径返回 null（占位）。
 */

import { useEffect, useMemo, useState } from 'react';
import dayjs from 'dayjs';
import type { PlatformOrderRefreshResult } from '@/api/types';
import { presentShippingChannel, summarizeShippingResult } from './shippingPresentation';

export interface ShippingSummaryCounts {
  /** 本次平台拉取返回的渠道数 / 成功数。 */
  platformPullChannels: number | null;
  platformPullSuccesses: number | null;
  /** 本次生成的导入批次数 / 已知批次行数。 */
  importedBatches: number | null;
  importedRows: number | null;
  /** 聚福宝仅报告未入库数；未返回或数量缺失时为 null。 */
  reportedNotImported: number | null;
  /** 今日新建订单（orders created_at 今日）。 */
  installedToday: number | null;
  /** OPEN 复核事项（岗位团队过滤时与徽标同口径）。 */
  reviewOpen: number | null;
  /** processing_stage=NEED_REVIEW：SKU/客户识别待人工。 */
  needReview: number | null;
  /** processing_stage=READY_TO_EXPORT：等整批确认（≡ 指标「待发货」）。 */
  readyToExport: number | null;
  /** processing_stage=WAITING_PROVIDER：等运单回填（≡ 指标「发货中」）。 */
  waitingProvider: number | null;
  /** processing_stage=TRACKING_RECEIVED：来源回填表待生成。 */
  trackingReceived: number | null;
  /** processing_stage=RETURN_FILE_READY：待回传来源平台。 */
  returnFileReady: number | null;
  /** 今日建单且已回填的发货批次（shipments SHIPPED，created_at 今日口径）。 */
  shippedToday: number | null;
}

const EMPTY: ShippingSummaryCounts = {
  platformPullChannels: null,
  platformPullSuccesses: null,
  importedBatches: null,
  importedRows: null,
  reportedNotImported: null,
  installedToday: null,
  reviewOpen: null,
  needReview: null,
  readyToExport: null,
  waitingProvider: null,
  trackingReceived: null,
  returnFileReady: null,
  shippedToday: null,
};

function platformPullCounts(result: PlatformOrderRefreshResult | null): Pick<
  ShippingSummaryCounts,
  'platformPullChannels' | 'platformPullSuccesses' | 'importedBatches' | 'importedRows' | 'reportedNotImported'
> {
  if (!result) {
    return {
      platformPullChannels: null,
      platformPullSuccesses: null,
      importedBatches: null,
      importedRows: null,
      reportedNotImported: null,
    };
  }
  const channels = result.channels.map(presentShippingChannel);
  const validChannels = channels.filter((channel) => channel.validContract);
  const summary = summarizeShippingResult(result);
  const jufubao = validChannels.find((channel) => channel.channel === 'JUFUBAO');
  return {
    platformPullChannels: channels.length,
    platformPullSuccesses: validChannels.filter((channel) => channel.status === 'OK').length,
    importedBatches: summary.batchCount,
    importedRows: summary.totalRows,
    reportedNotImported: jufubao?.status === 'OK' ? summary.reportedOrders : null,
  };
}

async function countOf(url: string, signal: AbortSignal): Promise<number | null> {
  try {
    const response = await fetch(url, { headers: { Accept: 'application/json' }, signal });
    if (!response.ok) return null;
    const body: { total_elements?: unknown } = await response.json();
    return typeof body.total_elements === 'number' ? body.total_elements : null;
  } catch {
    return null; // 计数取不到就占位，不阻塞骨架、不伪造。
  }
}

function stageCountUrl(stage: string): string {
  const params = new URLSearchParams({ processing_stage: stage, page: '0', size: '1' });
  return `/api/v1/orders?${params.toString()}`;
}

export function useShippingSummary(
  team: string | null,
  platformPullResult: PlatformOrderRefreshResult | null,
): ShippingSummaryCounts {
  const [counts, setCounts] = useState<ShippingSummaryCounts>(EMPTY);
  const pullCounts = useMemo(() => platformPullCounts(platformPullResult), [platformPullResult]);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    const today = dayjs().format('YYYY-MM-DD');

    const reviewParams = new URLSearchParams({ status: 'OPEN', page: '0', size: '1' });
    if (team) reviewParams.set('responsible_team', team);
    const todayOrdersParams = new URLSearchParams({ date_from: today, date_to: today, page: '0', size: '1' });
    const shippedParams = new URLSearchParams({
      shipment_status: 'SHIPPED',
      date_from: today,
      date_to: today,
      page: '0',
      size: '1',
    });

    Promise.all([
      countOf(`/api/v1/orders?${todayOrdersParams.toString()}`, signal),
      countOf(`/api/v1/review-cases?${reviewParams.toString()}`, signal),
      countOf(stageCountUrl('NEED_REVIEW'), signal),
      countOf(stageCountUrl('READY_TO_EXPORT'), signal),
      countOf(stageCountUrl('WAITING_PROVIDER'), signal),
      countOf(stageCountUrl('TRACKING_RECEIVED'), signal),
      countOf(stageCountUrl('RETURN_FILE_READY'), signal),
      countOf(`/api/v1/shipments?${shippedParams.toString()}`, signal),
    ]).then(([installedToday, reviewOpen, needReview, readyToExport, waitingProvider, trackingReceived, returnFileReady, shippedToday]) => {
      if (signal.aborted) return;
      setCounts({
        platformPullChannels: null,
        platformPullSuccesses: null,
        importedBatches: null,
        importedRows: null,
        reportedNotImported: null,
        installedToday,
        reviewOpen,
        needReview,
        readyToExport,
        waitingProvider,
        trackingReceived,
        returnFileReady,
        shippedToday,
      });
    });

    return () => controller.abort();
  }, [team]);

  return { ...counts, ...pullCounts };
}
