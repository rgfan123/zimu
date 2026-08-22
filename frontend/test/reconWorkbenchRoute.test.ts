/**
 * /workbench/recon（Issue #111）的用户可观察契约：
 * - 复用 /fulfillment/outbound-recon 的三种 query_type/query_value URL 契约与全部七态；
 * - 页面显式展示「金额对账未纳入本期」「当前为数量口径」与「¥ ——」金额占位；
 * - 旧 /fulfillment/outbound-recon 继续可用，且不注入工作台专用的金额口径横幅；
 * - 工作台入口在 internal.summary.order_id trim 后符合 Identifier 时把「系统内部事实」整卡
 *   变成真实 /orders/:id?return_to= 锚点；0 / 空白 / 路径或查询注入不渲染假链接；旧路由不启用；
 * - 可证 order_id 整卡进入订单详情后，「返回出库对账」必须回到原查询；非法/缺失 return_to
 *   保持 history 返回，不得 open redirect。
 *
 * 七态的逐字段差异状态（MATCH / MISMATCH / INTERNAL_ONLY / JD_ONLY / EMPTY /
 * JD_UNAVAILABLE / JD_NOT_FOUND）由纯函数 rowStatePresentation 定映射（outboundRecon.test.ts
 * 固定），本文件走页面渲染：同一成功视图中断言七种标记各自可见，不重复纯函数映射。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import type { OutboundReconQueryType, OutboundReconView } from '../src/api/types.ts';
import { internalOrderId } from '../src/pages/fulfillment/outboundRecon.ts';
import { safeOrderReturnLocation } from '../src/pages/orders/orderReturnLocation.ts';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/recon');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

/** 出库对账视图夹具：默认一笔 OK 成功视图，comparisons 覆盖全部七态，字段按需覆盖。 */
function reconView(overrides: Partial<OutboundReconView> = {}): OutboundReconView {
  return {
    query: { type: 'OUTBOUND_ORDER_NO', value: '202608130001' },
    audit: { request_id: 'req-1', operator: 'ops' },
    internal: {
      summary: { outbound_order_no: '202608130001', order_no: 'SO-20260813', shipment_status: 'SHIPPED' },
      items: [],
      tracking: null,
    },
    jd: { status: 'OK', business_code: null, message: null, client_mode: 'MOCK', summary: null, items: [] },
    comparisons: [
      { key: 'c1', label: '发货批次', internal_value: 'B1', jd_value: 'B1', internal_present: true, jd_present: true, state: 'MATCH', note: null },
      { key: 'c2', label: '实发数量', internal_value: 4, jd_value: 5, internal_present: true, jd_present: true, state: 'MISMATCH', note: '数量不一致' },
      { key: 'c3', label: '内部独有字段', internal_value: 'X', jd_value: null, internal_present: true, jd_present: false, state: 'INTERNAL_ONLY', note: '仅内部有' },
      { key: 'c4', label: '京东独有字段', internal_value: null, jd_value: 'Y', internal_present: false, jd_present: true, state: 'JD_ONLY', note: '仅京东有' },
      { key: 'c5', label: '两侧空字段', internal_value: null, jd_value: null, internal_present: false, jd_present: false, state: 'EMPTY', note: null },
      { key: 'c6', label: '京东未取到字段', internal_value: 'Z', jd_value: null, internal_present: true, jd_present: false, state: 'JD_UNAVAILABLE', note: null },
      { key: 'c7', label: '京东无记录字段', internal_value: 'W', jd_value: null, internal_present: true, jd_present: false, state: 'JD_NOT_FOUND', note: null },
    ],
    matched_count: 1,
    mismatch_count: 5,
    ...overrides,
  };
}

test('workbench recon page shows the amount-out-of-scope notice explicitly', async () => {
  globalThis.fetch = async () => {
    throw new Error('no query should not fetch');
  };

  await harness.mount(['/workbench/recon']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /输入单号开始查询/));
  assert.match(harness.bodyText(), /金额对账未纳入本期/);
  assert.match(harness.bodyText(), /当前为数量口径/);
  assert.match(harness.bodyText(), /¥ ——/);
});

