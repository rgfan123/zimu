import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let ReviewApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/workbench/reviews',
  });
  const browserGlobals = [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLBodyElement', 'HTMLInputElement',
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

function page(items: unknown[]) {
  return {
    items,
    page: 0,
    size: 20,
    total_elements: items.length,
    total_pages: items.length ? 1 : 0,
  };
}

function reviewCase() {
  return {
    id: '99', case_no: 'RC-TRACKING-CANDIDATES-99', case_type: 'ORDER_OPS', responsible_team: 'ORDER_OPS',
    reason_code: 'WECOM_TRACKING_DRAFT', status: 'OPEN', subject_type: 'TRACKING_DRAFT', subject_id: '81',
    detail: {}, suggestions: [], allowed_actions: ['CONFIRM_TRACKING_DRAFT'], version: 7,
    created_at: '2026-08-14T01:00:00Z',
  };
}

const taskCandidates = [
  {
    task_id: '44', fulfillment_no: 'FUL-MASKED-A', order_id: '12', order_no: 'ORD-A',
    order_line_id: '31', shipment_id: '55', receiver_name: '张三',
    requested_quantity: '8.000', shipped_quantity: '0.000', instructed_quantity: '8.000',
  },
  {
    task_id: '45', fulfillment_no: 'FUL-MASKED-B', order_id: '13', order_no: 'ORD-B',
    order_line_id: '32', shipment_id: '56', receiver_name: '张四',
    requested_quantity: '6.000', shipped_quantity: '0.000', instructed_quantity: '6.000',
  },
];

const atomicTaskCandidates = [
  taskCandidates[0],
  { ...taskCandidates[1], order_id: '12', order_no: 'ORD-A', shipment_id: '55' },
];

function trackingDraft(kind: 'MULTI' | 'ZERO' | 'CONFIRMED' | 'ATOMIC' | 'PARTIAL') {
  const multi = kind === 'MULTI';
  const atomic = kind === 'ATOMIC';
  const partial = kind === 'PARTIAL';
  const file = atomic || partial;
  return {
    id: '81', draft_no: 'TD-CANDIDATES-81', submission_id: '18', line_no: 1,
    raw_receiver_name: multi ? '张*' : '裴*', masked_receiver_name: multi ? '张*' : '裴*',
    tracking_no: multi || file ? 'JDVA123456789' : 'XYZ123456789',
    carrier_code: kind === 'CONFIRMED' ? 'JDVA_EXPRESS' : file ? 'JD' : null,
    carrier_candidates: file ? [
      { code: 'JD', name: '京东物流', source: 'FILE' },
    ] : multi ? [
      { code: 'JD', name: '京东物流', source: 'PREFIX' },
      { code: 'JDVA_EXPRESS', name: '京东亚洲一号仓配', source: 'PREFIX' },
    ] : [],
    manual_carrier_options: [
      { code: 'JD', name: '京东物流' },
      { code: 'JDVA_EXPRESS', name: '京东亚洲一号仓配' },
      { code: 'SF_EXPRESS', name: '顺丰速运' },
    ],
    task_id: kind === 'CONFIRMED' ? '45' : file ? '44' : null,
    task_candidates: atomic ? atomicTaskCandidates : partial ? [taskCandidates[0]] : multi ? taskCandidates : [],
    source: file ? 'WECOM_TRACKING_FILE' : 'WECOM_MESSAGE',
    confirmation_scope: atomic ? 'ATOMIC_SHIPMENT' : 'SINGLE_TASK',
    shipment_judgment: partial ? 'PARTIAL' : 'FULL',
    default_full_shipment: !partial,
    actual_quantity: partial ? '3.000' : null,
    validation_issues: file ? [] : multi
      ? ['TASK_NAME_MULTI_MATCH', 'CARRIER_MULTI_HIT']
      : ['TASK_NAME_NO_MATCH', 'CARRIER_PREFIX_UNMATCHED'],
    status: kind === 'CONFIRMED' ? 'CONFIRMED' : 'OPEN', revision: kind === 'CONFIRMED' ? 5 : 4,
    confirmed_by: kind === 'CONFIRMED' ? 'ops-reviewer' : null,
    confirmed_at: kind === 'CONFIRMED' ? '2026-08-14T02:00:00Z' : null,
    review_case_id: kind === 'CONFIRMED' ? null : '99', review_case_version: kind === 'CONFIRMED' ? null : 7,
    created_at: '2026-08-14T01:00:00Z',
  };
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

function candidateSection(title: string) {
  const section = [...document.querySelectorAll<HTMLElement>('section')]
    .find((candidate) => candidate.textContent?.includes(title));
  assert.ok(section, `missing candidate section: ${title}`);
  return section;
}

async function chooseCandidate(sectionTitle: string, optionText: string) {
  const selector = candidateSection(sectionTitle).querySelector<HTMLElement>('.ant-select-selector');
  assert.ok(selector, `missing select for ${sectionTitle}`);
  await act(async () => {
    selector.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    selector.click();
  });
  await waitFor(() => {
    const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option-content')]
      .find((candidate) => candidate.textContent?.includes(optionText));
    assert.ok(option, `missing option: ${optionText}`);
  });
  const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option-content')]
    .find((candidate) => candidate.textContent?.includes(optionText));
  assert.ok(option);
  await act(async () => option.click());
}

async function fillExactTaskNumber(value: string) {
  const input = document.querySelector<HTMLInputElement>('input[placeholder="输入完整系统任务号"]');
  assert.ok(input, 'missing exact task number input');
  const setter = Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value')?.set;
  assert.ok(setter, 'missing native input value setter');
  await act(async () => {
    setter.call(input, value);
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
}

async function mountRoute() {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: ['/workbench/reviews'], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(ReviewApp),
    ));
  });
  await waitFor(() => assert.match(bodyText(), /RC-TRACKING-CANDIDATES-99/));
  await act(async () => control('查看处理').click());
  await waitFor(() => assert.match(bodyText(), /待人工确认的运单草稿/));
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
  ReviewApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

test('real review route lets an operator choose deterministic name and prefix candidates', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') return jsonResponse(page([reviewCase()]));
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(trackingDraft('MULTI'));
    if (url === '/api/v1/tracking-drafts/81/confirm' && init?.method === 'POST') {
      return jsonResponse(trackingDraft('CONFIRMED'));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  assert.match(bodyText(), /收货人匹配到多个待回传发货任务/);
  assert.match(bodyText(), /运单前缀匹配到多个物流公司/);
  assert.notEqual(control('确认并记录运单').getAttribute('disabled'), null);

  await chooseCandidate('发货任务', 'FUL-MASKED-B');
  await chooseCandidate('物流公司', '京东亚洲一号仓配');
  await waitFor(() => assert.equal(control('确认并记录运单').getAttribute('disabled'), null));
  await act(async () => control('确认并记录运单').click());
  await waitFor(() => assert.match(bodyText(), /运单草稿已确认并记录正式运单/));

  const request = requests.find(({ url, init }) => url.endsWith('/tracking-drafts/81/confirm') && init?.method === 'POST');
  assert.ok(request);
  const command = JSON.parse(String(request.init?.body));
  assert.equal(command.task_id, '45');
  assert.equal(command.task_no, null);
  assert.equal(command.carrier_code, 'JDVA_EXPRESS');
  assert.equal('shipped_at' in command, false);
});

test('real review route resolves zero matches with an exact task number and enabled Carrier option', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') return jsonResponse(page([reviewCase()]));
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(trackingDraft('ZERO'));
    if (url === '/api/v1/tracking-drafts/81/confirm' && init?.method === 'POST') {
      return jsonResponse(trackingDraft('CONFIRMED'));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  assert.match(bodyText(), /收货人未匹配到待回传发货任务/);
  assert.match(bodyText(), /运单前缀未匹配到已启用的物流公司/);
  assert.notEqual(control('确认并记录运单').getAttribute('disabled'), null);

  await fillExactTaskNumber('FUL-MANUAL-ZERO-001');
  await chooseCandidate('物流公司', '顺丰速运');
  await waitFor(() => assert.equal(control('确认并记录运单').getAttribute('disabled'), null));
  await act(async () => control('确认并记录运单').click());
  await waitFor(() => assert.match(bodyText(), /运单草稿已确认并记录正式运单/));

  const request = requests.find(({ url, init }) => url.endsWith('/tracking-drafts/81/confirm') && init?.method === 'POST');
  assert.ok(request);
  const command = JSON.parse(String(request.init?.body));
  assert.equal(command.task_id, null);
  assert.equal(command.task_no, 'FUL-MANUAL-ZERO-001');
  assert.equal(command.carrier_code, 'SF_EXPRESS');
  assert.equal('shipped_at' in command, false);
});

test('file atomic shipment shows every required task as one confirmation scope', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') return jsonResponse(page([reviewCase()]));
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(trackingDraft('ATOMIC'));
    if (url === '/api/v1/tracking-drafts/81/confirm' && init?.method === 'POST') {
      return jsonResponse(trackingDraft('CONFIRMED'));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  const text = bodyText();
  assert.match(text, /原子确认同一发货批次的 2 条明细/);
  assert.match(text, /FUL-MASKED-A/);
  assert.match(text, /FUL-MASKED-B/);
  assert.match(text, /这些不是互斥候选/);
  assert.match(text, /回传文件明确的物流公司/);
  assert.equal(document.querySelector('input[placeholder="输入完整系统任务号"]'), null);

  await act(async () => control('确认整个发货批次并记录运单').click());
  await waitFor(() => assert.match(bodyText(), /运单草稿已确认并记录正式运单/));
  const request = requests.find(({ url, init }) => url.endsWith('/tracking-drafts/81/confirm') && init?.method === 'POST');
  assert.ok(request);
  const command = JSON.parse(String(request.init?.body));
  assert.equal(command.task_id, '44');
  assert.equal(command.actual_quantity, null);
});

test('file partial shipment shows and submits the parsed actual quantity', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') return jsonResponse(page([reviewCase()]));
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url.includes('/api/v1/tracking-drafts?')) return jsonResponse({ items: [trackingDraft('PARTIAL')] });
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(trackingDraft('PARTIAL'));
    if (url === '/api/v1/tracking-drafts/81/confirm' && init?.method === 'POST') {
      return jsonResponse(trackingDraft('CONFIRMED'));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  assert.match(bodyText(), /回传文件标记为部分发货/);
  const quantity = [...document.querySelectorAll<HTMLInputElement>('input')]
    .find((input) => input.value === '3.000');
  assert.ok(quantity, '必须显示文件解析的实发数量');
  assert.equal(control('确认并记录运单').getAttribute('disabled'), null);
  await waitFor(() => {
    const batchButton = control('批量确认已勾选运单（0）');
    assert.notEqual(batchButton.getAttribute('disabled'), null);
  });

  await act(async () => control('确认并记录运单').click());
  await waitFor(() => assert.match(bodyText(), /运单草稿已确认并记录正式运单/));
  const request = requests.find(({ url, init }) => url.endsWith('/tracking-drafts/81/confirm') && init?.method === 'POST');
  assert.ok(request);
  const command = JSON.parse(String(request.init?.body));
  assert.equal(command.task_id, '44');
  assert.equal(command.actual_quantity, '3.000');
  assert.equal(requests.some(({ url }) => url.endsWith('/tracking-drafts/batch-confirm')), false);
});

