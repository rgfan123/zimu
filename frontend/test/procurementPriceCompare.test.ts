import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let PriceCompareApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let simulate: typeof import('react-dom/test-utils')['Simulate'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/procurement/price-compare',
  });
  const browserGlobals = [
    'window',
    'document',
    'navigator',
    'HTMLElement',
    'HTMLInputElement',
    'HTMLTextAreaElement',
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
        initialEntries: ['/procurement/price-compare'],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(PriceCompareApp),
    ));
  });
}

function runResult(overrides: Record<string, unknown> = {}) {
  return {
    provider: 'deepseek',
    model: 'deepseek-chat',
    prompt_version: 'agent-foundation-v1',
    error: null,
    recommendation: {
      target_sku: 'SKU-1001',
      requested_quantity: '2',
      inventory: { available: '0', shortage: '2' },
      candidates: [
        { provider_code: 'P001', price: '12.34', price_basis: 'sku_commercial_price', note: '主数据进货价' },
        { provider_code: 'P002', price: '12.90', price_basis: 'provider_sku', note: '履约方映射价格' },
      ],
      excluded_candidates: [
        {
          provider_code: 'P003',
          price: '45.67',
          price_basis: 'provider_sku',
          note: '渠道报价异常高',
          exclusion_reason: 'price_outlier',
          exclusion_reason_detail: '与同组候选中位数偏离超过 2.0 倍（中位数 12.90，该候选价格 45.67）',
        },
        {
          provider_code: 'P004',
          price: null,
          price_basis: 'provider_sku',
          note: '未定价',
          exclusion_reason: 'price_missing',
          exclusion_reason_detail: '无可用价格（未定价或价格缺失），不可参与比价',
        },
      ],
      recommendation: { provider_code: 'P001', reason: '最低价且可比' },
      missing_fields: [],
      confidence: 0.9,
      requires_human: false,
    },
    ...overrides,
  };
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
  PriceCompareApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

test('price compare page renders two groups: comparable and excluded with visible reasons', async () => {
  const requested: Array<{ url: string; body?: string }> = [];
  globalThis.fetch = async (input, init) => {
    requested.push({ url: String(input), body: typeof init?.body === 'string' ? init.body : undefined });
    return jsonResponse(runResult());
  };

  await mountRoute();

  // 初始空态
  await waitFor(() => assert.match(bodyText(), /输入 SKU 编码或采购工单 ID 后开始比价/));

  // 输入 SKU 并点击比价
  const skuInput = document.querySelector<HTMLInputElement>('input[aria-label="SKU 编码"]');
  assert.ok(skuInput);
  await act(async () => {
    simulate.change(skuInput, { target: { value: 'SKU-1001' } });
  });
  const compareButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('开始比价'));
  assert.ok(compareButton);
  await act(async () => {
    simulate.click(compareButton);
  });

  await waitFor(() => assert.match(bodyText(), /可比候选/));
  assert.match(bodyText(), /被剔除候选/);
  assert.match(bodyText(), /价格离群/);
  assert.match(bodyText(), /价格缺失/);
  assert.match(bodyText(), /与同组候选中位数偏离超过 2.0 倍/);

  // 请求体正确：POST /api/v1/procurement-price-agent/compare，body 含 sku_id
  assert.deepEqual(requested, [{
    url: '/api/v1/procurement-price-agent/compare',
    body: JSON.stringify({ sku_id: 'SKU-1001', procurement_ticket_id: undefined, quantity: undefined }),
  }]);
  // 被剔除候选与理由可见（P003 离群 / P004 缺价）
  assert.match(bodyText(), /P003/);
  assert.match(bodyText(), /45\.67/);
  assert.match(bodyText(), /P004/);
});

test('price compare requires human routes to warning without hard recommendation', async () => {
  globalThis.fetch = async () => jsonResponse(runResult({
    recommendation: {
      target_sku: 'SKU-2001',
      requested_quantity: null,
      inventory: { available: '0', shortage: '6' },
      candidates: [],
      excluded_candidates: [
        {
          provider_code: 'P001',
          price: '12.34',
          price_basis: 'provider_sku',
          note: '映射已停用',
          exclusion_reason: 'mapping_stale',
          exclusion_reason_detail: '履约方 SKU 映射已停用或过期（工具返回 active=false）',
        },
      ],
      recommendation: null,
      missing_fields: ['candidates'],
      confidence: 0.85,
      requires_human: true,
    },
  }));

  await mountRoute();

  const skuInput = document.querySelector<HTMLInputElement>('input[aria-label="SKU 编码"]');
  assert.ok(skuInput);
  await act(async () => {
    simulate.change(skuInput, { target: { value: 'SKU-2001' } });
  });
  const compareButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('开始比价'));
  assert.ok(compareButton);
  await act(async () => {
    simulate.click(compareButton);
  });

  await waitFor(() => assert.match(bodyText(), /需要人工介入/));
  assert.match(bodyText(), /映射失效/);
  assert.match(bodyText(), /履约方 SKU 映射已停用或过期/);
  // 可比候选为空 → 转人工，不硬推
  assert.match(bodyText(), /无可比候选/);
});