test('workbench recon success view renders all seven row-state presentations distinctly', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return jsonResponse(reconView());
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /逐字段差异对照/));
  const text = harness.bodyText();
  // 七种差异标记必须各自可见且互不混淆（经过页面渲染，非纯函数映射）。
  assert.match(text, /(?<!不)一致/, 'MATCH → 一致');
  assert.match(text, /不一致/, 'MISMATCH → 不一致');
  assert.match(text, /仅内部有/, 'INTERNAL_ONLY → 仅内部有');
  assert.match(text, /仅京东有/, 'JD_ONLY → 仅京东有');
  assert.match(text, /两侧均为空/, 'EMPTY → 两侧均为空');
  assert.match(text, /京东未取到/, 'JD_UNAVAILABLE → 京东未取到');
  assert.match(text, /京东无记录/, 'JD_NOT_FOUND → 京东无记录');
});

const QUERY_CONTRACT: Array<{ type: OutboundReconQueryType; value: string; label: string }> = [
  { type: 'OUTBOUND_ORDER_NO', value: '202608130001', label: '系统出库单号' },
  { type: 'JD_DELIVERY_NO', value: 'JD-DELIVERY-1', label: '京东单号' },
  { type: 'ORDER_NO', value: 'SO-20260813', label: '订单号' },
];

for (const { type, value, label } of QUERY_CONTRACT) {
  test(`workbench recon honours the ${type} URL contract`, async () => {
    const requested: string[] = [];
    globalThis.fetch = async (input) => {
      const url = String(input);
      requested.push(url);
      if (url.startsWith('/api/v1/outbound-recon?')) {
        return jsonResponse(reconView({ query: { type, value } }));
      }
      throw new Error(`unexpected request: ${url}`);
    };

    await harness.mount([`/workbench/recon?query_type=${type}&query_value=${value}`]);

    await harness.waitFor(() => assert.match(harness.bodyText(), new RegExp(`查询条件：${label}「${value}」`)));
    assert.ok(
      requested.some((url) => url.includes(`query_type=${type}`) && url.includes(`query_value=${value}`)),
      `必须按 URL 契约请求 ${type}=${value}，实际请求：${requested.join(', ')}`,
    );
  });
}

test('workbench recon surfaces JD_NOT_FOUND distinctly (no record, not empty)', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return jsonResponse(reconView({
        jd: { status: 'NOT_FOUND', business_code: 'JD_NOT_FOUND', message: null, client_mode: 'MOCK', summary: null, items: [] },
        comparisons: [
          { key: 'k1', label: '发货批次', internal_value: 'B1', jd_value: null, internal_present: true, jd_present: false, state: 'JD_NOT_FOUND', note: '京东无记录' },
        ],
        matched_count: 0,
        mismatch_count: 1,
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东侧没有这笔出库记录/));
  assert.match(harness.bodyText(), /京东无记录/);
});

test('workbench recon surfaces JD_UNAVAILABLE distinctly (fetch failed, not empty)', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return jsonResponse(reconView({
        jd: { status: 'UNAVAILABLE', business_code: 'JD_TIMEOUT', message: '京东查询超时', client_mode: 'MOCK', summary: null, items: [] },
        comparisons: [
          { key: 'k1', label: '发货批次', internal_value: 'B1', jd_value: null, internal_present: true, jd_present: false, state: 'JD_UNAVAILABLE', note: '京东未取到' },
        ],
        matched_count: 0,
        mismatch_count: 1,
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东侧未取到/));
  assert.match(harness.bodyText(), /京东未取到/);
});

test('workbench recon keeps the input when the backend returns 404', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return apiErrorResponse(404, 'OUTBOUND_NOT_FOUND', '系统内部没有这笔出库');
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部没有这笔出库/));
  assert.match(harness.location(), /query_type=OUTBOUND_ORDER_NO&query_value=202608130001/, '查询条件必须留在 URL 中');
  const input = document.querySelector<HTMLInputElement>('input[placeholder*="202608130001"]');
  assert.equal(input?.value, '202608130001', '404 后输入框必须保留原始单号');
  assert.ok(control('重试'), '404 视图必须保留重试动作');
});

