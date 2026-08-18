import type { ContinuationExportCommand } from './types';

export function continuationExportRequest(
  fulfillmentId: string,
  body: ContinuationExportCommand,
  headers: Record<string, string>,
) {
  return {
    path: `/api/v1/fulfillments/${fulfillmentId}/continuation-exports`,
    options: {
      method: 'POST' as const,
      body,
      headers,
    },
  };
}
