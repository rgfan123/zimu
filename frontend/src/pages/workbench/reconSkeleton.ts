/**
 * 对账台骨架的纯投影（v-fin，spec #103 D14/D15 相邻）：
 * analytics/channels 双形态（按天行 / 单聚合行）统一聚合为分平台行 + 合计。
 * 数量是十进制字符串（防 BIGINT 精度丢失）：求和走字符串十进制加法，绝不 Number 直加；
 * 畸形行整行丢弃（fail-closed），可选字段缺失如实置 null（显示「—」，不编数）。
 */

export interface ChannelReconRow {
  channel: string;
  orderCount: number;
  /** 来源份数（十进制字符串）。 */
  canonicalQuantity: string;
  shippedQuantity: string;
  /** ×包装乘数后的实际件数；后端可选字段，缺失为 null。 */
  actualShippedQuantity: string | null;
  exceptionCount: number | null;
}

export interface ChannelReconTotals {
  orderCount: number;
  canonicalQuantity: string;
  shippedQuantity: string;
  actualShippedQuantity: string | null;
  exceptionCount: number | null;
}

const DECIMAL_PATTERN = /^\d+(\.\d+)?$/;

/** 非负十进制字符串加法（BigInt 逐位，无浮点参与）。 */
export function addDecimal(a: string, b: string): string {
  const [aInt, aFrac = ''] = a.split('.');
  const [bInt, bFrac = ''] = b.split('.');
  const scale = Math.max(aFrac.length, bFrac.length);
  const scaled = (int: string, frac: string) => BigInt(int + frac.padEnd(scale, '0'));
  const sum = (scaled(aInt, aFrac) + scaled(bInt, bFrac)).toString().padStart(scale + 1, '0');
  if (scale === 0) return sum;
  const head = sum.slice(0, -scale);
  const tail = sum.slice(-scale).replace(/0+$/, '');
  return tail ? `${head}.${tail}` : head;
}

function asQuantity(value: unknown): string | null {
  return typeof value === 'string' && DECIMAL_PATTERN.test(value) ? value : null;
}

/** 双形态聚合：同 source_channel 的行（含按天行）合并为一行；畸形行丢弃。 */
export function aggregateChannelMetrics(rows: unknown[]): { rows: ChannelReconRow[]; totals: ChannelReconTotals } {
  const byChannel = new Map<string, ChannelReconRow>();

  for (const raw of rows) {
    if (typeof raw !== 'object' || raw === null) continue;
    const record = raw as Record<string, unknown>;
    const channel = record.source_channel;
    const orderCount = record.order_count;
    const canonical = asQuantity(record.canonical_quantity);
    const shipped = asQuantity(record.shipped_quantity);
    if (typeof channel !== 'string' || !channel) continue;
    if (typeof orderCount !== 'number' || !Number.isFinite(orderCount)) continue;
    if (canonical === null || shipped === null) continue;
    const actual = asQuantity(record.actual_shipped_quantity);
    const exceptions = typeof record.exception_order_count === 'number' && Number.isFinite(record.exception_order_count)
      ? record.exception_order_count
      : null;

    const existing = byChannel.get(channel);
    if (!existing) {
      byChannel.set(channel, {
        channel,
        orderCount,
        canonicalQuantity: canonical,
        shippedQuantity: shipped,
        actualShippedQuantity: actual,
        exceptionCount: exceptions,
      });
    } else {
      existing.orderCount += orderCount;
      existing.canonicalQuantity = addDecimal(existing.canonicalQuantity, canonical);
      existing.shippedQuantity = addDecimal(existing.shippedQuantity, shipped);
      // 可选字段：任一天缺失则该渠道整体如实置 null（宁缺毋编）。
      existing.actualShippedQuantity = existing.actualShippedQuantity !== null && actual !== null
        ? addDecimal(existing.actualShippedQuantity, actual)
        : null;
      existing.exceptionCount = existing.exceptionCount !== null && exceptions !== null
        ? existing.exceptionCount + exceptions
        : null;
    }
  }

  const list = [...byChannel.values()];
  const totals: ChannelReconTotals = {
    orderCount: 0,
    canonicalQuantity: '0',
    shippedQuantity: '0',
    actualShippedQuantity: '0',
    exceptionCount: 0,
  };
  for (const row of list) {
    totals.orderCount += row.orderCount;
    totals.canonicalQuantity = addDecimal(totals.canonicalQuantity, row.canonicalQuantity);
    totals.shippedQuantity = addDecimal(totals.shippedQuantity, row.shippedQuantity);
    totals.actualShippedQuantity = totals.actualShippedQuantity !== null && row.actualShippedQuantity !== null
      ? addDecimal(totals.actualShippedQuantity, row.actualShippedQuantity)
      : null;
    totals.exceptionCount = totals.exceptionCount !== null && row.exceptionCount !== null
      ? totals.exceptionCount + row.exceptionCount
      : null;
  }
  if (list.length === 0) {
    totals.actualShippedQuantity = null;
    totals.exceptionCount = null;
  }
  return { rows: list, totals };
}

/** 渠道人话名（与发货台渠道词汇一致）；未知渠道原样显示。 */
export const RECON_CHANNEL_LABELS: Record<string, string> = {
  CAISHIXIAN: '彩食鲜',
  JUFUBAO: '聚福宝',
  FEIXIANG: '飞象',
  ZHONGHUI: '中汇',
  WECOM: '企业微信',
};
