import type { RequestOptions } from './client.ts';

/**
 * 来源回传（把我方运单号写回客户的来源平台）的请求契约。
 *
 * <p>两段式，<b>刻意不合并成一次点击</b>：check 是只读的，会真去平台读一次当前事实；
 * execute 携带那次 check 的稳定哈希，服务端据此确认「人看到的就是要写的」。
 * 哈希对不上（平台事实在中间变了）服务端会拒，不会拿旧结论去写。
 */

export type SourceSyncAddressStatus = 'UNKNOWN' | 'CLEAR' | 'CONFIRMATION_REQUIRED';

export type SourceSyncStatus =
  | 'PENDING'
  | 'SYNCING'
  | 'SYNCED'
  | 'FAILED'
  | 'RECONCILIATION_REQUIRED'
  | 'NOT_APPLICABLE';

export interface SourceSyncBlocker {
  code: string;
  field: string | null;
  message: string;
}

/** 我方事实：将要写出去的东西。 */
export interface SourceSyncInternalFacts {
  shipment_id: number;
  order_id: number;
  source_channel: string;
  source_ref: string | null;
  source_line_ref: string | null;
  receiver_name: string | null;
  receiver_phone: string | null;
  receiver_address: string | null;
  ordered_source_quantity: number | null;
  shipped_source_quantity: number | null;
  internal_shipped_quantity: number | null;
  fulfillment_outcome: string | null;
  carrier_code: string | null;
  carrier_name: string | null;
  carrier_output_value: string | null;
  tracking_number: string | null;
}

/** 平台事实：写之前从来源平台读回来的当前状态。 */
export interface SourceSyncPlatformFacts {
  available: boolean;
  business_code: string;
  message: string;
  platform_state: string | null;
  acceptance_required: boolean;
  address_status: SourceSyncAddressStatus;
  receiver_name: string | null;
  receiver_phone: string | null;
  receiver_address: string | null;
  sendable_quantity: number | null;
  carrier_mapped: boolean;
  effect_hash: string | null;
}

export interface SourceSyncProjection {
  status: SourceSyncStatus;
  attempt_count: number;
  lock_version: number;
  last_error_code: string | null;
  last_error_message: string | null;
  synced_at: string | null;
}

export interface SourceSyncCheck {
  shipment_id: number;
  ready: boolean;
  check_hash: string;
  artifact_hash: string | null;
  internal: SourceSyncInternalFacts;
  platform: SourceSyncPlatformFacts;
  blockers: SourceSyncBlocker[];
}

export interface SourceSyncOutcome {
  shipment_id: number;
  status: SourceSyncStatus;
  business_code: string;
  message: string;
  check_hash: string;
  version: number;
  platform_ref: string | null;
  completed_at: string | null;
}

/**
 * 一张 Shipment 一个稳定重放身份。
 *
 * <p>刻意<b>不</b>掺随机数：误点两次第二次会被服务端幂等注册表判为重放并回放首次结果，
 * 而不是往客户平台再写一次。这是不可逆外部写，重放保护比「每次一个新键」重要。
 */
export function sourceSyncIdempotencyKey(shipmentId: string): string {
  return `shipment-source-sync-${shipmentId}`;
}

export function sourceSyncExecuteRequest(
  shipmentId: string,
  expectedCheckHash: string,
  headers: Record<string, string>,
): { path: string; options: RequestOptions } {
  return {
    path: `/api/v1/shipments/${shipmentId}/source-sync/execute`,
    options: {
      method: 'POST',
      body: { expected_check_hash: expectedCheckHash },
      headers,
    },
  };
}
