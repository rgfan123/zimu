import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/system/fulfillment-providers');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
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
  wecom_group_chat_id: null,
  ...overrides,
});

test('履约方配置 renders the wecom group chat column with registered and missing values', async () => {
  globalThis.fetch = async () =>
    jsonResponse([
      providerFixture({ wecom_group_chat_id: 'wrJgVnTQAAD001' }),
      providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        tracking_sla_minutes: 1440,
      }),
    ]);

  await harness.mount(['/system/fulfillment-providers']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东云仓/));
  assert.match(harness.bodyText(), /企微群/);
  assert.match(harness.bodyText(), /wrJgVnTQAAD001/);
  assert.match(harness.bodyText(), /未登记/);
});

test('履约方配置 edit modal submits the registered group chat id in config', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url === '/api/v1/fulfillment-providers' && init?.method !== 'PATCH') {
      return jsonResponse([providerFixture({ id: '12', provider_code: 'TP', provider_name: '第三方履约', provider_type: 'THIRD_PARTY' })]);
    }
    if (url === '/api/v1/fulfillment-providers/12' && init?.method === 'PATCH') {
      return jsonResponse(providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        version: 1,
        wecom_group_chat_id: 'wrJgVnTQAAD003',
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/fulfillment-providers']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /第三方履约/));

  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink, 'provider directory must expose the edit action');
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /企微群 chatid/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const chatIdInput = document.querySelector<HTMLInputElement>('input[placeholder*="chatid"]');
  assert.ok(chatIdInput, 'the editor must expose the group chat id input');
  await act(async () => {
    Simulate.change(chatIdInput, { target: { value: 'wrJgVnTQAAD003' } });
  });

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton, 'the provider editor must expose a confirm action');
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH'),
    'confirm must submit the versioned PATCH',
  ));
  const patch = requests.find((request) => request.method === 'PATCH');
  assert.match(patch?.body ?? '', /"expected_version":0/);
  // 第三方履约方此前不提交 config；登记 chatid 后请求载荷必须携带 wecomGroupChatId
  assert.match(patch?.body ?? '', /"wecomGroupChatId":"wrJgVnTQAAD003"/);
});

test('履约方配置 clearing the group chat id submits explicit null', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  let registered = false;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url === '/api/v1/fulfillment-providers' && init?.method !== 'PATCH') {
      return jsonResponse([providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        version: registered ? 1 : 0,
        wecom_group_chat_id: registered ? 'wrJgVnTQAAD004' : null,
      })]);
    }
    if (url === '/api/v1/fulfillment-providers/12' && init?.method === 'PATCH') {
      registered = true;
      return jsonResponse(providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        version: 1,
        wecom_group_chat_id: null,
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/fulfillment-providers']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /第三方履约/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const openEditor = async () => {
    const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
      .find((link) => link.textContent?.includes('编辑'));
    assert.ok(editLink);
    await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
    await harness.waitFor(() => assert.match(harness.bodyText(), /企微群 chatid/));
  };
  const submit = async () => {
    const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
    assert.ok(confirmButton);
    await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));
  };

  // 先登记
  await openEditor();
  const chatIdInput = document.querySelector<HTMLInputElement>('input[placeholder*="chatid"]');
  assert.ok(chatIdInput);
  await act(async () => {
    Simulate.change(chatIdInput, { target: { value: 'wrJgVnTQAAD004' } });
  });
  await submit();
  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH'),
    'confirm must submit the registration PATCH',
  ));
  const setPatch = requests.find((request) => request.method === 'PATCH');
  assert.match(setPatch?.body ?? '', /"wecomGroupChatId":"wrJgVnTQAAD004"/);

  // 清除：清空输入后保存必须提交显式 null（而非省略键）
  await openEditor();
  const clearedInput = document.querySelector<HTMLInputElement>('input[placeholder*="chatid"]');
  assert.ok(clearedInput);
  await act(async () => {
    Simulate.change(clearedInput, { target: { value: '' } });
  });
  await submit();
  await harness.waitFor(() => assert.ok(
    requests.filter((request) => request.method === 'PATCH').length > 1,
    'clearing must submit a second PATCH',
  ));
  const clearPatch = requests.filter((request) => request.method === 'PATCH').at(-1);
  assert.match(clearPatch?.body ?? '', /"wecomGroupChatId":null/);
});

test('履约方配置 reminder interval shares the config payload with the group chat id', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url === '/api/v1/fulfillment-providers' && init?.method !== 'PATCH') {
      return jsonResponse([providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        wecom_group_chat_id: 'wrJgVnTQAAD009',
        wecom_reminder_interval_minutes: null,
      })]);
    }
    if (url === '/api/v1/fulfillment-providers/12' && init?.method === 'PATCH') {
      return jsonResponse(providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        version: 1,
        wecom_group_chat_id: 'wrJgVnTQAAD009',
        wecom_reminder_interval_minutes: 120,
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/fulfillment-providers']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /第三方履约/));

  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink);
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /回传提醒间隔/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const intervalInput = document.querySelector<HTMLInputElement>('input[placeholder*="默认等于运单回传时限"]');
  assert.ok(intervalInput, 'the editor must expose the reminder interval input');
  await act(async () => {
    Simulate.change(intervalInput, { target: { value: '120' } });
  });

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH'),
    'confirm must submit the versioned PATCH',
  ));
  const patch = requests.find((request) => request.method === 'PATCH');
  // 提醒间隔与企微群 chatid 在同一 config 载荷中共存，保留其他键语义
  assert.match(patch?.body ?? '', /"wecomReminderIntervalMinutes":120/);
  assert.match(patch?.body ?? '', /"wecomGroupChatId":"wrJgVnTQAAD009"/);
  assert.match(patch?.body ?? '', /"expected_version":0/);
});

test('履约方配置 clearing the reminder interval submits explicit null', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  let registered = true;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url === '/api/v1/fulfillment-providers' && init?.method !== 'PATCH') {
      return jsonResponse([providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        wecom_reminder_interval_minutes: registered ? 120 : null,
      })]);
    }
    if (url === '/api/v1/fulfillment-providers/12' && init?.method === 'PATCH') {
      registered = false;
      return jsonResponse(providerFixture({
        id: '12',
        provider_code: 'TP',
        provider_name: '第三方履约',
        provider_type: 'THIRD_PARTY',
        version: 1,
        wecom_reminder_interval_minutes: null,
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/fulfillment-providers']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /第三方履约/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink);
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /回传提醒间隔/));

  const intervalInput = document.querySelector<HTMLInputElement>('input[placeholder*="默认等于运单回传时限"]');
  assert.ok(intervalInput);
  await act(async () => {
    Simulate.change(intervalInput, { target: { value: '' } });
  });
  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH'),
    'confirm must submit the PATCH',
  ));
  const patch = requests.find((request) => request.method === 'PATCH');
  assert.match(patch?.body ?? '', /"wecomReminderIntervalMinutes":null/);
});
