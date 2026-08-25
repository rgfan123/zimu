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
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLInputElement', 'SVGElement',
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
