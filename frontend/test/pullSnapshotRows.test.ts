/**
 * 批次快照弹窗的取数口径（2026-08-28 生产实证：整行破折号）。
 *
 * 现场：聚福宝新单在弹窗里只有渠道单号，收件人/电话/地址/商品/件数全是「—」，
 * 但 app.orders 里收件人「丁」、电话、地址俱全，app.order_lines 里商品名与数量也在。
 *
 * 根因：弹窗把 raw_import_rows.raw_cells 当唯一数据源，而那份快照**刻意不含 PII**
 * （它会被渲染进企微群卡片）。结构化拉取的 raw_cells 只有
 * {source_ref, source_line_ref, item_index, snapshot}，后端 projectionFor 只认顶层标量，
 * snapshot 是对象被整个丢掉 → parsed={} → 前端 firstText 回退 '—'。
 *
 * 本文件锁死：订单实体优先、快照退居证据、拿不到时给**原因**而不是破折号，
 * 以及「商品与件数必须同源」这个既有不变量。
 */

import assert from 'node:assert/strict';
import test from 'node:test';

import {
  missingFactLabel,
  presentSnapshotRowFacts,
  reviewReasonFor,
  snapshotOrderIdsToLoad,
  type SnapshotImportRow,
  type SnapshotOrderSource,
} from '../src/pages/workbench/pullSnapshotRows.ts';

/**
 * 结构化平台拉取的真实行形状：`presentImportRow` 对着 {source_ref, source_line_ref,
 * item_index, snapshot} 这样的 raw_cells 跑出来的结果——除渠道单号外全是 '—' 占位。
 * 既有路由测试的夹具手工塞了 parsed，所以从来没覆盖到这个真实形状。
 */
function structuredRow(overrides: Partial<SnapshotImportRow> = {}): SnapshotImportRow {
  return {
    orderId: '31',
    orderLineId: '6',
    receiverName: '—',
    receiverPhone: '—',
    receiverAddress: '—',
    productName: '—',
    quantity: '—',
    specification: '—',
    ...overrides,
  };
}

/** Excel 上传口径：解析投影里带了收件人与商品，raw_cells 有真表头。 */
function excelRow(overrides: Partial<SnapshotImportRow> = {}): SnapshotImportRow {
  return {
    orderId: '—',
    orderLineId: '—',
    receiverName: '张三',
    receiverPhone: '13800000000',
    receiverAddress: '河南省开封市测试路 1 号',
    productName: '来源苹果',
    quantity: '2',
    specification: '5kg/箱',
    ...overrides,
  };
}

function order(overrides: Partial<SnapshotOrderSource> = {}): SnapshotOrderSource {
  return {
    order_no: 'SO-20260828-0007',
    order_status: 'NEED_REVIEW',
    receiver: {
      name: '丁小满', phone: '13800001111',
      province: '上海市', city: '上海市', district: '浦东新区', town: '张江镇',
      address: '科苑路 88 号',
    },
    lines: [{
      id: '6',
      product_name: '乔府大院金饭碗五常大米5kg',
      specification: '5kg/袋', unit: '袋', requested_quantity: 1,
    }],
    ...overrides,
  };
}

test('生产现场：结构化拉取行 + 订单实体 → 整行不再是破折号', () => {
  const facts = presentSnapshotRowFacts(structuredRow(), order());
  assert.equal(facts.receiverName, '丁小满');
  assert.equal(facts.receiverPhone, '13800001111');
  assert.equal(facts.receiverAddress, '上海市 上海市 浦东新区 张江镇 科苑路 88 号');
  assert.equal(facts.productName, '乔府大院金饭碗五常大米5kg');
  assert.equal(facts.quantity, '1袋');
  assert.equal(facts.receiverSource, 'ORDER');
  assert.equal(facts.productSource, 'ORDER');
});

test('没有订单实体时，结构化行如实说「未建单」，不是破折号', () => {
  const facts = presentSnapshotRowFacts(structuredRow({ orderId: '—', orderLineId: '—' }), null);
  assert.equal(facts.receiverName, null);
  assert.equal(facts.productName, null);
  assert.equal(facts.receiverSource, 'NONE');
  assert.equal(facts.hasOrder, false);
  assert.equal(missingFactLabel(facts), '未建单');
});

test('建了单但订单实体没拉到时说「未提供」，与「未建单」区分开', () => {
  const facts = presentSnapshotRowFacts(structuredRow(), null);
  assert.equal(facts.hasOrder, true);
  assert.equal(missingFactLabel(facts), '未提供');
});