test('workbench recon does not fabricate an /orders link without a provable order_id', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      // 只给业务订单号 order_no，不给可证映射到 /orders/:id 的 order_id。
      return jsonResponse(reconView({
        query: { type: 'ORDER_NO', value: 'SO-20260813' },
        internal: {
          summary: { outbound_order_no: '202608130001', order_no: 'SO-20260813', shipment_status: 'SHIPPED' },
          items: [],
          tracking: null,
        },
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/recon?query_type=ORDER_NO&query_value=SO-20260813']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /查询条件：订单号「SO-20260813」/));
  const detailLinks = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .filter((link) => (link.getAttribute('href') ?? '').startsWith('/orders/') && (link.getAttribute('href') ?? '').includes('return_to'));
  assert.equal(detailLinks.length, 0, '无 order_id 证据链时不得渲染带 return_to 的订单详情跳转');
});

test('workbench recon makes the internal-facts card a keyboard-reachable order link when order_id is present', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return jsonResponse(reconView({
        internal: {
          summary: {
            outbound_order_no: '202608130001',
            order_no: 'SO-20260813',
            order_id: '101',
            shipment_id: '55',
            shipment_status: 'SHIPPED',
          },
          items: [],
          tracking: null,
        },
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  const currentPath = '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001';
  await harness.mount([currentPath]);

  await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));
  const expectedHref = `/orders/101?return_to=${encodeURIComponent(currentPath)}`;
  const orderLinks = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .filter((link) => (link.getAttribute('href') ?? '').startsWith('/orders/'));
  assert.equal(orderLinks.length, 1, '有真实 order_id 时整卡必须是单一订单锚点');
  assert.equal(orderLinks[0].getAttribute('href'), expectedHref, 'href 必须落到 /orders/:id 并保留当前查询');
  assert.equal(orderLinks[0].tagName, 'A', '下钻必须是天然 <a>，键盘可达');
  assert.match(orderLinks[0].textContent ?? '', /系统内部事实/);
  assert.equal(orderLinks[0].querySelectorAll('a').length, 0, '整卡锚点内不得再嵌套锚点');
});

test('legacy /fulfillment/outbound-recon stays available without the workbench notice', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) {
      return jsonResponse(reconView({
        internal: {
          summary: {
            outbound_order_no: '202608130001',
            order_no: 'SO-20260813',
            order_id: '101',
            shipment_id: '55',
            shipment_status: 'SHIPPED',
          },
          items: [],
          tracking: null,
        },
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/outbound-recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /查询条件：系统出库单号「202608130001」/));
  assert.match(harness.bodyText(), /逐字段差异对照/, '旧路由必须保留完整七态对照视图');
  assert.doesNotMatch(harness.bodyText(), /金额对账未纳入本期/, '金额口径横幅只属于工作台入口，不污染旧路由');
  const orderLinks = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .filter((link) => (link.getAttribute('href') ?? '').startsWith('/orders/'));
  assert.equal(orderLinks.length, 0, '旧 /fulfillment/outbound-recon 不得启用订单下钻');
});

test('internalOrderId only accepts trimmed OpenAPI Identifier values', () => {
  assert.equal(internalOrderId({ order_id: '101' }), '101');
  assert.equal(internalOrderId({ order_id: ' 101 ' }), '101');
  assert.equal(internalOrderId({ order_id: '0' }), null);
  assert.equal(internalOrderId({ order_id: '   ' }), null);
  assert.equal(internalOrderId({ order_id: 'a/b' }), null);
  assert.equal(internalOrderId({ order_id: '\\' }), null);
  assert.equal(internalOrderId({ order_id: '?' }), null);
  assert.equal(internalOrderId({ order_id: '#' }), null);
  assert.equal(internalOrderId({ order_id: '101?return_to=https://evil.invalid' }), null);
  assert.equal(internalOrderId({ order_id: 101 }), null);
  assert.equal(internalOrderId({}), null);
});

const REJECTED_ORDER_IDS = ['0', '   ', 'a/b', '\\', '?', '#'];

for (const orderId of REJECTED_ORDER_IDS) {
  test(`workbench recon does not link for non-Identifier order_id ${JSON.stringify(orderId)}`, async () => {
    globalThis.fetch = async (input) => {
      const url = String(input);
      if (url.startsWith('/api/v1/outbound-recon?')) {
        return jsonResponse(reconView({
          internal: {
            summary: {
              outbound_order_no: '202608130001',
              order_no: 'SO-20260813',
              order_id: orderId,
              shipment_status: 'SHIPPED',
            },
            items: [],
            tracking: null,
          },
        }));
      }
      throw new Error(`unexpected request: ${url}`);
    };

    await harness.mount(['/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001']);
    await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));
    const orderLinks = [...document.querySelectorAll<HTMLAnchorElement>('a')]
      .filter((link) => (link.getAttribute('href') ?? '').includes('/orders/'));
    assert.equal(orderLinks.length, 0, `order_id ${JSON.stringify(orderId)} 不得渲染订单链接`);
    const hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
    assert.equal(hrefs.some((href) => href.includes('evil.invalid') || href.includes('a/b') || href.includes('%2F')), false);
  });
}

