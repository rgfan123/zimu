import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let AppRoot: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/workbench/business-followups',
  });
  for (const key of [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLInputElement', 'HTMLTextAreaElement', 'SVGElement',
    'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event', 'MouseEvent',
  ] as const) {
    Object.defineProperty(globalThis, key, {
      configurable: true,
      value: key === 'window' ? dom.window : dom.window[key],
    });
  }
  const nativeGetComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  const safeGetComputedStyle = (element: Element) => nativeGetComputedStyle(element);
  Object.defineProperty(dom.window, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });
  Object.defineProperty(globalThis, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });
  class ResizeObserverStub { observe() {} unobserve() {} disconnect() {} }
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
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function summary() {
  return {
    id: '9007199254740993',
    followup_no: 'BF-0000000001',
    message_submission_id: '9007199254740995',
    source_message_id: '9007199254740997',
    source_revision: 1,
    stage: 'PENDING_ORGANIZATION',
    processing_status: 'NOT_STARTED',
    created_by: 'manager-zhang',
    designated_reviewer: null,
    agent_slug: null,
    agent_version: null,
    task_status: null,
    task_attempts: null,
    task_failure_code: null,
    created_at: '2026-08-26T00:00:00Z',
    updated_at: '2026-08-26T00:00:00Z',
  };
}

function bodyText() {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

async function waitFor(assertion: () => void, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try { assertion(); return; } catch (error) { lastError = error; }
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 5)); });
  }
  throw lastError;
}

async function mountRoute(entry: string) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      { initialEntries: [entry], future: { v7_startTransition: true, v7_relativeSplatPath: true } },
      createElement(AppRoot),
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
  AppRoot = (await vite.ssrLoadModule('/src/App.tsx')).default;
});

beforeEach(() => { document.body.innerHTML = '<div id="root"></div>'; });

afterEach(async () => {
  if (mountedRoot) {
    await act(async () => mountedRoot?.unmount());
    mountedRoot = null;
  }
});

after(async () => { await vite.close(); dom.window.close(); });

test('list keeps draft private and detail is fetched only after the keyboard-reachable action', async () => {
  const requested: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requested.push(url);
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [summary()], page: 0, size: 20, total_elements: 1, total_pages: 1 });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    if (url === '/api/v1/business-followups/9007199254740993') {
      return jsonResponse({ ...summary(), employee_draft: 'DETAIL_ONLY_DRAFT' });
    }
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups');
  await waitFor(() => assert.match(bodyText(), /BF-0000000001/));
  assert.doesNotMatch(bodyText(), /DETAIL_ONLY_DRAFT/);
  assert.equal(requested.includes('/api/v1/business-followups/9007199254740993'), false);
  const detailButton = [...document.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '详情');
  assert.ok(detailButton);
  await act(async () => { detailButton.click(); });
  await waitFor(() => assert.match(bodyText(), /DETAIL_ONLY_DRAFT/));
  assert.ok(requested.includes('/api/v1/business-followups/9007199254740993'));
});

test('evidence route prefills a read-only string identifier without numeric coercion', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups?submission_id=9007199254740995');
  await waitFor(() => assert.match(bodyText(), /从消息证据新建客户跟进/));
  const source = document.querySelector<HTMLInputElement>('input[value="9007199254740995"]');
  assert.ok(source);
  assert.equal(source.readOnly, true);
});

test('opening organize disables stale cached Agent options while the catalog refreshes', async () => {
  let agentCalls = 0;
  let finishRefresh: ((response: Response) => void) | undefined;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [summary()], page: 0, size: 20, total_elements: 1, total_pages: 1 });
    }
    if (url === '/api/v1/agents') {
      agentCalls += 1;
      if (agentCalls === 1) {
        return jsonResponse({
          items: [{ slug: 'customer-followup-agent', name: '客户跟进', state: 'RUNNING', current_version: 1 }],
        });
      }
      return new Promise<Response>((resolve) => { finishRefresh = resolve; });
    }
    if (url.startsWith('/api/v1/operators?')) {
      return jsonResponse({
        items: [{
          id: '7', display_name: '跟进审批人', responsible_team: 'CUSTOMER_OPS',
          wecom_userid: 'followup-reviewer', active: true, version: 0,
        }],
        page: 0, size: 200, total_elements: 1, total_pages: 1,
      });
    }
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups');
  await waitFor(() => assert.match(bodyText(), /发起整理/));
  const startButton = [...document.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '发起整理');
  assert.ok(startButton);
  await act(async () => { startButton.click(); });
  await waitFor(() => assert.match(bodyText(), /由 \+1 发起整理/));
  const submit = [...document.querySelectorAll('button')]
    .find((button) => button.textContent?.includes('确认发起异步整理'));
  assert.ok(submit);
  assert.equal(submit.disabled, true);
  assert.ok(finishRefresh);
  await act(async () => {
    finishRefresh?.(jsonResponse({
      items: [{ slug: 'customer-followup-agent', name: '客户跟进', state: 'RUNNING', current_version: 2 }],
    }));
  });
});

