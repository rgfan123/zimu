import assert from 'node:assert/strict';
import test from 'node:test';
import {
  inventoryObservationPresentation,
  inventoryOverviewWarnings,
  inventoryQuantityLabel,
  inventoryQuantityUnit,
  inventorySourceLabel,
  type InventoryOverviewResponse,
} from '../src/pages/inventory/inventoryOverviewView.ts';

test('inventory quantities preserve explicit zero and never turn no observation into zero', () => {
  assert.equal(inventoryQuantityLabel('0.000', '件'), '0 件');
  assert.equal(inventoryQuantityLabel(null, '件'), '—');

  assert.deepEqual(inventoryObservationPresentation({
    observation_status: 'OBSERVED',
    freshness_status: 'CURRENT',
  }), {
    label: '时效正常',
    tone: 'success',
  });
  assert.deepEqual(inventoryObservationPresentation({
    observation_status: 'NOT_OBSERVED',
    freshness_status: 'NOT_OBSERVED',
  }), {
    label: '尚未观测',
    tone: 'warning',
  });
});

test('overview warnings expose stale and partial coverage without claiming company-wide realtime stock', () => {
  const response: InventoryOverviewResponse = {
    items: [{
      provider_id: '1',
      provider_code: 'JD',
      provider_name: '京东云仓',
      provider_type: 'JD_WAREHOUSE',
      sku_id: '2',
      sku_code: 'SKU-2',
      product_name: '商品',
      specification: '规格',
      unit: '盒',
      quantity_unit: 'JD_PIECE',
      warehouse_code: 'WH-1',
      observation_status: 'OBSERVED',
      total_quantity: '2.000',
      available_quantity: '1.000',
      unavailable_quantity: '1.000',
      observed_at: '2026-08-13T01:02:03Z',
      observation_age_seconds: 60,
      freshness_status: 'STALE',
      source_type: 'JD_ISC_QUERY_STOCK',
    }],
    page: 0,
    size: 20,
    total_elements: 1,
    total_pages: 1,
    coverage: {
      provider_count: 3,
      observed_provider_count: 1,
      sku_count: 7,
      observed_sku_count: 2,
      warehouse_count: 2,
      latest_observed_at: '2026-08-13T01:02:03Z',
      stale_count: 1,
      oldest_observed_at: '2026-08-12T01:02:03Z',
      partial: true,
      freshness_policy: 'PT15M',
    },
  };

  assert.deepEqual(inventoryOverviewWarnings(response), [
    '当前观测覆盖 1/3 个履约方、2/7 个 SKU，未观测范围不计为零库存。',
    '当前筛选范围有 1 条库存观测超过时效策略 PT15M；最早观测 2026-08-12 09:02，请重新查询后再用于履约判断。',
  ]);
});

test('stale observation is a distinct visible state', () => {
  assert.deepEqual(inventoryObservationPresentation({
    observation_status: 'OBSERVED',
    freshness_status: 'STALE',
  }), {
    label: '数据已过期',
    tone: 'error',
  });
});

test('inventory source presentation is an allowlist', () => {
  assert.equal(inventorySourceLabel('JD_ISC_QUERY_STOCK'), '京东实时库存');
  assert.equal(inventorySourceLabel('NORMALIZED_PROVIDER_SNAPSHOT'), '标准库存快照');
  assert.equal(inventorySourceLabel('UNKNOWN'), '历史来源待确认');
  assert.equal(inventorySourceLabel(null), '—');
  assert.equal(inventorySourceLabel('private-provider-debug-mode'), '未识别来源');
});

test('JD pieces are never mislabeled as an internal box or case unit', () => {
  assert.equal(inventoryQuantityUnit({ quantity_unit: 'JD_PIECE', unit: '盒' }), '件（京东）');
  assert.equal(inventoryQuantityUnit({ quantity_unit: 'INTERNAL_UNIT', unit: '盒' }), '盒');
  assert.equal(inventoryQuantityUnit({ quantity_unit: null, unit: '盒' }), '盒');
  assert.equal(inventoryQuantityUnit({ quantity_unit: 'UNKNOWN', unit: '盒' }), '单位待确认');
});
