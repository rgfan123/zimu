import assert from 'node:assert/strict';
import test, { after, afterEach, before } from 'node:test';
import { control, createRouteHarness, jsonResponse, page, type RouteHarness } from './routeHarness.ts';

/**
 * Issue #110 采购工作台（ADR 0005/0010，spec #120）：
 * 指标与工单表用真数（procurement-tickets size=1 计数 + list）；建议区在后端数据层
 * 落地前保留位置并如实说明；建议永不创建工单。SKU_OPS 岗位默认落地本页。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/procurement');
});

after(async () => {
  await harness.close();
});

afterEach(async () => {
  await harness.unmount();
});

function ticketFixture(id: string, overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id,
    ticket_no: `PT-2026-0823-0${id}`,
    fulfillment_id: '900',
    status: 'PENDING',
    requested_quantity: '640',
    fulfilled_quantity: '0',
    remaining_quantity: '640',
    items: [{ id: `${id}-1`, sku_id: 'SKU-JD-000073', requested_quantity: '640', fulfilled_quantity: '0', remaining_quantity: '640' }],
    receipts: [],
    version: 0,
    created_at: '2026-08-23T01:30:00Z',
    ...overrides,
  };
}

function stubProcurement() {
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/procurement-tickets')) {
      const params = new URLSearchParams(url.split('?')[1] ?? '');
      if (params.get('size') === '1') {
        if (params.get('status') === 'PENDING') return jsonResponse({ ...page([]), total_elements: 7 });
        if (params.get('status') === 'PARTIAL') return jsonResponse({ ...page([]), total_elements: 2 });
        return jsonResponse({ ...page([]), total_elements: 1 });
      }
      return jsonResponse({
        ...page([
          ticketFixture('1'),
          ticketFixture('2', { status: 'PARTIAL', fulfilled_quantity: '400', remaining_quantity: '240' }),
        ]),
        total_elements: 9,
      });
    }
    return jsonResponse(page([]));
  };
}

test('采购台骨架：四指标真数 + 建议区诚实态 + 工单表真数', async () => {
  window.localStorage.clear();
  stubProcurement();
  await harness.mount(['/workbench/procurement']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /PT-2026-0823-01/, '工单表必须渲染真实工单号'));
  const body = harness.bodyText();

  for (const label of ['待我确认建议', '待处理工单', '部分到货', '今日新增工单']) {
    assert.match(body, new RegExp(label), `指标「${label}」必须在位`);
  }
  assert.match(body, /7/, 'PENDING 计数');

  // 建议区接已注册的比价 Agent（只读运行，复用 agent console 运行时）
  assert.match(body, /为缺货工单比价/, '比价入口必须在位');
  assert.match(body, /procurement-price-agent/, 'Agent slug 留痕可见');
  assert.match(body, /点上方按钮为缺货工单跑一次比价/, '未运行时如实说明，不伪造建议');
  assert.match(body, /不创建工单、不改任何价格/, 'ADR 0010 语义必须写明');
  assert.doesNotMatch(body, /创建询价工单|创建采购工单/, '建议区不得出现创建工单动作（ADR 0010）');

  assert.match(body, /640/, '请求数量原样展示（十进制字符串不做运算）');
  assert.match(body, /PARTIAL/, '状态标签在位');

  const ticketsLink = [...document.querySelectorAll<HTMLAnchorElement>('main a')].find(
    (a) => a.getAttribute('href') === '/procurement/tickets',
  );
  assert.ok(ticketsLink, '采购协同入口必须存在');
});

test('工单为空时给出「缺货驱动」的诚实空态', async () => {
  window.localStorage.clear();
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/procurement-tickets')) {
      return jsonResponse({ ...page([]), total_elements: 0 });
    }
    return jsonResponse(page([]));
  };
  await harness.mount(['/workbench/procurement']);

  await harness.waitFor(() =>
    assert.match(harness.bodyText(), /工单由履约缺货自动创建，不由建议或预算创建/, '空态说明工单来源（ADR 0010）'));
});

test('商品运营岗位默认落地采购工作台', async () => {
  window.localStorage.clear();
  stubProcurement();
  await harness.mount(['/workbench/reviews']);

  await harness.dispatchEvent(control('请选择岗位'), new window.MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => control('商品运营'));
  await harness.dispatchEvent(control('商品运营'), new window.MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/procurement'));
  window.localStorage.clear();
});
