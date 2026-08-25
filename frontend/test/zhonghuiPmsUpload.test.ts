import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildPmsBatchUploadOverrides,
  pmsUploadSummary,
} from '../src/pages/product/zhonghuiPmsUpload.ts';

test('batch upload overrides emit all filled fields as decimal strings / trimmed text', () => {
  const overrides = buildPmsBatchUploadOverrides({
    brand_id: 164343,
    certification_type: 2,
    certification_id: 56118,
    third_id: 3407,
    limit_area_temp_id: 2075,
    goods_tax: 9,
    logistics_carrier: [1, 20],
    producing_area: ' 新疆 ',
    goods_num: 50,
    sale_unit: ' 箱 ',
    origincountry: 1,
    goods_price: 500,
    supply_price: 100.5,
  });
  assert.deepEqual(overrides, {
    brand_id: '164343',
    certification_type: '2',
    certification_id: '56118',
    third_id: '3407',
    limit_area_temp_id: '2075',
    goods_tax: '9',
    logistics_carrier: '1,20',
    producing_area: '新疆',
    goods_num: '50',
    sale_unit: '箱',
    origincountry: '1',
    goods_price: '500',
    supply_price: '100.5',
  });
});

test('batch upload overrides drop empty values', () => {
  const overrides = buildPmsBatchUploadOverrides({
    brand_id: undefined,
    certification_id: null,
    goods_tax: undefined,
    goods_num: null,
    producing_area: '   ',
    logistics_carrier: [],
  });
  assert.deepEqual(overrides, {});
});

test('batch upload overrides keep zero values as strings', () => {
  const overrides = buildPmsBatchUploadOverrides({ goods_tax: 0, goods_num: 0, goods_price: 0 });
  assert.deepEqual(overrides, { goods_tax: '0', goods_num: '0', goods_price: '0' });
});

test('upload summary formats totals', () => {
  assert.equal(pmsUploadSummary(0, 0, 0), '没有可上传的商品');
  assert.equal(pmsUploadSummary(10, 8, 2), '共 10 个：成功 8，失败 2');
});
