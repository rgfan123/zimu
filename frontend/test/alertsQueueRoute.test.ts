/**
 * Issue #64：运营提醒独立路由页（/workbench/alerts）的 public-route 契约：
 * - 直接深链接可进入提醒页并恢复提醒列表；
 * - 提醒状态筛选以 URL 为唯一事实源（与 #96 复核队列同一模式），刷新/回退可恢复；
 * - 提醒抽屉 ACK（确认已知晓）走版本化 acknowledge 命令且不推进业务状态；
 * - 复核/提醒两页互为上下文切换入口（#98：提醒页是隐藏可路由叶子，不占可见菜单位）。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  control,
  createRouteHarness,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/alerts');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function operationalAlert(id: string, overrides: { status?: string } = {}) {
  return {
    id,
    alert_no: `ALERT-Q-${id}`,
    alert_type: 'PROCUREMENT_REQUIRED',
    severity: 'YELLOW',
    status: overrides.status ?? 'OPEN',
    order_id: '101',
    message: '库存不足，已创建采购工单',
    detail: {},
    version: 0,
    acknowledged_by: null,
    acknowledged_at: null,
    created_at: '2026-08-20T02:00:00Z',
  };
}

/** 提醒页 mock：提醒列表按 status 筛选 + ACK 命令（ACK 后从列表消失）+ 复核队列（上下文切换落地用）。 */
function alertsFetch(
  requests: Array<{ url: string; init?: RequestInit }>,
  items: unknown[] = [operationalAlert('9')],
) {
  const acknowledged = new Set<string>();
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push({ url, init });
    if (url.startsWith('/api/v1/operational-alerts?')) {
      const params = new URLSearchParams(url.split('?')[1]);
      const status = params.get('status') ?? 'OPEN';
      const visible = items.filter((item) => !acknowledged.has((item as { id: string }).id));
      const filtered = status === 'OPEN'
        ? visible
        : visible.filter((item) => (item as { status: string }).status === status);
      return jsonResponse(page(filtered));
    }
    if (url === '/api/v1/operational-alerts/9/acknowledge' && init?.method === 'POST') {
      acknowledged.add('9');
      return jsonResponse(operationalAlert('9', { status: 'ACKNOWLEDGED' }));
    }
    if (url.startsWith('/api/v1/review-cases?')) {
      return jsonResponse(page([reviewCaseFixture('1')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('direct deep link renders the alerts page header and the OPEN alert list', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = alertsFetch(requests);

  await harness.mount(['/workbench/alerts']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  assert.match(harness.bodyText(), /运营提醒/, '页头标题必须保留');
  assert.ok(requests.some(({ url }) =>
    url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN'), '提醒列表请求必须带默认 OPEN');
});

test('alert status filter lives in the URL: deep link, refresh restore and select writes back', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = alertsFetch(requests, [
    operationalAlert('9'),
    operationalAlert('8', { status: 'ACKNOWLEDGED' }),
  ]);

  await harness.mount(['/workbench/alerts?status=ACKNOWLEDGED']);

  await harness.waitFor(() => assert.ok(
    requests.some(({ url }) => url === '/api/v1/operational-alerts?page=0&size=20&status=ACKNOWLEDGED'),
    'URL 中的状态必须实际影响提醒列表请求',
  ));
  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-8/));
  assert.doesNotMatch(harness.bodyText(), /ALERT-Q-9/, '已确认提醒不应出现在 ACKNOWLEDGED 列表之外');

  // 筛选控件变更写回 URL（可分享/刷新恢复）
  const statusSelector = document.querySelector<HTMLElement>('#alert-status-filter')?.closest('.ant-select-selector');
  assert.ok(statusSelector, '缺少提醒状态筛选控件');
  await harness.dispatchEvent(statusSelector, new MouseEvent('mousedown', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
      .some((candidate) => candidate.textContent?.includes('已恢复')),
  ));
  const resolvedOption = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((candidate) => candidate.textContent?.includes('已恢复'));
  assert.ok(resolvedOption);
  await harness.dispatchEvent(resolvedOption, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.location(), /status=RESOLVED/));
});

test('invalid alert status falls back to OPEN instead of filtering on garbage', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = alertsFetch(requests);

  await harness.mount(['/workbench/alerts?status=BOGUS']);

  await harness.waitFor(() => assert.ok(
    requests.some(({ url }) => url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN'),
    '非法 status 必须回退 OPEN',
  ));
});

test('alert drawer ACK posts a versioned acknowledge command and refreshes the list', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = alertsFetch(requests);

  await harness.mount(['/workbench/alerts']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  await harness.dispatchEvent(control('查看确认'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /确认提醒不会推进订单或履约状态/));

  const textarea = document.querySelector<HTMLTextAreaElement>('.ant-drawer textarea');
  assert.ok(textarea, '提醒抽屉必须提供确认备注输入');
  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  await act(async () => {
    Simulate.change(textarea, { target: { value: '已安排补货' } });
  });

  await harness.dispatchEvent(control('确认已知晓'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    requests.some(({ url, init }) =>
      url.endsWith('/operational-alerts/9/acknowledge') && init?.method === 'POST'),
    '必须调用版本化 acknowledge 命令',
  ));
  const request = requests.find(({ url, init }) =>
    url.endsWith('/operational-alerts/9/acknowledge') && init?.method === 'POST');
  assert.deepEqual(JSON.parse(String(request?.init?.body)), {
    expected_version: 0,
    note: '已安排补货',
  });
  await harness.waitFor(() => assert.match(harness.bodyText(), /运营提醒已确认；业务状态未被推进/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /当前没有运营提醒/,
    'ACK 后列表必须刷新（该提醒已不再是 OPEN）'));
});

test('alerts page offers a context link back to the review queue route', async () => {
  const requests: Array<{ url: string; init?: RequestInit }> = [];
  globalThis.fetch = alertsFetch(requests);

  await harness.mount(['/workbench/alerts']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  const reviewsLink = control('阻断复核');
  assert.ok(reviewsLink.tagName === 'A' && (reviewsLink.getAttribute('href') ?? '') === '/workbench/reviews',
    '上下文切换必须是直达复核路由的链接');
  await harness.dispatchEvent(reviewsLink, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/reviews'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
});
