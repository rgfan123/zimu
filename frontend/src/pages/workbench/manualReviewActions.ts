import type {
  ResolveCustomerReviewCommand,
  ResolveSkuReviewCommand,
  ReviewCase,
  SourceChannel,
  VersionedNoteCommand,
} from '@/api/types';

export type ReviewAction = 'CUSTOMER' | 'SKU' | 'JD_SKU_MAPPING' | 'SOURCE_FOLLOWUP' | 'ORDER_DRAFT' | 'TRACKING_DRAFT' | 'NAVIGATE';

function detailString(item: ReviewCase, key: string): string {
  const value = item.detail[key];
  if (typeof value !== 'string' || !value.trim()) {
    throw new Error(`复核证据缺少 ${key}`);
  }
  return value;
}

function sourceChannel(item: ReviewCase): SourceChannel {
  const value = detailString(item, 'source_channel');
  if (!['CAISHIXIAN', 'JUFUBAO', 'FEIXIANG', 'ZHONGHUI', 'WANGQI', 'DAZHE', 'WANQI', 'WECOM', 'MANUAL'].includes(value)) {
    throw new Error('复核证据中的来源渠道无效');
  }
  return value as SourceChannel;
}

export function reviewAction(item: ReviewCase): ReviewAction {
  if (item.reason_code === 'CUSTOMER_MATCH_REQUIRED') return 'CUSTOMER';
  if (item.reason_code === 'SKU_MAPPING_REQUIRED' || item.reason_code === 'MAPPING_MULTIPLIER') return 'SKU';
  if (item.reason_code === 'JD_SKU_MAPPING_BLOCKED' && item.subject_type === 'SHIPMENT') return 'JD_SKU_MAPPING';
  if (item.reason_code === 'MULTI_SHIPMENT_SOURCE_FOLLOWUP') return 'SOURCE_FOLLOWUP';
  if (item.reason_code === 'WECOM_ORDER_DRAFT' && item.subject_type === 'ORDER_DRAFT') return 'ORDER_DRAFT';
  if (item.reason_code === 'WECOM_TRACKING_DRAFT' && item.subject_type === 'TRACKING_DRAFT') return 'TRACKING_DRAFT';
  return 'NAVIGATE';
}

export function buildCustomerResolution(
  item: ReviewCase,
  customerId: string,
  remark: string,
): ResolveCustomerReviewCommand {
  return {
    expected_version: item.version,
    customer_id: customerId,
    source_channel: sourceChannel(item),
    source_customer_ref: detailString(item, 'source_customer_ref'),
    remark: remark.trim(),
  };
}

export function buildSkuResolution(
  item: ReviewCase,
  skuId: string,
  quantityMultiplier: string,
  remark: string,
): ResolveSkuReviewCommand {
  const refs = item.detail.missing_source_sku_refs;
  if (!Array.isArray(refs) || refs.length !== 1 || typeof refs[0] !== 'string' || !refs[0].trim()) {
    throw new Error('多组件或缺少来源 SKU 的事项必须先在主数据页处理映射');
  }
  return {
    expected_version: item.version,
    sku_id: skuId,
    source_channel: sourceChannel(item),
    source_sku_ref: refs[0],
    quantity_multiplier: quantityMultiplier,
    remark: remark.trim(),
  };
}

export function buildSourceFollowupCompletion(item: ReviewCase, note: string): VersionedNoteCommand {
  return { expected_version: item.version, note: note.trim() };
}

/** 通用人工闭环：主数据或线下问题处理完毕后标记已解决。 */
export function buildManualResolution(item: ReviewCase, note: string): VersionedNoteCommand {
  return { expected_version: item.version, note: note.trim() };
}

/** 关闭误建或不再需要的事项。 */
export function buildDismissCommand(item: ReviewCase, note: string): VersionedNoteCommand {
  return { expected_version: item.version, note: note.trim() };
}
