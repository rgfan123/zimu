/**
 * 京东结构化收货地址批量确认（jd-real-sdk-switch 04）的纯展示/组装逻辑。
 * 候选与已确认值严格区分：未确认不参与建单；批量导入只提交勾选且有完整必填层级的行，
 * 缺层级或候选不完整的行落到人工，不猜测、不静默填充。
 */

import type { JdReceiverAddressCandidate } from '../../api/types.ts';

/** 京东建单要求的四个必填层级；乡镇按履约方 townRequired 策略可选。 */
export const JD_RECEIVER_ADDRESS_REQUIRED_FIELDS = [
  'province',
  'city',
  'county',
  'detail_address',
] as const;

export interface JdReceiverAddressFields {
  province: string;
  city: string;
  county: string;
  town: string;
  detail_address: string;
}

export type JdReceiverAddressStatus = 'confirmed' | 'pending' | 'incomplete';

export interface JdReceiverAddressBatchItem {
  shipment_id: string;
  expected_version: number;
  province: string;
  city: string;
  county: string;
  town?: string;
  detail_address: string;
}

export interface JdReceiverAddressBatchBuild {
  items: JdReceiverAddressBatchItem[];
  skipped: Array<{ shipment_id: string; reason: string }>;
}

/** 行内可编辑字段的初始值：已确认行回填已确认值，未确认行回填来源候选。 */
export function jdReceiverAddressDefaults(row: JdReceiverAddressCandidate): JdReceiverAddressFields {
  if (row.confirmed) {
    return {
      province: row.province ?? '',
      city: row.city ?? '',
      county: row.county ?? '',
      town: row.town ?? '',
      detail_address: row.detail_address ?? '',
    };
  }
  return {
    province: row.candidate?.province ?? '',
    city: row.candidate?.city ?? '',
    county: row.candidate?.county ?? '',
    town: row.candidate?.town ?? '',
    detail_address: row.candidate?.detail_address ?? '',
  };
}

export function jdReceiverAddressStatus(row: JdReceiverAddressCandidate): JdReceiverAddressStatus {
  if (row.confirmed) return 'confirmed';
  return row.candidate_incomplete ? 'incomplete' : 'pending';
}

export function jdReceiverAddressStatusLabel(status: JdReceiverAddressStatus): string {
  if (status === 'confirmed') return '已确认';
  if (status === 'incomplete') return '需人工填写';
  return '待确认';
}

export function jdReceiverAddressStatusTone(status: JdReceiverAddressStatus): 'success' | 'processing' | 'warning' {
  if (status === 'confirmed') return 'success';
  if (status === 'incomplete') return 'warning';
  return 'processing';
}

/** 候选地址的展示文本；层级缺失时返回 null 而不是拼出残缺地址。 */
export function jdReceiverAddressCandidateText(row: JdReceiverAddressCandidate): string | null {
  const candidate = row.candidate;
  if (!candidate) return null;
  const parts = [
    candidate.province,
    candidate.city,
    candidate.county,
    candidate.town,
    candidate.detail_address,
  ].filter(Boolean);
  return parts.length ? parts.join(' ') : null;
}

/** 已确认地址的展示文本。 */
export function jdReceiverAddressConfirmedText(row: JdReceiverAddressCandidate): string | null {
  if (!row.confirmed) return null;
  const parts = [row.province, row.city, row.county, row.town, row.detail_address].filter(Boolean);
  return parts.length ? parts.join(' ') : null;
}

/**
 * 从勾选行与人工编辑值组装批量确认请求。
 * 必填层级缺失的行进入 skipped（不静默填充）；town 可留空（townRequired=false）。
 * 返回的 items 按 shipment_id 排序，保证同一组数据幂等键稳定。
 */
export function jdReceiverAddressBatchItems(
  rows: JdReceiverAddressCandidate[],
  values: Record<string, Partial<JdReceiverAddressFields>>,
): JdReceiverAddressBatchBuild {
  const items: JdReceiverAddressBatchItem[] = [];
  const skipped: Array<{ shipment_id: string; reason: string }> = [];
  for (const row of rows) {
    const edits = values[row.shipment_id] ?? {};
    const defaults = jdReceiverAddressDefaults(row);
    const province = (edits.province ?? defaults.province).trim();
    const city = (edits.city ?? defaults.city).trim();
    const county = (edits.county ?? defaults.county).trim();
    const town = (edits.town ?? defaults.town).trim();
    const detailAddress = (edits.detail_address ?? defaults.detail_address).trim();
    if (!province || !city || !county || !detailAddress) {
      skipped.push({ shipment_id: row.shipment_id, reason: '缺少必填层级，请人工补齐' });
      continue;
    }
    items.push({
      shipment_id: row.shipment_id,
      expected_version: row.expected_version,
      province,
      city,
      county,
      town: town || undefined,
      detail_address: detailAddress,
    });
  }
  items.sort((a, b) => a.shipment_id.localeCompare(b.shipment_id, 'en'));
  return { items, skipped };
}

/**
 * 批量确认的幂等键由确认内容决定：同一组发货单+同一组确认值重放返回首次结果，
 * 修改任一值即生成新键发起新确认，不会被旧结果静默吞掉。
 */
export function jdReceiverAddressBatchIdempotencyKey(items: JdReceiverAddressBatchItem[]): string {
  const content = items
    .map((item) => [
      item.shipment_id,
      item.expected_version,
      item.province,
      item.city,
      item.county,
      item.town ?? '',
      item.detail_address,
    ].join('|'))
    .join(';');
  return `shipment-jd-receiver-address-batch-${fnv1a(content).toString(16)}`;
}

function fnv1a(value: string): number {
  let hash = 0x811c9dc5;
  for (let index = 0; index < value.length; index++) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 0x01000193);
  }
  return hash >>> 0;
}