const RECON_QUERY = '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001';

const ALLOWED_RETURN_TO: Array<{ name: string; raw: string; expected: string }> = [
  {
    name: 'OUTBOUND_ORDER_NO',
    raw: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
    expected: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
  },
  {
    name: 'JD_DELIVERY_NO',
    raw: '/workbench/recon?query_type=JD_DELIVERY_NO&query_value=JD-DELIVERY-1',
    expected: '/workbench/recon?query_type=JD_DELIVERY_NO&query_value=JD-DELIVERY-1',
  },
  {
    name: 'ORDER_NO',
    raw: '/workbench/recon?query_type=ORDER_NO&query_value=SO-20260813',
    expected: '/workbench/recon?query_type=ORDER_NO&query_value=SO-20260813',
  },
  {
    name: 'strips extra query params',
    raw: '/workbench/recon?foo=1&query_type=OUTBOUND_ORDER_NO&query_value=202608130001&bar=baz',
    expected: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
  },
  {
    name: 'trims query_value',
    raw: '/workbench/recon?query_type=ORDER_NO&query_value=%20SO-20260813%20',
    expected: '/workbench/recon?query_type=ORDER_NO&query_value=SO-20260813',
  },
];

for (const { name, raw, expected } of ALLOWED_RETURN_TO) {
  test(`safeOrderReturnLocation accepts ${name}`, () => {
    assert.equal(safeOrderReturnLocation(raw), expected);
  });
}

