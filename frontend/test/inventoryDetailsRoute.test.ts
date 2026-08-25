import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import type { InventoryDetailsResponse } from '../src/api/types.ts';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let InventoryApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/inventory/details',
  });
  const browserGlobals = [
    'window',
    'document',
    'navigator',
    'HTMLElement',
    'HTMLInputElement',
    'SVGElement',
    'Element',
    'Document',
    'Node',
    'ShadowRoot',
    'MutationObserver',
    'Event',
    'MouseEvent',
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

async function mountRoute(initialEntry: string) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      {
        initialEntries: [initialEntry],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(InventoryApp),
    ));
  });
}

function detailsResponse(): InventoryDetailsResponse {
  return {
    context: {
      provider_id: '12',
      provider_code: 'JD',
      provider_name: '京东云仓',
      provider_type: 'JD_WAREHOUSE',
      sku_id: '34',
      sku_code: 'SKU-34',
      product_name: '子牧商品',
      specification: '500g/盒',
      unit: '盒',
      provider_sku_code: 'JD-GOODS-34',
      warehouse_code: 'WH-A',
    },
    observation: {
      observation_status: 'OBSERVED',
      total_quantity: '8.000',
      available_quantity: '5.000',
      unavailable_quantity: '3.000',
      quantity_unit: 'JD_PIECE',
      observed_at: '2026-08-14T01:02:03Z',
      observation_age_seconds: 1800,
      expires_at: '2026-08-14T01:17:03Z',
      freshness_status: 'STALE',
      source_type: 'JD_ISC_QUERY_STOCK',
      data_mode: 'CACHED_SNAPSHOT',
    },
    query_time: '2026-08-14T01:32:03Z',
    freshness_policy: 'PT15M',
    capabilities: [
      {
        group: 'BATCH_AND_SHELF_LIFE',
        label: '批次 / 库存水位变化 / 效期',
        integration_status: 'INTEGRATED',
        runtime_mode: 'MOCK',
        source_type: 'JD_ISC_READ_ONLY',
        explanation: '已接入京东 ISC 只读查询。',
        tools: [
          { code: 'JD_BATCH_CHANGES', label: '批次异动' },
          { code: 'JD_SHELF_LIFE_INVENTORY', label: '效期库存' },
        ],
      },
      {
        group: 'INVENTORY_FLOW',
        label: '库存流水',
        integration_status: 'INTEGRATED',
        runtime_mode: 'MOCK',
        source_type: 'JD_ISC_READ_ONLY',
        explanation: '已接入京东 ISC 只读查询。',
        tools: [{ code: 'JD_SHOP_STOCK_FLOW', label: '库存流水' }],
      },
      {
        group: 'SERIAL_NUMBER',
        label: '序列号',
        integration_status: 'INTEGRATED',
        runtime_mode: 'MOCK',
        source_type: 'JD_ISC_READ_ONLY',
        explanation: '已接入京东 ISC 只读查询。',
        tools: [{ code: 'JD_SERIAL_INSIDE', label: '在库序列号' }],
      },
    ],
  };
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
  InventoryApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

test('real details route presents stale cached JD capabilities without confusing mock tools with facts', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    if (url.startsWith('/api/v1/skus?')) return jsonResponse({ items: [], page: 0, size: 500, total_elements: 0, total_pages: 0 });
    if (url.startsWith('/api/v1/inventory/overview')) {
      return jsonResponse({
        items: [],
        page: 2,
        size: 50,
        total_elements: 0,
        total_pages: 0,
        coverage: {
          provider_count: 0,
          observed_provider_count: 0,
          sku_count: 0,
          observed_sku_count: 0,
          warehouse_count: 0,
          latest_observed_at: null,
          stale_count: 0,
          oldest_observed_at: null,
          partial: false,
          freshness_policy: 'PT15M',
        },
      });
    }
    return jsonResponse(detailsResponse());
  };
  const returnTo = '/inventory/overview?page=2&size=50&provider_id=12&sku_id=34&warehouse_code=WH-A';
  const entryParams = new URLSearchParams({
    provider_id: '12',
    sku_id: '34',
    warehouse_code: 'WH-A',
    return_to: returnTo,
  });
  await mountRoute(`/inventory/details?${entryParams}`);

  await waitFor(() => assert.match(bodyText(), /专业库存明细/));
  assert.deepEqual(requestedUrls, [
    '/api/v1/inventory/details?provider_id=12&sku_id=34&warehouse_code=WH-A',
  ]);
  assert.match(bodyText(), /SKU-34/);
  assert.match(bodyText(), /京东云仓/);
  assert.match(bodyText(), /WH-A/);
  assert.match(bodyText(), /缓存快照/);
  assert.match(bodyText(), /数据已过期/);
  assert.match(bodyText(), /模拟接口（不代表真实权限）/);
  assert.match(bodyText(), /批次 \/ 库存水位变化 \/ 效期/);
  assert.match(bodyText(), /库存流水/);
  assert.match(bodyText(), /序列号/);

  const links = [...document.querySelectorAll<HTMLAnchorElement>('a')];
  const shelfLifeLink = links.find((link) => link.textContent?.includes('效期库存'));
  assert.ok(shelfLifeLink);
  const shelfLifeUrl = new URL(shelfLifeLink.href);
  assert.equal(shelfLifeUrl.pathname, '/fulfillment/jd-stock');
  assert.equal(shelfLifeUrl.searchParams.get('kind'), 'shelfLifeInventory');
  assert.equal(shelfLifeUrl.searchParams.get('warehouse_no'), 'WH-A');
  assert.equal(shelfLifeUrl.searchParams.get('goods_no'), 'JD-GOODS-34');

  const returnLink = links.find((link) => link.textContent?.includes('返回总库存'));
  assert.ok(returnLink);
  assert.equal(new URL(returnLink.href).pathname + new URL(returnLink.href).search, returnTo);

  await act(async () => returnLink.click());
  await waitFor(() => assert.ok(
    requestedUrls.includes('/api/v1/inventory/overview?page=2&size=50&provider_id=12&sku_id=34&warehouse_code=WH-A'),
    '返回总库存必须带原筛选条件重新查询总库存页',
  ));
});

