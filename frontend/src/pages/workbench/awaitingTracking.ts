/**
 * 「已发出待回单」的纯投影（发货台一页闭环 4/n）。
 *
 * 独立成文件而非并入 shippingSkeleton：后者依赖 queuePresentation 等模块，
 * 直接用 node:test 加载会踩无扩展名 import；本模块零依赖，可被纯函数测试直接引。
 */

export interface AwaitingTrackingRow {
  shipmentId: string;
  shipmentNo: string;
  erpDeliveryNo: string | null;
  jdDeliveryNo: string | null;
  /** 京东运单查询态；第三方为 null。 */
  trackingStatus: string | null;
  attempts: number;
  lastQueryAt: string | null;
}

export interface AwaitingTrackingView {
  /** 京东 SDK 已建单、等自动轮询回运单。 */
  jd: AwaitingTrackingRow[];
  /** 第三方已导出发货清单、等对方回传运单文件。 */
  thirdParty: AwaitingTrackingRow[];
  total: number;
}

/** 运单已到位的终态：不再属于「等回单」。 */
const TRACKING_SETTLED = new Set(['TRACKED', 'TERMINAL_REVIEWED']);

/**
 * 「已发出待回单」投影。
 *
 * <p>回答的是操作员每天都要问、但界面从来没答过的问题：**这单到底在等什么？**
 * 两条路等的东西完全不同——京东是系统自动轮询（`ShipmentJdTrackingPoller`，
 * 默认 60s 一轮、同批次至少隔 1 分钟），第三方是**等人回传文件**，系统只能催。
 * 混在一起显示会让人以为第三方那几单也在自动推进。
 *
 * <p>刻意排除「京东履约方但尚未建单」的批次：那不是在等回单，是**还没发出去**，
 * 属于阻塞区的职责。混进来会让人误以为已经发了。
 */
export function presentAwaitingTracking(
  items: unknown[],
  providerTypeById: Map<string, string>,
): AwaitingTrackingView {
  const jd: AwaitingTrackingRow[] = [];
  const thirdParty: AwaitingTrackingRow[] = [];

  for (const item of items) {
    if (typeof item !== 'object' || item === null) continue;
    const record = item as Record<string, unknown>;
    // SHIPPED 表示运单已回填（ShipmentTrackingService 置位），不再是「等」
    if (record.shipment_status !== 'CREATED') continue;
    const id = record.id;
    if (typeof id !== 'string' && typeof id !== 'number') continue;

    const providerId = typeof record.provider_id === 'string' ? record.provider_id : null;
    const providerType = providerId ? providerTypeById.get(providerId) ?? null : null;

    const outbound = record.jd_outbound;
    const jdOutbound =
      typeof outbound === 'object' && outbound !== null ? (outbound as Record<string, unknown>) : null;

    const row: AwaitingTrackingRow = {
      shipmentId: String(id),
      shipmentNo: typeof record.shipment_no === 'string' ? record.shipment_no : String(id),
      erpDeliveryNo:
        jdOutbound && typeof jdOutbound.erp_delivery_no === 'string' ? jdOutbound.erp_delivery_no : null,
      jdDeliveryNo:
        jdOutbound && typeof jdOutbound.jd_delivery_no === 'string' ? jdOutbound.jd_delivery_no : null,
      trackingStatus:
        jdOutbound && typeof jdOutbound.tracking_query_status === 'string'
          ? jdOutbound.tracking_query_status
          : null,
      attempts:
        jdOutbound && typeof jdOutbound.tracking_query_attempt_count === 'number'
          ? jdOutbound.tracking_query_attempt_count
          : 0,
      lastQueryAt:
        jdOutbound && typeof jdOutbound.tracking_last_query_at === 'string'
          ? jdOutbound.tracking_last_query_at
          : null,
    };

    if (providerType === 'JD_WAREHOUSE') {
      // 只有真正提交成功的才算「已发出」；SUBMITTING 仍在飞行中，SYNC_FAILED 属告警区
      if (!jdOutbound || jdOutbound.sync_status !== 'SUBMITTED') continue;
      if (row.trackingStatus !== null && TRACKING_SETTLED.has(row.trackingStatus)) continue;
      jd.push(row);
    } else if (providerType === 'THIRD_PARTY') {
      thirdParty.push(row);
    }
    // 履约方类型未知：不猜归属，两组都不进（宁可少显示，不误导）
  }

  return { jd, thirdParty, total: jd.length + thirdParty.length };
}
