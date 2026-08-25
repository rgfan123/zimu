/**
 * issue #39 验收：订单中心菜单 IA 收敛。
 * - 旧直达 URL（/orders/pending、/orders/exceptions、/orders/tracking）不 404，
 *   且渲染合并后的订单列表并带上对应预设筛选；/demo/order 同样保持可达。
 * - 侧边栏菜单只保留「全部订单」一个可见订单叶子；模拟下单不再出现在菜单。
 */
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let App: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/orders',
  });
  const browserGlobals = [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLBodyElement', 'HTMLInputElement',
    'SVGElement', 'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event',
    'MouseEvent', 'KeyboardEvent', 'File', 'Blob', 'FormData',
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
      matches: false, media: '', onchange: null,
      addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
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
  Object.defineProperty(dom.window, 'scrollTo', { configurable: true, value() {} });
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function bodyText() {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

async function waitFor(assertion: () => void, timeoutMs = 3_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      assertion();
      return;
    } catch (error) {
      lastError = error;
    }
    await act(async () => new Promise((resolve) => setTimeout(resolve, 5)));
  }
  throw lastError;
}

function selectedSegment(): string {
  return document.querySelector('.ant-segmented-item-selected')?.textContent?.trim() ?? '';
}

function sidebarMenuText(): string {
  return document.querySelector('.ant-menu')?.textContent?.replace(/\s+/g, ' ') ?? '';
}

function headerText(): string {
  return document.querySelector('.ant-layout-header')?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

async function mountRoute(initialEntry: string) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: [initialEntry], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(App),
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
  App = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

const EMPTY_ORDER_PAGE = { items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 };

/** 订单列表预设直达：旧 URL 均渲染合并页并带上语义正确的预设筛选。 */
async function assertPresetRoute(entry: string, expectedPreset: string, expectedParams: Record<string, string>) {
  const orderRequests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/orders')) {
      orderRequests.push(url);
      return jsonResponse(EMPTY_ORDER_PAGE);
    }
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute(entry);

  await waitFor(() => assert.equal(selectedSegment(), expectedPreset));
  assert.ok(orderRequests.length > 0, `no orders request captured for ${entry}`);
  const params = new URL(orderRequests[orderRequests.length - 1], 'http://localhost').searchParams;
  assert.equal(params.get('page'), '0');
  assert.equal(params.get('size'), '20');
  for (const [key, value] of Object.entries(expectedParams)) {
    assert.equal(params.get(key), value, `${entry} should query ${key}=${value}`);
  }
  // 顶栏归属与页面名按旧叶子解析，语义不变
  assert.match(headerText(), /订单中心/);
  assert.match(headerText(), new RegExp(expectedPreset));

  // 菜单收敛：订单中心下只显示「全部订单」一个可见叶子
  assert.match(sidebarMenuText(), /订单中心/);
  assert.match(sidebarMenuText(), /全部订单/);
  assert.doesNotMatch(sidebarMenuText(), /待处理|异常订单|订单追踪/);
  assert.doesNotMatch(sidebarMenuText(), /模拟下单/);
}

test('all-order URL renders the merged list without preset filters', async () => {
  await assertPresetRoute('/orders', '全部订单', {});
});

test('legacy pending URL keeps NEED_REVIEW preset semantics', async () => {
  await assertPresetRoute('/orders/pending', '待处理', { processing_stage: 'NEED_REVIEW' });
});

test('legacy exceptions URL keeps FULFILLMENT_EXCEPTION preset semantics', async () => {
  await assertPresetRoute('/orders/exceptions', '异常订单', { order_status: 'FULFILLMENT_EXCEPTION' });
});

test('legacy tracking URL keeps SHIPPED preset semantics', async () => {
  await assertPresetRoute('/orders/tracking', '订单追踪', { order_status: 'SHIPPED' });
});

test('demo order page stays reachable while hidden from the sidebar menu', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/demo/v1/scenarios')) return jsonResponse([]);
    if (url.startsWith('/customer/v1/order-assistant/config')) {
      return jsonResponse({ service_ready: false, demo_mode: true });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute('/demo/order');

  await waitFor(() => assert.match(bodyText(), /演示环境说明/));
  assert.match(bodyText(), /智能提取服务尚未配置/);
  assert.match(headerText(), /模拟下单/);
  assert.doesNotMatch(sidebarMenuText(), /模拟下单/);
});
