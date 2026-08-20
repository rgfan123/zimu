/**
 * 履约目录三页的用户可观察契约（Issue #97 批次 A 迁移后固定行为）：
 * - FulfillmentTasksPage：列表渲染 + 错误态走 DataTable 默认错误条（含重试）；
 * - JdWarehousePage：出库单列表空态文案与导出动作；
 * - OutboundReconPage：查询条件写入 URL（刷新/分享可复现）并在后端失败时可读提示。
 *
 * 迁移只换承载结构，这三页此前没有 route 覆盖；本文件用 public-route 行为固定
 * 迁移后的可见行为，避免未来重构把错误态/URL 语义悄悄改掉。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  jsonResponse,
  page,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/fulfillment/tasks');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const providerFixture = {
  id: 'p1',
  provider_code: 'JD',
  provider_name: '京东云仓',
  provider_type: 'JD_WAREHOUSE',
  tracking_sla_minutes: 60,
  active: true,
  version: 0,
};

const fulfillmentFixture = {
  id: 'f1',
  fulfillment_no: 'FL-202608130001',
  provider_id: 'p1',
  requested_quantity: 10,
  cumulative_shipped_quantity: 4,
  cancelled_quantity: 0,
  shipping_progress: 'PARTIALLY_SHIPPED',
  outcome: 'IN_PROGRESS',
  exception_code: null,
  exception_reason: null,
};

test('fulfillment tasks page renders the list with the shared page shell and filter bar', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([providerFixture]);
    if (url.startsWith('/api/v1/fulfillments')) return jsonResponse(page([fulfillmentFixture]));
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/tasks']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /FL-202608130001/));
  assert.match(harness.bodyText(), /履约任务/);
  assert.match(harness.bodyText(), /京东云仓/);
  assert.match(harness.bodyText(), /部分发货/);
  assert.ok(control('刷新'), 'page header must expose the refresh action');
});

test('fulfillment tasks page shows a shared-table failure banner with retry', async () => {
  // 错误态：数据源失败时由 DataTable 渲染「履约任务加载失败」+ 重试，不再由页面自绘 Alert
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers') || url.startsWith('/api/v1/fulfillments')) {
      return apiErrorResponse(500, 'INTERNAL', 'provider directory exploded');
    }
    throw new Error(`unexpected request: ${url}`);
  };
  await harness.mount(['/fulfillment/tasks']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /履约任务加载失败/));
  assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/);
  assert.ok(control('重试'), 'DataTable error banner must keep the retry action');
});

test('jd warehouse page keeps the outbound list empty state and export action', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/jd-warehouse/status') {
      return jsonResponse({ client_mode: 'MOCK', credentials_configured: true, tenant_configured: true, live_ready: true });
    }
    if (url.startsWith('/api/v1/jd-order/outbound-order-nos')) {
      return jsonResponse({ success: true, business_code: 'MOCK_SUCCESS', data: { result_list: [] } });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/jd-warehouse']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东仓配连接检查/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无出库单数据/));
  assert.match(harness.bodyText(), /出库单列表/);
  assert.ok(control('导出当前列表'));
  assert.ok(control('查询'));
});

test('outbound recon page writes the query into the URL and shows a readable failure', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url.startsWith('/api/v1/outbound-recon')) {
      return apiErrorResponse(500, 'RECON_FAILED', '京东查询超时');
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/outbound-recon']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /输入单号开始查询/));
  const queryButton = document.querySelector<HTMLButtonElement>('button.ant-btn-primary');
  assert.ok(queryButton, 'query button must be present');
  assert.equal(queryButton.disabled, true, 'query must be disabled while the input is empty');

  const input = document.querySelector<HTMLInputElement>('input[placeholder*="202608130001"]');
  assert.ok(input, 'query input must keep the OUTBOUND_ORDER_NO placeholder');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, '202608130001');
  await harness.dispatchEvent(input, new Event('input', { bubbles: true }));
  await harness.dispatchEvent(queryButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() =>
    assert.match(harness.location(), /query_type=OUTBOUND_ORDER_NO&query_value=202608130001/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /查询未完成/));
  assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/);
  assert.ok(control('重试'));
});
