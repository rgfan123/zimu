import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  control,
  createRouteHarness,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';

/**
 * Issue #72：五类复核事项在抽屉里展示「做这个决定所需要的事实」。
 * 逐家族 route 级测试：关键事实可见、PII/未知键不出现、缺字段显示占位。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/reviews');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

/** 队列 + 抽屉动作所需的全部 mock；decoy 字段验证 fail-closed。 */
function fetchWithCase(reasonCode: string, detail: Record<string, unknown>) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.startsWith('/api/v1/review-cases?')) {
      return jsonResponse(page([reviewCaseFixture('1', {
        reasonCode,
        team: reasonCode === 'CUSTOMER_MATCH_REQUIRED' ? 'CUSTOMER_OPS' : 'ORDER_OPS',
      })].map((item) => ({ ...item, detail }))));
    }
    if (url.startsWith('/api/v1/operational-alerts?')) return jsonResponse(page([]));
    if (url.startsWith('/api/v1/customers?')) return jsonResponse(page([]));
    if (url.startsWith('/api/v1/skus?')) return jsonResponse(page([]));
    throw new Error(`unexpected request: ${url}`);
  };
}

async function openDrawer(reasonCode: string, detail: Record<string, unknown>) {
  globalThis.fetch = fetchWithCase(reasonCode, detail);
  await harness.mount(['/workbench/reviews']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  await control('查看处理').click();
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-FIXTURE-1/));
}

test('CUSTOMER_MATCH_REQUIRED：来源客户/收货可展示部分/候选客户档案可见，PII 不出现', async () => {
  await openDrawer('CUSTOMER_MATCH_REQUIRED', {
    source_channel: 'WECOM',
    customer_name: '彩食鲜北京一分店',
    source_customer_ref: 'CSX-CUST-0001',
    receiver_name: '张三',
    receiver_address: '上海市浦东新区测试路 1 号',
    receiver_phone: '13800000000',
    raw_payload: { sql: 'select *' },
    customer_candidates: [
      { customer_code: 'CUST-WECOM-0001', customer_name: '子牧测试客户', profile: { phone: '13900000000' } },
    ],
  });
  const text = harness.bodyText();
  assert.match(text, /来源客户名称原文/);
  assert.match(text, /彩食鲜北京一分店/);
  assert.match(text, /来源客户编号/);
  assert.match(text, /CSX-CUST-0001/);
  assert.match(text, /收货人/);
  assert.match(text, /张三/);
  assert.match(text, /收货地址/);
  assert.match(text, /上海市浦东新区测试路 1 号/);
  assert.match(text, /候选客户/);
  assert.match(text, /CUST-WECOM-0001 · 子牧测试客户/);
  assert.doesNotMatch(text, /13800000000|13900000000|raw_payload|select/);
});

test('CARRIER_MAPPING：运单号/前缀/来源物流公司/候选承运商可见，未知键不渲染', async () => {
  await openDrawer('CARRIER_MAPPING', {
    tracking_number: 'SF1390123456789',
    tracking_prefix: 'SF',
    source_logistics_company: '顺丰速运',
    carrier_candidates: [{ carrier_code: 'SF', carrier_name: '顺丰速运' }],
    receiver_phone: '13800000000',
    internal_payload: { token: 'do-not-render' },
  });
  const text = harness.bodyText();
  assert.match(text, /运单号原文/);
  assert.match(text, /SF1390123456789/);
  assert.match(text, /识别前缀/);
  assert.match(text, /SF/);
  assert.match(text, /来源物流公司/);
  assert.match(text, /顺丰速运/);
  assert.match(text, /候选标准承运商/);
  assert.doesNotMatch(text, /13800000000|internal_payload|token|do-not-render/);
});

test('QUANTITY_SCALE：来源数量/单位/乘数/换算结果/拒绝原因可见，PII 不出现', async () => {
  await openDrawer('QUANTITY_SCALE', {
    source_quantity: '1.500',
    source_unit: '盒',
    quantity_multiplier: '2.000',
    converted_quantity: '3.000',
    reject_reason: '换算后必须为正整数',
    provider_code: 'JD',
    receiver_phone: '13800000000',
    evidence_raw: [{ sql: 'select *' }],
  });
  const text = harness.bodyText();
  assert.match(text, /来源数量原文/);
  assert.match(text, /1\.500/);
  assert.match(text, /来源单位/);
  assert.match(text, /盒/);
  assert.match(text, /当前乘数/);
  assert.match(text, /2\.000/);
  assert.match(text, /换算后结果/);
  assert.match(text, /3\.000/);
  assert.match(text, /拒绝原因/);
  assert.match(text, /换算后必须为正整数/);
  assert.match(text, /履约方/);
  assert.match(text, /JD/);
  assert.doesNotMatch(text, /13800000000|evidence_raw|select/);
});

