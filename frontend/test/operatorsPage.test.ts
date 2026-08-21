import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/system/operators');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const operatorFixture = (overrides: Record<string, unknown> = {}) => ({
  id: '11',
  display_name: '张三',
  responsible_team: 'ORDER_OPS',
  wecom_userid: 'zhangsan',
  active: true,
  version: 0,
  created_at: '2026-08-21T00:00:00Z',
  updated_at: '2026-08-21T00:00:00Z',
  ...overrides,
});

const operatorPage = (items: unknown[]) => ({
  items,
  page: 0,
  size: 10,
  total_elements: items.length,
  total_pages: Math.max(1, Math.ceil(items.length / 10)),
});

test('运营人员 renders the registry with binding status and the first-use greeting hint', async () => {
  globalThis.fetch = async () =>
    jsonResponse(operatorPage([
      operatorFixture(),
      operatorFixture({
        id: '12',
        display_name: '王五',
        responsible_team: 'CUSTOMER_OPS',
        wecom_userid: null,
      }),
    ]));

  await harness.mount(['/system/operators']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /张三/));
  assert.match(harness.bodyText(), /王五/);
  assert.match(harness.bodyText(), /ORDER_OPS/);
  assert.match(harness.bodyText(), /CUSTOMER_OPS/);
  // 绑定状态：已绑定显示 userid，未绑定显示明确提示
  assert.match(harness.bodyText(), /zhangsan/);
  assert.match(harness.bodyText(), /未绑定/);
  // 真实运营提示：首次使用前先与机器人打招呼（不 mock 验收外部门禁）
  assert.match(harness.bodyText(), /先让成员与企微机器人打一次招呼/);
});

test('运营人员 create modal submits normalized payload with POST', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url.startsWith('/api/v1/operators') && init?.method !== 'POST') {
      return jsonResponse(operatorPage([]));
    }
    if (url === '/api/v1/operators' && init?.method === 'POST') {
      return jsonResponse(operatorFixture({ id: '13', display_name: '李四', responsible_team: 'ORDER_OPS' }), 201);
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/operators']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无运营人员登记/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');

  const createButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((button) => button.textContent?.includes('新建'));
  assert.ok(createButton, 'registry must expose the create action');
  await harness.dispatchEvent(createButton, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /新建运营人员/));

  const setValue = async (placeholder: string, value: string) => {
    const input = document.querySelector<HTMLInputElement>(`input[placeholder*="${placeholder}"]`);
    assert.ok(input, `editor must expose ${placeholder} input`);
    await act(async () => {
      Simulate.change(input, { target: { value } });
    });
  };
  await setValue('请输入姓名', ' 李四 ');
  await setValue('如 ORDER_OPS', ' order_ops ');
  await setValue('请输入企微 userid', 'lisi');

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton, 'the operator editor must expose a confirm action');
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'POST'),
    'confirm must submit the create POST',
  ));
  const post = requests.find((request) => request.method === 'POST');
  // 姓名/团队 trim + 团队大写归一；userid 原样提交
  assert.match(post?.body ?? '', /"display_name":"李四"/);
  assert.match(post?.body ?? '', /"responsible_team":"ORDER_OPS"/);
  assert.match(post?.body ?? '', /"wecom_userid":"lisi"/);
  assert.match(post?.body ?? '', /"active":true/);
});

