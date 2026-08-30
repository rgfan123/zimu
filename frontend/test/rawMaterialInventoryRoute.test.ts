/**
 * 票 06：「原料库存」入口受上游接通状态控制——端到端（外壳 + 侧栏 + 页面）。
 *
 * businessObjectNavigation.test.ts 断言的是导航树那一层的两态结果；这里断言的是**接线**：
 * 外壳真的把 `GET /api/v1/business-modules` 的答案用到了侧栏，页面真的照同一份答案说话。
 *
 * 菜单的两态断言刻意停在 `/inventory/overview`：「商品与主数据」是默认折叠组，只有当前路由
 * 在组内时才强制展开——停在总库存上，两态下这一组都是展开的，唯一的差别才真的是原料库存
 * 这一个链接（若停在原料库存页，未接通时整组会因为不含当前路由而收起，差别就说明不了问题）。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { createRouteHarness, jsonResponse, type RouteHarness } from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/inventory/raw-materials');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const NOT_CONNECTED = /原料库存未接通/;
const NOT_A_ZERO = /这是「读不到原料」，不是「没有原料」/;

/** 总库存页的最小夹具：本文件只关心侧栏，页面本身只要如实渲染出来即可。 */
function inventoryOverview() {
  return {
    items: [],
    page: 0,
    size: 20,
    total_elements: 0,
    total_pages: 0,
    coverage: {
      provider_count: 0,
      observed_provider_count: 0,
      sku_count: 0,
      observed_sku_count: 0,
      warehouse_count: 0,
      latest_observed_at: null,
      stale_count: 0,
      oldest_observed_at: null,
      partial: false,
      freshness_policy: 'PT15M',
    },
  };
}

/** 原料库存接通与否只改这一个应答；其余接口两态完全一致，确保差异只来自清单本身。 */
function fetchWith(openModules: string[]) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-modules')) return jsonResponse({ modules: openModules });
    if (url.startsWith('/api/v1/inventory/overview')) return jsonResponse(inventoryOverview());
    if (url === '/api/v1/fulfillment-providers') return jsonResponse([]);
    if (url.startsWith('/api/v1/skus')) {
      return jsonResponse({ items: [], page: 0, size: 500, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request ${url}`);
  };
}

/** 侧栏某个分组当前渲染出的可见入口名称（按 DOM 顺序）。 */
function railItems(groupTitle: string): string[] {
  const toggle = [...document.querySelectorAll<HTMLButtonElement>('.zs-nav .zs-grp-toggle')]
    .find((button) => button.textContent?.includes(groupTitle));
  if (!toggle?.parentElement) throw new Error(`missing rail group: ${groupTitle}`);
  return [...toggle.parentElement.querySelectorAll('a')]
    .map((link) => link.querySelector('.nm')?.textContent?.trim() ?? '');
}

test('上游未接通：侧边栏没有原料库存，同组其他入口一个不少', async () => {
  globalThis.fetch = fetchWith([]);
  await harness.mount(['/inventory/overview']);

  await harness.waitFor(() => {
    assert.deepEqual(
      railItems('商品与主数据'),
      ['商品档案', 'SKU 映射', '静态礼包', '总库存'],
      '未接通时「原料库存」不出现在侧边栏，且不影响同组其他入口',
    );
  });
  assert.equal(document.querySelector('.zs-nav a[href="/inventory/raw-materials"]'), null);
});

test('上游接通：原料库存出现在总库存之后，label 与位置都不被过滤层改写', async () => {
  globalThis.fetch = fetchWith(['raw-material-inventory']);
  await harness.mount(['/inventory/overview']);

  await harness.waitFor(() => {
    assert.deepEqual(
      railItems('商品与主数据'),
      ['商品档案', 'SKU 映射', '静态礼包', '总库存', '原料库存'],
      '接通后原料库存排在总库存之后（可见叶子 4 → 5，不超准入上限 6）',
    );
  });
  const link = document.querySelector<HTMLAnchorElement>('.zs-nav a[href="/inventory/raw-materials"]');
  assert.equal(link?.querySelector('.nm')?.textContent?.trim(), '原料库存');
});

test('未接通时直达仍渲染页面：说清是「读不到」，且一个结存数字都不显示', async () => {
  globalThis.fetch = fetchWith([]);
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), NOT_CONNECTED));
  const body = harness.bodyText();

  assert.equal(harness.location(), '/inventory/raw-materials', '降级 ≠ 删除：URL 直达照常可达');
  assert.match(body, NOT_A_ZERO, '必须区分「读不到原料」与「没有原料」');
  assert.doesNotMatch(body, /暂无数据/, '空态词会把故障读成没有库存');
  assert.doesNotMatch(body, /结存\s*[:：]?\s*0|总量\s*[:：]?\s*0/, '未接通时不得显示任何结存数字');
  assert.match(body, /不做出入库写入/, '页面如实写明本期只读范围');
  assert.doesNotMatch(body, /够不够[^—]*可以|原料充足|库存充足/, '不得出现「这单原料够不够」这类推断');
});

test('清单读不到时按未接通处置：菜单与页面口径一致，不各说各话', async () => {
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-modules')) throw new Error('network down');
    throw new Error(`unexpected request ${url}`);
  };
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), NOT_CONNECTED));
  assert.match(harness.bodyText(), NOT_A_ZERO);
  assert.equal(
    document.querySelector('.zs-nav a[href="/inventory/raw-materials"]'),
    null,
    '读不到清单时保守：菜单不放出入口，页面也不假称已接通',
  );
});

test('接通但本版本还没接上取数：如实说明，仍不显示任何结存数字', async () => {
  globalThis.fetch = fetchWith(['raw-material-inventory']);
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /原料库存已接通，但本页还没有接上取数/));
  const body = harness.bodyText();

  assert.match(body, NOT_A_ZERO, '没接上取数同样是「读不到」，不是「没有」');
  assert.doesNotMatch(body, /暂无数据/);
  assert.doesNotMatch(body, NOT_CONNECTED, '已接通就不能再说未接通');
});
