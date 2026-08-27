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
let productBundlesApi: typeof import('../src/api/endpoints.ts')['productBundlesApi'];
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

async function mountRoute(initialEntry = '/product/products') {
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
            initialEntries: [initialEntry],
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
  productBundlesApi = (await vite.ssrLoadModule('/src/api/endpoints.ts')).productBundlesApi;
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

test('product archive shows JD EMG and opens a new-product form without an existing-product selector', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/categories?page=0&size=200') {
      return jsonResponse(page([{ id: '9', code: 'MEAT/BEEF', name: '牛肉', active: true, version: 1 }]));
    }
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse([{ id: '11', provider_code: 'JD', provider_name: '京东仓', active: true, version: 1 }]);
    }
    if (url === '/api/v1/skus?page=0&size=10') {
      return jsonResponse(page([{
        id: '501',
        code: 'SKU-JD-000501',
        name: '子牧牛腱',
        active: true,
        version: 1,
        attributes: {
          category_id: '9',
          provider_id: '11',
          specification: '500g',
          unit: '袋',
          jd_emg_no: 'EMG4418691852262',
        },
      }]));
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/product/skus');
  await waitFor(() => assert.match(bodyText(), /EMG4418691852262/));
  assert.match(bodyText(), /京东EMG编号/);
  assert.equal(requestedUrls.some((url) => url.startsWith('/api/v1/products?')), false);

  const createButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === '新建');
  assert.ok(createButton);
  await act(async () => createButton.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  await waitFor(() => assert.match(bodyText(), /商品编码/));

  const labels = [...document.querySelectorAll<HTMLElement>('.ant-modal .ant-form-item-label')]
    .map((label) => label.textContent?.trim());
  assert.ok(labels.includes('商品编码'));
  assert.ok(labels.includes('商品名称'));
  assert.equal(labels.includes('商品'), false);
});

test('static bundle route lists static bundles and expands the component list inline on click', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/product-bundles?page=0&size=20') {
      return jsonResponse({
        items: [{
          id: '41',
          code: 'BUNDLE-NEW-YEAR',
          name: '新年牛羊肉礼包',
          active: false,
          version: 2,
          attributes: {
            barcode: '9250000000041',
            status: 'DRAFT',
            items: [
              { sku_id: '501', sku_code: 'SKU-BEEF', product_name: '牛腩块', specification: '500g', unit: '袋', quantity_per_bundle: '2' },
              { sku_id: '502', sku_code: 'SKU-LAMB', product_name: '羊蝎子', specification: '500g', unit: '袋', quantity_per_bundle: '1' },
            ],
          },
        }],
        page: 0,
        size: 20,
        total_elements: 1,
        total_pages: 1,
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/product/bundles');
  await waitFor(() => assert.match(bodyText(), /新年牛羊肉礼包/));
  assert.match(bodyText(), /BUNDLE-NEW-YEAR/);
  assert.match(bodyText(), /2 个组件/);
  // 文案口径：静态礼包 / 组件清单，不得混同 CustomBundle 的模糊叫法。
  assert.doesNotMatch(bodyText(), /内配|固定礼包|礼包管理/);
  // 展开前不应该已经把组件明细渲染出来。
  assert.equal(document.querySelector('.ant-table-expanded-row'), null);

  const inspectButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('组件清单'));
  assert.ok(inspectButton, 'bundle row must expose its component list');
  await act(async () => inspectButton.dispatchEvent(new MouseEvent('click', { bubbles: true })));

  // 就地展开：明细出现在表格自身的展开行里，不跳二级抽屉/卡片。
  await waitFor(() => assert.match(bodyText(), /牛腩块/));
  const expandedRow = document.querySelector<HTMLElement>('.ant-table-expanded-row');
  assert.ok(expandedRow, 'component detail must render as an inline expanded table row');
  assert.notEqual(expandedRow.style.display, 'none', 'expanded row must be visible after clicking the toggle');
  assert.equal(document.querySelector('.ant-drawer-open'), null, 'must not open a secondary drawer for component details');
  assert.match(bodyText(), /SKU-BEEF/);
  assert.match(bodyText(), /× 2 袋/);
  assert.match(bodyText(), /羊蝎子/);
  assert.deepEqual(requestedUrls, ['/api/v1/product-bundles?page=0&size=20']);

  // 再次点击收起：rc-table 保留展开行节点、以 display:none 隐藏（避免重挂载明细），
  // 而不是移出 DOM；全程不应发起任何新请求（明细已随列表一并取回）。
  await act(async () => inspectButton.dispatchEvent(new MouseEvent('click', { bubbles: true })));
  await waitFor(() => assert.equal(
    document.querySelector<HTMLElement>('.ant-table-expanded-row')?.style.display,
    'none',
  ));
  assert.deepEqual(requestedUrls, ['/api/v1/product-bundles?page=0&size=20']);
});

