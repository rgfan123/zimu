import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let OrdersApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/orders/101',
  });
  const browserGlobals = [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLInputElement', 'SVGElement',
    'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event', 'MouseEvent',
  ] as const;
  for (const key of browserGlobals) {
    Object.defineProperty(globalThis, key, {
      configurable: true,
      value: key === 'window' ? dom.window : dom.window[key],
    });
  }
  const nativeGetComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  const safeGetComputedStyle = (element: Element) => nativeGetComputedStyle(element);
  Object.defineProperty(dom.window, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });
  Object.defineProperty(globalThis, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });

  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: ResizeObserverStub });
  Object.defineProperty(dom.window, 'ResizeObserver', { configurable: true, value: ResizeObserverStub });
  Object.defineProperty(dom.window, 'matchMedia', {
    configurable: true,
    value: () => ({
      matches: false,
      media: '',
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent() { return false; },
    }),
  });
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    value: (callback: FrameRequestCallback) => setTimeout(callback, 0),
  });
  Object.defineProperty(globalThis, 'cancelAnimationFrame', {
    configurable: true,
    value: (handle: number) => clearTimeout(handle),
  });
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function bodyText(): string {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

async function waitFor(assertion: () => void, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      assertion();
      return;
    } catch (error) {
      lastError = error;
    }
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
  throw lastError;
}

async function mountRoute(pathname: string) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      {
        initialEntries: [pathname],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(OrdersApp),
    ));
  });
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  ({ MemoryRouter } = await import('react-router-dom'));
  const { createServer } = await import('vite');
  vite = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  OrdersApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
});

beforeEach(() => {
  document.body.innerHTML = '<div id="root"></div>';
});

afterEach(async () => {
  if (mountedRoot) {
    await act(async () => mountedRoot?.unmount());
    mountedRoot = null;
  }
});

after(async () => {
  await vite.close();
  dom.window.close();
});

