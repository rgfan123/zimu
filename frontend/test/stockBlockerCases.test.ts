import assert from 'node:assert/strict';
import test from 'node:test';

import {
  extractStockBlockerCases,
  mergeStockBlockers,
  STOCK_BLOCKED_REASON,
} from '../src/pages/workbench/stockBlockerCases.ts';

/** 夹具取自根因复算的真实形状（review_cases#41/#42：SKU 48 因单位判定误报被 JD_SKU_MAPPING_GATE_BLOCKED 阻断）。 */
const MAPPING_GATE_BLOCKER = {
  code: 'JD_SKU_MAPPING_GATE_BLOCKED',
  message: '非‘件’单位必须配置显式京东件数换算',
  product_name: '子牧A5澳洲和牛霜降肥牛卷',
  goods_no: 'EMG4418918549603',
  sku_code: 'SKU-JD-000048',
  sku_id: '48',
  order_line_ids: ['5'],
  missing_field: 'provider_skus.external_codes.jd_pieces_per_unit',
  // 门禁 14 种 issue 落到 blocker 上 code 一律被塌缩成 JD_SKU_MAPPING_GATE_BLOCKED，
  // 真原因只在这个字段里（ShipmentJdStockCheckService.mappingGateBlocker）。
  mapping_issue_code: 'UNIT_CONVERSION_MISSING',
};

const INSUFFICIENT_BLOCKER = {
  code: 'JD_STOCK_INSUFFICIENT',
  message: '「羊小腿」（京东商品编码 JD-SKU-000001）目标仓可用库存不足：需要 2 件，可用 0 件',
  goods_no: 'JD-SKU-000001',
  product_name: '羊小腿',
  sku_code: 'SKU-JD-000001',
  sku_id: '1',
  order_line_ids: ['9'],
};

function reviewCase(overrides: Record<string, unknown> = {}) {
  return {
    id: '42',
    case_no: 'RC-JD-STOCK-ABC',
    reason_code: STOCK_BLOCKED_REASON,
    subject_type: 'SHIPMENT',
    subject_id: '17',
    order_id: '31',
    order_no: 'SO-20260828-0007',
    detail: { blockers: [MAPPING_GATE_BLOCKER] },
    ...overrides,
  };
}

test('extractStockBlockerCases：解析出完整商品身份与换货定位字段', () => {
  const cases = extractStockBlockerCases([reviewCase()]);
  assert.equal(cases.length, 1);
  assert.deepEqual(cases[0], {
    caseId: '42',
    caseNo: 'RC-JD-STOCK-ABC',
    shipmentId: '17',
    orderId: '31',
    orderNo: 'SO-20260828-0007',
    blockers: [{
      code: 'JD_SKU_MAPPING_GATE_BLOCKED',
      message: '非‘件’单位必须配置显式京东件数换算',
      goodsNo: 'EMG4418918549603',
      productName: '子牧A5澳洲和牛霜降肥牛卷',
      skuCode: 'SKU-JD-000048',
      skuId: '48',
      orderLineIds: ['5'],
      missingField: 'provider_skus.external_codes.jd_pieces_per_unit',
      mappingIssueCode: 'UNIT_CONVERSION_MISSING',
    }],
  });
});

test('extractStockBlockerCases：缺 order_id/order_no 的事项回落为 null（整卡不造假链接）', () => {
  const cases = extractStockBlockerCases([reviewCase({ order_id: undefined, order_no: undefined })]);
  assert.equal(cases[0].orderId, null);
  assert.equal(cases[0].orderNo, null);
});

test('extractStockBlockerCases：非目标 reason_code 一律忽略', () => {
  assert.deepEqual(extractStockBlockerCases([reviewCase({ reason_code: 'JD_SKU_MAPPING_BLOCKED' })]), []);
});

test('extractStockBlockerCases：缺 code/message 的 blocker 丢弃但不崩整条事项', () => {
  const cases = extractStockBlockerCases([reviewCase({
    detail: { blockers: [{ message: '缺 code' }, MAPPING_GATE_BLOCKER] },
  })]);
  assert.equal(cases.length, 1);
  assert.equal(cases[0].blockers.length, 1);
});

test('extractStockBlockerCases：没有合法 blocker 的事项整条丢弃', () => {
  assert.deepEqual(extractStockBlockerCases([reviewCase({ detail: { blockers: [{ message: '缺 code' }] } })]), []);
});

test('extractStockBlockerCases：可选字段缺失时回落为 null / 空数组，不报错', () => {
  const cases = extractStockBlockerCases([reviewCase({
    detail: { blockers: [{ code: 'JD_STOCK_QUERY_FAILED', message: '京东库存查询失败，默认阻断' }] },
  })]);
  assert.deepEqual(cases[0].blockers[0], {
    code: 'JD_STOCK_QUERY_FAILED',
    message: '京东库存查询失败，默认阻断',
    goodsNo: null,
    productName: null,
    skuCode: null,
    skuId: null,
    orderLineIds: [],
    missingField: null,
    mappingIssueCode: null,
  });
});

test('extractStockBlockerCases：subject_type 非 SHIPMENT 时 shipmentId 为 null', () => {
  const cases = extractStockBlockerCases([reviewCase({ subject_type: 'ORDER', subject_id: '5' })]);
  assert.equal(cases[0].shipmentId, null);
});

test('mergeStockBlockers：跨事项按 code+skuId+goodsNo+message 去重', () => {
  const mapping = extractStockBlockerCases([reviewCase({ id: '41', case_no: 'RC-JD-SKU-41' })])[0];
  const stock = extractStockBlockerCases([reviewCase({
    id: '42',
    case_no: 'RC-JD-STOCK-42',
    detail: { blockers: [MAPPING_GATE_BLOCKER, INSUFFICIENT_BLOCKER] },
  })])[0];

  const merged = mergeStockBlockers([mapping, stock]);

  assert.equal(merged.length, 2);
  assert.deepEqual(merged.map((item) => item.code), ['JD_SKU_MAPPING_GATE_BLOCKED', 'JD_STOCK_INSUFFICIENT']);
});
