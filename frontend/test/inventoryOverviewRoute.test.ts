import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let InventoryApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let simulate: typeof import('react-dom/test-utils')['Simulate'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/inventory/overview',
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
  // Vite's browser-conditioned React scheduler otherwise keeps a Node MessagePort referenced
  // after JSDOM closes. Tests do not need paint scheduling, so use its timer fallback.
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function coverage(overrides: Record<string, unknown> = {}) {
  return {
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
    ...overrides,
  };
}

function overview(overrides: Record<string, unknown> = {}) {
  return {
    items: [],
    page: 0,
    size: 20,
    total_elements: 0,
    total_pages: 0,
    coverage: coverage(),
    ...overrides,
  };
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

async function mountRoute() {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      {
        initialEntries: ['/inventory/overview'],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(InventoryApp),
    ));
  });
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  ({ Simulate: simulate } = await import('react-dom/test-utils'));
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

test('real inventory route requests data, renders loading, then renders the empty state', async () => {
  let finishRequest: ((response: Response) => void) | undefined;
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    requestedUrls.push(String(input));
    return new Promise<Response>((resolve) => { finishRequest = resolve; });
  };

  await mountRoute();

  assert.match(bodyText(), /正在加载库存观测/);
  assert.deepEqual(requestedUrls, ['/api/v1/inventory/overview?page=0&size=20']);

  assert.ok(finishRequest, 'route request must be pending');
  await act(async () => {
    finishRequest?.(jsonResponse(overview()));
  });
  await waitFor(() => assert.match(bodyText(), /当前筛选范围内暂无匹配 SKU/));
  // 页头（PageShell）与页头说明文案随页面数据一起呈现。
  assert.match(bodyText(), /总库存/);
  assert.match(bodyText(), /按 SKU、仓库与履约方查看已落库的最新库存观测；未观测范围始终与零库存分开。/);
});

test('refresh stays reachable and re-requests the overview route', async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    return jsonResponse(overview());
  };
  await mountRoute();
  await waitFor(() => assert.equal(calls, 1));

  const refresh = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('刷新'));
  assert.ok(refresh, 'overview page must expose a refresh action');
  await act(async () => {
    simulate.click(refresh);
  });
  await waitFor(() => assert.equal(calls, 2));
});

test('real inventory route distinguishes permission failures from safe system failures', async () => {
  globalThis.fetch = async () => jsonResponse({
    business_code: 'FORBIDDEN',
    message: 'raw policy and credential detail',
    http_status: 403,
  }, 403);
  await mountRoute();

  await waitFor(() => assert.match(bodyText(), /暂无查看权限/));
  assert.doesNotMatch(bodyText(), /raw policy|credential/);

  await act(async () => mountedRoot?.unmount());
  mountedRoot = null;
  document.body.innerHTML = '<div id="root"></div>';
  globalThis.fetch = async () => jsonResponse({
    business_code: 'INTERNAL_ERROR',
    message: 'private stack trace',
    http_status: 500,
  }, 500);
  await mountRoute();

  await waitFor(() => assert.match(bodyText(), /总库存加载失败/));
  assert.match(bodyText(), /服务暂时不可用/);
  assert.doesNotMatch(bodyText(), /private stack trace/);
});

