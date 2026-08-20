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
  harness = await createRouteHarness('http://localhost/workbench/reviews');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function operationalAlert(id: string) {
  return {
    id,
    alert_no: `ALERT-Q-${id}`,
    alert_type: 'PROCUREMENT_REQUIRED',
    severity: 'YELLOW',
    status: 'OPEN',
    order_id: '101',
    message: '库存不足，已创建采购工单',
    detail: {},
    version: 0,
    created_at: '2026-08-20T02:00:00Z',
  };
}

/** 复核队列页 mock：按收到的筛选参数返回对应队列。 */
function reviewsFetch(requests: string[]) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/review-cases?')) {
      const params = new URLSearchParams(url.split('?')[1]);
      const items = params.get('reason_code') === 'SKU_MAPPING_REQUIRED'
        ? [reviewCaseFixture('1', {
            reasonCode: 'SKU_MAPPING_REQUIRED',
            team: params.get('responsible_team') ?? 'SKU_OPS',
          })]
        : [];
      return jsonResponse(page(items, Number(params.get('size') ?? 20)));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') {
      return jsonResponse(page([operationalAlert('9')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('status/reason_code/responsible_team 从 URL 恢复并实际影响队列请求', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  assert.ok(requests.includes(
    'GET /api/v1/review-cases?page=0&size=20&status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS',
  ), '队列请求必须带 URL 中的全部筛选');
  assert.match(harness.bodyText(), /SKU 映射待确认/, '事项类型筛选控件显示 URL 中的值');
  assert.match(harness.bodyText(), /商品运营/, '责任团队筛选控件显示 URL 中的值');
});

test('reason_code 与 responsible_team 同时出现时组合过滤（工作台行上下文）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=CUSTOMER_OPS']);

  await harness.waitFor(() => assert.ok(requests.some((r) =>
    r.includes('reason_code=SKU_MAPPING_REQUIRED') && r.includes('responsible_team=CUSTOMER_OPS'))));
  assert.match(harness.bodyText(), /客户运营/, '团队筛选控件显示 URL 中的 CUSTOMER_OPS');
});

test('无筛选参数时队列请求保持原状，不写多余参数（#95 兼容）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?import_batch=7']);

  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7'),
    '不带 reason/team 时请求与 #95 完全一致',
  ));
});

test('非法 status 值回退到默认 OPEN，不把坏链接当筛选', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=BOGUS']);

  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/review-cases?page=0&size=20&status=OPEN'),
    '非法 status 必须回退 OPEN',
  ));
});

test('view=alerts 直达运营提醒队列，URL 可恢复', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?view=alerts']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  assert.match(harness.bodyText(), /运营提醒/);
  assert.ok(requests.includes('GET /api/v1/operational-alerts?page=0&size=20&status=OPEN'));
});

test('切换到运营提醒视图时 view 参数写入 URL，可分享', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /阻断复核/));
  const alertsTab = [...document.querySelectorAll<HTMLElement>('.ant-segmented-item')]
    .find((candidate) => candidate.textContent?.includes('运营提醒'));
  assert.ok(alertsTab, '缺少运营提醒分段');
  await harness.dispatchEvent(alertsTab, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/reviews?view=alerts'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
});

test('改变责任团队筛选后 URL 同步更新（可分享/刷新恢复）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /责任团队/));
  const teamSelector = document.querySelector<HTMLElement>('#review-team-filter')?.closest('.ant-select-selector');
  assert.ok(teamSelector, '缺少责任团队筛选');
  await harness.dispatchEvent(teamSelector, new MouseEvent('mousedown', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
      .some((candidate) => candidate.textContent?.includes('订单运营')),
  ));
  const orderOps = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((candidate) => candidate.textContent?.includes('订单运营'));
  assert.ok(orderOps);
  await harness.dispatchEvent(orderOps, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.location(), /responsible_team=ORDER_OPS/));
  await harness.waitFor(() => assert.ok(requests.some((r) => r.includes('responsible_team=ORDER_OPS'))));
});
