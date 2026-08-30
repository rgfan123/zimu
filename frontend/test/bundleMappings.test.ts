import assert from 'node:assert/strict';
import test from 'node:test';
import type { MasterDataRecord, ProductBundleRecord } from '../src/api/types.ts';
import {
  activeBundleOptions,
  bundleLabelById,
  bundleMappingCreateBody,
  bundleMappingPresentation,
  bundleMappingUpdateBody,
} from '../src/pages/product/bundleMappings.ts';

const bundles: ProductBundleRecord[] = [
  {
    id: '11',
    code: 'BND-NEWYEAR',
    name: '年货礼包',
    active: true,
    version: 2,
    attributes: { status: 'ACTIVE', items: [] },
  },
  {
    id: '12',
    code: 'BND-DRAFT',
    name: '草稿礼包',
    active: false,
    version: 1,
    attributes: { status: 'DRAFT', items: [] },
  },
  {
    id: '13',
    code: 'BND-OLD',
    name: '去年礼包',
    active: false,
    version: 5,
    attributes: { status: 'INACTIVE', items: [] },
  },
];

const mapping: MasterDataRecord = {
  id: '901',
  code: 'DZ-礼盒A',
  name: '大者礼盒A',
  active: true,
  version: 3,
  attributes: {
    source_channel: 'DAZHE',
    source_bundle_ref: 'DZ-礼盒A',
    source_barcode: null,
    bundle_id: '11',
    quantity_multiplier: '1',
  },
};

test('可选礼包只给 ACTIVE，草稿和停用礼包不进下拉', () => {
  assert.deepEqual(activeBundleOptions(bundles), [
    { value: '11', label: '年货礼包（BND-NEWYEAR）' },
  ]);
  assert.deepEqual(activeBundleOptions([]), []);
});

test('礼包名称查表覆盖全部状态，历史映射指向停用礼包也能显示', () => {
  assert.deepEqual(bundleLabelById(bundles), {
    11: '年货礼包（BND-NEWYEAR）',
    12: '草稿礼包（BND-DRAFT）',
    13: '去年礼包（BND-OLD）',
  });
});

test('列表展示字段从 attributes 摊平，缺来源编号时回落到 code', () => {
  assert.deepEqual(bundleMappingPresentation(mapping), {
    sourceChannel: 'DAZHE',
    sourceBundleRef: 'DZ-礼盒A',
    sourceBundleName: '大者礼盒A',
    bundleId: '11',
  });

  const bare: MasterDataRecord = { id: '902', code: 'JFB-001', name: '聚福宝礼包', active: false, version: 1 };
  assert.deepEqual(bundleMappingPresentation(bare), {
    sourceChannel: '',
    sourceBundleRef: 'JFB-001',
    sourceBundleName: '聚福宝礼包',
    bundleId: '',
  });
});

test('新建请求体只发契约字段，不发一期恒为 1 的包装乘数', () => {
  const body = bundleMappingCreateBody({
    source_channel: 'DAZHE',
    source_bundle_ref: '  DZ-礼盒A  ',
    source_bundle_name: ' 大者礼盒A ',
    bundle_id: '11',
    active: true,
  });
  assert.deepEqual(body, {
    source_channel: 'DAZHE',
    source_bundle_ref: 'DZ-礼盒A',
    source_bundle_name: '大者礼盒A',
    bundle_id: '11',
    active: true,
  });
  assert.ok(!('quantity_multiplier' in body));
});

test('新建时留空的来源礼包名称不发出去，由后端回落到礼包名', () => {
  assert.deepEqual(
    bundleMappingCreateBody({ source_channel: 'JUFUBAO', source_bundle_ref: 'JFB-001', source_bundle_name: '   ', bundle_id: '11' }),
    { source_channel: 'JUFUBAO', source_bundle_ref: 'JFB-001', bundle_id: '11' },
  );
});

test('更新请求体必带 expected_version，没填的字段不发，避免把「没改」发成「改成空」', () => {
  assert.deepEqual(
    bundleMappingUpdateBody({ expected_version: 3, bundle_id: '11', source_bundle_name: '大者礼盒A', active: false }),
    { expected_version: 3, bundle_id: '11', source_bundle_name: '大者礼盒A', active: false },
  );

  // 只停用：目标礼包与名称都没出现在表单值里，就一个字段都不带。
  assert.deepEqual(
    bundleMappingUpdateBody({ expected_version: 7, active: false }),
    { expected_version: 7, active: false },
  );

  // 名称是例外：出现过就照原样带上，空串是明确的清空语义。
  assert.deepEqual(
    bundleMappingUpdateBody({ expected_version: 1, source_bundle_name: '  ' }),
    { expected_version: 1, source_bundle_name: '' },
  );
});
