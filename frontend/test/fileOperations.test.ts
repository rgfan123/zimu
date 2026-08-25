import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canConfirmReferenceRow,
  canReceiveTracking,
  presentImportRow,
  presentJdCargos,
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
      order_line_exceptions: ['SKU_MAPPING_REQUIRED'],
      sql: 'select * from app.skus',
    },
    jd_cargos: [],
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
    jdCargos: [],
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
    jd_cargos: [{
      product_name: '子牧牛腱子500g*2',
      provider_sku_code: 'JD-SKU-000001',
      plan_quantity: 6,
    }],
  });

  assert.equal(accepted.reason, '已写入系统订单');
  assert.equal(accepted.sourceOrderRef, 'CSX-ORDER-001');
  assert.equal(accepted.orderId, '101');
  assert.equal(accepted.orderLineId, '201');
  assert.deepEqual(accepted.jdCargos, [{
    productName: '子牧牛腱子500g*2',
    providerSkuCode: 'JD-SKU-000001',
    planQuantity: 6,
  }]);
  // 解析投影有意展示收货人手机号（确认明细核对字段），不再视为 PII 泄漏
  assert.equal(accepted.receiverPhone, '13800000000');
});

test('jd cargo presentation renders single, multi, and empty shipments distinctly', () => {
  // 单货品直接「N 件」；多货品必须带商品名逐行列出（\n 供前端 white-space: pre-line 换行）
  assert.equal(presentJdCargos([]), '—');
  assert.equal(presentJdCargos([{
    productName: '子牧牛腱子500g*2',
    providerSkuCode: 'JD-SKU-000001',
    planQuantity: 6,
  }]), '6 件');
  assert.equal(presentJdCargos([
    { productName: '礼包组件一', providerSkuCode: 'JD-SKU-000001', planQuantity: 2 },
    { productName: '礼包组件二', providerSkuCode: 'EMG-WANGQI-BUNDLE-002', planQuantity: 4 },
  ]), '礼包组件一: 2 件\n礼包组件二: 4 件');
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
    error_detail: { order_line_exceptions: ['SKU_MAPPING_REQUIRED'] },
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
    error_detail: { order_line_exceptions: ['SKU_MAPPING_REQUIRED'] },
  });
  assert.equal(unmapped.specification, '—');
});

test('rejection reason reads the plural order_line_exceptions array the backend actually writes', () => {
  // 后端 SourceImportService 写的是复数数组；旧代码读单数字符串键，该分支恒为空。
  const row = (exceptions: unknown, errorCode: string | null = null) => presentImportRow({
    id: 'row-51',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 52,
    raw_cells: { '商品编号': '2047705' },
    source_order_ref: 'WQ-ORDER-001',
    status: 'NEED_REVIEW',
    error_code: errorCode,
    error_detail: { order_line_exceptions: exceptions },
  });

  assert.equal(row(['SKU_MAPPING_CONFLICT']).reason, '来源商品对应多个 SKU，需要人工确认');
  // 一行拆多行时两个不同的异常码都要说出来，只取首个会瞒掉另一半
  assert.equal(
    row(['SKU_MAPPING_REQUIRED', 'SKU_MAPPING_CONFLICT']).reason,
    '来源商品尚未建立 SKU 映射；来源商品对应多个 SKU，需要人工确认',
  );
  // 同一文案去重，不重复刷屏
  assert.equal(
    row(['SKU_MAPPING_REQUIRED', 'SKU_MAPPING_REQUIRED']).reason,
    '来源商品尚未建立 SKU 映射',
  );
  // 行级异常码优先于 error_code：数组说得比映射后的粗粒度码更细
  assert.equal(
    row(['SKU_MAPPING_CONFLICT'], 'JD_CODE_CONFLICT').reason,
    '来源商品对应多个 SKU，需要人工确认',
  );
});

