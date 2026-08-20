import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/orders');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function orderSummary(id: string, orderNo: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    order_no: orderNo,
    source_channel: 'WECOM',
    customer_name: '测试客户',
    receiver_name: '张三',
    order_status: 'FULFILLING',
    processing_stage: 'WAITING_PROVIDER',
    processing_health: 'BLUE',
    completed_count: 0,
    total_count: 1,
    created_at: '2026-08-14T00:00:00Z',
    updated_at: '2026-08-14T02:00:00Z',
    version: 0,
    ...overrides,
  };
}

function ordersPage(items: unknown[], total = items.length) {
  return { items, page: 0, size: 20, total_elements: total, total_pages: Math.ceil(total / 20) };
}

/** 订单列表 mock：fulfillment-providers 固定返回空目录，订单请求记录 URL。 */
function ordersFetch(requests: string[], items: unknown[] = [], total?: number) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    requests.push(url);
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    if (url.startsWith('/api/v1/orders?')) return jsonResponse(ordersPage(items, total));
    throw new Error(`unexpected request: ${url}`);
  };
}

test('全部订单 route renders the PageShell page header with the order list and totals', async () => {
  const requests: string[] = [];
  globalThis.fetch = ordersFetch(requests, [
    orderSummary('101', 'ORD-101'),
    orderSummary('102', 'ORD-102'),
  ], 45);

  await harness.mount(['/orders']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
  // 侧边栏菜单 + PageShell 页头各渲染一次「全部订单」
  assert.ok((harness.bodyText().match(/全部订单/g) ?? []).length >= 2, 'page header must render the nav title');
  assert.match(harness.bodyText(), /ORD-102/);
  assert.match(harness.bodyText(), /共 45 条/);
  assert.ok(requests.some((url) => url.startsWith('/api/v1/orders?')), 'order list must hit the orders API');
});

test('待处理 route defaults to the NEED_REVIEW processing stage and keeps its tip', async () => {
  const requests: string[] = [];
  globalThis.fetch = ordersFetch(requests, []);

  await harness.mount(['/orders/pending']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待处理/));
  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('processing_stage=NEED_REVIEW')),
    'pending orders must default to the NEED_REVIEW stage',
  ));
  assert.match(harness.bodyText(), /默认按处理阶段「待复核」筛选/);
});

test('异常订单 route defaults to the FULFILLMENT_EXCEPTION status', async () => {
  const requests: string[] = [];
  globalThis.fetch = ordersFetch(requests, []);

  await harness.mount(['/orders/exceptions']);

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('order_status=FULFILLMENT_EXCEPTION')),
    'exception orders must default to the FULFILLMENT_EXCEPTION status',
  ));
  assert.match(harness.bodyText(), /异常订单/);
});

test('订单追踪 route defaults to the SHIPPED status', async () => {
  const requests: string[] = [];
  globalThis.fetch = ordersFetch(requests, []);

  await harness.mount(['/orders/tracking']);

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('order_status=SHIPPED')),
    'tracking orders must default to the SHIPPED status',
  ));
  assert.match(harness.bodyText(), /订单追踪/);
});

test('order list failure surfaces the DataTable error state with a working retry', async () => {
  let calls = 0;
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    calls += 1;
    if (calls === 1) return jsonResponse({ message: 'raw order list failure' }, 500);
    return jsonResponse(ordersPage([orderSummary('101', 'ORD-101')]));
  };

  await harness.mount(['/orders']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /订单列表加载失败/));
  assert.doesNotMatch(harness.bodyText(), /raw order list failure/);

  const retry = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('重试'));
  assert.ok(retry, 'the list error state must expose a retry action');
  await harness.dispatchEvent(retry, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
});

test('empty order list falls back to the DataTable default empty state', async () => {
  globalThis.fetch = ordersFetch([], []);

  await harness.mount(['/orders']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无数据/));
});

test('order rows keep the detail drill-down links for the order preset routes', async () => {
  globalThis.fetch = ordersFetch([], [orderSummary('101', 'ORD-101')]);

  await harness.mount(['/orders']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('详情'));
  assert.ok(detailLink, 'the order row must expose a detail action');
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/orders/101'));
  assert.match(detailLink.textContent ?? '', /详情/);
});
