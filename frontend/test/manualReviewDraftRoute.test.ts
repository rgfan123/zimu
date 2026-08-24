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
let useLocation: typeof import('react-router-dom')['useLocation'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let Fragment: typeof import('react')['Fragment'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/workbench/reviews',
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

function bodyText(): string {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

function locationText(): string {
  return document.querySelector('[data-testid="route-location"]')?.textContent ?? '';
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
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
  throw lastError;
}

function LocationProbe() {
  const location = useLocation();
  return createElement('output', { 'data-testid': 'route-location', hidden: true }, location.pathname);
}

async function mountRoute() {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      {
        initialEntries: ['/workbench/reviews'],
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(
        Fragment,
        null,
        createElement(ReviewApp),
        createElement(LocationProbe),
      ),
    ));
  });
}

function reviewCase(kind: 'ORDER_DRAFT' | 'TRACKING_DRAFT') {
  const orderDraft = kind === 'ORDER_DRAFT';
  return {
    id: '99',
    case_no: orderDraft ? 'RC-ORDER-99' : 'RC-TRACKING-99',
    case_type: 'ORDER_OPS',
    responsible_team: 'ORDER_OPS',
    reason_code: orderDraft ? 'WECOM_ORDER_DRAFT' : 'WECOM_TRACKING_DRAFT',
    status: 'OPEN',
    subject_type: kind,
    subject_id: orderDraft ? '27' : '81',
    detail: {},
    suggestions: [],
    allowed_actions: orderDraft
      ? ['CONFIRM_ORDER_DRAFT', 'REJECT_ORDER_DRAFT']
      : ['CONFIRM_TRACKING_DRAFT'],
    version: 7,
    created_at: '2026-08-14T01:00:00Z',
  };
}

function orderDraft(status: 'OPEN' | 'CONFIRMED' = 'OPEN') {
  return {
    id: '27',
    draft_no: 'OD-18-1',
    source_order_no: 'WECOM-SUB-18-1',
    submission_id: '18',
    status,
    revision: status === 'OPEN' ? 4 : 5,
    customer_candidates: [{
      customer_id: '12',
      customer_code: 'CUST-WECOM-0001',
      customer_name: '子牧测试客户',
      matched_by: 'deterministic-mapping',
    }],
    customer_name_raw: '子牧测试客户原始称呼',
    receiver_name: '张三',
    receiver_phone: '13800000000',
    receiver_address: '上海市浦东新区测试路 1 号',
    settlement_method: 'MONTHLY',
    missing_fields: [],
    lines: [{
      id: '31',
      line_no: 1,
      sku_candidates: [{ sku_id: '44', sku_code: 'SKU-44', product_name: '子牧羊小腿' }],
      product_name_raw: '羊小腿原始描述',
      spec_raw: '500g/盒',
      unit_raw: '盒',
      quantity: '2',
    }],
    review_case_id: status === 'OPEN' ? '99' : null,
    review_case_version: status === 'OPEN' ? 7 : null,
    confirmed_order_id: status === 'CONFIRMED' ? '500' : null,
    confirmed_by: status === 'CONFIRMED' ? 'ops-reviewer' : null,
    confirmed_at: status === 'CONFIRMED' ? '2026-08-14T02:00:00Z' : null,
    created_at: '2026-08-14T01:00:00Z',
    updated_at: '2026-08-14T01:00:00Z',
  };
}

