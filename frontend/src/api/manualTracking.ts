import type { RequestOptions } from './client.ts';

/**
 * 人工录入运单：手上只有单号、没有回填文件时的入口。
 *
 * <p>在此之前系统唯一的运单入口是上传回填后的导出文件。第三方履约方经常只在群里发一个
 * 运单号，运营手上有事实、系统不收——2026-08-30 生产实证：发货批次 18（谭华勇）自 08-27
 * 停在 CREATED 等了 48 小时，用户把顺丰单号发进企微两次都没人消费。
 */

export type ManualTrackingStatus = 'ACCEPTED' | 'REPLAYED' | 'CONFLICT';

export interface ManualTrackingOutcome {
  status: ManualTrackingStatus;
  tracking_number: string;
  message: string;
}

export interface CarrierOption {
  code: string;
  name: string;
}

/**
 * 一张发货批次一个固定幂等键。
 *
 * <p>刻意<b>不</b>掺随机数：运单号一落就会推发货卡、并可能触发来源回传真写客户平台。
 * 误点两次时，固定键让服务端判为重放并回放首次结果，而不是再写一次。
 */
export function manualTrackingIdempotencyKey(shipmentId: string): string {
  return `shipment-manual-tracking-${shipmentId}`;
}

export function manualTrackingRequest(
  shipmentId: string,
  carrier: string | undefined,
  trackingNumber: string,
  headers: Record<string, string>,
): { path: string; options: RequestOptions } {
  return {
    path: `/api/v1/shipments/${shipmentId}/manual-tracking`,
    options: {
      method: 'POST',
      // carrier 留空 = 让服务端按运单号前缀推断；推不出来它会报错要求明确指定，不会默认一个。
      body: { carrier: carrier?.trim() || undefined, tracking_number: trackingNumber.trim() },
      headers,
    },
  };
}

/** 录入结果的语气：成功 / 重复提交 / 和已有运单打架。 */
export function manualTrackingTone(status: ManualTrackingStatus): 'success' | 'info' | 'warning' {
  switch (status) {
    case 'ACCEPTED':
      return 'success';
    case 'REPLAYED':
      return 'info';
    default:
      return 'warning';
  }
}
