import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

/**
 * 展示层三原语（issue 17）：
 * PageShell / FilterBar / DataTable 的默认行为与结构断言。
 * 沿用既有 jsdom + Vite ssrLoadModule 挂载模式（见 manualReviewDraftRoute.test.ts），
 * 只挂载组件本身，不引入新的测试框架或渲染库。
 */

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

let PageShell: typeof import('../src/components/PageShell.tsx')['default'];
let FilterBar: typeof import('../src/components/FilterBar.tsx')['default'];
let DataTable: typeof import('../src/components/DataTable.tsx')['default'];
let DEFAULT_TABLE_SCROLL: typeof import('../src/components/DataTable.tsx')['DEFAULT_TABLE_SCROLL'];

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/fulfillment/shipments',
  });
  const browserGlobals = [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLBodyElement', 'HTMLInputElement', 'HTMLTextAreaElement',
    'SVGElement', 'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event',
    'MouseEvent', 'KeyboardEvent',
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
  Object.defineProperty(dom.window, 'scrollTo', { configurable: true, value() {} });
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function bodyText(): string {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

function findControl(text: string): HTMLElement {
  const element = [...document.querySelectorAll<HTMLElement>('button, a')]
    .find((candidate) => candidate.textContent?.includes(text));
  assert.ok(element, `missing control: ${text}`);
  return element;
}

async function mount(element: ReturnType<typeof createElement>) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(element);
  });
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  const { createServer } = await import('vite');
  vite = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  PageShell = (await vite.ssrLoadModule('/src/components/PageShell.tsx')).default;
  FilterBar = (await vite.ssrLoadModule('/src/components/FilterBar.tsx')).default;
  const dataTableModule = await vite.ssrLoadModule('/src/components/DataTable.tsx');
  DataTable = dataTableModule.default;
  DEFAULT_TABLE_SCROLL = dataTableModule.DEFAULT_TABLE_SCROLL;
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

test('DataTable 默认横向滚动收敛为固定宽度，页面不写 scroll 也有窄屏兜底', () => {
  assert.deepEqual(DEFAULT_TABLE_SCROLL, { x: 960 });
});

test('PageShell 渲染标题、说明、操作区，正文原样透传', async () => {
  await mount(createElement(
    PageShell,
    {
      title: '发货记录',
      description: '一次出库/发货批次，可包含同一订单下的多条订单行。',
      actions: createElement('button', null, '刷新'),
    },
    createElement('div', null, '正文内容'),
  ));

  const text = bodyText();
  assert.match(text, /发货记录/);
  assert.match(text, /一次出库\/发货批次，可包含同一订单下的多条订单行。/);
  assert.match(text, /刷新/);
  assert.match(text, /正文内容/);
  assert.ok(document.querySelector('.ant-card'), 'PageShell 头部应落在卡片内');
});

test('PageShell 不传 description / actions 时省略对应区块', async () => {
  await mount(createElement(PageShell, { title: '仅标题' }));
  assert.match(bodyText(), /仅标题/);
  assert.doesNotMatch(bodyText(), /刷新/);
});

test('FilterBar 渲染筛选控件与右对齐操作区', async () => {
  await mount(createElement(
    FilterBar,
    { actions: createElement('button', null, '查询') },
    createElement('label', null, '履约方'),
    createElement('label', null, '状态'),
  ));

  const text = bodyText();
  assert.match(text, /履约方/);
  assert.match(text, /状态/);
  assert.match(text, /查询/);
  assert.ok(document.querySelector('.ant-card'), 'FilterBar 应落在卡片内');
});

test('DataTable 渲染数据行', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [
      { title: '单号', dataIndex: 'no' },
      { title: '状态', dataIndex: 'status' },
    ],
    dataSource: [
      { id: '1', no: 'SH-1001', status: '已发货' },
      { id: '2', no: 'SH-1002', status: '已创建' },
    ],
  }));

  const text = bodyText();
  assert.match(text, /SH-1001/);
  assert.match(text, /SH-1002/);
  assert.match(text, /已发货/);
  assert.doesNotMatch(text, /暂无数据/);
});

test('DataTable 空数据默认渲染统一空态文案', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [],
  }));

  assert.match(bodyText(), /暂无数据/);
});

test('DataTable 空态文案可用 emptyText 覆盖', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [],
    emptyText: '当前筛选范围内暂无匹配记录',
  }));

  assert.match(bodyText(), /当前筛选范围内暂无匹配记录/);
  assert.doesNotMatch(bodyText(), /暂无数据/);
});

test('DataTable 错误态渲染错误提示与重试按钮，点击触发 onRetry', async () => {
  let retried = 0;
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [],
    error: new Error('boom'),
    errorTitle: 'Shipment 加载失败',
    onRetry: () => { retried += 1; },
  }));

  const text = bodyText();
  assert.match(text, /Shipment 加载失败/);
  // 通用兜底文案（issue 115）：未识别的异常也报告当前页面所在的 origin，
  // 让用户能自证——是不是还在指着内网地址、或者已经离开了能访问它的网络。
  // jsdom 挂载 URL 是 http://localhost/fulfillment/shipments，属于私网/本机地址。
  assert.match(text, /无法连接 http:\/\/localhost\/fulfillment\/shipments/);
  assert.match(text, /当前使用的是内网地址，离开该网络将无法访问/);
  assert.match(text, /重试/);

  await act(async () => {
    findControl('重试').click();
  });
  assert.equal(retried, 1);
});

test('DataTable 无错误时不渲染错误提示', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [],
  }));

  assert.doesNotMatch(bodyText(), /加载失败/);
  assert.doesNotMatch(bodyText(), /重试/);
});

test('DataTable loading 透传 antd Table 的加载遮罩', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [],
    loading: true,
  }));

  assert.ok(document.querySelector('.ant-spin-nested-loading'), 'loading 时应渲染 antd 加载遮罩');
});

test('DataTable 默认横向滚动作用于渲染出的表格（宽度 960 + overflow-x）', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [{ id: '1', no: 'SH-1' }],
  }));

  const content = document.querySelector<HTMLElement>('.ant-table-content');
  assert.ok(content, '应渲染可横向滚动的表格容器');
  assert.equal(content.style.overflowX, 'auto');
  const table = content.querySelector<HTMLElement>('table');
  assert.ok(table, '滚动容器内应有表格');
  assert.equal(table.style.width, '960px');
});

test('DataTable 显式 scroll 覆盖默认值', async () => {
  await mount(createElement(DataTable, {
    rowKey: 'id',
    columns: [{ title: '单号', dataIndex: 'no' }],
    dataSource: [{ id: '1', no: 'SH-1' }],
    scroll: { x: 1200 },
  }));

  const content = document.querySelector<HTMLElement>('.ant-table-content');
  assert.ok(content, '应渲染可横向滚动的表格容器');
  assert.equal(content.style.overflowX, 'auto');
  const table = content.querySelector<HTMLElement>('table');
  assert.ok(table, '滚动容器内应有表格');
  assert.equal(table.style.width, '1200px');
});
