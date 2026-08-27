/**
 * 全站统一时间展示入口（UIUX-04 #138）：默认本地时区（部署口径 Asia/Shanghai）
 * `YYYY-MM-DD HH:mm`；需要精确值的场景用秒级函数（悬停提示等）。
 * 禁止把后端原始时间戳字符串直接渲染上屏。
 *
 * 用 Intl + 手动拼装而不是 dayjs.format：dayjs 本地时区跟随浏览器，
 * 而部署与业务口径固定为 Asia/Shanghai；拼装保证连字符分隔的 `YYYY-MM-DD HH:mm`。
 */

const SHANGHAI_DATE_TIME = new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

function formatInShanghai(value: string, withSeconds: boolean): string {
  const parts = Object.fromEntries(
    SHANGHAI_DATE_TIME.formatToParts(new Date(value)).map((part) => [part.type, part.value]),
  );
  const base = `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
  return withSeconds ? `${base}:${parts.second}` : base;
}

/** 分钟级展示：`YYYY-MM-DD HH:mm`；空值/非法值回退 fallback（默认 —）。 */
export function formatDateTime(value?: string | null, fallback = '—'): string {
  if (!value) return fallback;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? fallback : formatInShanghai(value, false);
}

/** 秒级展示（精确值场景，如悬停提示 / 详情页）：`YYYY-MM-DD HH:mm:ss`。 */
export function formatDateTimeSeconds(value?: string | null, fallback = '—'): string {
  if (!value) return fallback;
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? fallback : formatInShanghai(value, true);
}

/** 月日+分钟展示（无年份，供列表密集列使用，如来源下单时间）：`MM-DD HH:mm`。 */
export function formatMonthDayTime(value?: string | null, fallback = '—'): string {
  if (!value) return fallback;
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return fallback;
  const parts = Object.fromEntries(
    SHANGHAI_DATE_TIME.formatToParts(parsed).map((part) => [part.type, part.value]),
  );
  return `${parts.month}-${parts.day} ${parts.hour}:${parts.minute}`;
}
