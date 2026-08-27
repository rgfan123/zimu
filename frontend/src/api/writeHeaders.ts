import { newRequestId } from './client.ts';
export interface TrustedWriteHeaderOptions {
  idempotencyKey?: string;
  extra?: Record<string, string>;
}

function explicitIdempotencyKey(value: string | undefined): string {
  if (value === undefined) {
    return newRequestId();
  }
  if (!/^[\x21-\x7e]{8,255}$/.test(value)) {
    throw new Error('explicit idempotency key must be 8 to 255 visible characters');
  }
  return value;
}

/** Browser commands carry replay protection only; the trusted gateway supplies operator identity. */
export function trustedWriteHeaders(
  options: TrustedWriteHeaderOptions = {},
): Record<string, string> {
  const reserved = new Set(['authorization', 'x-operator', 'idempotency-key']);
  const safeExtra = Object.fromEntries(
    Object.entries(options.extra ?? {}).filter(([name]) => !reserved.has(name.toLowerCase())),
  );
  return { ...safeExtra, 'Idempotency-Key': explicitIdempotencyKey(options.idempotencyKey) };
}
