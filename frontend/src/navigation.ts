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
  children?: readonly NavigationNode[];
}

export const NAVIGATION_GROUP_SUFFIX = '~';

export const appNavigation = [
  { path: '/dashboard', label: '工作台' },
  {
    path: '/workbench',
    label: '作业中心',
    children: [
      { path: '/workbench/reviews', label: '人工复核' },
      { path: '/workbench/channel-messages', label: '渠道消息' },
      { path: '/fulfillment/tasks', label: '履约任务' },
      { path: '/procurement/tickets', label: '采购协同' },
      { path: '/fulfillment/sales-outbound', label: '文件作业' },
      { path: '/fulfillment/shipments', label: '发货记录' },
      { path: '/fulfillment/outbound-recon', label: '出库信息对账' },
    ],
  },
  {
    path: '/orders',
    label: '订单中心',
    children: [
      { path: '/orders', label: '全部订单' },
      { path: '/orders/pending', label: '待处理' },
      { path: '/orders/exceptions', label: '异常订单' },
      { path: '/orders/tracking', label: '订单追踪' },
      { path: '/orders/:orderId', label: '订单详情', hideInMenu: true },
    ],
  },
  {
    path: '/agents',
    label: 'Agent 中心',
    children: [
      { path: '/agents', label: 'Agent 列表' },
      { path: '/agents/runs', label: '运行记录' },
      { path: '/agents/:slug', label: 'Agent 详情', hideInMenu: true },
      { path: '/agents/runs/:runId', label: '运行详情', hideInMenu: true },
      { path: '/agents/:slug/evals', label: '评测用例', hideInMenu: true },
    ],
  },
  {
    path: '/inventory',
    label: '库存中心',
    children: [
      { path: '/inventory/overview', label: '总库存' },
      { path: '/inventory/details', label: '专业库存明细', hideInMenu: true },
    ],
  },
  {
    path: '/product',
    label: '主数据',
    children: [
      { path: '/product/products', label: '商品基础信息', hideInMenu: true },
      { path: '/product/categories', label: '品类基础信息', hideInMenu: true },
      { path: '/product/skus', label: '商品档案' },
      { path: '/product/sku-mappings', label: 'SKU 映射' },
    ],
  },
  { path: '/analytics', label: '经营分析' },
  {
    path: '/system',
    label: '系统管理',
    children: [
      { path: '/system/connectors', label: '渠道接入' },
      { path: '/system/audit-logs', label: '操作审计' },
      { path: '/system/config', label: '系统配置', hideInMenu: true },
      { path: '/system/fulfillment-providers', label: '履约方配置' },
      {
        path: '/system/jd-tools',
        label: '京东工具',
        children: [
          { path: '/fulfillment/jd-warehouse', label: '连接与出库查询' },
          { path: '/fulfillment/jd-basicinfo', label: '基础资料查询' },
          { path: '/fulfillment/jd-stock', label: '库存原始查询' },
          { path: '/fulfillment/jd-serial', label: '序列号查询' },
          { path: '/fulfillment/jd-order', label: '京东专业单据' },
          { path: '/fulfillment/jd-return', label: '退货退供查询' },
        ],
      },
    ],
  },
  { path: '/demo/order', label: '模拟下单' },
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

/** 返回当前叶子路由的全部菜单祖先，保证直达深层 URL 时导航层级可见。 */
export function navigationOpenKeys(routes: readonly NavigationNode[], pathname: string): string[] {
  return navigationTrail(routes, pathname)
    .slice(0, -1)
    .map((route) => `${route.path}${NAVIGATION_GROUP_SUFFIX}`);
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
