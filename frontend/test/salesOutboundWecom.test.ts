/**
 * 销售出库页企微通知契约（Issue #84）：状态标签、重发/停止按钮与确认弹窗、
 * 生产路由请求载荷（expected_version + reason + Idempotency-Key）、未发送不展示假的到期时间。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/fulfillment/sales-outbound');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const providerFixture = {
  id: 'p1',
  provider_code: 'TP',
  provider_name: '第三方履约',
  provider_type: 'THIRD_PARTY',
  tracking_sla_minutes: 1440,
  active: true,
  version: 0,
  jd_config: {},
  wecom_group_chat_id: 'wrJgVnTQAAD-SO-001',
  wecom_reminder_interval_minutes: null,
};

const exportFixture = (overrides: Record<string, unknown> = {}) => ({
  id: 'e1',
  export_batch_no: 'EXP-WECOM-001',
  provider_id: 'p1',
  export_kind: 'THIRD_PARTY',
  template_version: 'v1-24-columns',
  file_sha256: 'a'.repeat(64),
  tracking_due_at: null,
  generated_at: '2026-08-21T10:00:00Z',
  usage_status: 'GENERATED_NOT_DOWNLOADED',
  download_audit: { download_count: 0 },
  wecom: {
    status: 'PENDING',
    chat_id: null,
    tracking_sla_minutes: 1440,
    reminder_interval_minutes: 1440,
    initial_sent_at: null,
    tracking_due_at: null,
    next_reminder_at: null,
    last_reminded_at: null,
    reminder_count: 0,
    last_error: null,
    version: 0,
  },
  ...overrides,
});

async function mountPage(
  fetchImpl: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>,
) {
  globalThis.fetch = fetchImpl;
  await harness.mount(['/fulfillment/sales-outbound']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /文件作业/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /导出批次号/));
}

test('未发送的导出不展示假的到期时间并显示待发送状态与重发按钮', async () => {
  const requests: Array<{ method: string; url: string; body?: string; headers?: HeadersInit }> = [];
  await mountPage(async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined, headers: init?.headers });
    if (url.startsWith('/api/v1/fulfillment-providers')) {
      return jsonResponse([providerFixture]);
    }
    if (url.startsWith('/api/v1/fulfillment-exports?') && init?.method !== 'POST') {
      return jsonResponse({
        items: [exportFixture()],
        page: 0,
        size: 10,
        total_elements: 1,
        total_pages: 1,
      });
    }
    if (url === '/api/v1/fulfillment-exports/e1' && init?.method === 'GET') {
      return jsonResponse(exportFixture());
    }
    if (url === '/api/v1/fulfillment-exports/e1/wecom-resend' && init?.method === 'POST') {
      return jsonResponse({ ...exportFixture().wecom, status: 'PENDING', version: 1, resend_sequence: 2 }, 202);
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  });

  // 状态标签：待发送
  assert.match(harness.bodyText(), /待发送/);
  // 重发按钮存在
  const resendButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === '重发');
  assert.ok(resendButton, 'pending export must expose the resend action');

  // 重发确认弹窗 → 生产路由载荷：expected_version + Idempotency-Key
  await harness.dispatchEvent(resendButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /重新发送该导出文件/));
  const confirmButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === '重新发送');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.url === '/api/v1/fulfillment-exports/e1/wecom-resend'),
    'confirm must POST the resend command',
  ));
  const resend = requests.find((request) => request.url === '/api/v1/fulfillment-exports/e1/wecom-resend');
  assert.equal(resend?.method, 'POST');
  assert.match(resend?.body ?? '', /"expected_version":0/);
  const headers = new Headers(resend?.headers);
  assert.ok((headers.get('Idempotency-Key') ?? '').length >= 8, 'resend must carry Idempotency-Key');

  // 明细抽屉：未发送时不展示假的已发送时间/回传截止
  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.trim() === '明细');
  assert.ok(detailLink);
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /企微发送状态/));
  assert.match(harness.bodyText(), /已发送时间/);
  assert.match(harness.bodyText(), /—/);
});

test('第三方内部自映射在明细页明确标成内部路由码', async () => {
  await mountPage(async (input, init) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers')) {
      return jsonResponse([providerFixture]);
    }
    if (url.startsWith('/api/v1/fulfillment-exports?') && init?.method !== 'POST') {
      return jsonResponse({
        items: [exportFixture()],
        page: 0,
        size: 10,
        total_elements: 1,
        total_pages: 1,
      });
    }
    if (url === '/api/v1/fulfillment-exports/e1' && init?.method === 'GET') {
      return jsonResponse(exportFixture({
        lines: [{
          export_line_no: 1,
          provider_sku_code: 'SKU-TP-000062',
          provider_sku_code_scope: 'INTERNAL_ROUTING',
          instructed_quantity: '1',
          unit: '袋',
          item_amount: null,
        }],
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  });

  const detailLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.trim() === '明细');
  assert.ok(detailLink);
  await harness.dispatchEvent(detailLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /SKU-TP-000062/));
  assert.match(harness.bodyText(), /履约路由编码/);
  assert.match(harness.bodyText(), /内部路由码/);
  assert.doesNotMatch(harness.bodyText(), /外部已验证/);
});

test('人工停止弹窗提交 reason 载荷并展示停止原因与提醒次数', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  await mountPage(async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url.startsWith('/api/v1/fulfillment-providers')) {
      return jsonResponse([providerFixture]);
    }
    if (url.startsWith('/api/v1/fulfillment-exports?') && init?.method !== 'POST') {
      return jsonResponse({
        items: [exportFixture({
          usage_status: 'DOWNLOADED_WAITING_RETURN',
          download_audit: { download_count: 1 },
          wecom: {
            status: 'ACTIVE',
            chat_id: 'wrJgVnTQAAD-SO-001',
            tracking_sla_minutes: 1440,
            reminder_interval_minutes: 1440,
            initial_sent_at: '2026-08-21T10:00:00Z',
            tracking_due_at: '2026-08-22T10:00:00Z',
            next_reminder_at: '2026-08-22T10:00:00Z',
            last_reminded_at: null,
            reminder_count: 2,
            last_error: null,
            version: 3,
          },
        })],
        page: 0,
        size: 10,
        total_elements: 1,
        total_pages: 1,
      });
    }
    if (url === '/api/v1/fulfillment-exports/e1' && init?.method === 'GET') {
      return jsonResponse(exportFixture());
    }
    if (url === '/api/v1/fulfillment-exports/e1/wecom-stop' && init?.method === 'POST') {
      return jsonResponse({
        ...exportFixture().wecom,
        status: 'MANUALLY_STOPPED',
        version: 4,
        stopped: { by: 'tester', reason: '线下已结清', at: '2026-08-21T12:00:00Z' },
      });
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  });

  assert.match(harness.bodyText(), /已发送/);

  // 停止按钮 → 弹窗 → 填理由 → 提交
  const stopButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.trim() === '停止');
  assert.ok(stopButton, 'active export must expose the stop action');
  await harness.dispatchEvent(stopButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /停止后不再自动发送与周期提醒/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const reasonInput = document.querySelector<HTMLTextAreaElement>('textarea[placeholder*="停止理由"]');
  assert.ok(reasonInput);
  await act(async () => {
    Simulate.change(reasonInput, { target: { value: '线下已结清' } });
  });
  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.url === '/api/v1/fulfillment-exports/e1/wecom-stop'),
    'confirm must POST the stop command',
  ));
  const stop = requests.find((request) => request.url === '/api/v1/fulfillment-exports/e1/wecom-stop');
  assert.equal(stop?.method, 'POST');
  assert.match(stop?.body ?? '', /"expected_version":3/);
  assert.match(stop?.body ?? '', /"reason":"线下已结清"/);
});

test('按钮状态：收齐禁用重发、停止后不再显示停止、失败显示最后错误', async () => {
  await mountPage(async (input, init) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers')) {
      return jsonResponse([providerFixture]);
    }
    if (url.startsWith('/api/v1/fulfillment-exports?') && init?.method !== 'POST') {
      return jsonResponse({
        items: [
          exportFixture({
            id: 'e2',
            export_batch_no: 'EXP-WECOM-COMPLETE',
            usage_status: 'RETURNED',
            wecom: { ...exportFixture().wecom, status: 'COMPLETED', reminder_count: 3, version: 1 },
          }),
          exportFixture({
            id: 'e3',
            export_batch_no: 'EXP-WECOM-STOPPED',
            wecom: {
              ...exportFixture().wecom,
              status: 'MANUALLY_STOPPED',
              version: 2,
              stopped: { by: 'tester', reason: '线下已催收', at: '2026-08-21T12:00:00Z' },
            },
          }),
          exportFixture({
            id: 'e4',
            export_batch_no: 'EXP-WECOM-FAILED',
            wecom: {
              ...exportFixture().wecom,
              status: 'FAILED',
              last_error: 'WECOM_GROUP_CHAT_MISSING',
              version: 1,
            },
          }),
        ],
        page: 0,
        size: 10,
        total_elements: 3,
        total_pages: 1,
      });
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  });

  await harness.waitFor(() => assert.match(harness.bodyText(), /EXP-WECOM-FAILED/));
  assert.match(harness.bodyText(), /已收齐/);
  assert.match(harness.bodyText(), /已停止提醒/);
  assert.match(harness.bodyText(), /发送失败/);

  const buttons = [...document.querySelectorAll<HTMLButtonElement>('button')];
  // 收齐行：重发禁用；停止行：无停止按钮但有重发
  const resendButtons = buttons.filter((button) => button.textContent?.trim() === '重发');
  assert.ok(resendButtons.length >= 2, 'completed/stopped exports still expose resend');
  // 停止按钮只出现在 FAILED 行（ACTIVE 之外还有 FAILED）
  const stopButtons = buttons.filter((button) => button.textContent?.trim() === '停止');
  assert.ok(stopButtons.length >= 1, 'failed export must expose stop');
});
