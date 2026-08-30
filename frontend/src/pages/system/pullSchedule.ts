/**
 * 渠道拉取时间表的表单逻辑（纯函数，不依赖 React / antd，便于单测）。
 *
 * 空值语义与后端严格一致，见 backend `ChannelPullSchedule`：
 * **读不到就按全局默认拉，绝不等于「不拉」**；要停某一档必须是显式的 `enabled: false`。
 * 因此后端的读投影永远带值（未配置时回显实际生效的默认），本模块也不做「空 = 关」的解读——
 * 界面上出现一个空白时间框，就是「以为关了其实在跑」的开始。
 */

import type { ConnectorConfig, ConnectorPullSchedule, ConnectorPullScheduleSlot } from '@/api/types';

/** 后端读投影缺失时的兜底（正常不会走到；防的是老版本后端或网络裁剪）。 */
const LAST_RESORT: ConnectorPullSchedule = {
  schedulable: false,
  configured: false,
  morning: { enabled: true, at: '09:00' },
  evening: { enabled: true, at: '18:00' },
  notify_wecom: true,
};

export const TIME_FORMAT = 'HH:mm';

/** HH:mm，00:00–23:59。与后端 `ConnectorPullSchedulePatch` 的 @Pattern 同一套判据。 */
const TIME_PATTERN = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

export function isValidTime(value: unknown): value is string {
  return typeof value === 'string' && TIME_PATTERN.test(value);
}

function normalizeSlot(
  slot: ConnectorPullScheduleSlot | undefined,
  fallback: ConnectorPullScheduleSlot,
): ConnectorPullScheduleSlot {
  if (!slot) return fallback;
  return {
    // 只有真正的布尔 false 才算停用：字段丢了、类型不对，一律按「开着」处理。
    enabled: slot.enabled === false ? false : true,
    at: isValidTime(slot.at) ? slot.at : fallback.at,
  };
}

/** 把后端投影收敛成界面一定能渲染的形状。任何缺失都回落成「照常拉」。 */
export function normalizeSchedule(raw: ConnectorPullSchedule | undefined | null): ConnectorPullSchedule {
  if (!raw) return LAST_RESORT;
  return {
    schedulable: raw.schedulable === true,
    configured: raw.configured === true,
    morning: normalizeSlot(raw.morning, LAST_RESORT.morning),
    evening: normalizeSlot(raw.evening, LAST_RESORT.evening),
    notify_wecom: raw.notify_wecom === false ? false : true,
  };
}

/** 界面上要出时间卡片的渠道：后端说了算，前端不另维护一份名单。 */
export function schedulableConnectors(rows: readonly ConnectorConfig[]): ConnectorConfig[] {
  return rows.filter((row) => normalizeSchedule(row.pull_schedule).schedulable);
}

/** 一张卡片的可编辑状态。 */
export interface PullScheduleDraft {
  morningEnabled: boolean;
  morningAt: string;
  eveningEnabled: boolean;
  eveningAt: string;
  notifyWecom: boolean;
}

export function toDraft(raw: ConnectorPullSchedule | undefined | null): PullScheduleDraft {
  const schedule = normalizeSchedule(raw);
  return {
    morningEnabled: schedule.morning.enabled,
    morningAt: schedule.morning.at,
    eveningEnabled: schedule.evening.enabled,
    eveningAt: schedule.evening.at,
    notifyWecom: schedule.notify_wecom,
  };
}

/**
 * 组装写入体。**整体替换**，五个字段一次全发。
 *
 * 后端刻意不接受部分 patch：「关掉早班」和「没提到早班」在报文里长得一样，而缺省会回落成
 * 默认（启用），于是一次漏发的字段会静悄悄地把用户刚关掉的档位重新打开。
 */
export function toPatchBody(draft: PullScheduleDraft): {
  morning: ConnectorPullScheduleSlot;
  evening: ConnectorPullScheduleSlot;
  notify_wecom: boolean;
} {
  return {
    morning: { enabled: draft.morningEnabled, at: draft.morningAt },
    evening: { enabled: draft.eveningEnabled, at: draft.eveningAt },
    notify_wecom: draft.notifyWecom,
  };
}

/** 提交前校验：时间必须是 HH:mm。返回 null 表示可以提交。 */
export function validateDraft(draft: PullScheduleDraft): string | null {
  if (!isValidTime(draft.morningAt)) return '第一次拉取时间必须是 HH:mm';
  if (!isValidTime(draft.eveningAt)) return '第二次拉取时间必须是 HH:mm';
  return null;
}

export function isDraftDirty(draft: PullScheduleDraft, raw: ConnectorPullSchedule | undefined | null): boolean {
  const saved = toDraft(raw);
  return (
    saved.morningEnabled !== draft.morningEnabled ||
    saved.morningAt !== draft.morningAt ||
    saved.eveningEnabled !== draft.eveningEnabled ||
    saved.eveningAt !== draft.eveningAt ||
    saved.notifyWecom !== draft.notifyWecom
  );
}

/** 卡片上的一句话说明：把「现在到底会怎么跑」直说出来，不让人自己拼。 */
export function describeSchedule(draft: PullScheduleDraft): string {
  const times = [
    draft.morningEnabled ? `第一次 ${draft.morningAt}` : null,
    draft.eveningEnabled ? `第二次 ${draft.eveningAt}` : null,
  ].filter((part): part is string => part !== null);
  if (times.length === 0) {
    return '两次拉取都已停用，这个平台不会自动拉单';
  }
  return `每天${times.join('、')} 自动拉单，${draft.notifyWecom ? '拉完推企微' : '拉完不推企微'}`;
}
