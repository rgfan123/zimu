import assert from 'node:assert/strict';
import test from 'node:test';
import {
  BUSINESS_MODULE_IDS,
  NO_OPEN_BUSINESS_MODULES,
  type BusinessModuleId,
} from '../src/businessModules.ts';
import {
  appNavigation,
  flattenNavigationLeaves,
  moduleVisibleNavigationTree,
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
      { path: '/workbench/business-followups', label: '客户跟进', hideInMenu: false },
      // Issue #64 运营提醒：上下文二级入口，随复核收件箱移入我的工作台。
      { path: '/workbench/alerts', label: '运营提醒', hideInMenu: true },
      // Issue #110：采购工作台露出，我的工作台可见入口达到 spec D6 的 4 个；UIUX-10 #144 更名为「采购」。
      { path: '/workbench/procurement', label: '采购', hideInMenu: false },
      { path: '/workbench/recon', label: '对账工作台', hideInMenu: false },
    ],
  );
});

const ALL_MODULES_OPEN: ReadonlySet<BusinessModuleId> = new Set(BUSINESS_MODULE_IDS);

/** 我的工作台在给定清单下的可见叶子，用于票 04 的两态断言。 */
function visibleWorkbenchPaths(open: ReadonlySet<BusinessModuleId>): string[] {
  const filtered = visibleNavigationTree(moduleVisibleNavigationTree(appNavigation, open));
  return findNavigationNode(filtered, '/workbench')?.children?.map(({ path }) => path) ?? [];
}

test('客户跟进是生产树里唯一的受控入口，接通与否决定它进不进菜单（票 04）', () => {
  const followUp = findNavigationNode(appNavigation, '/workbench/business-followups');
  assert.equal(
    followUp?.requiresModule,
    'customer-center',
    '客户跟进依赖客户中心（kehuzx）：未接通时整理与客户归属必然拿 KEHUZX_NOT_CONFIGURED，'
      + '入口必须由后端清单裁定，不得在前端另立判据',
  );
  assert.deepEqual(
    flattenNavigationLeaves(appNavigation)
      .filter(({ requiresModule }) => requiresModule)
      .map(({ path, requiresModule }) => ({ path, requiresModule })),
    [{ path: '/workbench/business-followups', requiresModule: 'customer-center' }],
    '受控是显式的：这一层只能带走明写了 requiresModule 的入口',
  );

  const open = visibleWorkbenchPaths(ALL_MODULES_OPEN);
  const closed = visibleWorkbenchPaths(NO_OPEN_BUSINESS_MODULES);

  assert.deepEqual(
    open,
    ['/workbench/shipping', '/workbench/reviews', '/workbench/business-followups',
      '/workbench/procurement', '/workbench/recon'],
    '客户中心已接通：客户跟进出现在原有位置（复核收件箱之后、采购之前）',
  );
  assert.equal(
    findNavigationNode(
      visibleNavigationTree(moduleVisibleNavigationTree(appNavigation, ALL_MODULES_OPEN)),
      '/workbench/business-followups',
    )?.label,
    '客户跟进',
    '接通态的 label 不得被过滤层改写',
  );
  assert.deepEqual(
    closed,
    open.filter((path) => path !== '/workbench/business-followups'),
    '客户中心未接通：菜单里少的恰好只有客户跟进这一项',
  );
  assert.equal(open.length, 5, '我的工作台可见叶子：接通 5');
  assert.equal(closed.length, 4, '我的工作台可见叶子：未接通 4');
  assert.ok(open.length <= 6, '我的工作台可见叶子不得超过准入上限 6');
});

test('客户跟进未接通只是不显示：路由与板块归属两态都不变（票 04，降级 ≠ 删除）', () => {
  // 过滤只作用于菜单：routes.tsx 的 routeConfig 与归属解析都直接读完整 appNavigation，
  // 与运行期清单无关——所以这里断言的是「与清单无关地成立」，不需要按两态各跑一遍。
  assert.ok(
    routableNavigationLeaves(appNavigation).some(({ path }) => path === '/workbench/business-followups'),
    '未接通时路由仍注册，既有 URL、书签与企微卡片里的直达链接都不得失效',
  );
  assert.deepEqual(navigationContext('/workbench/business-followups', ''), {
    section: '我的工作台',
    page: '客户跟进',
  });
  assert.deepEqual(
    navigationTrail(appNavigation, '/workbench/business-followups').map(({ label }) => label),
    ['我的工作台', '客户跟进'],
  );
});

