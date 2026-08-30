import type { BusinessModuleId } from './businessModules.ts';

export interface NavigationContext {
  section: string;
  page: string;
}

/** 不含 React 的生产导航元数据；路由组件与图标在 routes.tsx 按 path 绑定。 */
export interface NavigationNode {
  path: string;
  label: string;
  external?: string;
  hideInMenu?: boolean;
  /**
   * 该节点依赖的业务模块（票 03）。声明后，只有后端下发的开放清单包含该模块时它才进菜单；
   * 未声明的节点永远可见。运行期过滤只作用于菜单，路由注册与归属解析仍取完整导航树。
   */
  requiresModule?: BusinessModuleId;
  children?: readonly NavigationNode[];
}

export const appNavigation = [
  // Issue #104（spec #103 D5/D6）：新增一级板块「我的工作台」排最前，按岗位动线收纳工作台入口。
  // 采购工作台入口随 Issue #110 交付 /workbench/procurement 后加入。
  {
    path: '/workbench',
    label: '我的工作台',
    children: [
      { path: '/workbench/shipping', label: '今日发货工作台' },
      { path: '/workbench/reviews', label: '复核收件箱' },
      // 票 04：客户跟进的整理与客户归属全程要读客户中心（kehuzx）的客户档案——未接通时点进去
      // 必然拿到 KEHUZX_NOT_CONFIGURED。因此入口受运行期清单控制：接通才进菜单，位置、label 与
      // path 都不变；未接通只是不显示，路由照常注册、URL 照常直达（降级 ≠ 删除）。
      { path: '/workbench/business-followups', label: '客户跟进', requiresModule: 'customer-center' },
      // Issue #64 运营提醒独立路由：上下文二级入口（复核页 ↔ 提醒页互为切换），随复核收件箱移入本板块。
      { path: '/workbench/alerts', label: '运营提醒', hideInMenu: true },
      // Issue #110：采购工作台（建议区等 #121/#118 供数，工单区今天即真数）。UIUX-10 #144：采购入口去重后唯一入口。
      { path: '/workbench/procurement', label: '采购' },
      { path: '/workbench/recon', label: '对账工作台' },
    ],
  },
  { path: '/dashboard', label: '调度台' },
  // UIUX-11（2026-08-26 用户实测反馈 #4）：侧栏按「一天的工作流」重组，不按功能类别平铺。
  // 高频组在前（订单与发货、渠道与文件），低频配置默认折叠。
  // 2026-08-27 用户反馈：原「配置与主数据」9 个可见入口太挤，拆成
  // 「商品与主数据」（/master-data，商品主数据日常维护）+「系统与接入」（/system，低频系统级配置与审计）。
  {
    path: '/orders',
    label: '订单与发货',
    children: [
      { path: '/orders', label: '全部订单' },
      { path: '/fulfillment/shipments', label: '发货记录' },
      { path: '/fulfillment/tasks', label: '履约任务' },
      // 预设视图并入「全部订单」页内切换（Segmented），旧 URL 保留为隐藏直达路径，书签不失效。
      { path: '/orders/pending', label: '待处理', hideInMenu: true },
      { path: '/orders/exceptions', label: '异常订单', hideInMenu: true },
      { path: '/orders/tracking', label: '订单追踪', hideInMenu: true },
      { path: '/orders/:orderId', label: '订单详情', hideInMenu: true },
      { path: '/fulfillment/outbound-recon', label: '出库信息对账', hideInMenu: true },
    ],
  },
  {
    path: '/operations',
    label: '渠道与文件',
    children: [
      { path: '/workbench/channel-messages', label: '渠道消息' },
      { path: '/fulfillment/sales-outbound', label: '文件作业' },
      // UIUX-10 #144：采购入口去重——唯一采购入口在我的工作台（/workbench/procurement），本页保留直达。
      { path: '/procurement/tickets', label: '采购协同', hideInMenu: true },
      // 低频专用查询（Issue #98 准入规则）：隐藏菜单、保留路由与上下文入口，见 docs/agents/navigation-admission.md。
      { path: '/procurement/price-compare', label: '采购比价', hideInMenu: true },
    ],
  },
  {
    path: '/agents',
    label: 'Agent 中心',
    children: [
      { path: '/agents', label: 'Agent 列表' },
      { path: '/agents/runs', label: '运行记录' },
      { path: '/agents/reply-policies', label: '会话管理' },
      // UIUX-10 #144：消耗看板 / 履约单据助手降为隐藏直达（列表页内可进入）；创建入口在列表页按钮。
      { path: '/agents/cost', label: '消耗看板', hideInMenu: true },
      { path: '/agents/fulfillment-file', label: '履约单据助手', hideInMenu: true },
      { path: '/agents/new', label: '创建 Agent', hideInMenu: true },
      { path: '/agents/:slug', label: 'Agent 详情', hideInMenu: true },
      { path: '/agents/runs/:runId', label: '运行详情', hideInMenu: true },
      { path: '/agents/:slug/evals', label: '评测用例', hideInMenu: true },
    ],
  },
  {
    path: '/master-data',
    label: '商品与主数据',
    children: [
      { path: '/product/skus', label: '商品档案' },
      { path: '/product/sku-mappings', label: 'SKU 映射' },
      { path: '/product/bundles', label: '静态礼包' },
      { path: '/inventory/overview', label: '总库存' },
      // 票 06：原料库存归本板块而不是我的工作台——原料结存查询不是每日动线的必经环节，
      // 属主数据维护侧（spec D4）。入口受运行期清单控制：上游（yuanliaokc）未接通时不进菜单，
      // 但路由照常注册、URL 照常直达（降级 ≠ 删除）。它与「总库存」是两个业务对象：
      // 那边是 SKU 成品结存，这边是原料与批次，两者之间没有连接键（spec D6）。
      { path: '/inventory/raw-materials', label: '原料库存', requiresModule: 'raw-material-inventory' },
      { path: '/inventory/details', label: '专业库存明细', hideInMenu: true },
      { path: '/product/products', label: '商品基础信息', hideInMenu: true },
      { path: '/product/categories', label: '品类基础信息', hideInMenu: true },
    ],
  },
  {
    path: '/system',
    label: '系统与接入',
    children: [
      { path: '/system/connectors', label: '渠道接入' },
      { path: '/system/fulfillment-providers', label: '履约方配置' },
      // Issue #89：内部运营人员登记（姓名、企微 userid、所属责任团队）——系统管理配置主线入口
      { path: '/system/operators', label: '运营人员' },
      // 企微机器人管理台账（管理界面先行，运行时多机器人接线未启用）。
      { path: '/system/wecom-bots', label: '机器人管理' },
      // UIUX-10 #144：京东工具收敛为单入口（/system/jd-tools 页内 Tab），六个查询页保留隐藏直达。
      { path: '/system/jd-tools', label: '京东工具' },
      { path: '/fulfillment/jd-warehouse', label: '连接与出库查询', hideInMenu: true },
      { path: '/fulfillment/jd-basicinfo', label: '基础资料查询', hideInMenu: true },
      { path: '/fulfillment/jd-stock', label: '库存原始查询', hideInMenu: true },
      { path: '/fulfillment/jd-serial', label: '序列号查询', hideInMenu: true },
      { path: '/fulfillment/jd-order', label: '京东专业单据', hideInMenu: true },
      { path: '/fulfillment/jd-return', label: '退货退供查询', hideInMenu: true },
      { path: '/system/audit-logs', label: '操作审计' },
      { path: '/system/config', label: '系统配置', hideInMenu: true },
      // 票 05：MCP 开放面只读核对。低频专用查询（改完 MCP_MODULES 才看一次），且本板块可见叶子
      // 已满 6——按准入 1.1 降级为上下文二级入口：路由照常注册，发现路径由 Agent 列表页承载
      // （工具白名单的「未注册」标注就是它要回答的问题）。见 docs/agents/navigation-admission.md。
      { path: '/system/mcp-exposure', label: 'MCP 开放面', hideInMenu: true },
    ],
  },
  { path: '/analytics', label: '经营分析' },
  // Issue #104：Demo 页不再出现在日常菜单（URL 保留可直达，降级 ≠ 删除）。
  { path: '/demo/order', label: '模拟下单', hideInMenu: true },
  { path: '/bi', label: '管理驾驶舱', external: '/metabase' },
] as const satisfies readonly NavigationNode[];

