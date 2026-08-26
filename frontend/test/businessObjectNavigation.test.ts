import assert from 'node:assert/strict';
import test from 'node:test';
import {
  appNavigation,
  flattenNavigationLeaves,
  navigationContext,
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

test('my-workbench section leads the navigation with the role workbenches (Issue #104)', () => {
  const myWorkbench = findNavigationNode(appNavigation, '/workbench');

  assert.equal(appNavigation[0]?.path, '/workbench', '我的工作台必须排在导航最前（spec D6）');
  assert.deepEqual(
    myWorkbench?.children?.map(({ path, label, hideInMenu }) => ({ path, label, hideInMenu: hideInMenu ?? false })),
    [
      { path: '/workbench/shipping', label: '今日发货工作台', hideInMenu: false },
      { path: '/workbench/reviews', label: '复核收件箱', hideInMenu: false },
      // Issue #64 运营提醒：上下文二级入口，随复核收件箱移入我的工作台。
      { path: '/workbench/alerts', label: '运营提醒', hideInMenu: true },
      // Issue #110：采购工作台露出，我的工作台可见入口达到 spec D6 的 4 个；UIUX-10 #144 更名为「采购」。
      { path: '/workbench/procurement', label: '采购', hideInMenu: false },
      { path: '/workbench/recon', label: '对账工作台', hideInMenu: false },
    ],
  );
});

test('operations section keeps only the daily high-frequency entries within the admission cap', () => {
  const operations = findNavigationNode(appNavigation, '/operations');

  assert.deepEqual(
    operations?.children?.map(({ path, label, hideInMenu }) => ({ path, label, hideInMenu: hideInMenu ?? false })),
    [
      { path: '/workbench/channel-messages', label: '渠道消息', hideInMenu: false },
      { path: '/fulfillment/tasks', label: '履约任务', hideInMenu: false },
      // UIUX-10 #144：采购入口去重——唯一采购入口在我的工作台，本页降级为隐藏直达。
      { path: '/procurement/tickets', label: '采购协同', hideInMenu: true },
      { path: '/procurement/price-compare', label: '采购比价', hideInMenu: true },
      { path: '/fulfillment/sales-outbound', label: '文件作业', hideInMenu: false },
      { path: '/fulfillment/shipments', label: '发货记录', hideInMenu: false },
      { path: '/fulfillment/outbound-recon', label: '出库信息对账', hideInMenu: true },
    ],
  );
  const visibleChildren = operations?.children?.filter(({ hideInMenu }) => !hideInMenu);
  assert.ok((visibleChildren?.length ?? 0) <= 6, '作业中心可见叶子不得超过高频上限 6');
  const myWorkbenchVisible = findNavigationNode(appNavigation, '/workbench')?.children?.filter(
    ({ hideInMenu }) => !hideInMenu,
  );
  assert.ok((myWorkbenchVisible?.length ?? 0) <= 6, '我的工作台可见叶子不得超过上限 6');
});

test('demoted query tools stay routable and keep their section context', () => {
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routablePaths = routableNavigationLeaves(appNavigation).map(({ path }) => path);

  for (const path of ['/procurement/price-compare', '/fulfillment/outbound-recon', '/workbench/alerts', '/demo/order', '/procurement/tickets', '/agents/cost', '/agents/fulfillment-file', '/agents/new']) {
    const node = findNavigationNode(appNavigation, path);
    assert.equal(node?.hideInMenu, true, `${path} 必须降级为隐藏入口`);
    assert.equal(visiblePaths.includes(path), false, `${path} 不得出现在可见菜单`);
    assert.equal(routablePaths.includes(path), true, `${path} 必须保持可路由（降级不等于删除，旧路径不 404）`);
  }
  // Issue #104：发货台与对账台从隐藏注册升级为我的工作台可见入口（票面「01 再露出」的落地）。
  for (const path of ['/workbench/shipping', '/workbench/procurement', '/workbench/recon', '/workbench/reviews']) {
    assert.equal(visiblePaths.includes(path), true, `${path} 必须是我的工作台可见入口`);
    assert.equal(routablePaths.includes(path), true, `${path} 必须可路由`);
  }
  assert.deepEqual(navigationContext('/procurement/price-compare', ''), {
    section: '作业中心',
    page: '采购比价',
  });
  assert.deepEqual(navigationContext('/fulfillment/outbound-recon', ''), {
    section: '作业中心',
    page: '出库信息对账',
  });
  assert.deepEqual(navigationContext('/workbench/recon', ''), {
    section: '我的工作台',
    page: '对账工作台',
  });
  assert.deepEqual(navigationContext('/workbench/alerts', ''), {
    section: '我的工作台',
    page: '运营提醒',
  });
  assert.deepEqual(navigationContext('/workbench/shipping', ''), {
    section: '我的工作台',
    page: '今日发货工作台',
  });
});

test('京东工具收敛为单入口，六个查询页保留隐藏直达', () => {
  const jdTools = findNavigationNode(appNavigation, '/system/jd-tools');

  assert.equal(jdTools?.label, '京东工具');
  assert.equal(jdTools?.children, undefined, '京东工具必须是单入口叶子（页内 Tab 切换）');
  for (const path of ['/fulfillment/jd-warehouse', '/fulfillment/jd-basicinfo', '/fulfillment/jd-stock', '/fulfillment/jd-serial', '/fulfillment/jd-order', '/fulfillment/jd-return']) {
    const node = findNavigationNode(appNavigation, path);
    assert.equal(node?.hideInMenu, true, `${path} 必须降级为隐藏入口`);
  }
  assert.deepEqual(navigationContext('/fulfillment/jd-stock', ''), {
    section: '系统管理',
    page: '库存原始查询',
  });
  assert.deepEqual(navigationContext('/system/jd-tools', ''), {
    section: '系统管理',
    page: '京东工具',
  });
});

test('采购入口全站唯一（我的工作台），Agent 中心收敛为列表/运行记录两入口', () => {
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const procurementVisible = visiblePaths.filter((path) => path.startsWith('/workbench/procurement') || path.startsWith('/procurement/'));
  assert.deepEqual(procurementVisible, ['/workbench/procurement'], '采购相关可见入口只能有一个（我的工作台「采购」）');
  const agents = findNavigationNode(appNavigation, '/agents');
  assert.deepEqual(
    agents?.children?.filter(({ hideInMenu }) => !hideInMenu)?.map(({ path }) => path),
    ['/agents', '/agents/runs'],
    'Agent 中心可见入口收敛为 Agent 列表 + 运行记录',
  );
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
      { path: '/system/operators', label: '运营人员' },
      { path: '/system/jd-tools', label: '京东工具' },
    ],
  );
  assert.equal(findNavigationNode(appNavigation, '/system/config')?.hideInMenu, true);
});