test('real details route renders unsupported provider capabilities as not integrated without fake results', async () => {
  const response = detailsResponse();
  response.context.provider_code = 'TP';
  response.context.provider_name = '第三方履约方';
  response.context.provider_type = 'THIRD_PARTY';
  response.context.provider_sku_code = null;
  response.observation = {
    observation_status: 'NOT_OBSERVED',
    total_quantity: null,
    available_quantity: null,
    unavailable_quantity: null,
    quantity_unit: null,
    observed_at: null,
    observation_age_seconds: null,
    expires_at: null,
    freshness_status: 'NOT_OBSERVED',
    source_type: null,
    data_mode: 'NO_OBSERVATION',
  };
  response.capabilities = response.capabilities.map((capability) => ({
    ...capability,
    integration_status: 'NOT_INTEGRATED',
    runtime_mode: 'NOT_APPLICABLE',
    source_type: null,
    explanation: '当前履约方尚未接入该类专业库存查询。',
    tools: [],
  }));
  globalThis.fetch = async () => jsonResponse(response);
  await mountRoute('/inventory/details?provider_id=12&sku_id=34&warehouse_code=WH-A');

  await waitFor(() => assert.match(bodyText(), /第三方履约方/));
  assert.match(bodyText(), /尚无观测/);
  assert.equal((bodyText().match(/未接入/g) ?? []).length >= 3, true);
  // Issue #104 外壳后侧栏常驻京东工具导航锚点：只断言页面主内容区不提供 JD 工具深链。
  assert.equal(document.querySelectorAll<HTMLAnchorElement>('main a[href^="/fulfillment/jd-"]').length, 0);
  assert.doesNotMatch(bodyText(), /在途数量|预留数量|0 件/);
});

test('real details route distinguishes permission denial from a safe system failure', async () => {
  const entry = '/inventory/details?provider_id=12&sku_id=34&warehouse_code=WH-A';
  globalThis.fetch = async () => jsonResponse({
    business_code: 'FORBIDDEN',
    message: 'private role policy detail',
    http_status: 403,
  }, 403);
  await mountRoute(entry);

  await waitFor(() => assert.match(bodyText(), /暂无查看权限/));
  assert.doesNotMatch(bodyText(), /private role policy detail/);

  await act(async () => mountedRoot?.unmount());
  mountedRoot = null;
  document.body.innerHTML = '<div id="root"></div>';
  globalThis.fetch = async () => jsonResponse({
    business_code: 'INTERNAL_ERROR',
    message: 'private provider stack trace',
    http_status: 500,
  }, 500);
  await mountRoute(entry);

  await waitFor(() => assert.match(bodyText(), /专业库存明细加载失败/));
  assert.match(bodyText(), /服务暂时不可用/);
  assert.doesNotMatch(bodyText(), /private provider stack trace/);
});