/** 段级模式匹配；动态段视为通配，返回静态段数量用于选择最具体路由。 */
export function routeMatchScore(pattern: string, pathname: string): number {
  const patternSegments = pattern.split('/').filter(Boolean);
  const pathSegments = pathname.split('/').filter(Boolean);
  if (patternSegments.length !== pathSegments.length) return -1;

  let staticCount = 0;
  for (let index = 0; index < patternSegments.length; index++) {
    const segment = patternSegments[index];
    if (segment.startsWith(':')) continue;
    if (segment !== pathSegments[index]) return -1;
    staticCount++;
  }
  return staticCount;
}

export function flattenNavigationLeaves(routes: readonly NavigationNode[]): NavigationNode[] {
  const result: NavigationNode[] = [];
  const walk = (items: readonly NavigationNode[]) => {
    for (const route of items) {
      if (route.children?.length) walk(route.children);
      else result.push(route);
    }
  };
  walk(routes);
  return result;
}

export function routableNavigationLeaves(routes: readonly NavigationNode[]): NavigationNode[] {
  return flattenNavigationLeaves(routes).filter((route) => !route.external);
}

/** 从导航树解析当前叶子及完整祖先链，静态段更多的模式优先。 */
export function navigationTrail(routes: readonly NavigationNode[], pathname: string): NavigationNode[] {
  let best: { score: number; pathLength: number; trail: NavigationNode[] } | undefined;

  const walk = (items: readonly NavigationNode[], ancestors: NavigationNode[]) => {
    for (const route of items) {
      if (route.children?.length) {
        walk(route.children, [...ancestors, route]);
        continue;
      }

      const score = routeMatchScore(route.path, pathname);
      if (score < 0) continue;
      if (!best || score > best.score || (score === best.score && route.path.length > best.pathLength)) {
        best = { score, pathLength: route.path.length, trail: [...ancestors, route] };
      }
    }
  };

  walk(routes, []);
  return best?.trail ?? [];
}

