import assert from 'node:assert/strict';
import test from 'node:test';
import {
  jdSerialQueryPrefill,
  jdStockQueryPrefill,
} from '../src/pages/fulfillment/jdQueryPrefill.ts';

test('JD stock tool accepts only the selected query contract context', () => {
  const params = new URLSearchParams({
    kind: 'shelfLifeInventory',
    warehouse_no: 'WH-A',
    goods_no: 'JD-GOODS-34',
    private_debug: 'never-forward',
  });

  assert.deepEqual(jdStockQueryPrefill(params), {
    kind: 'shelfLifeInventory',
    values: {
      warehouse_no: 'WH-A',
      goods_no: 'JD-GOODS-34',
    },
  });
});

test('JD serial tool uses its allowlist and rejects an unknown query kind', () => {
  assert.deepEqual(jdSerialQueryPrefill(new URLSearchParams({
    kind: 'inside',
    goods_no: 'JD-GOODS-34',
    warehouse_no: 'not-an-inside-field',
  })), {
    kind: 'inside',
    values: { goods_no: 'JD-GOODS-34' },
  });
  assert.deepEqual(jdSerialQueryPrefill(new URLSearchParams({
    kind: 'private',
    goods_no: 'must-not-cross-kinds',
  })), {
    kind: 'mall',
    values: {},
  });
});
