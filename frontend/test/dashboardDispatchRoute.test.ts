import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';
import { saasVisualTokens } from '../src/theme/saasTheme.ts';

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
function dashboardFetch(
  requests: string[],
  queueItems: unknown[] = [],
  summaryOverrides: { attention?: unknown[] } = {},
) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (/^\/api\/v1\/dashboard\/summary\?business_date=\d{4}-\d{2}-\d{2}$/.test(url)) {
      return jsonResponse(summary(summaryOverrides));
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

function actionableCardLink(href: string): HTMLAnchorElement {
  const link = document.querySelector<HTMLAnchorElement>(`a[href="${href}"]`);
  assert.ok(link, `缺少指向 ${href} 的链接`);
  assert.equal(link.tagName, 'A', '必须是真实锚点，键盘可聚焦');
  assert.ok(link.querySelector('.ant-card'), 'actionable Link 内必须包含 .ant-card');
  return link;
}

/** jsdom 把 inline `style.color` 序列化为 `rgb(r, g, b)`，与 token hex 对照时先规范化。 */
function cssRgb(hex: string): string {
  const n = hex.replace('#', '');
  const r = Number.parseInt(n.slice(0, 2), 16);
  const g = Number.parseInt(n.slice(2, 4), 16);
  const b = Number.parseInt(n.slice(4, 6), 16);
  return `rgb(${r}, ${g}, ${b})`;
}

test('待人工介入 KPI 整张 Card 可点击，直达 OPEN 完整列表，且不带伪造时间参数', async () => {
  const requests: string[] = [];
  globalThis.fetch = dashboardFetch(requests, [
    reviewCaseFixture('1', { reasonCode: 'SKU_MAPPING_REQUIRED', team: 'SKU_OPS' }),
    reviewCaseFixture('2', { reasonCode: 'SYNC_FAILED', team: 'ORDER_OPS' }),
  ]);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待人工介入/));
  const kpiLink = actionableCardLink('/workbench/reviews?status=OPEN');
  assert.match(kpiLink.textContent ?? '', /3/, 'KPI 链接文本包含待介入数量');
  assert.doesNotMatch(kpiLink.getAttribute('href') ?? '', /date/, 'KPI 链接不得携带时间参数');
  await harness.dispatchEvent(kpiLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/reviews?status=OPEN'));
});

test('attention 原因卡：复核原因卡直达 reason 预筛列表，提醒专用卡直达提醒路由', async () => {
  globalThis.fetch = dashboardFetch([]);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待人工介入/));
  const reviewCard = actionableCardLink('/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED');
  const alertCard = actionableCardLink('/workbench/alerts');
  for (const link of [reviewCard, alertCard]) {
    assert.doesNotMatch(link.getAttribute('href') ?? '', /date/, 'attention 卡链接不得携带时间参数');
  }
});

test('点击提醒专用 attention 卡落在 /workbench/alerts 提醒页并拉取提醒列表', async () => {
  const requests: string[] = [];
  globalThis.fetch = dashboardFetch(requests);

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待人工介入/));
  const alertCard = actionableCardLink('/workbench/alerts');
  await harness.dispatchEvent(alertCard, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/alerts'));
  assert.ok(requests.includes('GET /api/v1/operational-alerts?page=0&size=20&status=OPEN'),
    '提醒页必须实际拉取提醒列表');
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

test('工作台 page header renders and KPI stays readable when the issues detail fails', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (/^\/api\/v1\/dashboard\/summary\?business_date=\d{4}-\d{2}-\d{2}$/.test(url)) {
      return jsonResponse(summary());
    }
    if (url.startsWith('/api/v1/review-cases?')) {
      return jsonResponse({ message: 'raw issue stack' }, 500);
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') {
      return jsonResponse(page([]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /明细加载失败，请刷新重试/));
  // KPI 卡不受明细失败影响
  assert.match(harness.bodyText(), /今日订单数/);
  assert.doesNotMatch(harness.bodyText(), /raw issue stack/);
  // 侧边栏菜单 + PageShell 页头各渲染一次「工作台」
  assert.ok((harness.bodyText().match(/工作台/g) ?? []).length >= 2, 'page header must render the nav title');
});

test('OUT_OF_STOCK + severity=YELLOW 仍走 YELLOW，不走旧原因表；明细原因列不派生严重/关注标记', async () => {
  globalThis.fetch = dashboardFetch(
    [],
    [reviewCaseFixture('1', { reasonCode: 'OUT_OF_STOCK', team: 'ORDER_OPS' })],
    { attention: [{ reason_code: 'OUT_OF_STOCK', count: 4, severity: 'YELLOW' }] },
  );

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /缺货/));
  const stockCard = actionableCardLink('/workbench/alerts');
  const iconWrap = stockCard.querySelector('.anticon-warning')?.parentElement;
  assert.equal(
    iconWrap?.style.color,
    cssRgb(saasVisualTokens.semantic.warning),
    '后端 YELLOW 必须映射 waiting，而不是 CRITICAL_REASONS 的红',
  );
  assert.notEqual(iconWrap?.style.color, cssRgb(saasVisualTokens.semantic.error));

  const reasonCell = document.querySelector<HTMLElement>('.ant-table-tbody td');
  assert.ok(reasonCell, '明细必须有原因列');
  assert.match(reasonCell.textContent ?? '', /缺货/, '原因列只显示 reasonLabel');
  assert.doesNotMatch(reasonCell.textContent ?? '', /严重|关注/, '原因列不得出现前端派生的严重/关注标记');
  assert.equal(reasonCell.querySelector('[title="严重"], [title="关注"]'), null);
});

test('OUT_OF_STOCK + severity=RED 走 error；未知 reason_code 仍整卡链到真实预筛 URL', async () => {
  globalThis.fetch = dashboardFetch(
    [],
    [],
    {
      attention: [
        { reason_code: 'OUT_OF_STOCK', count: 4, severity: 'RED' },
        { reason_code: 'FUTURE_REASON', count: 2, severity: 'YELLOW' },
      ],
    },
  );

  await harness.mount(['/dashboard']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /缺货/));
  const stockCard = actionableCardLink('/workbench/alerts');
  const iconWrap = stockCard.querySelector('.anticon-warning')?.parentElement;
  assert.equal(
    iconWrap?.style.color,
    cssRgb(saasVisualTokens.semantic.error),
    '后端 RED 必须映射 error，证明不是总显示 YELLOW',
  );

  const unknownHref = '/workbench/reviews?status=OPEN&reason_code=FUTURE_REASON';
  const unknownCard = actionableCardLink(unknownHref);
  await harness.dispatchEvent(unknownCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), unknownHref));
});
