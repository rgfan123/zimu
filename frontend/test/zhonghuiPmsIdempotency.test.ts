import assert from 'node:assert/strict';
import test from 'node:test';
import { zhonghuiPmsBatchIdempotencyKey } from '../src/api/zhonghuiPmsIdempotency.ts';

test('same batch body produces the same stable idempotency key', () => {
  const body = { sku_ids: ['1', '2'], overrides: { brand_id: '164343', producing_area: '新疆' } };
  assert.equal(zhonghuiPmsBatchIdempotencyKey(body), zhonghuiPmsBatchIdempotencyKey(body));
});

test('different sku selection or overrides produce different keys', () => {
  const base = { sku_ids: ['1', '2'], overrides: { brand_id: '164343' } };
  const moreSkus = { sku_ids: ['1', '2', '3'], overrides: { brand_id: '164343' } };
  const otherOverrides = { sku_ids: ['1', '2'], overrides: { brand_id: '999' } };
  const noOverrides = { sku_ids: ['1', '2'], overrides: undefined };
  assert.notEqual(zhonghuiPmsBatchIdempotencyKey(base), zhonghuiPmsBatchIdempotencyKey(moreSkus));
  assert.notEqual(zhonghuiPmsBatchIdempotencyKey(base), zhonghuiPmsBatchIdempotencyKey(otherOverrides));
  assert.notEqual(zhonghuiPmsBatchIdempotencyKey(base), zhonghuiPmsBatchIdempotencyKey(noOverrides));
});

test('idempotency key matches writeHeaders constraints (ASCII visible, 8-255 chars)', () => {
  const key = zhonghuiPmsBatchIdempotencyKey({ sku_ids: ['1', '2'], overrides: { producing_area: '新疆' } });
  assert.match(key, /^[\x21-\x7e]{8,255}$/);
  assert.ok(key.startsWith('zhonghui-pms-batch-'));
});
