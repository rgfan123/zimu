/**
 * Issue #96：工作台「待人工介入」→ 人工复核队列的 URL 约定。
 * - 复核队列页（/workbench/reviews）的状态 / 事项类型 / 责任团队筛选全部进入 query string，
 *   分享链接、刷新与浏览器回退均可恢复，并实际影响列表 API 请求。
 * - 时间口径：DashboardController summary SQL 中「待人工介入」KPI、attention 聚合与明细
 *   均不带时间边界（全部 OPEN 复核事项），因此这里绝不伪造 business_date/date 参数——
 *   那类参数在复核列表 API 上没有过滤效果，只会让分享链接说谎。
 * - 本模块是 /workbench/reviews 唯一 URL 契约：含 #95 的批次参数（batchUrl 仅做兼容再导出）。
 */

/** 复核队列页的批次筛选参数名（#95 约定；与文件作业页 FILE_JOB_BATCH_PARAM 同名不同属）。 */
export const REVIEWS_BATCH_PARAM = 'import_batch';

export const REVIEWS_STATUS_PARAM = 'status';
export const REVIEWS_REASON_PARAM = 'reason_code';
export const REVIEWS_TEAM_PARAM = 'responsible_team';
export const REVIEWS_VIEW_PARAM = 'view';

/** 队列视图的 URL 值（唯一事实源）；页面与 URL 之间不再有第二套大小写表示。 */
export type ReviewQueueView = 'reviews' | 'alerts';

export interface ReviewQueueFilters {
  status?: string;
  reasonCode?: string;
  team?: string;
  /** 导入批次标识（#95 约定），与筛选共存。 */
  batchId?: string;
  /** 复核队列默认视图；仅 'alerts' 需要显式写出。 */
  view?: ReviewQueueView;
}

/** 组装复核队列页 URL；未设置的筛选不出现，默认视图不写出。 */
export function reviewsQueueUrl(filters: ReviewQueueFilters = {}): string {
  const params = new URLSearchParams();
  if (filters.status) params.set(REVIEWS_STATUS_PARAM, filters.status);
  if (filters.reasonCode) params.set(REVIEWS_REASON_PARAM, filters.reasonCode);
  if (filters.team) params.set(REVIEWS_TEAM_PARAM, filters.team);
  if (filters.batchId) params.set(REVIEWS_BATCH_PARAM, filters.batchId);
  if (filters.view && filters.view !== 'reviews') params.set(REVIEWS_VIEW_PARAM, filters.view);
  const query = params.toString();
  return query ? `/workbench/reviews?${query}` : '/workbench/reviews';
}

/** 仅带批次上下文的复核队列 URL（#95 动线入口），委托统一构造器避免两份 URL 逻辑漂移。 */
export function reviewsUrlForBatch(id: string): string {
  return reviewsQueueUrl({ batchId: id });
}

/**
 * 仅以「运营提醒」（operational_alerts.alert_type）形式出现、复核队列不存在的原因码。
 * attention 聚合把两类来源并入同一 reason_code（DashboardController attention() SQL），
 * 复核队列按 reason_code 过滤后没有这些码，因此这类卡直达提醒队列而非空复核列表。
 * 其余原因码（含未知码）按复核事项处理：工作台「待人工介入」KPI 本就只统计复核事项。
 * 注意 FULFILLMENT_EXCEPTION 等既作复核原因码也作提醒类型的码归复核队列（存在真实待办）。
 * 后端新增提醒类型时需与本集合同步（见 DashboardController attention() 的 alert 分支）。
 */
export const ALERT_ONLY_ATTENTION_CODES: ReadonlySet<string> = new Set([
  'PROCUREMENT_REQUIRED',
  'JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED',
  'JD_SKU_MAPPING',
  'OUT_OF_STOCK',
]);

/** attention 原因卡的目标：复核事项原因码 → reason 预筛复核队列；提醒专用码 → 提醒队列。 */
export function attentionCardUrl(reasonCode: string): string {
  return ALERT_ONLY_ATTENTION_CODES.has(reasonCode)
    ? reviewsQueueUrl({ view: 'alerts' })
    : reviewsQueueUrl({ status: 'OPEN', reasonCode });
}
