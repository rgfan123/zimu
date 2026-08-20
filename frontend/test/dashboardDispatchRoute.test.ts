import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/dashboard');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function summary(overrides: { attention?: unknown[] } = {}) {
  return {
    business_date: '2026-08-20',
    order_count: 12,
    shipped_order_count: 9,
    pending_review_count: 3,
    trend: [
      { business_date: '2026-08-14', order_count: 10, shipped_order_count: 8 },
      { business_date: '2026-08-15', order_count: 12, shipped_order_count: 9 },
    ],
    attention: overrides.attention ?? [
      { reason_code: 'SKU_MAPPING_REQUIRED', count: 2, severity: 'RED' },
      { reason_code: 'PROCUREMENT_REQUIRED', count: 1, severity: 'YELLOW' },
    ],
  };
}

/** 工作台 mock：summary + OPEN 复核事项；跳转后继续服务复核队列页的请求。 */
function dashboardFetch(requests: string[], queueItems: unknown[] = []) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (/^\/api\/v1\/dashboard\/summary\?business_date=\d{4}-\d{2}-\d{2}$/.test(url)) {
      return jsonResponse(summary());
    }
    if (url === '/api/v1/review-cases?page=0&size=200&status=OPEN') {
      return jsonResponse(page(queueItems, 200));
    }
    if (/^\/api\/v1\/review-cases\?page=0&size=20&status=OPEN(&|$)/.test(url)) {
      return jsonResponse(page(queueItems, 20));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') {
      return jsonResponse(page([]));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('待人工介入 KPI 数字可点击，直达 OPEN 完整列表，且不带伪造时间参数', async () => {
  const requests: string[] = [];
  globalThis.fetch = dashboardFetch(requests, [
    reviewCaseFixture('1', { reasonCode: 'SKU_MAPPING_REQUIRED', team: 'SKU_OPS' }),
    reviewCaseFixture('2', { reasonCode: 'SYNC_FAILED', team: 'ORDER_OPS' }),
  ]);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待人工介入/));
  const kpiLink = document.querySelector<HTMLAnchorElement>('a[href="/workbench/reviews?status=OPEN"]');
  assert.ok(kpiLink, 'KPI 数字必须是链接');
  assert.match(kpiLink.textContent ?? '', /3/, 'KPI 链接文本是待介入数量');
  assert.doesNotMatch(kpiLink.getAttribute('href') ?? '', /date/, 'KPI 链接不得携带时间参数');
});

test('attention 原因卡：复核原因卡直达 reason 预筛列表，提醒专用卡直达提醒队列', async () => {
  globalThis.fetch = dashboardFetch([]);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待人工介入/));
  const reviewCard = document.querySelector<HTMLAnchorElement>(
    'a[href="/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED"]');
  assert.ok(reviewCard, '复核原因 attention 卡必须是链接');
  const alertCard = document.querySelector<HTMLAnchorElement>(
    'a[href="/workbench/reviews?view=alerts"]');
  assert.ok(alertCard, '提醒专用 attention 卡必须直达提醒队列');
  for (const link of document.querySelectorAll<HTMLAnchorElement>('.ant-card a[href^="/workbench/reviews"]')) {
    assert.doesNotMatch(link.getAttribute('href') ?? '', /date/, 'attention 卡链接不得携带时间参数');
  }
});

test('待人工介入明细每行可点击直达按该行上下文预筛的复核队列', async () => {
  const requests: string[] = [];
  globalThis.fetch = dashboardFetch(requests, [
    reviewCaseFixture('1', { reasonCode: 'SKU_MAPPING_REQUIRED', team: 'SKU_OPS' }),
    reviewCaseFixture('2', { reasonCode: 'SYNC_FAILED', team: 'ORDER_OPS' }),
  ]);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  const row1 = document.querySelector<HTMLAnchorElement>(
    'a[href="/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS"]');
  assert.ok(row1, 'SKU_OPS 行链接按 reason+team 预筛');
  const row2 = document.querySelector<HTMLAnchorElement>(
    'a[href="/workbench/reviews?status=OPEN&reason_code=SYNC_FAILED&responsible_team=ORDER_OPS"]');
  assert.ok(row2, 'ORDER_OPS 行链接按 reason+team 预筛');
});

test('点击明细行链接后落在按 reason/team 预筛的复核队列，URL 参数实际影响 API 请求', async () => {
  const requests: string[] = [];
  const items = [reviewCaseFixture('1', { reasonCode: 'SKU_MAPPING_REQUIRED', team: 'SKU_OPS' })];
  globalThis.fetch = dashboardFetch(requests, items);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  const rowLink = document.querySelector<HTMLAnchorElement>(
    'a[href="/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS"]');
  assert.ok(rowLink);
  await harness.dispatchEvent(rowLink, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(
    harness.location(),
    '/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS',
  ));
  await harness.waitFor(() => assert.ok(
    requests.includes(
      'GET /api/v1/review-cases?page=0&size=20&status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS',
    ),
    '落地列表必须按行上下文预筛',
  ));
});

test('无待办时明确显示「当前无待人工介入」，不是通用空表', async () => {
  globalThis.fetch = dashboardFetch([], []);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /当前无待人工介入/));
});
