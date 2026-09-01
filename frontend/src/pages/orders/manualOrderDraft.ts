import type { ManualOrderFormValues } from '../../api/manualOrderCreate.ts';

/**
 * 手工建单草稿：保存在**本机浏览器** localStorage（迁移编号冻结期内不落库），
 * 换设备/换浏览器不可见；成功建单后草稿即清除。
 *
 * <p>存取全部注入 Storage 接缝：页面传 window.localStorage，单测传内存实现；
 * 隐私模式/配额溢出等一切 Storage 异常都吞掉降级（保存失败如实返回 false，
 * 读取失败当无草稿），草稿是便利性功能，绝不允许它挡建单主流程。
 */

/** 稳定契约：换键等于丢掉所有人的既存草稿，结构不兼容时必须升版本号换键。 */
export const MANUAL_ORDER_DRAFT_KEY = 'zimu.manual-order-draft.v1';

export interface ManualOrderDraftEnvelope {
  saved_at: string;
  values: ManualOrderFormValues;
}

type DraftReadStorage = Pick<Storage, 'getItem'>;
type DraftWriteStorage = Pick<Storage, 'setItem'>;
type DraftClearStorage = Pick<Storage, 'removeItem'>;

export function encodeManualOrderDraft(values: ManualOrderFormValues, savedAt: string): string {
  const envelope: ManualOrderDraftEnvelope = { saved_at: savedAt, values };
  return JSON.stringify(envelope);
}

/** 坏数据宁丢不炸：任何解析/形状问题都视同「没有草稿」。 */
export function decodeManualOrderDraft(raw: string | null): ManualOrderDraftEnvelope | null {
  if (!raw) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null;
  const candidate = parsed as { saved_at?: unknown; values?: unknown };
  if (typeof candidate.saved_at !== 'string') return null;
  if (typeof candidate.values !== 'object' || candidate.values === null || Array.isArray(candidate.values)) {
    return null;
  }
  return { saved_at: candidate.saved_at, values: candidate.values as ManualOrderFormValues };
}

export function saveManualOrderDraft(
  storage: DraftWriteStorage,
  values: ManualOrderFormValues,
  savedAt: string,
): boolean {
  try {
    storage.setItem(MANUAL_ORDER_DRAFT_KEY, encodeManualOrderDraft(values, savedAt));
    return true;
  } catch {
    return false;
  }
}

export function loadManualOrderDraft(storage: DraftReadStorage): ManualOrderDraftEnvelope | null {
  try {
    return decodeManualOrderDraft(storage.getItem(MANUAL_ORDER_DRAFT_KEY));
  } catch {
    return null;
  }
}

export function clearManualOrderDraft(storage: DraftClearStorage): void {
  try {
    storage.removeItem(MANUAL_ORDER_DRAFT_KEY);
  } catch {
    // 清不掉就留着：下次成功建单会再试一次，坏草稿也会被 decode 兜底丢弃。
  }
}
