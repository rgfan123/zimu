/**
 * 今日发货工作台测试夹具：正常路径给出精确 PlatformOrderRefreshResult DTO，
 * 恶意/畸形输入走 explicit unknown seam，避免把防御性拒绝写成合法合同。
 */

import type { PlatformOrderRefreshResult } from '../src/api/types.ts';

export type RefreshChannel = PlatformOrderRefreshResult['channels'][number];

export const REFRESH_URL = '/api/v1/platform-orders/refresh';
export const PROTOTYPE_KEYS = ['__proto__', 'constructor', 'toString'] as const;
export const GENERIC_FAILED_COPY = '该渠道刷新失败，请稍后重试';
export const CONTRACT_ERROR_COPY = '渠道响应格式异常，请联系管理员';

/** 渠道刷新结果夹具：默认彩食鲜 OK 且已生成导入批次。 */
export function channel(overrides: Partial<RefreshChannel> = {}): RefreshChannel {
  return {
    channel: 'CAISHIXIAN',
    status: 'OK',
    business_code: 'OK',
    batch_no: 'IMP-CSX-001',
    batch_id: '7',
    row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    ...overrides,
  };
}

/** 恶意/畸形运行时输入夹具：放宽为 unknown，专用于防御性函数拒绝路径。 */
export function rawChannel(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    channel: 'CAISHIXIAN',
    status: 'OK',
    batch_no: 'IMP-CSX-001',
    batch_id: '7',
    row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    ...overrides,
  };
}

export function refreshResult(
  channels: PlatformOrderRefreshResult['channels'],
  dateBegin = '2026-08-21',
  dateEnd = '2026-08-21',
): PlatformOrderRefreshResult {
  return { channels, date_begin: dateBegin, date_end: dateEnd };
}

export function rawRefreshResult(
  channels: unknown[],
  dateBegin = '2026-08-21',
  dateEnd = '2026-08-21',
): unknown {
  return { channels, date_begin: dateBegin, date_end: dateEnd };
}

export function failedRefreshError(channels: unknown) {
  return {
    status: 502,
    body: {
      business_code: 'PLATFORM_REFRESH_ALL_FAILED' as const,
      details: { channels },
    },
  };
}