function trackingDraft(status: 'OPEN' | 'CONFIRMED' = 'OPEN') {
  return {
    id: '81',
    draft_no: 'TD-18-1',
    submission_id: '18',
    line_no: 1,
    raw_receiver_name: '张三',
    masked_receiver_name: '张*',
    tracking_no: 'SF1234567890',
    carrier_code: 'SF_EXPRESS',
    carrier_candidates: [{ code: 'SF_EXPRESS', name: '顺丰速运', source: 'STATED' }],
    manual_carrier_options: [{ code: 'SF_EXPRESS', name: '顺丰速运' }],
    task_id: '44',
    task_candidates: [{
      task_id: '44',
      fulfillment_no: 'FUL-20260813-0044',
      order_id: '12',
      order_no: 'ORD-20260813-0012',
      order_line_id: '31',
      shipment_id: '55',
      receiver_name: '张三',
      requested_quantity: '8.000',
      shipped_quantity: '0.000',
      instructed_quantity: '8.000',
    }],
    source: 'WECOM_MESSAGE',
    confirmation_scope: 'SINGLE_TASK',
    shipment_judgment: 'FULL',
    default_full_shipment: true,
    actual_quantity: null,
    validation_issues: [],
    status,
    revision: status === 'OPEN' ? 4 : 5,
    confirmed_by: status === 'CONFIRMED' ? 'ops-reviewer' : null,
    confirmed_at: status === 'CONFIRMED' ? '2026-08-14T02:00:00Z' : null,
    review_case_id: status === 'OPEN' ? '99' : null,
    review_case_version: status === 'OPEN' ? 7 : null,
    created_at: '2026-08-14T01:00:00Z',
  };
}

function findControl(text: string): HTMLElement {
  const element = [...document.querySelectorAll<HTMLElement>('button, a')]
    .find((candidate) => candidate.textContent?.includes(text));
  assert.ok(element, `missing control: ${text}`);
  return element;
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement, Fragment } = await import('react'));
  ({ MemoryRouter, useLocation } = await import('react-router-dom'));
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

