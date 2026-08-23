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

test('刷新后岗位保留；未知团队值原样显示而不是崩溃或丢弃', async () => {
  window.localStorage.setItem('zimu.workbench-role', 'FULFILLMENT_OPS');
  globalThis.fetch = async () => jsonResponse(page([]));
  await harness.mount(['/workbench/reviews']);
  assert.match(harness.bodyText(), /履约运营/, '已选岗位在重新挂载后保留');
  await harness.unmount();

  window.localStorage.setItem('zimu.workbench-role', 'MYSTERY_TEAM');
  await harness.mount(['/workbench/reviews']);
  assert.match(harness.bodyText(), /MYSTERY_TEAM/, '未知团队按 D2 原样显示');
});
