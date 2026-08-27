import assert from 'node:assert/strict';
import test from 'node:test';
import type { MasterDataRecord } from '../src/api/types.ts';
import {
  SOURCE_MAPPING_CHANNELS,
  buildSourceSkuMappingMatrix,
  internalSkuPresentation,
  sourceMappingPresentation,
} from '../src/pages/product/skuMappingMatrix.ts';

const skus: MasterDataRecord[] = [
  {
    id: '101',
    code: 'SKU-000101',
    name: '羊小腿 500g',
    active: true,
    version: 2,
    attributes: { specification: '500g', unit: '袋' },
  },
  {
    id: '102',
    code: 'SKU-000102',
    name: '牛腩 1kg',
    active: true,
    version: 1,
    attributes: { specification: '1kg', unit: '盒' },
  },
];

const mappings: MasterDataRecord[] = [
  {
    id: '501',
    code: 'CAISHIXIAN:彩食鲜羊小腿',
    name: '彩食鲜羊小腿',
    active: true,
    version: 3,
    attributes: {
      source_channel: 'CAISHIXIAN',
      source_sku_ref: '彩食鲜羊小腿',
      sku_id: '101',
      quantity_multiplier: '2.000',
    },
  },
  {
    id: '502',
    code: 'JUFUBAO:聚福宝牛腩',
    name: '聚福宝牛腩',
    active: true,
    version: 1,
    attributes: {
      source_channel: 'JUFUBAO',
      source_sku_ref: '聚福宝牛腩',
      sku_id: '102',
      quantity_multiplier: '1.000',
    },
  },
  {
    id: '503',
    code: 'JUFUBAO:聚福宝牛腩礼盒',
    name: '聚福宝牛腩礼盒',
    active: true,
    version: 1,
    attributes: {
      source_channel: 'JUFUBAO',
      source_sku_ref: '聚福宝牛腩礼盒',
      sku_id: '102',
      quantity_multiplier: '2.000',
    },
  },
  {
    id: '504',
    code: 'WECOM:WECOM-SKU-101',
    name: '企业微信羊小腿',
    active: true,
    version: 1,
    attributes: {
      source_channel: 'WECOM',
      source_sku_ref: 'WECOM-SKU-101',
      sku_id: '101',
      quantity_multiplier: '1.000',
    },
  },
];

test('内部 SKU 为唯一行，固定列覆盖全部可事前配置的来源渠道', () => {
  const matrix = buildSourceSkuMappingMatrix(skus, mappings);

  // 2026-08-27 中汇 60043846 事故后补齐：中汇/大者/万齐此前没有任何事前配置入口，
  // 映射只能等出事后在复核抽屉里补。WECOM（录入渠道）与 WANGQI（误建渠道）刻意不列。
  assert.deepEqual(SOURCE_MAPPING_CHANNELS, ['FEIXIANG', 'CAISHIXIAN', 'JUFUBAO', 'ZHONGHUI', 'DAZHE', 'WANQI']);
  assert.deepEqual(matrix.channels, ['FEIXIANG', 'CAISHIXIAN', 'JUFUBAO', 'ZHONGHUI', 'DAZHE', 'WANQI']);
  assert.deepEqual(matrix.rows.map((row) => row.sku.code), ['SKU-000101', 'SKU-000102']);
  assert.deepEqual(matrix.rows[0]?.mappingsByChannel.FEIXIANG, []);
  assert.deepEqual(matrix.rows[0]?.mappingsByChannel.CAISHIXIAN.map((mapping) => mapping.id), ['501']);
  assert.deepEqual(matrix.rows[1]?.mappingsByChannel.JUFUBAO.map((mapping) => mapping.id), ['502', '503']);
});

test('只显示所选平台，企业微信不进入本矩阵', () => {
  const matrix = buildSourceSkuMappingMatrix(skus, mappings, ['JUFUBAO', 'FEIXIANG']);

  assert.deepEqual(matrix.channels, ['FEIXIANG', 'JUFUBAO']);
  assert.equal(Object.hasOwn(matrix.rows[0]!.mappingsByChannel, 'WECOM'), false);
  assert.equal(matrix.rows[0]!.mappingsByChannel.FEIXIANG.length, 0);
});

test('内部 SKU 单元格以商品名称为主标题、SKU 编码为次标题', () => {
  assert.deepEqual(internalSkuPresentation(skus[0]!), {
    primary: '羊小腿 500g',
    secondary: 'SKU-000101',
    meta: '500g · 袋',
  });
});

test('所有平台映射单元格以商品名称为主标题、平台 SKU 为次标题', () => {
  assert.deepEqual(sourceMappingPresentation({
    ...mappings[0]!,
    name: '彩食鲜羊小腿商品名',
    attributes: {
      ...mappings[0]!.attributes,
      source_sku_ref: '2047862',
    },
  }), {
    primary: '彩食鲜羊小腿商品名',
    secondary: '2047862',
    multiplier: '2.000',
  });
});