test('detail keeps Zimu employee material separate from Kehuzx verified read evidence', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({
        items: [{ ...summary(), stage: 'DRAFT_READY', processing_status: 'SUCCEEDED' }],
        page: 0, size: 20, total_elements: 1, total_pages: 1,
      });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    if (url === '/api/v1/business-followups/9007199254740993') {
      return jsonResponse({
        ...summary(),
        stage: 'DRAFT_READY',
        processing_status: 'SUCCEEDED',
        employee_draft: '员工说客户想先看样品',
        latest_draft: {
          version: 1,
          status: 'DRAFT',
          agent_run_id: 'run_source_evidence',
          agent_slug: 'customer-followup-agent',
          agent_version: 1,
          content: {
            title: '华北餐饮跟进',
            summary: 'Kehuzx 已匹配唯一客户',
            agent_suggestion: '建议先确认样品需求',
            facts: [
              { source: 'ZIMU', label: '员工材料', value: '希望先看样品' },
              { source: 'KEHUZX', label: '客户编号', value: 'KH-C-001' },
            ],
            requires_human: false,
            missing_fields: [],
          },
          zimu_source_summary: {
            source: 'ZIMU', followup_id: '9007199254740993', followup_no: 'BF-0000000001',
            message_submission_id: '9007199254740995', source_revision: 1,
          },
          kehuzx_source_summary: {
            source: 'KEHUZX', candidate_count: 1, failures: [],
            calls: [{
              tool: 'search_customers', response_digest: 'a'.repeat(64),
              contract_version: 'kehuzx-mcp-v1', upstream_commit: 'c6a2418',
              queried_at: '2026-08-26T04:00:00Z',
            }],
          },
          upstream_refs: [{ entity_type: 'customer', id: 'kehuzx-customer-1' }],
          created_at: '2026-08-26T04:00:01Z',
        },
      });
    }
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups');
  await waitFor(() => assert.match(bodyText(), /BF-0000000001/));
  const detailButton = [...document.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '详情');
  assert.ok(detailButton);
  await act(async () => { detailButton.click(); });
  await waitFor(() => assert.match(bodyText(), /华北餐饮跟进/));
  const text = bodyText();
  assert.match(text, /ZIMU.*员工材料.*希望先看样品/);
  assert.match(text, /KEHUZX.*客户编号.*KH-C-001/);
  assert.match(text, /契约 kehuzx-mcp-v1.*提交 c6a2418/);
  assert.match(text, /上游引用：customer:kehuzx-customer-1/);
});

