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
    url: 'http://localhost/fulfillment/sales-outbound',
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

function control(text: string) {
  const element = [...document.querySelectorAll<HTMLElement>('button, a')]
    .find((candidate) => candidate.textContent?.includes(text));
  assert.ok(element, `missing control: ${text}`);
  return element;
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
  const { message, notification } = await import('antd');
  await act(async () => {
    message.destroy();
    notification.destroy();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
});

after(async () => {
  await vite.close();
  dom.window.close();
});

test('successful source import shows accepted row details before whole-batch confirmation', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/import-batches/source-orders' && init?.method === 'POST') {
      return jsonResponse({
        id: '7', batch_no: 'IMP-CSX-DETAIL-7', batch_type: 'SOURCE_ORDER', import_mode: 'NEW',
        revision_no: 1, source_channel: 'CAISHIXIAN', template_family: 'CSX_ORDER', template_version: '1',
        template_fingerprint: 'fixture', original_file_name: 'caishixian.xlsx', content_sha256: 'a'.repeat(64),
        status: 'COMPLETED', confirmed_at: null,
        row_counts: { total: 1, accepted: 1, need_review: 0, rejected: 0 },
        generated_fulfillment_export_ids: [], received_at: '2026-08-14T06:00:00Z',
      }, 201);
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse({
        items: [{
          id: '71', sheet_name: '待发货明细', sheet_index: 0, row_index: 2,
          raw_cells: { '商品编号': '2047705', '商品名称': '子牧牛腱子500g*2' },
          source_order_ref: 'CSX-ORDER-001', status: 'ACCEPTED', error_code: null, error_detail: {},
          order_id: '101', order_line_id: '201',
        }],
        page: 0, size: 200, total_elements: 1, total_pages: 1,
      });
    }
    if (url === '/api/v1/import-batches/7/confirm' && init?.method === 'POST') {
      return jsonResponse({
        id: '7', batch_no: 'IMP-CSX-DETAIL-7', batch_type: 'SOURCE_ORDER', import_mode: 'NEW',
        revision_no: 1, source_channel: 'CAISHIXIAN', template_family: 'CSX_ORDER', template_version: '1',
        template_fingerprint: 'fixture', original_file_name: 'caishixian.xlsx', content_sha256: 'a'.repeat(64),
        status: 'COMPLETED', confirmed_at: '2026-08-14T07:00:00Z', confirmed_by: 'tester',
        row_counts: { total: 1, accepted: 1, need_review: 0, rejected: 0 },
        generated_fulfillment_export_ids: ['91'], received_at: '2026-08-14T06:00:00Z',
      });
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: ['/fulfillment/sales-outbound'], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(App),
    ));
  });
  await waitFor(() => assert.match(bodyText(), /来源订单导入/));

  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx,.csv"]');
  assert.ok(fileInput, 'missing source import file input');
  const file = new File(['fixture'], 'caishixian.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] });
  await act(async () => fileInput.dispatchEvent(new Event('change', { bubbles: true })));
  await waitFor(() => assert.match(bodyText(), /caishixian\.xlsx/));
  await act(async () => control('开始导入').click());

  await waitFor(() => assert.match(bodyText(), /IMP-CSX-DETAIL-7/));
  assert.match(bodyText(), /确认明细/);
  assert.match(bodyText(), /将确认/);
  assert.match(bodyText(), /2047705/);
  assert.match(bodyText(), /子牧牛腱子500g\*2/);
  assert.ok(requests.includes('GET /api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED'));
  assert.ok(control('确认本批次（已接收 1 行）'));
});

test('confirming the batch passes a popconfirm and marks accepted rows as confirmed', async () => {
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/import-batches/source-orders' && init?.method === 'POST') {
      return jsonResponse({
        id: '7', batch_no: 'IMP-CSX-DETAIL-7', batch_type: 'SOURCE_ORDER', import_mode: 'NEW',
        revision_no: 1, source_channel: 'CAISHIXIAN', template_family: 'CSX_ORDER', template_version: '1',
        template_fingerprint: 'fixture', original_file_name: 'caishixian.xlsx', content_sha256: 'a'.repeat(64),
        status: 'COMPLETED', confirmed_at: null,
        row_counts: { total: 1, accepted: 1, need_review: 0, rejected: 0 },
        generated_fulfillment_export_ids: [], received_at: '2026-08-14T06:00:00Z',
      }, 201);
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse({
        items: [{
          id: '71', sheet_name: '待发货明细', sheet_index: 0, row_index: 2,
          raw_cells: { '商品编号': '2047705', '商品名称': '子牧牛腱子500g*2' },
          source_order_ref: 'CSX-ORDER-001', status: 'ACCEPTED', error_code: null, error_detail: {},
          order_id: '101', order_line_id: '201',
        }],
        page: 0, size: 200, total_elements: 1, total_pages: 1,
      });
    }
    if (url === '/api/v1/import-batches/7/confirm' && init?.method === 'POST') {
      return jsonResponse({
        id: '7', batch_no: 'IMP-CSX-DETAIL-7', batch_type: 'SOURCE_ORDER', import_mode: 'NEW',
        revision_no: 1, source_channel: 'CAISHIXIAN', template_family: 'CSX_ORDER', template_version: '1',
        template_fingerprint: 'fixture', original_file_name: 'caishixian.xlsx', content_sha256: 'a'.repeat(64),
        status: 'COMPLETED', confirmed_at: '2026-08-14T07:00:00Z', confirmed_by: 'tester',
        row_counts: { total: 1, accepted: 1, need_review: 0, rejected: 0 },
        generated_fulfillment_export_ids: ['91'], received_at: '2026-08-14T06:00:00Z',
      });
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: ['/fulfillment/sales-outbound'], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(App),
    ));
  });
  await waitFor(() => assert.match(bodyText(), /来源订单导入/));

  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx,.csv"]');
  assert.ok(fileInput, 'missing source import file input');
  const file = new File(['fixture'], 'caishixian.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] });
  await act(async () => fileInput.dispatchEvent(new Event('change', { bubbles: true })));
  await waitFor(() => assert.match(bodyText(), /caishixian\.xlsx/));
  await act(async () => control('开始导入').click());

  await waitFor(() => assert.match(bodyText(), /确认本批次（已接收 1 行）/));
  assert.match(bodyText(), /形成履约承诺/);

  await act(async () => control('确认本批次（已接收 1 行）').click());
  await waitFor(() => assert.match(document.body.textContent ?? '', /确认后已接收的 1 行将写入系统订单并生成履约文件/));
  const popconfirmOk = [...document.querySelectorAll<HTMLElement>('.ant-popconfirm-buttons button')]
    .find((candidate) => candidate.textContent?.includes('确认本批次'));
  assert.ok(popconfirmOk, 'missing popconfirm ok button');
  await act(async () => popconfirmOk.click());

  await waitFor(() => assert.match(bodyText(), /批次已确认，生成履约文件 1 份/));
  assert.match(bodyText(), /已确认/);
  assert.ok(control('查看系统订单'));
});

