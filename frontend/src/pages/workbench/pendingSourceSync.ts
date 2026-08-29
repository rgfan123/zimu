/**
 * 工作台「待回传给客户平台」的取数与判据。
 *
 * <p><b>为什么工作台要有这一段</b>：动线是 待确认 → 复核 → 已发出待回单 → <b>断了</b>。
 * 运单号一到、状态转 SHIPPED，单子就从工作台整个消失，可回传这一步在界面上无处可见——
 * 2026-08-29 有两张聚福宝单就是这么躺了一整天没人知道。
 *
 * <p>判据只用列表已有的字段，<b>不</b>在这里调 source-sync/check：那是一次真实的
 * 平台读取，为了渲染一个列表就对每一行去读客户平台，是拿别人的系统当我们的缓存。
 * 这里只负责「看起来需要处理」，权威结论留给用户点开后的那一次检查。
 */

/** 已接入在线回传的渠道；与 SourceSyncFactsReader 的白名单同源。 */
const ONLINE_CHANNELS = new Set(['JUFUBAO', 'CAISHIXIAN', 'FEIXIANG']);

export interface PendingSourceSyncRow {
  shipmentId: string;
  shipmentNo: string;
  channel: string;
  receiverName: string | null;
  trackingNumber: string | null;
  /** 上一次回传留下的状态；null 表示从没回传过。 */
  syncStatus: string | null;
}

export interface PendingSourceSyncView {
  total: number;
  rows: PendingSourceSyncRow[];
}

interface RawShipment {
  id?: unknown;
  shipment_no?: unknown;
  source_channel?: unknown;
  source_sync_status?: unknown;
  receiver_name?: unknown;
  tracking?: { tracking_number?: unknown } | null;
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value : null;
}

/**
 * 挑出「已发货、有运单号、渠道支持在线回传、还没回传成功」的批次。
 *
 * <p>SYNCED 之外的状态（含 FAILED、RECONCILIATION_REQUIRED）<b>都</b>留在列表里：
 * 回传失败和从没回传过，对操作员是同一件待办——都还没告诉客户平台。
 */
export function presentPendingSourceSync(items: unknown[]): PendingSourceSyncView {
  const rows: PendingSourceSyncRow[] = [];
  for (const item of items) {
    const shipment = item as RawShipment;
    const id = text(shipment?.id);
    const channel = text(shipment?.source_channel);
    const trackingNumber = text(shipment?.tracking?.tracking_number);
    if (!id || !channel || !trackingNumber) continue;
    if (!ONLINE_CHANNELS.has(channel)) continue;
    const syncStatus = text(shipment?.source_sync_status);
    if (syncStatus === 'SYNCED' || syncStatus === 'NOT_APPLICABLE') continue;
    rows.push({
      shipmentId: id,
      shipmentNo: text(shipment?.shipment_no) ?? id,
      channel,
      receiverName: text(shipment?.receiver_name),
      trackingNumber,
      syncStatus,
    });
  }
  return { total: rows.length, rows };
}

/** 上一次回传出过事的要说清楚，别和「还没发过」混为一谈。 */
export function pendingSourceSyncNote(row: PendingSourceSyncRow): string | null {
  switch (row.syncStatus) {
    case null:
      return null;
    case 'SYNCING':
      return '正在回传中';
    case 'RECONCILIATION_REQUIRED':
      return '上次回传结果不明，要先对账';
    case 'FAILED':
      return '上次回传失败了';
    default:
      return `上次状态 ${row.syncStatus}`;
  }
}
