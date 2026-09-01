/**
 * V100 手工建单页（/orders/manual-create）的最小路由测试（参照 rawMaterialInventoryRoute 模式）：
 * - 表单渲染：客户检索、收货三要素、商品行、两步提交按钮；
 * - 一个按钮两步走的成功态：POST /orders/manual → POST /orders/{id}/fulfillment-routing，
 *   成功后呈现订单号 + 发货单数 + 「前往发货单提交京东出库」；
 * - ①成功②失败：路由失败必须原样呈现后端 message，订单号仍在，且可用最新版本重试路由。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  isShellBaselineRequest,
  jsonResponse,
  page,
  shellBaselineResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/orders/manual-create');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const customerFixture = {
  id: '11',
  code: 'C001',
  name: '张三公司',
  active: true,
  version: 0,
  attributes: { contact_name: '张三', contact_phone: '13900000000' },
};

const skuFixture = {
  id: '15',
  code: 'SKU-15',
  name: '雷山鸡',
  active: true,
  version: 0,
  attributes: { specification: '500g', unit: '袋' },
};

/** 建单 201 返回的 OrderDetail 子集：页面只消费 id / order_no / source_ref / version。 */
const createdOrderFixture = {
  id: '901',
  order_no: 'SO-M-20260831-01',
  source_channel: 'MANUAL',
  source_ref: 'MAN-20260831-9F',
  order_status: 'SKU_MAPPED',
  version: 2,
};

interface RecordedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: unknown;
}

/**
 * 固定夹具的 fetch 桩：读客户/SKU 主数据 + 两个写端点由各用例注入应答；
 * 跳转发货记录后的三条挂载请求给空数据（本文件不测那页的内容）。
 */
function manualCreateFetch(
  requests: RecordedRequest[],
  writes: {
    create?: () => Response;
    routing?: () => Response;
    orderDetail?: () => Response;
  } = {},
) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push({
      url,
      method: init?.method ?? 'GET',
      headers: (init?.headers ?? {}) as Record<string, string>,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : null,
    });
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    if (url.startsWith('/api/v1/customers')) return jsonResponse(page([customerFixture]));
    if (url.startsWith('/api/v1/skus')) return jsonResponse(page([skuFixture]));
    if (url === '/api/v1/orders/manual') {
      if (!writes.create) throw new Error('unexpected manual create');
      return writes.create();
    }
    if (url === `/api/v1/orders/${createdOrderFixture.id}/fulfillment-routing`) {
      if (!writes.routing) throw new Error('unexpected fulfillment routing');
      return writes.routing();
    }
    if (url === `/api/v1/orders/${createdOrderFixture.id}`) {
      if (!writes.orderDetail) throw new Error('unexpected order detail read');
      return writes.orderDetail();
    }
    // 成功后「前往发货单提交京东出库」跳发货记录页的挂载请求。
    if (url.startsWith('/api/v1/shipments/jd-receiver-address-candidates')) return jsonResponse([]);
    if (url.startsWith('/api/v1/shipments?')) return jsonResponse(page([]));
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    throw new Error(`unexpected request ${url}`);
  };
}

function nativeInputValueSetter(element: HTMLElement): (value: string) => void {
  const proto = element instanceof HTMLTextAreaElement
    ? HTMLTextAreaElement.prototype
    : HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
  assert.ok(setter, 'missing native value setter');
  return (value: string) => setter.call(element, value);
}