test('static bundle list sorts rows by name for a stable order regardless of backend id order', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/product-bundles?page=0&size=20') {
      // 后端按 id 升序返回；id 9 的礼包名称在字母序上应排在 id 2 之前，
      // 用来断言前端确实按名称重排，而不是照抄后端的插入序。
      return jsonResponse({
        items: [
          {
            id: '9',
            code: 'BUNDLE-9250909000005',
            name: '子牧原切牛肉礼包3100g',
            active: true,
            version: 1,
            attributes: { barcode: null, status: 'ACTIVE', items: [] },
          },
          {
            id: '2',
            code: '万齐-牛羊精选礼包-6000g',
            name: '2026原切精品牛羊礼包5800g',
            active: true,
            version: 1,
            attributes: { barcode: null, status: 'ACTIVE', items: [] },
          },
        ],
        page: 0,
        size: 20,
        total_elements: 2,
        total_pages: 1,
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/product/bundles');
  await waitFor(() => assert.match(bodyText(), /子牧原切牛肉礼包3100g/));

  const rowNames = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody > tr.ant-table-row')]
    .map((row) => row.querySelector('.product-identity__name')?.textContent ?? '');
  assert.deepEqual(rowNames, ['2026原切精品牛羊礼包5800g', '子牧原切牛肉礼包3100g']);
});

test('static bundle status tags visually distinguish draft, active, and inactive', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/product-bundles?page=0&size=20') {
      const record = (id: string, name: string, status: 'DRAFT' | 'ACTIVE' | 'INACTIVE') => ({
        id,
        code: `BUNDLE-${id}`,
        name,
        active: status === 'ACTIVE',
        version: 1,
        attributes: { barcode: null, status, items: [] },
      });
      return jsonResponse({
        items: [
          record('1', '草稿礼包', 'DRAFT'),
          record('2', '在售礼包', 'ACTIVE'),
          record('3', '下架礼包', 'INACTIVE'),
        ],
        page: 0,
        size: 20,
        total_elements: 3,
        total_pages: 1,
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/product/bundles');
  await waitFor(() => assert.match(bodyText(), /在售礼包/));

  const tagColorOf = (label: string) => {
    const tag = [...document.querySelectorAll<HTMLElement>('.ant-table-tbody .ant-tag')]
      .find((candidate) => candidate.textContent?.trim() === label);
    assert.ok(tag, `missing status tag for ${label}`);
    return [...tag.classList].find((cls) => cls.startsWith('ant-tag-'));
  };

  const draftColor = tagColorOf('草稿');
  const activeColor = tagColorOf('启用');
  const inactiveColor = tagColorOf('下架');
  assert.equal(activeColor, 'ant-tag-success');
  assert.equal(draftColor, 'ant-tag-warning');
  assert.equal(inactiveColor, 'ant-tag-default');
  // 草稿与下架此前同为默认灰色、无法区分，现在必须各自使用不同的语义色。
  assert.notEqual(draftColor, inactiveColor);
});

test('static bundle create keeps the component list and uses the trusted write boundary', async () => {
  let capturedUrl = '';
  let capturedInit: RequestInit | undefined;
  globalThis.fetch = async (input, init) => {
    capturedUrl = String(input);
    capturedInit = init;
    return jsonResponse({
      id: '42',
      code: 'BUNDLE-SPRING',
      name: '春日礼包',
      active: false,
      version: 0,
      attributes: { status: 'DRAFT', items: [] },
    }, 201);
  };

  await productBundlesApi.create({
    bundle_code: 'BUNDLE-SPRING',
    bundle_name: '春日礼包',
    barcode: '9250000000042',
    description: '春季固定清单',
    status: 'DRAFT',
    items: [
      { sku_id: '501', quantity_per_bundle: '2', emg_code_snapshot: 'EMG-501' },
      { sku_id: '502', quantity_per_bundle: '1' },
    ],
  });

  assert.equal(capturedUrl, '/api/v1/product-bundles');
  assert.equal(capturedInit?.method, 'POST');
  assert.deepEqual(JSON.parse(String(capturedInit?.body)), {
    bundle_code: 'BUNDLE-SPRING',
    bundle_name: '春日礼包',
    barcode: '9250000000042',
    description: '春季固定清单',
    status: 'DRAFT',
    items: [
      { sku_id: '501', quantity_per_bundle: '2', emg_code_snapshot: 'EMG-501' },
      { sku_id: '502', quantity_per_bundle: '1' },
    ],
  });
  const headers = capturedInit?.headers as Record<string, string>;
  assert.ok(headers['Idempotency-Key']);
  assert.equal(headers['X-Operator'], undefined);
});

test('static bundle create form loads every internal SKU page so later components are selectable', async () => {
  const requestedUrls: string[] = [];
  const skuPage = (page: number, size: number, totalPages: number, totalElements: number, items: unknown[]) => ({
    items,
    page,
    size,
    total_elements: totalElements,
    total_pages: totalPages,
  });
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url === '/api/v1/product-bundles?page=0&size=20') {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/skus?page=0&size=200') {
      const items = Array.from({ length: 200 }, (_, index) => ({
        id: String(501 + index),
        code: `SKU-${String(501 + index)}`,
        name: `牛腩块 ${index + 1}`,
        active: true,
        version: 1,
        attributes: { product_id: '1', provider_id: '2', specification: '500g', unit: '袋', purchase_price: null, retail_price: null },
      }));
      return jsonResponse(skuPage(0, 200, 2, 201, items));
    }
    if (url === '/api/v1/skus?page=1&size=200') {
      return jsonResponse(skuPage(1, 200, 2, 201, [{
        id: '701',
        code: 'SKU-701',
        name: '羊腿肉 201',
        active: true,
        version: 1,
        attributes: { product_id: '1', provider_id: '2', specification: '500g', unit: '袋', purchase_price: null, retail_price: null },
      }]));
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await mountRoute('/product/bundles');
  await waitFor(() => assert.match(bodyText(), /暂无静态礼包/));
  const createButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('新建静态礼包'));
  assert.ok(createButton, 'bundle page must expose creation');
  await act(async () => createButton.dispatchEvent(new MouseEvent('click', { bubbles: true })));

  await waitFor(() => assert.match(bodyText(), /静态礼包至少需要一个组件/));
  assert.match(bodyText(), /静态礼包编码/);
  assert.match(bodyText(), /静态礼包名称/);
  assert.match(bodyText(), /选择内部 SKU/);
  assert.ok(document.querySelector('input[placeholder="单份用量"]'));
  assert.match(bodyText(), /添加组件/);
  // 回归：组件 SKU 选择跨页取全，必须请求第 1 页之后的内部 SKU。
  assert.deepEqual(requestedUrls, [
    '/api/v1/product-bundles?page=0&size=20',
    '/api/v1/skus?page=0&size=200',
    '/api/v1/skus?page=1&size=200',
  ]);

  // 第 201 个内部 SKU（位于第 2 页）必须真实可选中：搜索后仍能命中该选项。
  const skuSelector = [...document.querySelectorAll<HTMLElement>('.ant-select-selector')]
    .find((candidate) => candidate.textContent?.includes('选择内部 SKU'));
  assert.ok(skuSelector, 'missing internal SKU selector');
  await act(async () => {
    skuSelector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    skuSelector.click();
  });
  const skuSearchInput = skuSelector.querySelector<HTMLInputElement>('.ant-select-selection-search-input');
  assert.ok(skuSearchInput, 'missing internal SKU search input');
  const setter = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value')?.set;
  assert.ok(setter, 'missing native input value setter');
  await act(async () => {
    setter.call(skuSearchInput, '羊腿肉 201');
    skuSearchInput.dispatchEvent(new Event('input', { bubbles: true }));
  });
  await waitFor(() => {
    const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option-content')]
      .find((candidate) => candidate.textContent?.includes('羊腿肉 201'));
    assert.ok(option, 'later-page SKU must be selectable');
  });
});
