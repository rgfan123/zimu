import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { createRouteHarness, jsonResponse, type RouteHarness } from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/system/jd-tools');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

/** 六个京东查询页挂载时会打状态接口；统一返回模拟模式即可。 */
function jdFetch() {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/status')) {
      return jsonResponse({ client_mode: 'MOCK', live_ready: false });
    }
    return jsonResponse({});
  };
}

test('京东工具单入口渲染页内 Tab（UIUX-10 #144）', async () => {
  globalThis.fetch = jdFetch();
  await harness.mount(['/system/jd-tools']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /京东工具/));
  for (const label of ['连接与出库查询', '基础资料查询', '库存原始查询', '序列号查询', '京东专业单据', '退货退供查询']) {
    assert.match(harness.bodyText(), new RegExp(label), `Tab「${label}」必须存在`);
  }
  // 默认激活第一个 Tab：连接与出库查询页内容可见
  await harness.waitFor(() => assert.match(harness.bodyText(), /京东仓配/));
});

test('旧直达 URL 打开对应 Tab（书签不失效）', async () => {
  globalThis.fetch = jdFetch();
  await harness.mount(['/fulfillment/jd-stock']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /库存原始查询/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /京东库存原始查询/));
});