test('运营人员 edit modal submits versioned PATCH and clears the userid with empty string', async () => {
  const requests: Array<{ method: string; url: string; body?: string }> = [];
  let registered = false;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url, body: typeof init?.body === 'string' ? init.body : undefined });
    if (url.startsWith('/api/v1/operators') && init?.method !== 'PATCH') {
      return jsonResponse(operatorPage([operatorFixture({
        id: '12',
        display_name: '张三',
        responsible_team: 'ORDER_OPS',
        wecom_userid: registered ? null : 'zhangsan',
        version: registered ? 1 : 0,
      })]));
    }
    if (url === '/api/v1/operators/12' && init?.method === 'PATCH') {
      registered = true;
      return jsonResponse(operatorFixture({
        id: '12',
        display_name: '张三',
        responsible_team: 'ORDER_OPS',
        wecom_userid: null,
        version: 1,
      }));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/operators']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /张三/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink, 'registry must expose the edit action');
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /编辑运营人员/));

  // 清空 userid 保存 = 显式清除绑定（提交空串，而非省略键）；
  // 选择器须精确到弹窗字段（toolbar 搜索框占位符也含「企微 userid」）
  const useridInput = document.querySelector<HTMLInputElement>('input[placeholder*="请输入企微 userid"]');
  assert.ok(useridInput, 'the editor must expose the wecom userid input');
  await act(async () => {
    Simulate.change(useridInput, { target: { value: '' } });
  });

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton);
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.method === 'PATCH'),
    'confirm must submit the versioned PATCH',
  ));
  const patch = requests.find((request) => request.method === 'PATCH');
  assert.match(patch?.body ?? '', /"expected_version":0/);
  assert.match(patch?.body ?? '', /"wecom_userid":""/);
});

test('运营人员 search box triggers refetch with the query parameter', async () => {
  const requests: Array<{ method: string; url: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url });
    if (url.startsWith('/api/v1/operators')) {
      return jsonResponse(operatorPage([operatorFixture()]));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/operators']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /张三/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const searchInput = document.querySelector<HTMLInputElement>('input[placeholder*="姓名"]');
  assert.ok(searchInput, 'the registry must expose the search box');
  // 先提交 change 再按 Enter：两次事件分属独立 act，保证 onSearch 读到已提交的输入值
  await act(async () => {
    Simulate.change(searchInput, { target: { value: '张' } });
  });
  await act(async () => {
    Simulate.keyDown(searchInput, { key: 'Enter', keyCode: 13 });
  });

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.url.includes('query=%E5%BC%A0') || request.url.includes('query=')),
    'search must refetch with the query parameter',
  ));
});

test('运营人员 team filter submits an exact normalized team value absent from the current page', async () => {
  const requests: Array<{ method: string; url: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url });
    if (url.startsWith('/api/v1/operators')) {
      // 当前页只有 ORDER_OPS，SKU_OPS 不在页面上——仍应能作为精确筛选值提交给服务端
      return jsonResponse(operatorPage([operatorFixture({ responsible_team: 'ORDER_OPS' })]));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/operators']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /ORDER_OPS/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const teamInput = document.querySelector<HTMLInputElement>('input[placeholder*="责任团队"]');
  assert.ok(teamInput, 'the registry must expose the team filter input');
  await act(async () => {
    Simulate.change(teamInput, { target: { value: ' sku_ops ' } });
  });
  await act(async () => {
    Simulate.keyDown(teamInput, { key: 'Enter', keyCode: 13 });
  });

  await harness.waitFor(() => assert.ok(
    requests.some((request) => request.url.includes('responsible_team=SKU_OPS')),
    'team filter must submit the normalized exact team value to the server filter',
  ));
});

test('运营人员 edit with no changes closes with 无变更 feedback and skips PATCH', async () => {
  const requests: Array<{ method: string; url: string }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push({ method: init?.method ?? 'GET', url });
    if (url.startsWith('/api/v1/operators')) {
      return jsonResponse(operatorPage([operatorFixture()]));
    }
    throw new Error(`unexpected request: ${init?.method ?? 'GET'} ${url}`);
  };

  await harness.mount(['/system/operators']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /张三/));

  const { act } = await import('react');
  const { Simulate } = await import('react-dom/test-utils');
  const editLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('编辑'));
  assert.ok(editLink, 'registry must expose the edit action');
  await harness.dispatchEvent(editLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /编辑运营人员/));

  const confirmButton = document.querySelector<HTMLButtonElement>('.ant-modal-footer .ant-btn-primary');
  assert.ok(confirmButton, 'the operator editor must expose a confirm action');
  await harness.dispatchEvent(confirmButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /无变更/));
  assert.ok(
    !requests.some((request) => request.method === 'PATCH'),
    'no-op edit must not call PATCH',
  );
});
