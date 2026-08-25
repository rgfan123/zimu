import assert from 'node:assert/strict';
import test from 'node:test';
import { jdStockBlockers, jdStockReviewEvidence } from '../src/pages/workbench/jdStockReview.ts';

test('jd stock review evidence renders product, demand and observed stock for each item', () => {
  const detail = {
    shipment_id: '16',
    blockers: [{ code: 'JD_STOCK_INSUFFICIENT', message: '库存不足' }],
    observations: [{
      sku_id: '19',
      sku_code: 'SKU-JD-000019',
      product_name: '精选牛肉卷',
      goods_no: 'EMG4418767478832',
      warehouse_code: '118085840',
      required_quantity: '1',
      quantity_unit: 'JD_PIECE',
      observation_status: 'OBSERVED',
      stock_quantity: '207.000',
      usable_quantity: '0.000',
    }],
  };

  assert.deepEqual(jdStockBlockers(detail), ['京东可用库存不足']);
  const rows = jdStockReviewEvidence(detail);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].productLabel, '精选牛肉卷 · SKU SKU-JD-000019');
  assert.equal(rows[0].goodsLabel, 'EMG4418767478832');
  assert.equal(rows[0].demandLabel, '1 JD_PIECE');
  assert.equal(rows[0].observationLabel, '总库存 207.000 / 可用 0.000（仓 118085840）');
});

test('jd stock review evidence covers unobserved items and unknown blocker codes verbatim', () => {
  const detail = {
    blockers: [{ code: 'JD_STOCK_QUERY_FAILED', message: '查询失败' }, { code: 'SOME_NEW_CODE' }],
    observations: [{
      sku_id: '19',
      product_name: '精选牛肉卷',
      observation_status: 'NOT_OBSERVED',
      warehouse_code: 'WH-1',
    }],
  };

  assert.deepEqual(jdStockBlockers(detail), ['京东库存查询失败', 'SOME_NEW_CODE']);
  const rows = jdStockReviewEvidence(detail);
  assert.equal(rows.length, 1);
  assert.equal(rows[0].goodsLabel, '—');
  assert.equal(rows[0].observationLabel, '未观测（仓 WH-1）');
});

test('jd stock review evidence ignores empty or malformed observations', () => {
  assert.deepEqual(jdStockReviewEvidence({ observations: [] }), []);
  assert.deepEqual(jdStockReviewEvidence({ observations: [{ goods_no: 'X' }] }), []);
  assert.deepEqual(jdStockReviewEvidence({}), []);
  assert.deepEqual(jdStockBlockers({}), []);
});
