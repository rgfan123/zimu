import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import type { InventoryDetailCapability, InventoryDetailContext } from '../src/api/types.ts';
import {
  inventoryCapabilityTools,
  safeInventoryReturnLocation,
} from '../src/pages/inventory/inventoryDetailsView.ts';

const context: InventoryDetailContext = {
  provider_id: '12',
  provider_code: 'JD',
  provider_name: '京东云仓',
  provider_type: 'JD_WAREHOUSE',
  sku_id: '34',
  sku_code: 'SKU-34',
  product_name: '商品',
  specification: '规格',
  unit: '件',
  provider_sku_code: 'JD-GOODS-34',
  warehouse_code: 'WH-A',
};

test('inventory detail tool links carry only documented provider context to existing JD routes', () => {
  const capability: InventoryDetailCapability = {
    group: 'BATCH_AND_SHELF_LIFE',
    label: '批次 / 库存水位变化 / 效期',
    integration_status: 'INTEGRATED',
    runtime_mode: 'MOCK',
    source_type: 'JD_ISC_READ_ONLY',
    explanation: '已接入',
    tools: [
      { code: 'JD_SHELF_LIFE_INVENTORY', label: '效期库存' },
      { code: 'PRIVATE_DEBUG_TOOL', label: '内部调试' },
    ],
  };

  assert.deepEqual(inventoryCapabilityTools(capability, context), [{
    code: 'JD_SHELF_LIFE_INVENTORY',
    label: '效期库存',
    href: '/fulfillment/jd-stock?kind=shelfLifeInventory&goods_no=JD-GOODS-34&warehouse_no=WH-A',
  }]);
  assert.deepEqual(inventoryCapabilityTools({ ...capability, integration_status: 'NOT_INTEGRATED' }, context), []);
});

test('inventory detail return location accepts only the total-inventory route', () => {
  assert.equal(
    safeInventoryReturnLocation('/inventory/overview?page=2&size=50&provider_id=12'),
    '/inventory/overview?page=2&size=50&provider_id=12',
  );
  assert.equal(safeInventoryReturnLocation('/orders?token=private'), '/inventory/overview');
  assert.equal(safeInventoryReturnLocation('https://example.invalid/inventory/overview'), '/inventory/overview');
  assert.equal(safeInventoryReturnLocation('javascript:alert(1)'), '/inventory/overview');
});

test('OpenAPI publishes the inventory details context, freshness, capability and no-observation contract', () => {
  const openapi = readFileSync(new URL('../../docs/openapi.yaml', import.meta.url), 'utf8');
  assert.match(openapi, /\/api\/v1\/inventory\/details:/);
  assert.match(openapi, /InventoryDetailsResponse:/);
  assert.match(openapi, /data_mode: \{ type: string, enum: \[CACHED_SNAPSHOT, NO_OBSERVATION\] \}/);
  assert.match(openapi, /integration_status: \{ type: string, enum: \[INTEGRATED, NOT_INTEGRATED, CONTEXT_MISSING\] \}/);
  assert.match(openapi, /runtime_mode: \{ type: string, enum: \[REAL, MOCK, UNKNOWN, NOT_APPLICABLE\] \}/);
  assert.ok(openapi.includes("pattern: '^\\S+$'"), 'warehouse_code must reject whitespace like the HTTP controller');
});
