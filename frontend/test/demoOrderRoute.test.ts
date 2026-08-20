import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/demo/order');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const scenario = { scenario_code: 'WECOM_FULL_LOOP', scenario_name: '企微全链路', description: '从接单到来源回传' };

function demoFetch(requests: string[], runOverrides: Record<string, unknown> = {}) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/demo/v1/scenarios') {
      if (init?.method === 'POST') {
        return jsonResponse({
          id: 'run-1',
          run_no: 'DEMO-20260814-001',
          scenario_code: 'WECOM_FULL_LOOP',
          status: 'SUCCEEDED',
          data_scope: 'DEMO',
          order_id: '9001',
          started_at: '2026-08-14T00:00:00Z',
          finished_at: '2026-08-14T00:00:05Z',
          error: null,
          timeline: [],
          order: {
            id: '9001',
            order_no: 'DEMO-ORD-9001',
            source_channel: 'WECOM',
            customer_name: '演示客户',
            receiver_name: '演示收货人',
            order_status: 'SYNCED',
            processing_stage: 'COMPLETED',
            processing_health: 'GREEN',
            completed_count: 1,
            total_count: 1,
            created_at: '2026-08-14T00:00:00Z',
            updated_at: '2026-08-14T00:00:05Z',
            version: 0,
          },
          ...runOverrides,
        });
      }
      return jsonResponse([scenario]);
    }
    if (/^\/demo\/v1\/runs\/run-1$/.test(url)) {
      return jsonResponse({
        id: 'run-1',
        run_no: 'DEMO-20260814-001',
        scenario_code: 'WECOM_FULL_LOOP',
        status: 'SUCCEEDED',
        data_scope: 'DEMO',
        order_id: '9001',
        started_at: '2026-08-14T00:00:00Z',
        finished_at: '2026-08-14T00:00:05Z',
        error: null,
        timeline: [],
        ...runOverrides,
      });
    }
    if (url === '/customer/v1/order-assistant/config') {
      return jsonResponse({ service_ready: false, provider: 'mock', model: 'mock' });
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('模拟下单 route renders the page header, demo notice and fixed scenarios', async () => {
  const requests: string[] = [];
  globalThis.fetch = demoFetch(requests);

  await harness.mount(['/demo/order']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /模拟下单/));
  assert.match(harness.bodyText(), /演示环境说明/);
  assert.match(harness.bodyText(), /演示订单与正式业务数据严格隔离/);
  const fixedTab = [...document.querySelectorAll<HTMLElement>('.ant-tabs-tab')]
    .find((tab) => tab.textContent?.includes('固定演示场景'));
  assert.ok(fixedTab, 'the demo page must expose the fixed-scenario tab');
  await harness.dispatchEvent(fixedTab, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /企微全链路/));
  assert.ok(requests.some((url) => url === 'GET /demo/v1/scenarios'), 'scenarios must load from the demo API');
});

test('running a fixed scenario stays in the DEMO scope with its own run number', async () => {
  const requests: string[] = [];
  globalThis.fetch = demoFetch(requests);

  await harness.mount(['/demo/order']);
  const fixedTab = [...document.querySelectorAll<HTMLElement>('.ant-tabs-tab')]
    .find((tab) => tab.textContent?.includes('固定演示场景'));
  assert.ok(fixedTab);
  await harness.dispatchEvent(fixedTab, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /企微全链路/));

  const scenarioCard = [...document.querySelectorAll<HTMLElement>('div')]
    .find((element) => element.textContent?.includes('企微全链路')
      && element.getAttribute('style')?.includes('cursor: pointer'));
  assert.ok(scenarioCard, 'the fixed scenario must render a selectable card');
  await harness.dispatchEvent(scenarioCard, new MouseEvent('click', { bubbles: true }));

  const runButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('开始模拟下单'));
  assert.ok(runButton);
  await harness.dispatchEvent(runButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((url) => url === 'POST /demo/v1/scenarios'),
    'running a scenario must POST to the demo API',
  ));
  await harness.waitFor(() => assert.match(harness.bodyText(), /DEMO-20260814-001/));
  assert.match(harness.bodyText(), /DEMO-ORD-9001/);
  assert.match(harness.bodyText(), /数据域/);
  assert.match(harness.bodyText(), /DEMO/);
  assert.match(harness.bodyText(), /本次会话运行记录/);
});