test('confirming a full sibling keeps the current file partial review open', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const partial = trackingDraft('PARTIAL');
  const fullSibling = {
    ...trackingDraft('ATOMIC'),
    id: '82',
    draft_no: 'TD-CANDIDATES-82',
    line_no: 2,
    review_case_id: '100',
    review_case_version: 3,
  };
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') return jsonResponse(page([reviewCase()]));
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url.includes('/api/v1/tracking-drafts?')) return jsonResponse({ items: [partial, fullSibling] });
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(partial);
    if (url === '/api/v1/tracking-drafts/batch-confirm' && init?.method === 'POST') {
      return jsonResponse({
        results: [{ draft_id: '82', success: true, detail: { ...fullSibling, status: 'CONFIRMED' } }],
        success_count: 1,
        failure_count: 0,
      });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  await waitFor(() => assert.equal(control('批量确认已勾选运单（1）').getAttribute('disabled'), null));
  await act(async () => control('批量确认已勾选运单（1）').click());
  await waitFor(() => assert.match(bodyText(), /批量确认完成：成功 1 行/));

  assert.match(bodyText(), /回传文件标记为部分发货/);
  assert.ok([...document.querySelectorAll<HTMLInputElement>('input')]
    .some((input) => input.value === '3.000'), '当前 PARTIAL 的实发数量和抽屉必须保留');
  const batchRequest = requests.find(({ url, init }) => (
    url.endsWith('/tracking-drafts/batch-confirm') && init?.method === 'POST'
  ));
  assert.ok(batchRequest);
  const command = JSON.parse(String(batchRequest.init?.body));
  assert.deepEqual(command.lines.map((line: { draft_id: string }) => line.draft_id), ['82']);
});
