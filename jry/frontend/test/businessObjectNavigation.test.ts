import assert from 'node:assert/strict';
import test from 'node:test';
import {
  appNavigation,
  flattenNavigationLeaves,
  navigationContext,
  navigationOpenKeys,
  navigationTrail,
  routableNavigationLeaves,
  visibleNavigationTree,
  type NavigationNode,
} from '../src/navigation.ts';

function findNavigationNode(routes: readonly NavigationNode[], path: string): NavigationNode | undefined {
  for (const route of routes) {
    if (route.path === path) return route;
    const nested = route.children ? findNavigationNode(route.children, path) : undefined;
    if (nested) return nested;
  }
  return undefined;
}

test('production navigation keeps the workbench limited to six daily operations', () => {
  const workbench = findNavigationNode(appNavigation, '/workbench');

  assert.deepEqual(
    workbench?.children?.map(({ path, label }) => ({ path, label })),
    [
      { path: '/workbench/reviews', label: '人工复核' },
      { path: '/workbench/channel-messages', label: '渠道消息' },
      { path: '/fulfillment/tasks', label: '履约任务' },
      { path: '/procurement/tickets', label: '采购协同' },
      { path: '/fulfillment/sales-outbound', label: '文件作业' },
      { path: '/fulfillment/shipments', label: '发货记录' },
    ],
  );
});

test('production navigation nests the six legacy JD URLs under system tools', () => {
  const jdTools = findNavigationNode(appNavigation, '/system/jd-tools');

  assert.equal(jdTools?.label, '京东工具');
  assert.deepEqual(
    jdTools?.children?.map(({ path, label }) => ({ path, label })),
    [
      { path: '/fulfillment/jd-warehouse', label: '连接与出库查询' },
      { path: '/fulfillment/jd-basicinfo', label: '基础资料查询' },
      { path: '/fulfillment/jd-stock', label: '库存原始查询' },
      { path: '/fulfillment/jd-serial', label: '序列号查询' },
      { path: '/fulfillment/jd-order', label: '京东专业单据' },
      { path: '/fulfillment/jd-return', label: '退货退供查询' },
    ],
  );
  assert.deepEqual(navigationOpenKeys(appNavigation, '/fulfillment/jd-stock'), [
    '/system~',
    '/system/jd-tools~',
  ]);
  assert.deepEqual(navigationContext('/fulfillment/jd-stock', ''), {
    section: '系统管理 / 京东工具',
    page: '库存原始查询',
  });
});

test('provider configuration is a provider-neutral system page', () => {
  assert.deepEqual(findNavigationNode(appNavigation, '/system/fulfillment-providers'), {
    path: '/system/fulfillment-providers',
    label: '履约方配置',
  });
  assert.deepEqual(navigationContext('/system/fulfillment-providers', ''), {
    section: '系统管理',
    page: '履约方配置',
  });
});

test('system menu exposes each connector and provider configuration capability once', () => {
  const system = findNavigationNode(appNavigation, '/system');
  const visibleChildren = system?.children?.filter(({ hideInMenu }) => !hideInMenu);

  assert.deepEqual(
    visibleChildren?.map(({ path, label }) => ({ path, label })),
    [
      { path: '/system/connectors', label: '渠道接入' },
      { path: '/system/audit-logs', label: '操作审计' },
      { path: '/system/fulfillment-providers', label: '履约方配置' },
      { path: '/system/jd-tools', label: '京东工具' },
    ],
  );
  assert.equal(findNavigationNode(appNavigation, '/system/config')?.hideInMenu, true);
});

test('product operations expose one product archive and keep technical product/category pages out of the menu', () => {
  const product = findNavigationNode(appNavigation, '/product');
  const visibleChildren = product?.children?.filter(({ hideInMenu }) => !hideInMenu);

  assert.deepEqual(visibleChildren, [
    { path: '/product/skus', label: '商品档案' },
    { path: '/product/sku-mappings', label: 'SKU 映射' },
  ]);
  assert.equal(findNavigationNode(appNavigation, '/product/products')?.hideInMenu, true);
  assert.equal(findNavigationNode(appNavigation, '/product/categories')?.hideInMenu, true);
});

test('hidden dynamic routes remain routable but are filtered from the visible menu', () => {
  const detail = findNavigationNode(appNavigation, '/orders/:orderId');
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routablePaths = routableNavigationLeaves(appNavigation).map(({ path }) => path);

  assert.equal(detail?.hideInMenu, true);
  assert.equal(visiblePaths.includes('/orders/:orderId'), false);
  assert.equal(routablePaths.includes('/orders/:orderId'), true);
  assert.equal(navigationTrail(appNavigation, '/orders/ord-20260813').at(-1)?.path, '/orders/:orderId');
});

test('external navigation remains visible but is not registered as an application route', () => {
  const bi = findNavigationNode(appNavigation, '/bi');
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routablePaths = routableNavigationLeaves(appNavigation).map(({ path }) => path);

  assert.equal(bi?.external, '/metabase');
  assert.equal(visiblePaths.includes('/bi'), true);
  assert.equal(routablePaths.includes('/bi'), false);
});

test('orders stay canonical and inventory has one business-level overview', () => {
  const orders = findNavigationNode(appNavigation, '/orders');

  assert.equal(orders?.children?.filter(({ label }) => label === '全部订单').length, 1);
  assert.equal(orders?.children?.some(({ label }) => label.includes('京东')), false);
  assert.deepEqual(findNavigationNode(appNavigation, '/inventory')?.children?.filter(({ hideInMenu }) => !hideInMenu), [
    { path: '/inventory/overview', label: '总库存' },
  ]);
  assert.deepEqual(navigationContext('/inventory/overview', ''), {
    section: '库存中心',
    page: '总库存',
  });
});

test('production navigation has no duplicate leaf paths', () => {
  const paths = flattenNavigationLeaves(appNavigation).map(({ path }) => path);
  assert.equal(new Set(paths).size, paths.length);
});
