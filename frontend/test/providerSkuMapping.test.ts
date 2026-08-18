import assert from 'node:assert/strict';
import test from 'node:test';
import {
  JD_PIECES_PER_UNIT_PATTERN,
  buildProviderSkuMappingCreateBody,
  buildProviderSkuMappingUpdateBody,
  jdPiecesPerUnitLabel,
  optionalJdPiecesPerUnit,
} from '../src/pages/product/providerSkuMapping.ts';

test('京东件数换算与后端公开 decimal-string 契约一致', () => {
  for (const value of ['1', '0.125', '2.5', '999']) {
    assert.equal(JD_PIECES_PER_UNIT_PATTERN.test(value), true, value);
    assert.equal(optionalJdPiecesPerUnit(` ${value} `), value);
  }

  for (const value of ['0', '0.000', '-1', '1.0001', '.5', '1.']) {
    assert.equal(JD_PIECES_PER_UNIT_PATTERN.test(value), false, value);
    assert.throws(() => optionalJdPiecesPerUnit(value), /京东件数换算/);
  }
});

test('空换算保持未配置，不暗中默认为 1 件', () => {
  assert.equal(optionalJdPiecesPerUnit(undefined), undefined);
  assert.equal(optionalJdPiecesPerUnit('  '), undefined);
  assert.equal(jdPiecesPerUnitLabel(null), '—');
  assert.equal(jdPiecesPerUnitLabel('2.5'), '2.5 件');
});

test('履约方 SKU 创建和编辑均把显式换算投影到公开 API 载荷', () => {
  assert.deepEqual(buildProviderSkuMappingCreateBody({
    provider_id: '11',
    sku_id: '22',
    provider_sku_code: 'JD-GOODS-01',
    provider_sku_name: '羊小腿',
    merchant_sku_code: 'ERP-SKU-01',
    jd_pieces_per_unit: ' 2.5 ',
    active: true,
  }), {
    provider_id: '11',
    sku_id: '22',
    provider_sku_code: 'JD-GOODS-01',
    provider_sku_name: '羊小腿',
    merchant_sku_code: 'ERP-SKU-01',
    jd_pieces_per_unit: '2.5',
    active: true,
  });

  assert.deepEqual(buildProviderSkuMappingUpdateBody({
    expected_version: 3,
    provider_sku_code: 'JD-GOODS-01',
    provider_sku_name: '',
    merchant_sku_code: ' ERP-SKU-02 ',
    jd_pieces_per_unit: '1',
    active: false,
  }), {
    expected_version: 3,
    provider_sku_code: 'JD-GOODS-01',
    provider_sku_name: undefined,
    merchant_sku_code: 'ERP-SKU-02',
    jd_pieces_per_unit: '1',
    active: false,
  });
});
