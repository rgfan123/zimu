/**
 * 来源礼包映射页签的路由级验收：/product/sku-mappings?tab=bundle。
 * 盯两件事——页签懒挂载（SKU 矩阵不跟着发请求），以及列表按后端投影字段照实呈现。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { createRouteHarness, jsonResponse, page, type RouteHarness } from './routeHarness.ts';

let harness: RouteHarness;

const bundleMappings = [
  {
    id: '901',
    code: 'DZ-礼盒A',
    name: '大者年货礼盒',
    active: true,
    version: 3,
    attributes: {
      source_channel: 'DAZHE',
      source_barcode: null,
      bundle_id: '11',
      quantity_multiplier: '1',
      source_bundle_ref: 'DZ-礼盒A',
    },
  },
  {
    id: '902',
    code: 'JFB-007',
    name: '聚福宝双人餐',
    active: false,
    version: 1,
    attributes: {
      source_channel: 'JUFUBAO',
      source_barcode: null,
      bundle_id: '12',
      quantity_multiplier: '1',
      source_bundle_ref: 'JFB-007',
    },
  },
];

const productBundles = [
  { id: '11', code: 'BND-NEWYEAR', name: '年货礼包', active: true, version: 2, attributes: { status: 'ACTIVE', items: [] } },
  { id: '12', code: 'BND-OLD', name: '去年礼包', active: false, version: 5, attributes: { status: 'INACTIVE', items: [] } },
];

before(async () => {
  harness = await createRouteHarness('http://localhost/product/sku-mappings');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

test('礼包映射页签列出来源礼包与目标礼包，且不连带拉取 SKU 矩阵', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url.startsWith('/api/v1/source-bundle-mappings?')) return jsonResponse(page(bundleMappings, 10));
    if (url.startsWith('/api/v1/product-bundles?')) return jsonResponse(page(productBundles, 200));
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await harness.mount(['/product/sku-mappings?tab=bundle']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /大者年货礼盒/));

  // 两个页签都在，礼包映射是当前页签。
  assert.match(harness.bodyText(), /SKU 映射/);
  assert.match(harness.bodyText(), /礼包映射/);
  // 渠道翻成中文；来源礼包编号与名称、目标礼包名称照实呈现。
  assert.match(harness.bodyText(), /大者/);
  assert.match(harness.bodyText(), /DZ-礼盒A/);
  assert.match(harness.bodyText(), /年货礼包（BND-NEWYEAR）/);
  // 历史映射指向已停用礼包时照样显示，不装作不认识。
  assert.match(harness.bodyText(), /聚福宝双人餐/);
  assert.match(harness.bodyText(), /去年礼包（BND-OLD）/);
  // 一期口径写在页脚，不给包装乘数入口。
  assert.match(harness.bodyText(), /一个来源礼包单位恒等于一份目标礼包，暂不支持包装乘数。/);

  // 只发礼包映射列表与礼包清单；SKU 矩阵在未激活的页签里，一个请求都不发。
  assert.deepEqual([...requestedUrls].sort(), [
    '/api/v1/product-bundles?page=0&size=200',
    '/api/v1/source-bundle-mappings?page=0&size=10',
  ]);
});

test('默认进 SKU 映射页签，历史遗留的 ?tab=provider 也落回 SKU 映射', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/skus?')) return jsonResponse(page([], 200));
    if (url.startsWith('/api/v1/source-sku-mappings?')) return jsonResponse(page([], 200));
    if (url === '/api/v1/provider-sku-mappings/jd-pieces-candidates') return jsonResponse([]);
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  await harness.mount(['/product/sku-mappings?tab=provider']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /暂无内部 SKU/));
  assert.match(harness.bodyText(), /个内部 SKU · 显示 6 个平台/);
});