/** 菜单可见性只处理 hideInMenu；完整导航仍用于注册隐藏路由。 */
export function visibleNavigationTree<T extends NavigationNode>(routes: readonly T[]): T[] {
  const visible: T[] = [];
  for (const route of routes) {
    if (route.hideInMenu) continue;
    if (route.children?.length) {
      const children = visibleNavigationTree(route.children);
      if (!children.length) continue;
      visible.push({ ...route, children } as T);
    } else {
      visible.push(route);
    }
  }
  return visible;
}

/**
 * 运行期模块过滤（票 03）：按后端下发的**已开放**模块清单裁掉声明了 `requiresModule`
 * 且模块未开放的节点整支。
 *
 * 性质（本票的硬约束，改动此函数时必须保持）：
 * - **只做过滤**——不新增节点、不改写路径，因此 `routableNavigationLeaves`（路由注册）、
 *   `navigationTrail` / `navigationContext`（侧边栏展开与顶栏归属）全部照旧取完整导航树，
 *   既有 URL 直达与隐藏页面包屑不受影响；
 * - **保守**——清单读不到时传空集即可，未接通的模块一律不出现（不是反过来全放行）；
 * - **全开即恒等**——所有模块开放时结果与输入结构一致，未声明 `requiresModule` 的节点零影响。
 */
export function moduleVisibleNavigationTree<T extends NavigationNode>(
  routes: readonly T[],
  openModules: ReadonlySet<BusinessModuleId>,
): T[] {
  const visible: T[] = [];
  for (const route of routes) {
    if (route.requiresModule && !openModules.has(route.requiresModule)) continue;
    if (route.children?.length) {
      const children = moduleVisibleNavigationTree(route.children, openModules);
      if (!children.length) continue;
      visible.push({ ...route, children } as T);
    } else {
      visible.push(route);
    }
  }
  return visible;
}

export function navigationContextFromRoutes(
  routes: readonly NavigationNode[],
  pathname: string,
  fallbackPage: string,
): NavigationContext {
  const trail = navigationTrail(routes, pathname);
  const page = trail.at(-1)?.label ?? fallbackPage;
  const section = trail
    .slice(0, -1)
    .map((route) => route.label)
    .join(' / ');

  return { section: section || page, page };
}

/** 兼容既有调用者；归属只从生产导航树解析，不维护 URL 前缀映射。 */
export function navigationContext(pathname: string, fallbackPage: string): NavigationContext {
  return navigationContextFromRoutes(appNavigation, pathname, fallbackPage);
}