test('real order detail route renders Shipment-level JD facts without raw supplier data', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse([{
        id: '11',
        provider_code: 'JD',
        provider_name: '京东云仓',
        provider_type: 'JD_WAREHOUSE',
        tracking_sla_minutes: 60,
        active: true,
        version: 0,
      }]);
    }
    if (url === '/api/v1/orders/101/timeline') return jsonResponse([]);
    if (url === '/api/v1/orders/101/shipments') {
      return jsonResponse([
        {
          id: '501',
          shipment_no: 'SHIP-501',
          order_id: '101',
          provider_id: '11',
          outbound_order_no: '202608140001',
          shipment_sequence: 1,
          shipment_status: 'CREATED',
          items: [],
          jd_outbound: {
            erp_delivery_no: '202608140001',
            jd_delivery_no: 'JD-501',
            sync_status: 'SYNC_FAILED',
            failure_phase: 'SUBMIT',
            retry_count: 1,
            retryable: true,
            client_mode: 'REAL',
            last_error_code: 'JD_TIMEOUT',
            last_error_message: 'raw supplier response must stay hidden',
            updated_at: '2026-08-14T01:00:00Z',
          },
          created_at: '2026-08-14T00:00:00Z',
          updated_at: '2026-08-14T00:30:00Z',
          raw_response: 'raw supplier payload must stay hidden',
          receiver: { name: 'raw name', phone: '13800000000', address: 'raw address' },
        },
        {
          id: '502',
          shipment_no: 'SHIP-502',
          order_id: '101',
          provider_id: '11',
          outbound_order_no: '202608140002',
          shipment_sequence: 2,
          shipment_status: 'SHIPPED',
          items: [],
          tracking: {
            id: '901',
            logistics_company_code: 'SF',
            logistics_company_name: '顺丰',
            tracking_number: 'SF501',
            received_at: '2026-08-14T02:00:00Z',
          },
          jd_outbound: {
            erp_delivery_no: '202608140002',
            jd_delivery_no: 'JD-502',
            sync_status: 'SUBMITTED',
            retry_count: 1,
            retryable: false,
            client_mode: 'REAL',
            tracking_query_status: 'TRACKED',
            updated_at: '2026-08-14T02:00:00Z',
          },
          created_at: '2026-08-14T00:00:00Z',
          updated_at: '2026-08-14T02:00:00Z',
        },
        {
          id: '503',
          shipment_no: 'SHIP-503',
          order_id: '101',
          provider_id: '11',
          outbound_order_no: '202608140003',
          shipment_sequence: 3,
          shipment_status: 'CREATED',
          items: [],
          tracking: null,
          jd_outbound: null,
          shipped_at: null,
          created_at: '2026-08-14T00:00:00Z',
          updated_at: '2026-08-14T02:10:00Z',
        },
        {
          id: '504',
          shipment_no: 'SHIP-504',
          order_id: '101',
          provider_id: '11',
          outbound_order_no: '202608140004',
          shipment_sequence: 4,
          shipment_status: 'CREATED',
          items: [],
          tracking: null,
          jd_outbound: {
            erp_delivery_no: '202608140004',
            jd_delivery_no: 'JD-504',
            sync_status: 'SUBMITTED',
            failure_phase: null,
            tracking_query_status: 'PENDING',
            updated_at: '2026-08-14T02:20:00Z',
          },
          shipped_at: null,
          created_at: '2026-08-14T00:00:00Z',
          updated_at: '2026-08-14T02:20:00Z',
        },
      ]);
    }
    if (url === '/api/v1/orders/101') {
      return jsonResponse({
        id: '101',
        order_no: 'ORD-101',
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
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/orders/101');

  await waitFor(() => assert.match(bodyText(), /京东云仓/));
  const text = bodyText();
  assert.match(text, /商户出库号/);
  assert.match(text, /202608140001/);
  assert.match(text, /JD-501/);
  assert.match(text, /失败/);
  assert.match(text, /提交建单/);
  assert.match(text, /未建单/);
  assert.match(text, /同步中/);
  assert.match(text, /已回传/);
  assert.match(text, /顺丰 · SF501/);
  assert.doesNotMatch(text, /raw supplier|13800000000|raw address/);
  assert.deepEqual(new Set(requestedUrls), new Set([
    '/api/v1/orders/101',
    '/api/v1/orders/101/timeline',
    '/api/v1/orders/101/shipments',
    '/api/v1/fulfillment-providers',
  ]));
});

test('real total-orders route submits a fulfillment-provider filter without changing the order identity', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse([
        {
          id: '11', provider_code: 'JD', provider_name: '京东云仓',
          provider_type: 'JD_WAREHOUSE', tracking_sla_minutes: 60, active: true, version: 0,
        },
        {
          id: '12', provider_code: 'TP', provider_name: '第三方履约',
          provider_type: 'THIRD_PARTY', tracking_sla_minutes: 60, active: true, version: 0,
        },
      ]);
    }
    if (url.startsWith('/api/v1/orders?')) {
      return jsonResponse({
        items: [{
          id: '101',
          order_no: 'ORD-ONE-COMPANY-ORDER',
          source_channel: 'WECOM',
          customer_name: '测试客户',
          receiver_name: '张三',
          order_status: 'FULFILLING',
          processing_stage: 'WAITING_PROVIDER',
          processing_health: 'BLUE',
          completed_count: 0,
          total_count: 2,
          created_at: '2026-08-14T00:00:00Z',
          updated_at: '2026-08-14T02:00:00Z',
          version: 0,
        }],
        page: 0,
        size: 20,
        total_elements: 1,
        total_pages: 1,
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/orders');
  await waitFor(() => assert.match(bodyText(), /ORD-ONE-COMPANY-ORDER/));

  const providerInput = document.querySelector<HTMLInputElement>('input[aria-label="履约方"]');
  assert.ok(providerInput, 'total-orders route must expose a fulfillment-provider filter');
  await act(async () => {
    providerInput.dispatchEvent(new dom.window.MouseEvent('mousedown', { bubbles: true }));
  });
  await waitFor(() => assert.match(bodyText(), /京东云仓/));
  const jdOption = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((option) => option.textContent?.includes('京东云仓'));
  assert.ok(jdOption, 'provider options must use the provider directory');
  await act(async () => {
    jdOption.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  });

  const searchButton = document.querySelector<HTMLButtonElement>('.ant-input-search-button');
  assert.ok(searchButton, 'total-orders filter bar must expose the search action');
  await act(async () => {
    searchButton.dispatchEvent(new dom.window.MouseEvent('click', { bubbles: true }));
  });
  await waitFor(() => assert.ok(requestedUrls.some((url) => url.includes('provider_id=11'))));

  const filteredRequest = requestedUrls.find((url) => url.includes('provider_id=11'));
  assert.match(filteredRequest ?? '', /^\/api\/v1\/orders\?/);
  assert.equal(document.body.textContent?.match(/ORD-ONE-COMPANY-ORDER/g)?.length, 1);
});

test('legacy JD order-query URL is explicitly identified as a channel tool outside total orders', async () => {
  globalThis.fetch = async (input) => jsonResponse({ message: `unexpected request ${String(input)}` }, 500);

  await mountRoute('/fulfillment/jd-order');

  await waitFor(() => assert.match(bodyText(), /系统渠道工具/));
  assert.match(bodyText(), /不计入公司总订单/);
  assert.match(bodyText(), /调整单 \/ 销毁单 \/ 异常单 \/ 采购单 \/ 加工单 \/ 作业关联/);
});

test('order detail keeps JD facts visible and exposes a retry when the provider directory fails', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse({ business_code: 'FORBIDDEN', message: 'private provider policy' }, 403);
    }
    if (url === '/api/v1/orders/101/timeline') return jsonResponse([]);
    if (url === '/api/v1/orders/101/shipments') {
      return jsonResponse([{
        id: '505', shipment_no: 'SHIP-505', order_id: '101', provider_id: '11',
        outbound_order_no: '202608140005', shipment_sequence: 1, shipment_status: 'CREATED', items: [],
        tracking: null,
        jd_outbound: {
          erp_delivery_no: '202608140005', jd_delivery_no: 'JD-505', sync_status: 'SUBMITTED',
          failure_phase: null, tracking_query_status: 'PENDING', updated_at: '2026-08-14T02:20:00Z',
        },
        shipped_at: null, created_at: '2026-08-14T00:00:00Z', updated_at: '2026-08-14T02:20:00Z',
      }]);
    }
    if (url === '/api/v1/orders/101') {
      return jsonResponse({
        id: '101', order_no: 'ORD-101', source_channel: 'WECOM', receiver_name: '张三',
        order_status: 'FULFILLING', processing_stage: 'WAITING_PROVIDER', processing_health: 'BLUE',
        completed_count: 0, total_count: 1, created_at: '2026-08-14T00:00:00Z',
        updated_at: '2026-08-14T02:00:00Z', version: 0,
        receiver: { name: '张三', phone: '13900000000', province: '', city: '', district: '', town: '', address: '测试地址' },
        settlement: { method: 'MONTHLY' }, lines: [], review_cases: [],
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/orders/101');

  await waitFor(() => assert.match(bodyText(), /履约方信息加载失败/));
  assert.match(bodyText(), /同步中/);
  assert.doesNotMatch(bodyText(), /private provider policy/);
  const providerAlert = [...document.querySelectorAll<HTMLElement>('.ant-alert')]
    .find((alert) => alert.textContent?.includes('履约方信息加载失败'));
  assert.match(providerAlert?.textContent ?? '', /重试/);
});

test('total orders exposes a retry instead of silently emptying a failed provider filter', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse({ business_code: 'INTERNAL_ERROR', message: 'private directory stack' }, 500);
    }
    if (url.startsWith('/api/v1/orders?')) {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/orders');

  await waitFor(() => assert.match(bodyText(), /履约方目录加载失败/));
  assert.doesNotMatch(bodyText(), /private directory stack/);
  const providerAlert = [...document.querySelectorAll<HTMLElement>('.ant-alert')]
    .find((alert) => alert.textContent?.includes('履约方目录加载失败'));
  assert.match(providerAlert?.textContent ?? '', /重试/);
});