test('operations section keeps only the daily high-frequency entries within the admission cap', () => {
  // UIUX-11：作业中心拆分——发货记录/履约任务迁入「订单与发货」，本组只剩渠道与文件两个高频入口。
  const operations = findNavigationNode(appNavigation, '/operations');

  assert.deepEqual(
    operations?.children?.map(({ path, label, hideInMenu }) => ({ path, label, hideInMenu: hideInMenu ?? false })),
    [
      { path: '/workbench/channel-messages', label: '渠道消息', hideInMenu: false },
      { path: '/fulfillment/sales-outbound', label: '文件作业', hideInMenu: false },
      // UIUX-10 #144：采购入口去重——唯一采购入口在我的工作台，本页降级为隐藏直达。
      { path: '/procurement/tickets', label: '采购协同', hideInMenu: true },
      { path: '/procurement/price-compare', label: '采购比价', hideInMenu: true },
    ],
  );
  const visibleChildren = operations?.children?.filter(({ hideInMenu }) => !hideInMenu);
  assert.ok((visibleChildren?.length ?? 0) <= 6, '渠道与文件可见叶子不得超过高频上限 6');
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
    section: '渠道与文件',
    page: '采购比价',
  });
  assert.deepEqual(navigationContext('/fulfillment/outbound-recon', ''), {
    section: '订单与发货',
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
    section: '系统与接入',
    page: '库存原始查询',
  });
  assert.deepEqual(navigationContext('/system/jd-tools', ''), {
    section: '系统与接入',
    page: '京东工具',
  });
});

test('采购入口全站唯一（我的工作台），Agent 中心收敛为列表/运行记录/会话回复策略三入口', () => {
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const procurementVisible = visiblePaths.filter((path) => path.startsWith('/workbench/procurement') || path.startsWith('/procurement/'));
  assert.deepEqual(procurementVisible, ['/workbench/procurement'], '采购相关可见入口只能有一个（我的工作台「采购」）');
  const agents = findNavigationNode(appNavigation, '/agents');
  assert.deepEqual(
    agents?.children?.filter(({ hideInMenu }) => !hideInMenu)?.map(({ path }) => path),
    ['/agents', '/agents/runs', '/agents/reply-policies'],
    'Agent 中心可见入口：Agent 列表 + 运行记录 + 会话回复策略（按会话控制机器人自动回复）',
  );
});

test('provider configuration is a provider-neutral system page', () => {
  assert.deepEqual(findNavigationNode(appNavigation, '/system/fulfillment-providers'), {
    path: '/system/fulfillment-providers',
    label: '履约方配置',
  });
  assert.deepEqual(navigationContext('/system/fulfillment-providers', ''), {
    section: '系统与接入',
    page: '履约方配置',
  });
});

test('system menu exposes each connector and provider configuration capability once', () => {
  // 2026-08-27：系统级配置与审计独立为「系统与接入」组；能力清单不变，各出现一次。
  const system = findNavigationNode(appNavigation, '/system');
  const visible = system?.children?.filter(({ hideInMenu }) => !hideInMenu)?.map(({ path }) => path) ?? [];

  for (const path of [
    '/system/connectors', '/system/audit-logs', '/system/fulfillment-providers',
    '/system/operators', '/system/wecom-bots', '/system/jd-tools',
  ]) {
    assert.equal(visible.filter((item) => item === path).length, 1, `${path} 必须在系统与接入组恰好出现一次`);
  }
  assert.equal(findNavigationNode(appNavigation, '/system/config')?.hideInMenu, true);
});