const REJECTED_RETURN_TO: Array<{ name: string; raw: string | null | undefined }> = [
  { name: 'https protocol', raw: 'https://evil.invalid/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'http protocol', raw: 'http://evil.invalid/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'protocol-relative //', raw: '//evil.invalid/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: '// inside query', raw: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001&x=https://evil.invalid' },
  { name: 'backslash', raw: '/workbench/recon\\x?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'other path /orders', raw: '/orders/101?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'other path /fulfillment/outbound-recon', raw: '/fulfillment/outbound-recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'illegal query_type', raw: '/workbench/recon?query_type=SHIPMENT_NO&query_value=202608130001' },
  { name: 'empty query_value', raw: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=' },
  { name: 'whitespace query_value', raw: '/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=%20%20' },
  { name: 'missing query_value', raw: '/workbench/recon?query_type=OUTBOUND_ORDER_NO' },
  { name: 'missing leading slash', raw: 'workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001' },
  { name: 'empty string', raw: '' },
  { name: 'null', raw: null },
  { name: 'undefined', raw: undefined },
];

for (const { name, raw } of REJECTED_RETURN_TO) {
  test(`safeOrderReturnLocation rejects ${name}`, () => {
    assert.equal(safeOrderReturnLocation(raw), null);
  });
}

function orderDetailBody(id = '101') {
  return {
    id,
    order_no: `ORD-${id}`,
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
    receiver: {
      name: '张三', phone: '13900000000', province: '上海市', city: '上海市',
      district: '浦东新区', town: '', address: '测试路 1 号',
    },
    settlement: { method: 'MONTHLY' },
    lines: [],
    review_cases: [],
  };
}

function reconWithOrderId() {
  return reconView({
    internal: {
      summary: {
        outbound_order_no: '202608130001',
        order_no: 'SO-20260813',
        order_id: '101',
        shipment_id: '55',
        shipment_status: 'SHIPPED',
      },
      items: [],
      tracking: null,
    },
  });
}

function reconAndOrderFetch() {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/outbound-recon?')) return jsonResponse(reconWithOrderId());
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    if (url === '/api/v1/orders/101/timeline' || url === '/api/v1/orders/101/shipments') return jsonResponse([]);
    if (url === '/api/v1/orders/101') return jsonResponse(orderDetailBody());
    throw new Error(`unexpected request: ${url}`);
  };
}

function pageHrefs(): string[] {
  return [...document.querySelectorAll('a')].map((anchor) => anchor.getAttribute('href') ?? '');
}

test('workbench recon order card click-through returns via 返回出库对账 to the original query', async () => {
  globalThis.fetch = reconAndOrderFetch();

  await harness.mount([RECON_QUERY]);
  await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));

  const expectedHref = `/orders/101?return_to=${encodeURIComponent(RECON_QUERY)}`;
  const orderLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => (link.getAttribute('href') ?? '').startsWith('/orders/'));
  assert.ok(orderLink, '可证 order_id 必须渲染整卡锚点');
  assert.equal(orderLink.getAttribute('href'), expectedHref);
  await harness.dispatchEvent(orderLink, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), expectedHref));
  await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
  const back = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => (link.textContent ?? '').includes('返回出库对账'));
  assert.ok(back, '合法 return_to 必须渲染「返回出库对账」锚点');
  assert.equal(back.getAttribute('href'), RECON_QUERY, '返回锚点必须是原出库对账查询');
  assert.equal(pageHrefs().some((href) => href.includes('evil.invalid')), false);

  await harness.dispatchEvent(back, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), RECON_QUERY));
  await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /查询条件：系统出库单号「202608130001」/));
});

test('order detail without return_to keeps history back and does not invent a recon link', async () => {
  globalThis.fetch = reconAndOrderFetch();

  await harness.mount([RECON_QUERY, '/orders/101']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
  assert.doesNotMatch(harness.bodyText(), /返回出库对账/);
  const back = control('返回');
  assert.equal(back.getAttribute('href'), null, '缺失 return_to 不得生成返回 href');
  assert.equal(pageHrefs().some((href) => href.includes('/workbench/recon') || href.includes('evil.invalid')), false);

  await harness.dispatchEvent(back, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), RECON_QUERY));
  await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));
});

const ILLEGAL_RETURN_TO = [
  'https://evil.invalid/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
  '//evil.invalid/workbench/recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
  '/fulfillment/outbound-recon?query_type=OUTBOUND_ORDER_NO&query_value=202608130001',
];

for (const raw of ILLEGAL_RETURN_TO) {
  test(`order detail rejects illegal return_to ${JSON.stringify(raw)} and keeps history back`, async () => {
    globalThis.fetch = reconAndOrderFetch();

    await harness.mount([RECON_QUERY, `/orders/101?return_to=${encodeURIComponent(raw)}`]);
    await harness.waitFor(() => assert.match(harness.bodyText(), /ORD-101/));
    assert.doesNotMatch(harness.bodyText(), /返回出库对账/);
    const back = control('返回');
    assert.equal(back.getAttribute('href'), null, '非法 return_to 不得生成返回 href');
    assert.equal(
      pageHrefs().some((href) => href.includes('evil.invalid') || href.includes('https:') || href === raw || href.includes(raw)),
      false,
      '非法 return_to 不得渲染 open redirect 锚点',
    );

    await harness.dispatchEvent(back, new MouseEvent('click', { bubbles: true }));
    await harness.waitFor(() => assert.equal(harness.location(), RECON_QUERY));
    await harness.waitFor(() => assert.match(harness.bodyText(), /系统内部事实/));
  });
}