test('real review route loads the original WeCom evidence and confirms an order draft through the public command', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') {
      return jsonResponse(page([reviewCase('ORDER_DRAFT')]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/order-drafts/27' && (init?.method ?? 'GET') === 'GET') return jsonResponse(orderDraft());
    if (url === '/api/v1/message-submissions/18') {
      return jsonResponse({
        id: '18', submission_no: 'SUB-18', status: 'DRAFTED', source_message_id: '9',
        current_intent: 'CUSTOMER_ORDER', latest_error: null, interpretations: [], latest_task: null,
        created_at: '2026-08-14T01:00:00Z',
      });
    }
    if (url === '/api/v1/channel-messages/9') {
      return jsonResponse({
        id: '9', corp_id: 'corp', connection_id: 'business-relay', bot_id: 'bot', message_id: 'msg-9',
        chat_id: 'group-1', chat_type: 'group', sender_user_id: 'customer-1', message_type: 'text',
        content: '张三收，上海测试路1号，羊小腿两盒，月底结算', quote_content: null,
        raw_payload_ref: 'channel-message:9', received_at: '2026-08-14T01:00:00Z', submission_id: '18',
      });
    }
    if (url.startsWith('/api/v1/customers?')) {
      return jsonResponse({ ...page([{ id: '12', code: 'CUST-12', name: '子牧测试客户', active: true, version: 0 }]), size: 50 });
    }
    if (url.startsWith('/api/v1/skus?')) {
      return jsonResponse({ ...page([{ id: '44', code: 'SKU-44', name: '子牧羊小腿', active: true, version: 0 }]), size: 50 });
    }
    if (url === '/api/v1/order-drafts/27/confirm' && init?.method === 'POST') {
      return jsonResponse(orderDraft('CONFIRMED'));
    }
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    if (url === '/api/v1/orders/500/timeline' || url === '/api/v1/orders/500/shipments') return jsonResponse([]);
    if (url === '/api/v1/orders/500') {
      return jsonResponse({
        id: '500', order_no: 'ORD-500', source_channel: 'WECOM', order_status: 'CONFIRMED',
        fulfillment_status: 'PENDING', receiver: { name: '张三', phone: '13800000000', address: '上海测试路1号' },
        settlement: { method: 'MONTHLY', settlement_time: '2026-08-31T16:00:00Z' }, lines: [],
        review_cases: [], note: '', created_at: '2026-08-14T02:00:00Z', updated_at: '2026-08-14T02:00:00Z',
      });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  await waitFor(() => assert.match(bodyText(), /RC-ORDER-99/));
  await act(async () => findControl('查看处理').click());
  await waitFor(() => {
    assert.match(bodyText(), /原始企微消息证据/);
    assert.match(bodyText(), /羊小腿两盒/);
    assert.match(bodyText(), /确定性候选/);
  });

  const dateInput = document.querySelector<HTMLInputElement>('input[placeholder="选择结算时间"]');
  assert.ok(dateInput, 'settlement DatePicker must be visible');
  await act(async () => {
    dateInput.click();
  });
  await waitFor(() => assert.ok(document.querySelector('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden)')));
  const dateCell = document.querySelector<HTMLElement>('td[title="2026-08-31"] .ant-picker-cell-inner')
    ?? document.querySelector<HTMLElement>('.ant-picker-cell-in-view .ant-picker-cell-inner');
  assert.ok(dateCell, 'DatePicker must expose a selectable date');
  await act(async () => dateCell.click());
  const dateOk = document.querySelector<HTMLButtonElement>('.ant-picker-ok button');
  assert.ok(dateOk, 'showTime DatePicker must expose an OK action');
  await act(async () => dateOk.click());
  await waitFor(() => assert.equal(findControl('确认并生成正式订单').getAttribute('disabled'), null));
  await act(async () => findControl('确认并生成正式订单').click());

  await waitFor(() => assert.equal(locationText(), '/orders/500'));
  const request = requests.find(({ url, init }) => url.endsWith('/order-drafts/27/confirm') && init?.method === 'POST');
  assert.ok(request, 'the browser route must call the public confirm command');
  const command = JSON.parse(String(request.init?.body));
  assert.equal(command.expected_revision, 4);
  assert.equal(command.expected_case_version, 7);
  assert.equal(command.customer.customer_id, '12');
  assert.deepEqual(command.items, [{ line_no: 1, sku_id: '44', quantity: '2' }]);
  assert.ok(typeof command.settlement.settlement_time === 'string' && command.settlement.settlement_time.length > 0);
  assert.ok((request.init?.headers as Record<string, string>)['Idempotency-Key']);
  assert.equal((request.init?.headers as Record<string, string>)['X-Operator'], undefined);
});

test('real review route confirms a complete tracking draft without inventing a shipped time', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN') {
      return jsonResponse(page([reviewCase('TRACKING_DRAFT')]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/tracking-drafts/81' && (init?.method ?? 'GET') === 'GET') return jsonResponse(trackingDraft());
    if (url === '/api/v1/tracking-drafts/81/confirm' && init?.method === 'POST') {
      return jsonResponse(trackingDraft('CONFIRMED'));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await mountRoute();
  await waitFor(() => assert.match(bodyText(), /RC-TRACKING-99/));
  await act(async () => findControl('查看处理').click());
  await waitFor(() => {
    assert.match(bodyText(), /待人工确认的运单草稿/);
    assert.match(bodyText(), /FUL-20260813-0044/);
    assert.match(bodyText(), /默认按整项发货/);
  });

  const confirm = findControl('确认并记录运单');
  assert.equal(confirm.getAttribute('disabled'), null);
  await act(async () => confirm.click());
  await waitFor(() => assert.match(bodyText(), /运单草稿已确认并记录正式运单/));

  const request = requests.find(({ url, init }) => url.endsWith('/tracking-drafts/81/confirm') && init?.method === 'POST');
  assert.ok(request, 'the browser route must call the public tracking confirm command');
  const command = JSON.parse(String(request.init?.body));
  assert.deepEqual(command, {
    expected_draft_revision: 4,
    expected_case_version: 7,
    task_id: '44',
    task_no: null,
    carrier_code: 'SF_EXPRESS',
    actual_quantity: null,
    remark: '',
  });
  assert.equal('shipped_at' in command, false);
  assert.ok((request.init?.headers as Record<string, string>)['Idempotency-Key']);
  assert.equal((request.init?.headers as Record<string, string>)['X-Operator'], undefined);
});