test('card deep link requires feedback and submits a version-fenced REST decision', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  const detail = {
    ...summary(),
    stage: 'PENDING_APPROVAL',
    processing_status: 'SUCCEEDED',
    designated_reviewer: '跟进审批人',
    employee_draft: '只在后台显示的员工材料',
    latest_draft: {
      version: 3,
      status: 'READY',
      agent_run_id: 'run_decision',
      agent_slug: 'customer-followup-agent',
      agent_version: 1,
      content: { title: '草稿 v3', summary: '等待核对', facts: [], requires_human: false, missing_fields: [] },
      zimu_source_summary: {
        source: 'ZIMU', followup_id: '9007199254740993', followup_no: 'BF-0000000001',
        message_submission_id: '9007199254740995', source_revision: 1,
      },
      kehuzx_source_summary: { source: 'KEHUZX', candidate_count: 1, failures: [], calls: [] },
      upstream_refs: [],
      created_at: '2026-08-26T04:00:01Z',
    },
    draft_versions: [],
    approvals: [],
  };
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ url, init });
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [detail], page: 0, size: 20, total_elements: 1, total_pages: 1 });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    if (url === '/api/v1/business-followups/9007199254740993' && init?.method !== 'POST') {
      return jsonResponse(detail);
    }
    if (url === '/api/v1/business-followups/9007199254740993/decisions' && init?.method === 'POST') {
      return jsonResponse(detail, 202);
    }
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups?followup_id=9007199254740993'
    + '&expected_draft_version=3&decision=redo#capability=0123456789abcdef0123456789abcdef');
  await waitFor(() => assert.match(bodyText(), /让 Agent 重做/));
  const textarea = document.querySelector<HTMLTextAreaElement>('textarea');
  assert.ok(textarea);
  const { Simulate } = await import('react-dom/test-utils');
  await act(async () => { Simulate.change(textarea, { target: { value: '按新预算范围重做' } }); });
  const submit = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === '确认提交');
  assert.ok(submit);
  await act(async () => { submit.click(); });

  await waitFor(() => assert.ok(requests.some(({ url, init: request }) =>
    url.endsWith('/decisions') && request?.method === 'POST')));
  const posted = requests.find(({ url, init: request }) => url.endsWith('/decisions') && request?.method === 'POST');
  assert.deepEqual(JSON.parse(String(posted?.init?.body)), {
    expected_draft_version: 3,
    decision: 'REDO',
    reason: '按新预算范围重做',
    capability: '0123456789abcdef0123456789abcdef',
  });
});

test('detail shows each confirmed follow-up Assignment with its approval, agent run and execution outcome', async () => {
  const detail = {
    ...summary(),
    stage: 'CONFIRMED',
    processing_status: 'SUCCEEDED',
    employee_draft: '已确认后续执行',
    latest_draft: null,
    draft_versions: [],
    approvals: [],
    assignments: [{
      id: '71',
      followup_id: '9007199254740993',
      draft_version: 3,
      approval_id: '61',
      agent_run_id: 'run_confirmed_v3',
      task_type: 'KEHUZX_CUSTOMER_LINK',
      logical_target: 'kehuzx-customer:KH-260826-001',
      assignee_type: 'DETERMINISTIC_MCP',
      assignee_ref: 'kehuzx:customer-write',
      status: 'FAILED',
      due_at: '2026-08-28T10:00:00Z',
      priority: 'NORMAL',
      idempotency_key: 'followup:9007199254740993:v3:customer:KH-260826-001',
      execution_task_key: 'followup-assignment:71',
      request_id: 'req_assignment_71',
      external_entity_type: 'sample_request',
      external_entity_id: 'sample-19',
      result_code: 'UPSTREAM_REJECTED',
      created_at: '2026-08-26T04:00:01Z',
      started_at: '2026-08-26T04:01:00Z',
      completed_at: '2026-08-26T04:02:00Z',
      updated_at: '2026-08-26T04:02:00Z',
    }],
  };
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-followups?')) {
      return jsonResponse({ items: [detail], page: 0, size: 20, total_elements: 1, total_pages: 1 });
    }
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    if (url === '/api/v1/business-followups/9007199254740993') return jsonResponse(detail);
    throw new Error(`unexpected request ${url}`);
  };

  await mountRoute('/workbench/business-followups');
  await waitFor(() => assert.match(bodyText(), /BF-0000000001/));
  const detailButton = [...document.querySelectorAll('button')]
    .find((button) => button.textContent?.trim() === '详情');
  assert.ok(detailButton);
  await act(async () => { detailButton.click(); });

  await waitFor(() => assert.match(bodyText(), /后续 Assignment/));
  const text = bodyText();
  assert.match(text, /KEHUZX_CUSTOMER_LINK.*FAILED/);
  assert.match(text, /Approval 61.*草稿 v3.*Agent run run_confirmed_v3/);
  assert.match(text, /DETERMINISTIC_MCP.*kehuzx:customer-write.*优先级 NORMAL/);
  assert.match(text, /请求 req_assignment_71.*外部结果 sample_request:sample-19.*UPSTREAM_REJECTED/);
});
