import type { RequestOptions } from './client.ts';

/** One stable replay identity per Shipment-level JD outbound operation. */
export function shipmentJdOutboundIdempotencyKey(shipmentId: string): string {
  return `shipment-jd-so-order-${shipmentId}`;
}

export function shipmentJdOutboundSubmitRequest(
  shipmentId: string,
  headers: Record<string, string>,
): { path: string; options: RequestOptions } {
  return {
    path: `/api/v1/shipments/${shipmentId}/jd-so-order`,
    options: { method: 'POST', body: {}, headers },
  };
}
