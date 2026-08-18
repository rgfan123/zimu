import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canConfirmReferenceRow,
  canReceiveTracking,
  presentImportRow,
  presentTrackingBatchRow,
  summarizeImportBatch,
} from '../src/pages/fulfillment/fileOperations.ts';

test('import summary keeps accepted and review rows distinct', () => {
  assert.equal(
    summarizeImportBatch({ total: 6, accepted: 4, need_review: 2, rejected: 0 }),
    '共 6 行，已接收 4 行，待复核 2 行，拒绝 0 行',
  );
});

test('tracking can only be returned after the fulfillment file was downloaded', () => {
  assert.equal(canReceiveTracking('THIRD_PARTY', 'GENERATED_NOT_DOWNLOADED'), false);
  assert.equal(canReceiveTracking('THIRD_PARTY', 'DOWNLOADED_WAITING_RETURN'), true);
  assert.equal(canReceiveTracking('THIRD_PARTY', 'RETURN_OVERDUE'), true);
  assert.equal(canReceiveTracking('THIRD_PARTY', 'RETURNED'), false);
  assert.equal(canReceiveTracking('JD_OFFICIAL', 'DOWNLOADED_WAITING_RETURN'), false);
});

test('mapping confirmation requires an unambiguous provider code and positive multiplier', () => {
  assert.equal(canConfirmReferenceRow({ match_status: 'MATCHED', provider_sku_code: 'EMG1', quantity_multiplier: '2' }), true);
  assert.equal(canConfirmReferenceRow({ match_status: 'NEED_REVIEW', provider_sku_code: 'EMG1', quantity_multiplier: '2' }), false);
  assert.equal(canConfirmReferenceRow({ match_status: 'MATCHED', provider_sku_code: '', quantity_multiplier: '2' }), false);
  assert.equal(canConfirmReferenceRow({ match_status: 'MATCHED', provider_sku_code: 'EMG1', quantity_multiplier: '0' }), false);
});

test('import issue rows expose actionable source fields without dumping raw cells or internal details', () => {
  const issue = presentImportRow({
    id: 'row-17',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 18,
    raw_cells: {
      '商品编号': 'CX-SKU-001',
      '商品名称': '子牧羊小腿',
      '收货人姓名': '张三',
      '收货人手机号': '13800000000',
      '详细地址': '上海市浦东新区',
    },
    source_order_ref: 'CX-ORDER-001',
    status: 'NEED_REVIEW',
    error_code: 'SKU_MATCH',
    error_detail: {
      order_line_exception: 'SKU_MAPPING_REQUIRED',
      sql: 'select * from app.skus',
    },
  });

  assert.deepEqual(issue, {
    id: 'row-17',
    sheet: '待发货明细',
    row: 18,
    sourceOrderRef: 'CX-ORDER-001',
    sourceSkuRef: 'CX-SKU-001',
    sourceProductName: '子牧羊小腿',
    receiverName: '张三',
    receiverPhone: '13800000000',
    receiverAddress: '上海市浦东新区',
    productName: '子牧羊小腿',
    quantity: '—',
    specification: '—',
    fulfillmentType: null,
    reason: '来源商品尚未建立 SKU 映射',
    status: 'NEED_REVIEW',
    orderId: '—',
    orderLineId: '—',
  });
  // 解析投影（收货人/手机号/地址）为确认明细有意展示的白名单字段；内部字段与原始 error_detail 不得泄漏
  assert.doesNotMatch(JSON.stringify(issue), /select \*|sql/);
});

test('accepted import rows show their safe order persistence link', () => {
  const accepted = presentImportRow({
    id: 'row-19',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 20,
    raw_cells: {
      '商品编号': '2047705',
      '商品名称': '子牧牛腱子500g*2',
      '收货人手机号': '13800000000',
    },
    source_order_ref: 'CSX-ORDER-001',
    status: 'ACCEPTED',
    error_detail: {},
    order_id: '101',
    order_line_id: '201',
  });

  assert.equal(accepted.reason, '已写入系统订单');
  assert.equal(accepted.sourceOrderRef, 'CSX-ORDER-001');
  assert.equal(accepted.orderId, '101');
  assert.equal(accepted.orderLineId, '201');
  // 解析投影有意展示收货人手机号（确认明细核对字段），不再视为 PII 泄漏
  assert.equal(accepted.receiverPhone, '13800000000');
});

test('resolved SKU rows show the remaining customer review instead of a stale mapping error', () => {
  const issue = presentImportRow({
    id: 'row-18',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 19,
    raw_cells: { '商品编号': '2066622', '商品名称': '子牧雷山黑猪五花肉450g*2' },
    source_order_ref: 'CX-ORDER-002',
    status: 'NEED_REVIEW',
    error_code: 'CUSTOMER_MATCH',
    error_detail: { review_case_reason: 'CUSTOMER_MATCH_REQUIRED' },
  });

  assert.equal(issue.reason, '客户身份尚未建立明确映射');
});

