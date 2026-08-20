/**
 * Issue #95：导入批次标识在两个页面间的 URL 约定与 fail-closed 校验。
 * - 文件作业页（/fulfillment/sales-outbound?import_batch=…）：批次标识进 URL，刷新/回退不丢。
 * - 人工复核页（/workbench/reviews?import_batch=…）：复核列表按批次筛选，筛选可分享。
 * 非法批次标识必须显式报错，绝不猜测或静默退化成全局队列。
 * 复核页 URL 的完整契约（含批次参数与构造器）在 reviewQueueUrl.ts（Issue #96），此处再导出保持兼容。
 */

export const FILE_JOB_BATCH_PARAM = 'import_batch';

export { REVIEWS_BATCH_PARAM, reviewsUrlForBatch } from './reviewQueueUrl.ts';

export type BatchIdParam =
  | { kind: 'absent' }
  | { kind: 'invalid'; raw: string }
  | { kind: 'valid'; id: string };

const BATCH_ID_PATTERN = /^[1-9][0-9]*$/;

/** 批次标识 fail-closed 解析：缺失=absent；非法（非正整数，含空串/空白）显式 invalid，绝不按无筛选处理。 */
export function parseBatchIdParam(raw: string | null): BatchIdParam {
  if (raw === null) return { kind: 'absent' };
  return BATCH_ID_PATTERN.test(raw) ? { kind: 'valid', id: raw } : { kind: 'invalid', raw };
}

/** 非法批次标识的可读错误文案：文件作业与人工复核两侧共用同一口径，避免文案漂移。 */
export function invalidBatchIdMessage(raw: string, stoppedAction: string): string {
  return `分享链接中的批次标识「${raw}」不是有效的批次编号，已停止${stoppedAction}，请核对链接。`;
}

export function fileJobUrlForBatch(id: string): string {
  return `/fulfillment/sales-outbound?${FILE_JOB_BATCH_PARAM}=${id}`;
}
