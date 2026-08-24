import assert from 'node:assert/strict';
import test from 'node:test';
import {
  isSkuMappingReasonCode,
  reviewCaseSummary,
  safeReviewDetailRows,
  skuMappingDetailRows,
  skuMappingEvidenceCell,
  skuMappingEvidenceItems,
  SOURCE_NOT_PROVIDED,
} from '../src/presentation/publicReady.ts';

test('SKU 映射 reason_code 判定覆盖四种阻断事项', () => {
  assert.equal(isSkuMappingReasonCode('SKU_MAPPING_REQUIRED'), true);
  assert.equal(isSkuMappingReasonCode('SKU_MAPPING_CONFLICT'), true);
  assert.equal(isSkuMappingReasonCode('SOURCE_SKU_MAPPING_REQUIRED'), true);
  assert.equal(isSkuMappingReasonCode('PROVIDER_SKU_MAPPING_REQUIRED'), true);
  assert.equal(isSkuMappingReasonCode('CUSTOMER_MATCH_REQUIRED'), false);
  assert.equal(isSkuMappingReasonCode('JD_SKU_MAPPING_BLOCKED'), false);
});

test('SKU 映射抽屉固定字段逐条展示，缺字段显示「来源未提供」而不是整行消失', () => {
  const rows = skuMappingDetailRows({
    source_channel: 'FEIXIANG',
    line_no: 1,
    source_sheet_name: 'Sheet1',
    source_row_index: 5,
    missing_source_sku_refs: ['FX-001'],
    source_product_name: '子牧羊小腿',
    source_quantity: '1.500',
  });
  // 规格/单位后端未提供 → 占位呈现，行不消失。
  assert.deepEqual(rows, [
    { label: '来源渠道', value: 'FEIXIANG' },
    { label: '订单行', value: '1' },
    { label: '来源工作表', value: 'Sheet1' },
    { label: '来源行号', value: '5' },
    { label: '待映射来源商品', value: 'FX-001' },
    { label: '来源商品名称', value: '子牧羊小腿' },
    { label: '来源规格', value: SOURCE_NOT_PROVIDED },
    { label: '来源单位', value: SOURCE_NOT_PROVIDED },
    { label: '来源数量', value: '1.500' },
  ]);
});

test('SKU 映射明细单元格：空白与缺失统一呈现「来源未提供」', () => {
  assert.equal(skuMappingEvidenceCell('子牧羊小腿'), '子牧羊小腿');
  assert.equal(skuMappingEvidenceCell('   '), SOURCE_NOT_PROVIDED);
  assert.equal(skuMappingEvidenceCell(null), SOURCE_NOT_PROVIDED);
  assert.equal(skuMappingEvidenceCell(undefined), SOURCE_NOT_PROVIDED);
  assert.equal(skuMappingEvidenceCell(1.5), '1.5');
});

test('结构化证据逐行列出，多商品不合并成一串编号', () => {
  const items = skuMappingEvidenceItems({
    source_channel: 'FEIXIANG',
    evidence_items: [
      {
        source_sku_ref: 'FX-001',
        product_name: '子牧羊小腿',
        specification: '500g/盒',
        unit: '盒',
        quantity: '2.000',
      },
      {
        source_sku_ref: 'FX-002',
        product_name: '未映射商品',
        unit: '件',
        quantity: '1.000',
      },
    ],
  });
  assert.deepEqual(items, [
    {
      sourceSkuRef: 'FX-001',
      productName: '子牧羊小腿',
      specification: '500g/盒',
      unit: '盒',
      quantity: '2.000',
    },
    {
      sourceSkuRef: 'FX-002',
      productName: '未映射商品',
      specification: null,
      unit: '件',
      quantity: '1.000',
    },
  ]);
});

test('证据项只读取固定字段，未知键与嵌套自由文本被 fail-closed 丢弃', () => {
  const items = skuMappingEvidenceItems({
    evidence_items: [
      {
        source_sku_ref: 'FX-001',
        product_name: '子牧羊小腿',
        receiver_phone: '13800000000',
        internal_payload: { sql: 'select *' },
      },
    ],
  });
  assert.deepEqual(items, [
    {
      sourceSkuRef: 'FX-001',
      productName: '子牧羊小腿',
      specification: null,
      unit: null,
      quantity: null,
    },
  ]);
  assert.doesNotMatch(JSON.stringify(items), /13800000000|select \*/);
});

test('证据缺失时从白名单字段 missing_source_sku_refs 退化为逐编号一行', () => {
  const items = skuMappingEvidenceItems({
    source_channel: 'FEIXIANG',
    missing_source_sku_refs: ['FX-001', 'FX-002'],
  });
  assert.deepEqual(items, [
    { sourceSkuRef: 'FX-001', productName: null, specification: null, unit: null, quantity: null },
    { sourceSkuRef: 'FX-002', productName: null, specification: null, unit: null, quantity: null },
  ]);
  assert.deepEqual(skuMappingEvidenceItems({ source_channel: 'FEIXIANG' }), []);
});

test('新放行字段进入标量白名单，PII 键仍被丢弃', () => {
  const rows = safeReviewDetailRows({
    source_channel: 'FEIXIANG',
    line_no: 2,
    source_product_name: '子牧羊小腿',
    source_specification: '500g/盒',
    source_unit: '盒',
    source_quantity: '1.500',
    source_sheet_name: 'Sheet1',
    source_row_index: 5,
    missing_source_sku_refs: ['FX-001'],
    receiver_phone: '13800000000',
    raw_payload: { stack: 'internal' },
    unknown_internal_field: 'do-not-render',
  });
  const labels = rows.map((row) => row.label);
  assert.ok(labels.includes('来源商品名称'));
  assert.ok(labels.includes('来源规格'));
  assert.ok(labels.includes('来源单位'));
  assert.ok(labels.includes('来源数量'));
  assert.ok(labels.includes('来源工作表'));
  assert.ok(labels.includes('来源行号'));
  assert.doesNotMatch(JSON.stringify(rows), /13800000000|raw_payload|do-not-render|stack/);
});

test('复核摘要包含新放行的来源商品信息，PII 不外泄', () => {
  const summary = reviewCaseSummary({
    reason_code: 'SKU_MAPPING_REQUIRED',
    detail: {
      missing_source_sku_refs: ['FX-001'],
      source_product_name: '子牧羊小腿',
      source_specification: '500g/盒',
      source_quantity: '1.500',
      source_sheet_name: 'Sheet1',
      source_row_index: 5,
      receiver_phone: '13800000000',
      raw_payload: { access_token: 'do-not-render' },
    },
  });
  assert.match(summary, /来源商品名称：子牧羊小腿/);
  assert.match(summary, /来源规格：500g\/盒/);
  assert.match(summary, /来源工作表：Sheet1/);
  assert.doesNotMatch(summary, /13800000000|access_token|do-not-render|raw_payload/);
});