test('tracking batch rows expose per-row result and failure reason without PII', () => {
  const failed = presentTrackingBatchRow({
    id: 'trk-1',
    sheet_name: '发货清单',
    sheet_index: 0,
    row_index: 2,
    raw_cells: {
      '结果': 'FAILED',
      '异常原因': '客户拒收',
      '收件人': '张三',
      '电话': '13800000000',
      '地址': '不应出现在逐行结果',
      '实际发货数量': '0',
    },
    source_order_ref: 'OUT-001',
    status: 'ACCEPTED',
  });

  assert.equal(failed.rowIndex, 2);
  assert.equal(failed.outboundOrderNo, 'OUT-001');
  assert.equal(failed.result, '失败');
  assert.equal(failed.failureReason, '客户拒收');
  assert.equal(failed.actualQuantity, '0');
  assert.doesNotMatch(JSON.stringify(failed), /张三|13800000000|不应出现/);

  const shipped = presentTrackingBatchRow({
    id: 'trk-2',
    sheet_name: '发货清单',
    sheet_index: 0,
    row_index: 3,
    raw_cells: { '结果': 'SHIPPED', '实际发货数量': '2', '快递公司': '顺丰', '物流单号': 'SF123', '异常原因': '' },
    source_order_ref: 'OUT-002',
    status: 'ACCEPTED',
  });
  assert.equal(shipped.result, '已发货');
  assert.equal(shipped.carrier, '顺丰');
  assert.equal(shipped.trackingNo, 'SF123');
  assert.equal(shipped.failureReason, '—');
});

test('tracking batch rows without raw result cell degrade to a placeholder', () => {
  const row = presentTrackingBatchRow({
    id: 'trk-3',
    sheet_name: '发货清单',
    sheet_index: 0,
    row_index: 4,
    raw_cells: {},
    source_order_ref: 'OUT-003',
    status: 'ACCEPTED',
  });
  assert.equal(row.result, '—');
  assert.equal(row.outboundOrderNo, 'OUT-003');
});

test('import rows show the source SKU fulfillment ownership (JD vs third-party)', () => {
  const jd = presentImportRow({
    id: 'row-31',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 32,
    raw_cells: { '商品编号': '2047705' },
    status: 'ACCEPTED',
    sku_fulfillment: { provider_type: 'JD_WAREHOUSE', provider_name: '京东云仓（华东）' },
    order_id: '101',
    order_line_id: '201',
  });
  assert.equal(jd.fulfillmentType, "JD_WAREHOUSE");

  const thirdParty = presentImportRow({
    id: 'row-32',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 33,
    raw_cells: { '商品编号': '2047706' },
    status: 'ACCEPTED',
    sku_fulfillment: { provider_type: 'THIRD_PARTY', provider_name: '顺达第三方履约' },
    order_id: '102',
    order_line_id: '202',
  });
  assert.equal(thirdParty.fulfillmentType, "THIRD_PARTY");

  const unmapped = presentImportRow({
    id: 'row-33',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 34,
    raw_cells: { '商品编号': '2047707' },
    status: 'NEED_REVIEW',
    error_code: 'SKU_MATCH',
    error_detail: { order_line_exception: 'SKU_MAPPING_REQUIRED' },
  });
  assert.equal(unmapped.fulfillmentType, null);
});

test('import row specification falls back to internal SKU default when source omitted it', () => {
  const row = presentImportRow({
    id: 'row-41',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 42,
    raw_cells: { '商品编号': '2047829', '商品名称': '子牧新西兰羔羊肉卷200g*4' },
    status: 'ACCEPTED',
    parsed: {
      product_name: '子牧新西兰羔羊肉卷200g*4',
      specification: '来源未提供',
      source_sku_ref: '2047829',
    },
    sku_fulfillment: { provider_type: 'JD_WAREHOUSE', provider_name: '京东云仓', sku_specification: '200g*4' },
    order_id: '101',
    order_line_id: '201',
  });
  assert.equal(row.specification, '200g*4');

  const withParsedSpec = presentImportRow({
    id: 'row-42',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 43,
    raw_cells: { '商品编号': '2047829', '商品名称': '子牧牛腱子500g*2' },
    status: 'ACCEPTED',
    parsed: { specification: '500g*2', source_sku_ref: '2047829' },
    sku_fulfillment: { provider_type: 'THIRD_PARTY', provider_name: '顺达', sku_specification: '500g*2' },
    order_id: '102',
    order_line_id: '202',
  });
  assert.equal(withParsedSpec.specification, '500g*2');

  const unmapped = presentImportRow({
    id: 'row-43',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 44,
    raw_cells: { '商品编号': '2047829' },
    status: 'NEED_REVIEW',
    error_code: 'SKU_MATCH',
    error_detail: { order_line_exception: 'SKU_MAPPING_REQUIRED' },
  });
  assert.equal(unmapped.specification, '—');
});
