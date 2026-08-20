import type { SourceChannel } from '../../api/types';

const CHANNELS: SourceChannel[] = ['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'DAZHE', 'WANQI', 'WECOM'];

/** URL `ch` 参数是数据中台跨刷新、可分享的渠道筛选状态。 */
export function parseChannels(raw: string | null): SourceChannel[] {
  if (!raw) return [...CHANNELS];
  const selected = raw
    .split(',')
    .filter((value, index, values): value is SourceChannel =>
      CHANNELS.includes(value as SourceChannel) && values.indexOf(value) === index,
    );
  return selected.length ? selected : [...CHANNELS];
}

/** 空选与全选都使用缺省 URL，避免制造两个含义相同的链接。 */
export function serializeChannels(channels: SourceChannel[]): string | null {
  if (!channels.length || (channels.length === CHANNELS.length && CHANNELS.every((channel) => channels.includes(channel)))) {
    return null;
  }
  return channels.join(',');
}
