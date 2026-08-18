import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let AdminApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let ConfigProvider: typeof import('antd')['ConfigProvider'];
let AntApp: typeof import('antd')['App'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/product/products',
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
      ConfigProvider,
      null,
      createElement(
        AntApp,
        null,
        createElement(
          MemoryRouter,
          {
            initialEntries: ['/product/products'],
            future: { v7_startTransition: true, v7_relativeSplatPath: true },
          },
          createElement(AdminApp),
        ),
      ),
    ));
  });
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  ({ MemoryRouter } = await import('react-router-dom'));
  ({ App: AntApp, ConfigProvider } = await import('antd'));

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

test('real product route presents the category name and code instead of an internal category id', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/categories?page=0&size=200') {
      return jsonResponse(page([{
        id: '9',
        code: 'MEAT/BEEF',
        name: '牛肉',
        active: true,
        version: 1,
      }]));
    }
    if (url === '/api/v1/products?page=0&size=10') {
      return jsonResponse(page([{
        id: '101',
        code: 'P-BEEF-001',
        name: '子牧牛腱',
        active: true,
        version: 2,
        attributes: { category_id: '9' },
      }]));
    }
    if (url === '/api/v1/products/tags') {
      return jsonResponse(['fresh']);
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute();

  await waitFor(() => assert.match(bodyText(), /子牧牛腱/));
  const row = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody .ant-table-row')]
    .find((candidate) => candidate.textContent?.includes('P-BEEF-001'));
  assert.ok(row, 'the product must be visible through the production route');
  // 商品之后是品类列；档案字段（主图/标签/毛利）不应破坏品类展示。
  const categoryCell = row.querySelectorAll<HTMLTableCellElement>('td')[1];
  assert.equal(categoryCell?.textContent?.trim(), '牛肉（MEAT/BEEF）');
  assert.deepEqual(new Set(requestedUrls), new Set([
    '/api/v1/categories?page=0&size=200',
    '/api/v1/products?page=0&size=10',
    '/api/v1/products/tags',
  ]));
});
