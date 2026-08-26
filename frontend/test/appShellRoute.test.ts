import assert from 'node:assert/strict';
import test, { after, afterEach, before } from 'node:test';
import { control, createRouteHarness, jsonResponse, page, type RouteHarness } from './routeHarness.ts';

/**
 * Issue #104：应用外壳（原型形态契约 ADR 0001/0002）。
 * 断言对象是壳层行为：无顶栏、岗位选择器（localStorage、首次未选择态、跳转、URL 不携带岗位）、
 * 「我的工作台」板块露出、Demo 不在日常菜单。页面内容一律用空数据打桩。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/reviews');
});

after(async () => {
  await harness.close();
});

afterEach(async () => {
  await harness.unmount();
});

function click(target: Element) {
  return harness.dispatchEvent(target, new window.MouseEvent('click', { bubbles: true, cancelable: true }));
}

test('外壳无顶栏，品牌下方是未选择态的岗位选择器，我的工作台入口露出', async () => {
  window.localStorage.clear();
  globalThis.fetch = async () => jsonResponse(page([]));
  await harness.mount(['/workbench/reviews']);

  const body = harness.bodyText();
  assert.match(body, /子牧履约中台/);
  assert.match(body, /请选择岗位/, '首次进入必须显示未选择态，不得默认某个岗位');
  assert.match(body, /共享网关身份/, '用户区如实显示 Nginx 注入的共享身份');
  assert.doesNotMatch(body, /业务运营/, '旧顶栏的硬编码环境 Tag 必须随顶栏一起删除');
  assert.equal(
    [...document.querySelectorAll('button')].some((candidate) =>
      candidate.getAttribute('aria-label')?.includes('菜单'),
    ),
    false,
    '侧栏折叠按钮随顶栏删除（原型无折叠态）',
  );

  assert.match(body, /我的工作台/);
  assert.match(body, /今日发货工作台/);
  assert.match(body, /对账工作台/);
  assert.doesNotMatch(body, /模拟下单/, 'Demo 页面不再出现在日常菜单（URL 保留）');
  // UIUX-10 #144：京东工具收敛为单入口——系统管理只渲染「京东工具」叶子，六个查询页不再占菜单。
  assert.ok(
    body.indexOf('操作审计') !== -1 &&
      body.indexOf('京东工具') !== -1 &&
      body.indexOf('操作审计') < body.indexOf('京东工具'),
    '系统管理的直属条目必须渲染在京东工具入口之前',
  );
  assert.doesNotMatch(body, /连接与出库查询/, '六个京东查询页不再直接出现在菜单');
});

test('选择岗位后跳到该岗位工作台、写入 localStorage，URL 不携带岗位', async () => {
  window.localStorage.clear();
  globalThis.fetch = async () => jsonResponse(page([]));
  await harness.mount(['/workbench/reviews']);

  await click(control('请选择岗位'));
  await harness.waitFor(() => control('履约运营'));
  await click(control('履约运营'));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/shipping'));
  assert.equal(window.localStorage.getItem('zimu.workbench-role'), 'FULFILLMENT_OPS');
  assert.doesNotMatch(harness.location(), /FULFILLMENT|role|岗位/, '分享 URL 不得携带岗位');

  // ADR 0004：岗位只重排导航分组顺序，绝不隐藏——切岗后全部板块仍可见（D1：岗位 ≠ 权限）。
  const reordered = harness.bodyText();
  for (const section of ['主数据', '系统管理', '京东工具', 'Agent 中心', '经营分析']) {
    assert.match(reordered, new RegExp(section), `切换岗位后「${section}」板块必须仍然可见`);
  }
  // 履约运营的分组优先序把库存中心排到主数据之前；未列入优先表的分组保持默认相对顺序。
  assert.ok(reordered.indexOf('库存中心') < reordered.indexOf('主数据'), '履约运营岗位下库存中心应排在主数据之前');
  assert.ok(
    reordered.indexOf('操作审计') < reordered.indexOf('京东工具'),
    '切岗重排后京东工具入口仍须排在系统管理自身条目之后',
  );
});

test('全局搜索是诚实入口：说明未接入跨对象搜索，回车直达订单查询', async () => {
  window.localStorage.clear();
  // 跳转目标 /orders 会拉分页列表与 fulfillment-providers（裸数组），按 URL 分流打桩。
  globalThis.fetch = async (input: RequestInfo | URL) =>
    String(input).includes('/fulfillment-providers') ? jsonResponse([]) : jsonResponse(page([]));
  await harness.mount(['/workbench/reviews']);

  await click(control('搜单号'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /尚未接入后端/));

  const input = document.querySelector<HTMLInputElement>('.zs-so-input');
  assert.ok(input, '搜索 overlay 必须渲染输入框');
  const setValue = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
  setValue?.call(input, 'PO-20260823');
  await harness.dispatchEvent(input, new window.Event('input', { bubbles: true }));
  await harness.dispatchEvent(
    input,
    new window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }),
  );

  await harness.waitFor(() => assert.equal(harness.location(), '/orders?query=PO-20260823'));
});

test('财务岗位落地对账工作台', async () => {
  window.localStorage.clear();
  globalThis.fetch = async () => jsonResponse(page([]));
  await harness.mount(['/workbench/reviews']);

  await click(control('请选择岗位'));
  await harness.waitFor(() => control('财务'));
  await click(control('财务'));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/recon'));
});

test('刷新后岗位保留；复核徽标显示岗位团队的真实 OPEN 计数；未知团队值原样显示', async () => {
  window.localStorage.setItem('zimu.workbench-role', 'FULFILLMENT_OPS');
  const badgeRequests: string[] = [];
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/review-cases') && url.includes('size=1')) {
      badgeRequests.push(url);
      return jsonResponse({ ...page([]), total_elements: 7 });
    }
    return jsonResponse(page([]));
  };
  await harness.mount(['/workbench/reviews']);
  assert.match(harness.bodyText(), /履约运营/, '已选岗位在重新挂载后保留');

  // 徽标契约（ADR 0004）：size=1 只取 total_elements、按岗位团队过滤，显示真实计数。
  await harness.waitFor(() => {
    const badge = document.querySelector('.zs-nav a .bg');
    assert.equal(badge?.textContent, '7', '复核收件箱徽标应显示团队 OPEN 总数');
  });
  assert.ok(
    badgeRequests.some((url) => url.includes('responsible_team=FULFILLMENT_OPS')),
    '徽标计数必须按岗位团队过滤',
  );
  await harness.unmount();

  window.localStorage.setItem('zimu.workbench-role', 'MYSTERY_TEAM');
  await harness.mount(['/workbench/reviews']);
  assert.match(harness.bodyText(), /MYSTERY_TEAM/, '未知团队按 D2 原样显示');
});