test('MAPPING_MULTIPLIER：与 QUANTITY_SCALE 同一组数量换算事实', async () => {
  await openDrawer('MAPPING_MULTIPLIER', {
    source_quantity: '2',
    source_unit: '箱',
    quantity_multiplier: '1.500',
    converted_quantity: '3.000',
    reject_reason: '乘数快照缺失，需人工确认',
    receiver_phone: '13800000000',
  });
  const text = harness.bodyText();
  assert.match(text, /来源数量原文/);
  assert.match(text, /2/);
  assert.match(text, /箱/);
  assert.match(text, /1\.500/);
  assert.match(text, /3\.000/);
  assert.match(text, /乘数快照缺失/);
  assert.doesNotMatch(text, /13800000000/);
});

test('IMPORT_DATA：问题单元格/列名/sheet/行号/拒绝原因可见，其他单元格与 PII 不出现', async () => {
  await openDrawer('IMPORT_DATA', {
    source_sheet_name: 'Sheet1',
    source_row_index: 7,
    column_name: '下单数量',
    cell_value: '1.5.3',
    reject_reason: '数量最多三位小数',
    receiver_phone: '13800000000',
    all_cells: [{ column: '收货人手机', value: '13800000000' }],
  });
  const text = harness.bodyText();
  assert.match(text, /来源工作表/);
  assert.match(text, /Sheet1/);
  assert.match(text, /来源行号/);
  assert.match(text, /7/);
  assert.match(text, /列名/);
  assert.match(text, /下单数量/);
  assert.match(text, /原始单元格值/);
  assert.match(text, /1\.5\.3/);
  assert.match(text, /数量最多三位小数/);
  assert.doesNotMatch(text, /13800000000|all_cells|收货人手机/);
});

test('REVISION_AFTER_EXPORT：改动明细改前/改后与导出版本可见，电话改动项不出现', async () => {
  await openDrawer('REVISION_AFTER_EXPORT', {
    changes: [
      { field: 'quantity', line_no: 1, before: '2.000', after: '3.000' },
      { field: 'receiver_name', line_no: null, before: '张三', after: '李四' },
      { field: 'receiver_phone', line_no: null, before: '13800000000', after: '13900000000' },
      { field: 'internal_secret', line_no: null, before: 'x', after: 'y' },
    ],
    export_batch_no: 'EXP-20260820-001',
    template_version: 'v1-24-columns',
    source_version: 'revision-2',
    change_reason: '客户修改数量',
    receiver_phone: '13800000000',
  });
  const text = harness.bodyText();
  assert.match(text, /改动字段/);
  assert.match(text, /数量（第 1 行）：改前 2\.000 → 改后 3\.000/);
  assert.match(text, /收货人：改前 张三 → 改后 李四/);
  assert.match(text, /已导出文件批次/);
  assert.match(text, /EXP-20260820-001/);
  assert.match(text, /导出模板版本/);
  assert.match(text, /v1-24-columns/);
  assert.match(text, /来源版本/);
  assert.match(text, /revision-2/);
  assert.match(text, /客户修改数量/);
  assert.doesNotMatch(text, /13800000000|13900000000|internal_secret|receiver_phone/);
});

test('企微运单文件失败在复核抽屉显示稳定可读原因', async () => {
  await openDrawer('WECOM_TRACKING_FILE_REVIEW', {
    source: 'WECOM_TRACKING_FILE',
    error_code: 'WECOM_TRACKING_FILE_INVALID',
    message: '回传文件格式或内容不符合精确 24 列模板，请下载原件核对',
    source_url: 'https://temporary.example/secret',
    aeskey: 'must-not-render',
  });

  const text = harness.bodyText();
  assert.match(text, /企微运单文件/);
  assert.match(text, /WECOM_TRACKING_FILE_INVALID/);
  assert.match(text, /精确 24 列模板/);
  assert.doesNotMatch(text, /temporary\.example|must-not-render|aeskey|source_url/);
});

test('五家族缺字段时显示「来源未提供」占位，不整行消失', async () => {
  // CUSTOMER_MATCH_REQUIRED 后端只写了渠道：其余字段必须全部以占位呈现。
  await openDrawer('CUSTOMER_MATCH_REQUIRED', { source_channel: 'WECOM' });
  const text = harness.bodyText();
  for (const label of ['来源客户名称原文', '来源客户编号', '收货人', '收货地址', '候选客户']) {
    assert.match(text, new RegExp(label));
  }
  assert.ok((text.match(/来源未提供/g) ?? []).length >= 5, '每个缺字段显示占位而不是消失');
});

test('已解决事项的解决记录无白名单字段时显示提示而不是空表格', async () => {
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.startsWith('/api/v1/review-cases?')) {
      const base = reviewCaseFixture('1', { reasonCode: 'FULFILLMENT_EXCEPTION', status: 'RESOLVED' });
      return jsonResponse(page([{ ...base, resolution: { internal_note: 'do-not-render' } }]));
    }
    if (url.startsWith('/api/v1/operational-alerts?')) return jsonResponse(page([]));
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  await control('查看处理').click();
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-FIXTURE-1/));
  const text = harness.bodyText();
  assert.match(text, /解决记录没有可公开展示的补充字段/);
  assert.doesNotMatch(text, /internal_note|do-not-render/);
});
