import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import { isShellBaselineRequest, shellBaselineResponse, withShellRequests } from './routeHarness.ts';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let AdminApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/product/sku-mappings',
  });
  const browserGlobals = [
    'window',
    'document',
    'navigator',
    'HTMLElement',
    'HTMLBodyElement',
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
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function page(items: unknown[]) {
  return {
    items,
    page: 0,
    size: items.length || 10,
    total_elements: items.length,
    total_pages: items.length ? 1 : 0,
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
        initialEntries: ['/product/sku-mappings'],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(AdminApp),
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
  AdminApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

test('real SKU mapping route renders the page heading, matrix rows and auxiliary panels', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/skus?page=0&size=200') {
      return jsonResponse(page([
        { id: '501', code: 'SKU-BEEF-01', name: '子牧牛腱', active: true, version: 1, attributes: { specification: '500g*2袋', unit: '袋' } },
        { id: '502', code: 'SKU-LAMB-01', name: '子牧羊腿', active: true, version: 1, attributes: { specification: '1kg', unit: '袋' } },
      ]));
    }
    if (url === '/api/v1/source-sku-mappings?page=0&size=200') {
      return jsonResponse(page([
        { id: '601', code: 'FX-001', name: '飞象牛腱', active: true, version: 1, attributes: { source_channel: 'FEIXIANG', sku_id: '501', source_sku_ref: 'FX-001', quantity_multiplier: '2.000' } },
        { id: '602', code: 'JFB-007', name: '聚福宝羊腿', active: true, version: 1, attributes: { source_channel: 'JUFUBAO', sku_id: '502', source_sku_ref: 'JFB-007', quantity_multiplier: '1.000' } },
      ]));
    }
    if (url === '/api/v1/provider-sku-mappings/jd-pieces-candidates') {
      return jsonResponse([]);
    }
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute();
  await waitFor(() => assert.match(bodyText(), /子牧牛腱/));

  // 页头（标题 + 说明 + 主数据标识）随页面数据一起呈现。
  assert.match(bodyText(), /SKU 映射矩阵/);
  assert.match(bodyText(), /以内部 SKU 为主键，横向查看各来源渠道的平台商品映射。/);
  assert.match(bodyText(), /主数据/);
  // 矩阵行 = 内部 SKU；映射单元格展示来源商品与包装换算。
  assert.match(bodyText(), /SKU-BEEF-01/);
  assert.match(bodyText(), /飞象牛腱/);
  assert.match(bodyText(), /FX-001/);
  assert.match(bodyText(), /数量乘数 2\.000/);
  assert.match(bodyText(), /2 个内部 SKU · 显示 6 个平台/);
  // 两个辅助核对面板与页脚口径说明。
  assert.match(bodyText(), /使用文件辅助核对/);
  assert.match(bodyText(), /京东件数换算/);
  assert.match(
    bodyText(),
    /数量乘数用于把平台商品数量换算为内部 SKU 数量。各渠道均只展示有证据的显式映射，未映射时不会自动猜测。/,
  );
  // 只请求矩阵与件数候选数据；预览面板在用户上传前不发请求。
  // 末尾那次是外壳基线请求：AppLayout 每次挂载都要读业务模块开放清单来过滤导航树（票 03），
  // 与本页无关，按 React 先子后父的 effect 顺序排在页面挂载期请求之后。
  assert.deepEqual(requestedUrls, withShellRequests(
    '/api/v1/skus?page=0&size=200',
    '/api/v1/source-sku-mappings?page=0&size=200',
    '/api/v1/provider-sku-mappings/jd-pieces-candidates',
  ));
});

test('SKU mapping route renders the empty matrix state without claiming implicit mappings', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/skus?page=0&size=200') return jsonResponse(page([]));
    if (url === '/api/v1/source-sku-mappings?page=0&size=200') return jsonResponse(page([]));
    if (url === '/api/v1/provider-sku-mappings/jd-pieces-candidates') return jsonResponse([]);
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute();
  await waitFor(() => assert.match(bodyText(), /暂无内部 SKU/));
  assert.match(bodyText(), /0 个内部 SKU · 显示 6 个平台/);
  // 空矩阵仍展示页头与口径说明，未映射不猜测。
  assert.match(bodyText(), /SKU 映射矩阵/);
  assert.doesNotMatch(bodyText(), /未映射 · 添加/);
});
