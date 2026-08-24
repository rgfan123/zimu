import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/system/connectors');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const connectorFixture = (overrides: Record<string, unknown> = {}) => ({
  source_channel: 'CAISHIXIAN',
  client_mode: 'MOCK',
  transport_mode: 'EXCEL',
  enabled: true,
  endpoint: null,
  credential_configured: true,
  version: 3,
  ...overrides,
});

const providerFixture = (overrides: Record<string, unknown> = {}) => ({
  id: '11',
  provider_code: 'JD',
  provider_name: '京东云仓',
  provider_type: 'JD_WAREHOUSE',
  tracking_sla_minutes: 60,
  active: true,
  version: 0,
  jd_config: {},
  ...overrides,
});

const auditLogFixture = (overrides: Record<string, unknown> = {}) => ({
  id: '1',
  data_scope: 'BUSINESS',
  operator: 'admin',
  actor_type: 'HUMAN',
  service: 'order',
  operation: 'order.create',
  order_id: '101',
  request_id: null,
  trace_id: null,
  request_payload: null,
  response_payload: null,
  http_status: 200,
  business_code: null,
  latency_ms: 12,
  created_at: '2026-08-14T00:00:00Z',
  ...overrides,
});

function auditPage(items: unknown[]) {
  return { items, page: 0, size: 10, total_elements: items.length, total_pages: items.length ? 1 : 0 };
}

test('渠道接入 renders the page header, intro copy, connector rows and a working refresh', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url === '/api/v1/connectors') {
      return jsonResponse([
        connectorFixture(),
        connectorFixture({ source_channel: 'JUFUBAO', enabled: false }),
      ]);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/system/connectors']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  assert.match(harness.bodyText(), /渠道接入/);
  assert.match(harness.bodyText(), /统一维护四个来源渠道的接入方式/);
  assert.match(harness.bodyText(), /聚福宝/);
  assert.match(harness.bodyText(), /文件接入/);
  assert.match(harness.bodyText(), /仿真模式/);

  const refresh = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('刷新'));
  assert.ok(refresh);
  const before = requests.filter((url) => url === '/api/v1/connectors').length;
  await harness.dispatchEvent(refresh, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    requests.filter((url) => url === '/api/v1/connectors').length > before,
    'refresh must re-request the connector directory',
  ));
});

test('渠道接入 test-connection posts through the public API and surfaces the result', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/api/v1/connectors') return jsonResponse([connectorFixture()]);
    if (url === '/api/v1/connectors/CAISHIXIAN/test-connection') {
      return jsonResponse({ success: true, checked_at: '2026-08-14T00:00:00Z', latency_ms: 42 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/system/connectors']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));

  const testButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('测试连接'));
  assert.ok(testButton);
  await harness.dispatchEvent(testButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.includes('POST /api/v1/connectors/CAISHIXIAN/test-connection'),
    'test connection must POST to the connector endpoint',
  ));
  await harness.waitFor(() => assert.match(harness.bodyText(), /连通性测试：通过/));
  assert.match(harness.bodyText(), /延迟 42 ms/);
});

test('渠道接入 empty directory keeps the business empty copy', async () => {
  globalThis.fetch = async () => jsonResponse([]);

  await harness.mount(['/system/connectors']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无渠道连接器配置/));
});

test('操作审计 renders the header and filters issue exact list requests', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url.startsWith('/api/v1/audit-logs?')) return jsonResponse(auditPage([auditLogFixture()]));
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/system/audit-logs']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /操作审计/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /创建订单/));
  assert.match(harness.bodyText(), /admin/);

  const operatorInput = document.querySelector<HTMLInputElement>('input[placeholder="操作员"]');
  assert.ok(operatorInput, 'audit logs must expose the operator filter');
  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  await act(async () => {
    Simulate.change(operatorInput, { target: { value: 'zhangsan' } });
  });
  await harness.waitFor(() => assert.ok(
    requests.some((url) => url.includes('operator=zhangsan')),
    'operator input must issue the filtered list request',
  ));

  const refresh = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('刷新'));
  assert.ok(refresh, 'audit logs must expose a refresh action');
});

test('操作审计 detail drawer shows the whitelisted business fields', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url.startsWith('/api/v1/audit-logs?')) return jsonResponse(auditPage([auditLogFixture()]));
    if (url === '/api/v1/audit-logs/1') {
      return jsonResponse(auditLogFixture({
        request_payload: { order_no: 'ORD-101', raw_secret: 'must stay hidden' },
        response_payload: { ok: true },
      }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/system/audit-logs']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /创建订单/));

  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('详情'));
  assert.ok(detailLink);
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /审计详情/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /请求业务摘要/));
  assert.match(harness.bodyText(), /ORD-101/);
  assert.doesNotMatch(harness.bodyText(), /must stay hidden/);
});

test('系统配置 renders the read-only overview with both panels', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/connectors') return jsonResponse([connectorFixture()]);
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([providerFixture()]);
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/system/config']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /集中查看渠道接入与履约方配置/));
  assert.match(harness.bodyText(), /系统配置/);
  assert.match(harness.bodyText(), /只读总览/);
  assert.match(harness.bodyText(), /渠道接入配置/);
  assert.match(harness.bodyText(), /履约方配置/);
  assert.match(harness.bodyText(), /彩食鲜/);
  assert.match(harness.bodyText(), /京东云仓/);
});

test('履约方配置 renders the header, intro copy and the provider directory', async () => {
  globalThis.fetch = async () => jsonResponse([providerFixture()]);

  await harness.mount(['/system/fulfillment-providers']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东云仓/));
  assert.match(harness.bodyText(), /履约方配置/);
  assert.match(harness.bodyText(), /统一维护京东云仓与第三方履约方/);
});

test('渠道接入 edit modal submits the versioned PATCH with preserved fields', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url === '/api/v1/connectors') return jsonResponse([connectorFixture()]);
    if (url === '/api/v1/connectors/CAISHIXIAN' && init?.method === 'PATCH') {
      return jsonResponse(connectorFixture({ transport_mode: 'API' }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/connectors']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));

  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink);
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /编辑渠道连接器/));

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton, 'the connector editor must expose a confirm action');
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH' && request.url === '/api/v1/connectors/CAISHIXIAN'),
    'confirm must submit the versioned PATCH',
  ));
  const patch = requests.find((request) => request.method === 'PATCH');
  assert.match(patch?.body ?? '', /"expected_version":3/);
  assert.match(patch?.body ?? '', /"transport_mode":"EXCEL"/);
});