/** 按 placeholder 扫描取控件：jsdom 的 CSS 引擎解析含空格/加号的属性选择器不可靠。 */
function byPlaceholder(placeholder: string): HTMLInputElement | HTMLTextAreaElement {
  const element = [...document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input, textarea')]
    .find((candidate) => candidate.getAttribute('placeholder') === placeholder);
  assert.ok(element, `missing input with placeholder ${placeholder}`);
  return element;
}

async function fillInput(placeholder: string, value: string) {
  const element = byPlaceholder(placeholder);
  nativeInputValueSetter(element)(value);
  await harness.dispatchEvent(element, new Event('input', { bubbles: true }));
}

/** 打开一个 antd Select 并点选包含 optionText 的选项（既有 route 测试的驱动模式）。 */
async function pickSelectOption(selectorText: string, optionText: string, searchText?: string) {
  const selector = [...document.querySelectorAll<HTMLElement>('.ant-select-selector')]
    .find((candidate) => candidate.textContent?.includes(selectorText));
  assert.ok(selector, `missing select ${selectorText}`);
  await harness.dispatchEvent(selector, new MouseEvent('mousedown', { bubbles: true }));
  if (searchText !== undefined) {
    const searchInput = selector.querySelector<HTMLInputElement>('.ant-select-selection-search-input');
    assert.ok(searchInput, `missing search input for ${selectorText}`);
    nativeInputValueSetter(searchInput)(searchText);
    await harness.dispatchEvent(searchInput, new Event('input', { bubbles: true }));
  }
  await harness.waitFor(() => {
    assert.ok(
      [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
        .some((candidate) => candidate.textContent?.includes(optionText)),
      `option ${optionText} must appear`,
    );
  });
  const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((candidate) => candidate.textContent?.includes(optionText));
  assert.ok(option);
  await harness.dispatchEvent(option, new MouseEvent('click', { bubbles: true }));
}

/** 填完整张表单：选客户（触发联系人预填）→ 补地址 → 选 SKU → 填数量。 */
async function fillManualOrderForm() {
  await harness.waitFor(() => assert.match(harness.bodyText(), /建单并生成发货单/));
  await pickSelectOption('搜索客户', 'C001 · 张三公司');

  // 客户档案联系人/电话预填空着的收货字段（可改）。
  await harness.waitFor(() => {
    assert.equal(byPlaceholder('李四').value, '张三');
    assert.equal(byPlaceholder('139...').value, '13900000000');
  });

  await fillInput('省市区 + 详细地址', '贵州省贵阳市观山湖区 长岭北路 1 号');
  await pickSelectOption('搜索系统 SKU', 'SKU-15 · 雷山鸡 · 500g', '雷山');
  await fillInput('数量（正整数）', '3');
}

test('手工建单页渲染两步表单：客户检索、收货三要素、商品行、提交按钮', async () => {
  globalThis.fetch = manualCreateFetch([]);
  await harness.mount(['/orders/manual-create']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /手工建单/));
  const body = harness.bodyText();
  assert.match(body, /客户编码/);
  assert.match(body, /收货人姓名/);
  assert.match(body, /收货电话/);
  assert.match(body, /收货地址/);
  assert.match(body, /商品行/);
  assert.match(body, /添加商品行/);
  assert.ok(control('建单并生成发货单'), '提交按钮必须存在');
  assert.match(body, /人工提交/, '页面必须说明京东出库仍走人工提交闸门');
  // 「订单与发货」板块的可见入口（当前路由就在组内，组必然展开）。
  assert.ok(
    document.querySelector('.zs-nav a[href="/orders/manual-create"]'),
    '手工建单必须出现在侧边栏',
  );
});

test('两步成功：建单 → 自动路由 → 呈现订单号与发货单数，跳发货记录提交京东出库', async () => {
  const requests: RecordedRequest[] = [];
  globalThis.fetch = manualCreateFetch(requests, {
    create: () => jsonResponse(createdOrderFixture, 201),
    routing: () => jsonResponse({
      order_id: '901',
      order_version: 3,
      shipment_ids: ['501', '502'],
    }, 201),
  });
  await harness.mount(['/orders/manual-create']);
  await fillManualOrderForm();

  await harness.dispatchEvent(control('建单并生成发货单'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /SO-M-20260831-01 已建单，生成 2 张发货单/));
  const body = harness.bodyText();
  assert.match(body, /MAN-20260831-9F/, '来源单号（MAN-）必须可见');
  assert.match(body, /前往发货单提交京东出库/);

  // 两个写请求按序发出，载荷与契约一致（quantity 是 JSON 正整数）。
  const writes = requests.filter((request) => request.method === 'POST');
  assert.deepEqual(writes.map(({ url }) => url), [
    '/api/v1/orders/manual',
    '/api/v1/orders/901/fulfillment-routing',
  ]);
  assert.deepEqual(writes[0].body, {
    customer_code: 'C001',
    receiver: { name: '张三', phone: '13900000000', address: '贵州省贵阳市观山湖区 长岭北路 1 号' },
    items: [{ sku_id: '15', quantity: 3 }],
  });
  assert.match(writes[0].headers['Idempotency-Key'] ?? '', /^manual-order-/, '幂等键由浏览器按草稿生成');
  assert.deepEqual(writes[1].body, { expected_order_version: 2 });
  assert.match(writes[1].headers['Idempotency-Key'] ?? '', /^manual-order-routing-901-v2$/);

  // 指路按钮跳发货记录列表（列表无订单定位参数，单号以消息钉住）。
  await harness.dispatchEvent(control('前往发货单提交京东出库'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.location(), /\/fulfillment\/shipments/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /发货记录/));
});

test('①成功②失败：路由失败原样呈现后端 message，订单不丢，可按最新版本重试', async () => {
  let routingCalls = 0;
  const requests: RecordedRequest[] = [];
  globalThis.fetch = manualCreateFetch(requests, {
    create: () => jsonResponse(createdOrderFixture, 201),
    routing: () => {
      routingCalls += 1;
      return routingCalls === 1
        ? apiErrorResponse(409, 'ORDER_ROUTING_REVIEW_OPEN', '订单仍有开放复核事项')
        : jsonResponse({ order_id: '901', order_version: 6, shipment_ids: ['503'] }, 201);
    },
    orderDetail: () => jsonResponse({ ...createdOrderFixture, version: 5 }),
  });
  await harness.mount(['/orders/manual-create']);
  await fillManualOrderForm();

  await harness.dispatchEvent(control('建单并生成发货单'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /SO-M-20260831-01 已创建，但发货单尚未生成/));
  const body = harness.bodyText();
  assert.match(body, /订单仍有开放复核事项/, '路由失败必须原样呈现后端 message');
  assert.ok(control('重试路由'));
  assert.ok(
    [...document.querySelectorAll<HTMLAnchorElement>('a')]
      .some((link) => link.getAttribute('href') === '/orders/901'),
    '必须提供订单详情直达链接',
  );

  // 重试：先重读订单拿当前版本（5），再按新版本换幂等键路由成功。
  await harness.dispatchEvent(control('重试路由'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /SO-M-20260831-01 已建单，生成 1 张发货单/));
  const retryRouting = requests.filter(
    (request) => request.method === 'POST' && request.url.endsWith('/fulfillment-routing'),
  ).at(-1);
  assert.deepEqual(retryRouting?.body, { expected_order_version: 5 });
  assert.match(retryRouting?.headers['Idempotency-Key'] ?? '', /^manual-order-routing-901-v5$/);
});