test('filter and pagination interactions issue the exact route requests and retain filters', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    requestedUrls.push(String(input));
    const url = new URL(String(input), 'http://localhost');
    const page = Number(url.searchParams.get('page') ?? '0');
    return jsonResponse(overview({ page, total_elements: 45, total_pages: 3 }));
  };
  await mountRoute();

  const providerInput = document.querySelector<HTMLInputElement>('input[aria-label="履约方 ID"]');
  const skuInput = document.querySelector<HTMLInputElement>('input[aria-label="SKU ID"]');
  const warehouseInput = document.querySelector<HTMLInputElement>('input[aria-label="仓库编码"]');
  assert.ok(providerInput && skuInput && warehouseInput);
  await act(async () => {
    simulate.change(providerInput, { target: { value: '12' } });
    simulate.change(skuInput, { target: { value: '34' } });
    simulate.change(warehouseInput, { target: { value: 'WH-A' } });
  });

  const search = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('查询'));
  assert.ok(search);
  await act(async () => {
    simulate.click(search);
  });
  await waitFor(() => assert.equal(
      requestedUrls.at(-1),
      '/api/v1/inventory/overview?page=0&size=20&provider_id=12&sku_id=34&warehouse_code=WH-A',
    ));

  const nextPage = document.querySelector<HTMLButtonElement>('.ant-pagination-next button');
  assert.ok(nextPage, 'a multi-page response must render the next-page action');
  await act(async () => {
    simulate.click(nextPage);
  });
  await waitFor(() => assert.equal(
      requestedUrls.at(-1),
      '/api/v1/inventory/overview?page=1&size=20&provider_id=12&sku_id=34&warehouse_code=WH-A',
    ));
});

test('a target-warehouse filter keeps an unobserved SKU visible without presenting zero stock', async () => {
  globalThis.fetch = async (input) => {
    const url = new URL(String(input), 'http://localhost');
    if (url.searchParams.get('warehouse_code') !== 'WH-B') {
      return jsonResponse(overview());
    }
    return jsonResponse(overview({
      items: [
        {
          provider_id: '12',
          provider_code: 'JD',
          provider_name: '京东云仓',
          provider_type: 'JD_WAREHOUSE',
          sku_id: '34',
          sku_code: 'SKU-34',
          product_name: '待观测商品',
          specification: '规格',
          unit: '件',
          quantity_unit: null,
          warehouse_code: null,
          observation_status: 'NOT_OBSERVED',
          total_quantity: null,
          available_quantity: null,
          unavailable_quantity: null,
          observed_at: null,
          observation_age_seconds: null,
          freshness_status: 'NOT_OBSERVED',
          source_type: null,
        },
        {
          provider_id: '12',
          provider_code: 'JD',
          provider_name: '京东云仓',
          provider_type: 'JD_WAREHOUSE',
          sku_id: '35',
          sku_code: 'SKU-ZERO',
          product_name: '零库存商品',
          specification: '规格',
          unit: '件',
          quantity_unit: 'JD_PIECE',
          warehouse_code: 'WH-B',
          observation_status: 'OBSERVED',
          total_quantity: '0.000',
          available_quantity: '0.000',
          unavailable_quantity: '0.000',
          observed_at: '2026-08-14T01:02:03Z',
          observation_age_seconds: 60,
          freshness_status: 'CURRENT',
          source_type: 'JD_ISC_QUERY_STOCK',
        },
      ],
      total_elements: 2,
      total_pages: 1,
      coverage: coverage({ provider_count: 1, sku_count: 1, partial: true }),
    }));
  };
  await mountRoute();

  const warehouseInput = document.querySelector<HTMLInputElement>('input[aria-label="仓库编码"]');
  const search = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('查询'));
  assert.ok(warehouseInput && search);
  await act(async () => {
    simulate.change(warehouseInput, { target: { value: 'WH-B' } });
  });
  await act(async () => {
    simulate.click(search);
  });
  await waitFor(() => assert.match(bodyText(), /SKU-34/));

  const rows = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody .ant-table-row')];
  const unobservedRow = rows.find((row) => row.textContent?.includes('SKU-34'));
  const zeroRow = rows.find((row) => row.textContent?.includes('SKU-ZERO'));
  assert.ok(unobservedRow, 'the master-data SKU must remain visible when the target warehouse has no observation');
  assert.ok(zeroRow, 'an observed explicit-zero SKU must remain distinguishable in the same target warehouse');
  const unobservedText = unobservedRow.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  const zeroText = zeroRow.textContent?.replace(/\s+/g, ' ').trim() ?? '';
  assert.match(unobservedText, /目标仓 WH-B 尚未观测/);
  assert.match(unobservedText, /尚未观测/);
  assert.doesNotMatch(unobservedText, /0\s*件/);
  assert.match(zeroText, /0 件（京东）/);
  assert.match(zeroText, /时效正常/);
});