test('unregistered line exception codes fail closed instead of leaking backend text', () => {
  const leaked = presentImportRow({
    id: 'row-52',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 53,
    raw_cells: { '商品编号': '2047705' },
    status: 'NEED_REVIEW',
    error_code: 'SKU_MATCH',
    error_detail: {
      order_line_exceptions: ['BRAND_NEW_BACKEND_CODE', { code: 'not-a-string' }],
      message: 'org.postgresql.util.PSQLException: relation app.skus',
    },
  });
  // 未登记码整条丢弃后退回 error_code 文案，绝不把英文码或后端自由文本透给 operator
  assert.equal(leaked.reason, '来源商品尚未建立 SKU 映射');
  assert.doesNotMatch(JSON.stringify(leaked), /BRAND_NEW_BACKEND_CODE|PSQLException/);

  // 后端解析期拒绝行一律写 NEED_REVIEW —— 全仓没有任何代码把 raw_import_rows.status
  // 写成 REJECTED（只在 SourceImportService:636 的读侧过滤白名单里出现），
  // 所以线上真正会被 operator 看到的兜底句是「该行需要人工复核」。
  const noneKnown = presentImportRow({
    id: 'row-53',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 54,
    raw_cells: { '商品编号': '2047705' },
    status: 'NEED_REVIEW',
    error_detail: { order_line_exceptions: ['BRAND_NEW_BACKEND_CODE'] },
  });
  assert.equal(noneKnown.reason, '该行需要人工复核');
});

test('malformed error_detail shapes degrade to the error_code reason', () => {
  const shape = (detail: Record<string, unknown>) => presentImportRow({
    id: 'row-54',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 55,
    raw_cells: { '商品编号': '2047705' },
    status: 'NEED_REVIEW',
    error_code: 'SKU_MATCH',
    error_detail: detail,
  }).reason;

  assert.equal(shape({ order_line_exceptions: 'SKU_MAPPING_CONFLICT' }), '来源商品尚未建立 SKU 映射');
  assert.equal(shape({ order_line_exceptions: null }), '来源商品尚未建立 SKU 映射');
  assert.equal(shape({ order_line_exceptions: [] }), '来源商品尚未建立 SKU 映射');
  assert.equal(shape({}), '来源商品尚未建立 SKU 映射');
});

test('every source-file rejection code names its own blocker instead of the generic fallback', () => {
  // SourceFileParser 逐行拒绝码全集 + SourceImportService.markReview / Connector reviewRequired；
  // 任何一条落到兜底句 = operator 分不清退款单、售后单还是行号重复。
  const expected: Array<[string, string]> = [
    ['IMPORT_VALIDATION', '来源文件的必填值或同单收货信息需要核对'],
    ['QUANTITY_SCALE', '商品数量最多支持三位小数'],
    ['SOURCE_LINE_REF_REQUIRED', '来源行缺少子订单 ID，无法定位到唯一来源行'],
    ['SOURCE_ORDER_TYPE_BLOCKED', '来源行不是可发货的实体销售订单'],
    ['SOURCE_ORDER_STATUS_BLOCKED', '来源子订单状态不是明确的待发货状态'],
    ['SOURCE_ORDER_ALREADY_FULFILLED', '来源行已有发货、收货或物流事实，不再重复发货'],
    ['SOURCE_ORDER_REFUND_BLOCKED', '来源行存在退款事实，已停止发货'],
    ['SOURCE_ORDER_AFTER_SALES_BLOCKED', '来源行存在售后事实，已停止发货'],
    ['SOURCE_LINE_REF_DUPLICATE', '同一来源订单内子订单 ID 重复，需在来源文件内去重'],
    ['JUFUBAO_RECEIVER_REQUIRED', '来源订单缺少完整收货信息'],
    ['JUFUBAO_QUANTITY_INVALID', '来源订单商品数量缺失或不是正整数'],
    ['JUFUBAO_CREATED_TIME_REQUIRED', '来源订单缺少有效的创建时间'],
    ['SOURCE_SKU_MAPPING_REQUIRED', '来源商品尚未建立 SKU 映射'],
    ['PROVIDER_SKU_MAPPING_REQUIRED', '内部 SKU 尚未建立履约方商品编码映射'],
    ['MAPPING_MULTIPLIER', '来源 SKU 映射缺少有效的数量换算倍数'],
  ];

  for (const [code, reason] of expected) {
    const row = presentImportRow({
      id: `row-${code}`,
      sheet_name: '待发货明细',
      sheet_index: 0,
      row_index: 60,
      raw_cells: { '商品编号': '2047705' },
      source_order_ref: 'WQ-ORDER-002',
      status: 'NEED_REVIEW',
      error_code: code,
      error_detail: { message: '万齐来源行存在退款事实' },
    });
    assert.equal(row.reason, reason, `${code} 应有专属文案，而非通用兜底句`);
  }
});

test('the safe-message allowlist still wins over the code map', () => {
  const row = presentImportRow({
    id: 'row-61',
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 62,
    raw_cells: {},
    status: 'NEED_REVIEW',
    error_code: 'IMPORT_VALIDATION',
    error_detail: { message: '数量必须大于 0' },
  });
  assert.equal(row.reason, '数量必须大于 0');
});