test('tracking return requires a popconfirm and lists per-row failure reasons', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url === '/api/v1/fulfillment-exports/9/tracking-imports' && init?.method === 'POST') {
      return jsonResponse({
        id: '50', batch_no: 'TRK-50', batch_type: 'PROVIDER_TRACKING', import_mode: 'NEW',
        revision_no: 1, source_channel: 'CAISHIXIAN', template_family: 'THIRD_PARTY_TRACKING',
        template_version: 'v1-24-columns', template_fingerprint: 'fixture',
        original_file_name: 'return.xlsx', content_sha256: 'b'.repeat(64), status: 'COMPLETED',
        confirmed_at: '2026-08-14T08:00:00Z',
        row_counts: { total: 2, accepted: 2, need_review: 0, rejected: 0 },
        generated_fulfillment_export_ids: [], received_at: '2026-08-14T07:00:00Z',
        business_results: { shipped: 1, partial: 0, failed: 1 },
        generated_source_return_export_ids: ['61'],
        rows: [
          {
            id: '501', sheet_name: '发货清单', sheet_index: 0, row_index: 2,
            raw_cells: { '结果': 'SHIPPED', '实际发货数量': '3', '快递公司': '顺丰', '物流单号': 'SF001', '异常原因': '' },
            source_order_ref: 'OUT-501', status: 'ACCEPTED', order_id: '101', order_line_id: '201',
          },
          {
            id: '502', sheet_name: '发货清单', sheet_index: 0, row_index: 3,
            raw_cells: { '结果': 'FAILED', '实际发货数量': '0', '快递公司': '', '物流单号': '', '异常原因': '客户拒收' },
            source_order_ref: 'OUT-502', status: 'ACCEPTED', order_id: '102', order_line_id: '202',
          },
        ],
      }, 201);
    }
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({
        items: [{
          id: '9', export_batch_no: 'EXP-9', provider_id: '1', export_kind: 'THIRD_PARTY',
          template_version: 'v1-24-columns', generated_at: '2026-08-14T06:00:00Z',
          tracking_due_at: '2026-08-16T06:00:00Z', usage_status: 'DOWNLOADED_WAITING_RETURN',
          download_audit: { download_count: 1 }, tracking_import_batch_id: null,
        }],
        page: 0, size: 10, total_elements: 1, total_pages: 1,
      });
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: ['/fulfillment/sales-outbound'], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(App),
    ));
  });
  await waitFor(() => assert.match(bodyText(), /回传/));
  await act(async () => control('回传').click());
  await waitFor(() => assert.match(bodyText(), /回传履约结果/));
  assert.match(bodyText(), /校验并接收/);

  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx"]');
  assert.ok(fileInput, 'missing tracking return file input');
  const file = new File(['fixture'], 'return.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] });
  await act(async () => fileInput.dispatchEvent(new Event('change', { bubbles: true })));
  await waitFor(() => assert.match(bodyText(), /return\.xlsx/));

  await act(async () => control('校验并接收').click());
  await waitFor(() => assert.match(document.body.textContent ?? '', /确认校验并接收本批回传结果/));
  const popconfirmOk = [...document.querySelectorAll<HTMLElement>('.ant-popconfirm-buttons button')]
    .find((candidate) => candidate.textContent?.includes('校验并接收'));
  assert.ok(popconfirmOk, 'missing tracking popconfirm ok button');
  await act(async () => popconfirmOk.click());

  await waitFor(() => assert.match(bodyText(), /客户拒收/));
  assert.match(bodyText(), /已发货/);
  assert.match(bodyText(), /部分发货/);
  assert.match(bodyText(), /OUT-501/);
  assert.match(bodyText(), /OUT-502/);
  assert.ok(requests.includes('POST /api/v1/fulfillment-exports/9/tracking-imports'));
});
