/**
 * 票 06/09：「原料库存」入口受上游接通状态控制，接通后读真数据——端到端（外壳 + 侧栏 + 页面）。
 *
 * businessObjectNavigation.test.ts 断言的是导航树那一层的两态结果；这里断言的是**接线**：
 * 外壳真的把 `GET /api/v1/business-modules` 的答案用到了侧栏，页面真的照同一份答案说话，
 * 且票 09 之后模块开放的页面真的去读 `GET /api/v1/raw-material-inventory/stock` 并按
 * business_code 码表区分失败措辞。
 *
 * 菜单的两态断言刻意停在 `/inventory/overview`：「商品与主数据」是默认折叠组，只有当前路由
 * 在组内时才强制展开——停在总库存上，两态下这一组都是展开的，唯一的差别才真的是原料库存
 * 这一个链接（若停在原料库存页，未接通时整组会因为不含当前路由而收起，差别就说明不了问题）。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { apiErrorResponse, createRouteHarness, jsonResponse, type RouteHarness } from './routeHarness.ts';

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

/** 结存单行夹具：与后端契约测试同一形状（kg 三列 decimal-string）。 */
function stockItem(overrides: Record<string, unknown> = {}) {
  return {
    material_id: 7,
    material_code: 'RM-007',
    material_name: '雷山黑猪前腿',
    category: '猪肉',
    spec: '冻品',
    unit: 'kg',
    piece_count: 12,
    current_kg: '103.5',
    available_kg: '90.25',
    frozen_kg: '13.25',
    batch_count: 3,
    earliest_expiry: '2026-11-02',
    status: 'near_expiry',
    ...overrides,
  };
}

/**
 * 原料库存接通与否只改 business-modules 一个应答；stock 应答由各用例注入，
 * 其余接口两态完全一致，确保差异只来自清单与结存本身。
 */
function fetchWith(openModules: string[], stock?: () => Response) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.startsWith('/api/v1/business-modules')) return jsonResponse({ modules: openModules });
    if (url.startsWith('/api/v1/raw-material-inventory/stock')) {
      if (!stock) throw new Error(`unexpected stock request ${url}`);
      return stock();
    }
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

test('未接通时直达仍渲染页面：说清是「读不到」，且一个结存数字都不显示、不发取数请求', async () => {
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

test('接通且读取成功：结存表格按 decimal-string 原样呈现，不再出现任何「读不到」措辞', async () => {
  globalThis.fetch = fetchWith(
    ['raw-material-inventory'],
    () => jsonResponse({ source: 'YUANLIAOKC', items: [stockItem(), stockItem({
      material_id: 8,
      material_code: 'RM-008',
      material_name: '糯米',
      category: null,
      spec: null,
      piece_count: null,
      current_kg: '40',
      available_kg: '40',
      frozen_kg: '0',
      batch_count: 1,
      earliest_expiry: null,
      status: 'normal',
    })] }),
  );
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RM-007/));
  const body = harness.bodyText();

  assert.match(body, /雷山黑猪前腿/);
  // kg 三列 decimal-string 原样出屏（103.5 就是 103.5，不是 parseFloat 后的再格式化）。
  assert.match(body, /103\.5 kg/);
  assert.match(body, /90\.25 kg/);
  assert.match(body, /13\.25 kg/);
  assert.match(body, /临期/, 'near_expiry 以警示 Tag 呈现');
  assert.match(body, /正常/, 'normal 以正常 Tag 呈现');
  assert.doesNotMatch(body, NOT_A_ZERO, '读到了数据就不再出现失败收尾句');
  assert.doesNotMatch(body, NOT_CONNECTED);
  assert.doesNotMatch(body, /当前无在库物料/, '有数据时不得出现空态措辞');
});

test('接通但取数失败：按 business_code 说清原因，一个结存数字、一张空表都不显示', async () => {
  globalThis.fetch = fetchWith(
    ['raw-material-inventory'],
    () => apiErrorResponse(503, 'RAW_MATERIAL_UNAVAILABLE', '上游暂不可用'),
  );
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /原料库存暂时读不到/));
  const body = harness.bodyText();

  assert.match(body, NOT_A_ZERO, '取数失败同样是「读不到」，不是「没有」');
  assert.doesNotMatch(body, /暂无数据/, '失败不得呈现为空表');
  assert.doesNotMatch(body, /当前无在库物料/, '失败不得冒充「读到了没有」');
  assert.doesNotMatch(body, /RM-|kg\b|物料编码/, '失败时不渲染表格与任何结存数字');
  assert.doesNotMatch(body, NOT_CONNECTED, '模块开放时的取数失败不得误说成未接通');
});

test('契约漂移与未知码各说各话：漂移必须停下，认不出的码不猜含义', async () => {
  globalThis.fetch = fetchWith(
    ['raw-material-inventory'],
    () => apiErrorResponse(502, 'RAW_MATERIAL_CONTRACT_DRIFT', '结构漂移'),
  );
  await harness.mount(['/inventory/raw-materials']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /原料库存返回结构与约定不一致/));
  assert.match(harness.bodyText(), NOT_A_ZERO);
  await harness.unmount();

  globalThis.fetch = fetchWith(
    ['raw-material-inventory'],
    () => apiErrorResponse(500, 'SOMETHING_ELSE', '未知失败'),
  );
  await harness.mount(['/inventory/raw-materials']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /原料库存读取失败/));
  assert.match(harness.bodyText(), /不在已知分类里/);
});

test('接通且 items 为空：「当前无在库物料」是读到了没有，与「读不到」措辞可区分', async () => {
  globalThis.fetch = fetchWith(
    ['raw-material-inventory'],
    () => jsonResponse({ source: 'YUANLIAOKC', items: [] }),
  );
  await harness.mount(['/inventory/raw-materials']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /当前无在库物料/));
  const body = harness.bodyText();

  assert.match(body, /读取已成功/, '空清单必须先说明读取成功——这正是它与失败的分界');
  assert.doesNotMatch(body, NOT_A_ZERO, '合法的零在库不携带失败收尾句');
  assert.doesNotMatch(body, NOT_CONNECTED);
  assert.doesNotMatch(body, /暂无数据/, '成功空态也不用含混的「暂无数据」');
});