test('product operations expose product archive, SKU mappings, and static bundle management', () => {
  // 2026-08-27：商品主数据独立为「商品与主数据」组；三个可见入口保持不变。
  const masterData = findNavigationNode(appNavigation, '/master-data');
  const visibleChildren = masterData?.children
    ?.filter(({ hideInMenu, path }) => !hideInMenu && path.startsWith('/product/'));

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
  // 2026-08-27：库存挂在「商品与主数据」组；业务级总览仍只有一个。
  const masterDataInventory = findNavigationNode(appNavigation, '/master-data')?.children
    ?.filter(({ hideInMenu, path }) => !hideInMenu && path.startsWith('/inventory/'));
  assert.deepEqual(masterDataInventory, [
    { path: '/inventory/overview', label: '总库存' },
  ]);
  assert.deepEqual(navigationContext('/inventory/overview', ''), {
    section: '商品与主数据',
    page: '总库存',
  });
});

test('order presets collapse into one visible entry while direct URLs stay routable', () => {
  const orders = findNavigationNode(appNavigation, '/orders');
  const visiblePaths = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routablePaths = routableNavigationLeaves(appNavigation).map(({ path }) => path);

  const visibleChildren = orders?.children?.filter(({ hideInMenu }) => !hideInMenu) ?? [];
  // UIUX-11：订单与发货组 = 全部订单 + 发货记录 + 履约任务；预设视图仍页内切换。
  assert.deepEqual(
    visibleChildren.map(({ label }) => label),
    ['全部订单', '发货记录', '履约任务'],
  );
  for (const presetPath of ['/orders/pending', '/orders/exceptions', '/orders/tracking']) {
    assert.equal(visiblePaths.includes(presetPath), false, `${presetPath} 不再出现在菜单`);
    assert.equal(routablePaths.includes(presetPath), true, `${presetPath} 直达仍可路由（书签不失效）`);
  }
});

test('production navigation has no duplicate leaf paths', () => {
  const paths = flattenNavigationLeaves(appNavigation).map(({ path }) => path);
  assert.equal(new Set(paths).size, paths.length);
});

test('运行期模块过滤只做过滤：全部模块开放时可见结果与过滤前完全一致（票 03 回归证明）', () => {
  // 门禁语义扩展：菜单可见性 = hideInMenu（编译期）∘ 已开放业务模块清单（运行期）。
  // 运行期那一层只允许**减少**节点，且减少的必须恰好是显式声明了 requiresModule 的那些——
  // 它不得新增节点、不得改写路径，否则「导航树是唯一事实源」就不再成立。
  const visibleBefore = flattenNavigationLeaves(visibleNavigationTree(appNavigation)).map(({ path }) => path);
  const routableBefore = routableNavigationLeaves(appNavigation).map(({ path }) => path);
  const allOpen = new Set(BUSINESS_MODULE_IDS);

  assert.deepEqual(
    flattenNavigationLeaves(visibleNavigationTree(moduleVisibleNavigationTree(appNavigation, allOpen))).map(
      ({ path }) => path,
    ),
    visibleBefore,
    '全部模块开放 = 零行为变化',
  );

  // 清单读不到时的保守态：只有受控节点消失，其余一个不少。
  const gatedPaths = flattenNavigationLeaves(appNavigation)
    .filter(({ requiresModule }) => requiresModule)
    .map(({ path }) => path);
  assert.deepEqual(
    flattenNavigationLeaves(
      visibleNavigationTree(moduleVisibleNavigationTree(appNavigation, NO_OPEN_BUSINESS_MODULES)),
    ).map(({ path }) => path),
    visibleBefore.filter((path) => !gatedPaths.includes(path)),
    '未开放任何模块时，消失的必须恰好是声明了 requiresModule 的叶子',
  );

  // 过滤是纯函数，不得就地改写导航树本身——routes.tsx 的 routeConfig 与 navigationContext
  // 都直接读 appNavigation，源树一旦被改，既有 URL 直达与顶栏归属就会跟着塌。
  assert.deepEqual(
    routableNavigationLeaves(appNavigation).map(({ path }) => path),
    routableBefore,
    '模块过滤后 appNavigation 的可路由叶子必须一个不少（降级 ≠ 删除，隐藏 ≠ 删路由）',
  );
});
