import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  isShellBaselineRequest,
  jsonResponse,
  shellBaselineResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/procurement/tickets');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function ticket(id: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    ticket_no: `T-${id}`,
    fulfillment_id: 'F-1',
    status: 'PENDING',
    requested_quantity: '10',
    fulfilled_quantity: '4',
    remaining_quantity: '6',
    items: [],
    receipts: [],
    version: 1,
    created_at: '2026-08-14T00:00:00Z',
    ...overrides,
  };
}

function ticketsPage(items: unknown[], total = items.length) {
  return { items, page: 0, size: 10, total_elements: total, total_pages: Math.ceil(total / 10) };
}

function ticketsFetch(requests: string[], items: unknown[] = [], detailOverrides: Record<string, unknown> = { status: 'FAILED' }) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    if (url.startsWith('/api/v1/procurement-tickets')) {
      if (url.includes('/retry') || url.includes('/cancel-remaining')) {
        return jsonResponse(ticket('1', detailOverrides));
      }
      if (/^\/api\/v1\/procurement-tickets\/[^?]+$/.test(url)) {
        return jsonResponse(ticket('1', detailOverrides));
      }
      return jsonResponse(ticketsPage(items));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('采购工单 route renders the page header, intro copy and the ticket list', async () => {
  const requests: string[] = [];
  globalThis.fetch = ticketsFetch(requests, [ticket('1'), ticket('2')]);

  await harness.mount(['/procurement/tickets']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));
  assert.match(harness.bodyText(), /采购协同/);
  assert.match(harness.bodyText(), /查看缺货补齐进度与不可变回执/);
  assert.match(harness.bodyText(), /T-2/);
  assert.match(harness.bodyText(), /共 2 条/);
});

test('采购协同 exposes the contextual entry to 采购比价 (demoted tool stays discoverable)', async () => {
  globalThis.fetch = ticketsFetch([], [ticket('1')]);

  await harness.mount(['/procurement/tickets']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));

  const compareLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('采购比价'));
  assert.ok(compareLink, '采购协同必须提供指向采购比价的上下文入口');
  assert.equal(compareLink.getAttribute('href'), '/procurement/price-compare', '上下文入口 href 必须指向原路径');

  await harness.dispatchEvent(compareLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.location(), /\/procurement\/price-compare/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /开始比价/));
});

test('采购工单 loading state keeps the view-level loading copy', async () => {
  let finishTicketsRequest: ((response: Response) => void) | undefined;
  // 只把「采购工单列表」这一路请求攥在手里，外壳基线请求（业务模块开放清单，票 03）照常应答。
  // 外壳请求排在页面挂载期请求之后：桩若不分流，它会把 finishTicketsRequest 覆盖成外壳那次的
  // resolve，随后 resolve 的就不是列表请求，页面永远停在「正在加载采购工单…」。
  globalThis.fetch = (input: RequestInfo | URL) => {
    const url = String(input);
    if (isShellBaselineRequest(url)) return Promise.resolve(shellBaselineResponse());
    return new Promise<Response>((resolve) => { finishTicketsRequest = resolve; });
  };

  await harness.mount(['/procurement/tickets']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /正在加载采购工单…/));
  assert.ok(finishTicketsRequest, 'route request must be pending');
  finishTicketsRequest?.(jsonResponse(ticketsPage([])));
  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无采购工单/));
});

test('empty ticket list shows the business empty copy', async () => {
  globalThis.fetch = ticketsFetch([], []);

  await harness.mount(['/procurement/tickets']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无采购工单/));
});

test('status filter and refresh issue the exact list requests', async () => {
  const requests: string[] = [];
  globalThis.fetch = ticketsFetch(requests, [ticket('1')]);

  await harness.mount(['/procurement/tickets']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));

  const statusSelector = [...document.querySelectorAll<HTMLElement>('.ant-select-selector')]
    .find((selector) => selector.textContent?.includes('全部'));
  assert.ok(statusSelector, 'the filter bar must expose a status control');
  await harness.dispatchEvent(statusSelector, new MouseEvent('mousedown', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /待处理/));
  const statusOption = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((option) => option.textContent?.includes('待处理'));
  assert.ok(statusOption);
  await harness.dispatchEvent(statusOption, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('status=PENDING')),
    'status selection must issue the filtered list request',
  ));

  const refresh = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('刷新'));
  assert.ok(refresh, 'the page must expose a refresh action');
  const before = requests.filter((url) => url.startsWith('GET /api/v1/procurement-tickets')).length;
  await harness.dispatchEvent(refresh, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    requests.filter((url) => url.startsWith('GET /api/v1/procurement-tickets')).length > before,
    'refresh must re-request the list',
  ));
});

test('view-level failure keeps the safe failure alert with a retry', async () => {
  let calls = 0;
  globalThis.fetch = async () => {
    calls += 1;
    if (calls === 1) return jsonResponse({ message: 'raw ticket stack' }, 500);
    return jsonResponse(ticketsPage([ticket('1')]));
  };

  await harness.mount(['/procurement/tickets']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /采购工单加载失败/));
  assert.doesNotMatch(harness.bodyText(), /raw ticket stack/);
  const retry = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('重试'));
  assert.ok(retry);
  await harness.dispatchEvent(retry, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));
});

test('detail drawer keeps the retry action and submits the versioned command', async () => {
  const requests: string[] = [];
  globalThis.fetch = ticketsFetch(requests, [ticket('1', { status: 'FAILED' })]);

  await harness.mount(['/procurement/tickets']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));

  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('详情'));
  assert.ok(detailLink);
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /采购工单 T-1/));

  const retryButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('重试采购'));
  assert.ok(retryButton, 'a FAILED ticket must expose the retry action');
  await harness.dispatchEvent(retryButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /重试采购工单/));

  const noteInput = document.querySelector<HTMLTextAreaElement>('textarea');
  assert.ok(noteInput, 'the confirm modal must ask for the handling note');
  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  await act(async () => {
    Simulate.change(noteInput, { target: { value: '已重新发起采购' } });
  });

  const confirmButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('确认提交'));
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('POST /api/v1/procurement-tickets/1/retry')),
    'confirm must submit the retry command',
  ));
});

test('detail drawer keeps the cancel-remaining action and submits the versioned command', async () => {
  const requests: string[] = [];
  globalThis.fetch = ticketsFetch(requests, [ticket('1', { status: 'PARTIAL', remaining_quantity: '6' })], { status: 'PARTIAL', remaining_quantity: '6' });

  await harness.mount(['/procurement/tickets']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /T-1/));

  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('详情'));
  assert.ok(detailLink);
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /采购工单 T-1/));

  const cancelButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('取消剩余缺口'));
  assert.ok(cancelButton, 'a PARTIAL ticket with a remaining gap must expose cancel-remaining');
  await harness.dispatchEvent(cancelButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /取消剩余缺口/));

  const noteInput = document.querySelector<HTMLTextAreaElement>('textarea');
  assert.ok(noteInput);
  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  await act(async () => {
    Simulate.change(noteInput, { target: { value: '剩余缺口转人工跟进' } });
  });

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('POST /api/v1/procurement-tickets/1/cancel-remaining')),
    'confirm must submit the cancel-remaining command',
  ));
});