test('product operations expose product archive, SKU mappings, and static bundle management', () => {
  const product = findNavigationNode(appNavigation, '/product');
  const visibleChildren = product?.children?.filter(({ hideInMenu }) => !hideInMenu);

  assert.deepEqual(visibleChildren, [
    { path: '/product/skus', label: '商品档案' },
    { path: '/product/sku-mappings', label: 'SKU 映射' },
    { path: '/product/bundles', label: '静态礼包' },
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

test('order presets collapse into one visible entry while direct URLs stay routable', () => {
  const orders = findNavigationNode(appNavigation, '/orders');
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routablePaths = routableNavigationLeaves(appNavigation).map(({ path }) => path);

  const visibleChildren = orders?.children?.filter(({ hideInMenu }) => !hideInMenu) ?? [];
  assert.equal(visibleChildren.length, 1, '订单中心菜单只剩「全部订单」一个入口');
  assert.equal(visibleChildren[0]?.label, '全部订单');
  for (const presetPath of ['/orders/pending', '/orders/exceptions', '/orders/tracking']) {
    assert.equal(visiblePaths.includes(presetPath), false, `${presetPath} 不再出现在菜单`);
    assert.equal(routablePaths.includes(presetPath), true, `${presetPath} 直达仍可路由（书签不失效）`);
  }
});

test('production navigation has no duplicate leaf paths', () => {
  const paths = flattenNavigationLeaves(appNavigation).map(({ path }) => path);
  assert.equal(new Set(paths).size, paths.length);
});