test('coverage-wide stale facts stay visible when every item on the current page is current', async () => {
  const currentItems = Array.from({ length: 20 }, (_, index) => ({
    provider_id: '1',
    provider_code: 'JD',
    provider_name: '京东云仓',
    provider_type: 'JD_WAREHOUSE' as const,
    sku_id: String(index + 1),
    sku_code: `SKU-${index + 1}`,
    product_name: '商品',
    specification: '规格',
    unit: '件',
    quantity_unit: 'JD_PIECE' as const,
    warehouse_code: 'WH-A',
    observation_status: 'OBSERVED' as const,
    total_quantity: '2.000',
    available_quantity: '1.000',
    unavailable_quantity: '1.000',
    observed_at: '2026-08-14T01:02:03Z',
    observation_age_seconds: 60,
    freshness_status: 'CURRENT' as const,
    source_type: 'JD_ISC_QUERY_STOCK' as const,
  }));
  globalThis.fetch = async () => jsonResponse(overview({
    items: currentItems,
    total_elements: 22,
    total_pages: 2,
    coverage: coverage({
      provider_count: 1,
      observed_provider_count: 1,
      sku_count: 1,
      observed_sku_count: 1,
      warehouse_count: 2,
      latest_observed_at: '2026-08-14T01:02:03Z',
      stale_count: 2,
      oldest_observed_at: '2026-08-12T01:02:03Z',
    }),
  }));
  await mountRoute();

  await waitFor(() => assert.match(bodyText(), /时效正常/));
  assert.match(bodyText(), /当前筛选范围有 2 条库存观测超过时效策略 PT15M/);
  assert.match(bodyText(), /最早观测/);
});

test('an inventory row drills into details with its business context and a reversible overview location', async () => {
  globalThis.fetch = async () => jsonResponse(overview({
    items: [{
      provider_id: '12',
      provider_code: 'JD',
      provider_name: '京东云仓',
      provider_type: 'JD_WAREHOUSE',
      sku_id: '34',
      sku_code: 'SKU-34',
      product_name: '子牧商品',
      specification: '500g/盒',
      unit: '盒',
      quantity_unit: 'JD_PIECE',
      warehouse_code: 'WH-A',
      observation_status: 'OBSERVED',
      total_quantity: '2.000',
      available_quantity: '1.000',
      unavailable_quantity: '1.000',
      observed_at: '2026-08-14T01:02:03Z',
      observation_age_seconds: 60,
      freshness_status: 'CURRENT',
      source_type: 'JD_ISC_QUERY_STOCK',
    }],
    total_elements: 1,
    total_pages: 1,
  }));
  await mountRoute();

  await waitFor(() => assert.match(bodyText(), /查看明细/));
  const productRow = [...document.querySelectorAll<HTMLTableRowElement>('tbody tr')]
    .find((row) => row.textContent?.includes('SKU-34'));
  assert.ok(productRow, 'the inventory row must render the product identity');
  const productText = productRow.textContent ?? '';
  assert.ok(
    productText.indexOf('子牧商品') < productText.indexOf('SKU-34'),
    'the product name must be rendered before the secondary SKU code',
  );
  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('查看明细'));
  assert.ok(detailLink, 'the inventory row must expose a real drill-down link');
  const detailUrl = new URL(detailLink.href);
  assert.equal(detailUrl.pathname, '/inventory/details');
  assert.equal(detailUrl.searchParams.get('provider_id'), '12');
  assert.equal(detailUrl.searchParams.get('sku_id'), '34');
  assert.equal(detailUrl.searchParams.get('warehouse_code'), 'WH-A');
  assert.equal(detailUrl.searchParams.get('return_to'), '/inventory/overview?page=0&size=20');
});