test('Excel 口径（无订单）保留原有快照展示，不因这次改动退化', () => {
  const facts = presentSnapshotRowFacts(excelRow(), null);
  assert.equal(facts.receiverName, '张三');
  assert.equal(facts.productName, '来源苹果');
  assert.equal(facts.quantity, '2');
  assert.equal(facts.receiverSource, 'SNAPSHOT');
  assert.equal(facts.productSource, 'SNAPSHOT');
});

test('未建单行的后端整数投影不会被字符串读取器丢掉', () => {
  const facts = presentSnapshotRowFacts(excelRow({ quantity: 3 }), null);
  assert.equal(facts.quantity, '3');
  assert.equal(facts.productSource, 'SNAPSHOT');
});

test('订单实体优先于快照——快照里的脱敏值不得盖住真值', () => {
  const facts = presentSnapshotRowFacts(excelRow({ orderId: '31', orderLineId: '6' }), order());
  assert.equal(facts.receiverName, '丁小满');
  assert.equal(facts.productName, '乔府大院金饭碗五常大米5kg');
});

test('商品与件数同源：订单行匹配不上时整组退回快照，不混口径', () => {
  // order_line_id 指向订单里不存在的行 → 商品组整体退快照，收件人仍走订单。
  const facts = presentSnapshotRowFacts(excelRow({ orderId: '31', orderLineId: '999' }), order());
  assert.equal(facts.productSource, 'SNAPSHOT');
  assert.equal(facts.productName, '来源苹果');
  assert.equal(facts.quantity, '2');
  assert.equal(facts.receiverSource, 'ORDER', '收件人与商品是两组事实，各自独立取源');
});

test('订单行只有商品名没数量时，件数为 null 而不是借快照的数字', () => {
  const facts = presentSnapshotRowFacts(
    excelRow({ orderId: '31', orderLineId: '6' }),
    order({ lines: [{ id: '6', product_name: '乔府大院金饭碗五常大米5kg', requested_quantity: null }] }),
  );
  assert.equal(facts.productSource, 'ORDER');
  assert.equal(facts.productName, '乔府大院金饭碗五常大米5kg');
  assert.equal(facts.quantity, null, '同源不变量：不允许商品取订单、件数取快照');
});

test('订单号与状态随订单实体带出', () => {
  const facts = presentSnapshotRowFacts(structuredRow(), order());
  assert.equal(facts.orderNo, 'SO-20260828-0007');
  assert.equal(facts.orderStatus, 'NEED_REVIEW');
});

// —— 「这一行为什么待复核」——

test('待复核原因按 order_line_id 精确匹配复核事项', () => {
  const reason = reviewReasonFor(structuredRow(), [
    { order_id: '31', order_line_id: '5', reason_code: 'JD_STOCK_BLOCKED' },
    { order_id: '31', order_line_id: '6', reason_code: 'SKU_MAPPING_REQUIRED' },
  ]);
  assert.equal(reason, 'SKU_MAPPING_REQUIRED');
});

test('订单行匹配不到时退回同订单的事项', () => {
  const reason = reviewReasonFor(structuredRow({ orderLineId: '—' }), [
    { order_id: '31', order_line_id: null, reason_code: 'SKU_MAPPING_REQUIRED' },
  ]);
  assert.equal(reason, 'SKU_MAPPING_REQUIRED');
});

test('没有对应复核事项时为 null，不编造原因', () => {
  assert.equal(reviewReasonFor(structuredRow(), []), null);
  assert.equal(reviewReasonFor(structuredRow({ orderId: '—', orderLineId: '—' }),
    [{ order_id: '99', order_line_id: '9', reason_code: 'SKU_MAPPING_REQUIRED' }]), null);
});

test('原因码进 facts 供渲染层套中文', () => {
  const facts = presentSnapshotRowFacts(structuredRow(), order(), 'SKU_MAPPING_REQUIRED');
  assert.equal(facts.reviewReasonCode, 'SKU_MAPPING_REQUIRED');
});

test('snapshotOrderIdsToLoad：去重并剔除 — 占位（一单多行只拉一次）', () => {
  assert.deepEqual(
    snapshotOrderIdsToLoad([
      structuredRow(),
      structuredRow({ orderLineId: '7' }),
      structuredRow({ orderId: '32' }),
      structuredRow({ orderId: '—' }),
    ]),
    ['31', '32'],
  );
});
