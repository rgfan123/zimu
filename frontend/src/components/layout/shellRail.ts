/**
 * 侧栏轨道视图模型（Issue #104 · ADR 0004）。
 * 数据单一事实源仍是 navigation.ts 的 appNavigation；本模块只做展示层：
 * 分组拍平、字形、岗位重排与徽标声明。岗位只重排顺序，绝不隐藏任何分组（D1）。
 * 票 03 追加运行期模块过滤：未接通的业务模块对应的节点不进轨道（同样只过滤，不新增不改写）。
 */

// 相对路径而非 `@/` 别名：本模块承载运行期模块过滤的接线，必须能被 node:test 直接加载
// （node 的类型擦除不认 Vite 别名，值导入会 ERR_MODULE_NOT_FOUND）。改回别名，
// runtimeModuleVisibility.test.ts 就失去了对「过滤有没有真的接上」的覆盖。
import type { BusinessModuleId } from '../../businessModules.ts';
import {
  appNavigation,
  moduleVisibleNavigationTree,
  visibleNavigationTree,
  type NavigationNode,
} from '../../navigation.ts';

export interface RailItem {
  path: string;
  label: string;
  glyph: string;
  external?: string;
  badge?: 'reviews-open';
}

export interface RailGroup {
  key: string;
  title: string;
  items: RailItem[];
}

const GLYPHS: Record<string, string> = {
  '/workbench/shipping': '▣',
  '/workbench/reviews': '!',
  '/workbench/business-followups': '◇',
  '/workbench/procurement': '⊟',
  '/workbench/recon': '▦',
  '/dashboard': '◱',
  '/workbench/channel-messages': '⊙',
  '/fulfillment/tasks': '⊞',
  '/procurement/tickets': '⊟',
  '/fulfillment/sales-outbound': '↩',
  '/fulfillment/shipments': '⊚',
  '/orders': '≡',
  '/orders/pending': '◔',
  '/orders/exceptions': '⚑',
  '/orders/tracking': '⊙',
  '/agents': '◈',
  '/agents/runs': '◉',
  '/agents/cost': '◍',
  '/agents/fulfillment-file': '⊟',
  '/agents/new': '＋',
  '/inventory/overview': '▤',
  '/inventory/raw-materials': '◫',
  '/product/skus': '≣',
  '/product/sku-mappings': '⇄',
  '/product/bundles': '⊞',
  '/analytics': '◱',
  '/bi': '↗',
  '/system/connectors': '⇄',
  '/system/audit-logs': '▥',
  '/system/fulfillment-providers': '⊛',
  '/system/operators': '☖',
  '/fulfillment/jd-warehouse': '⌾',
  '/fulfillment/jd-basicinfo': '≡',
  '/fulfillment/jd-stock': '▤',
  '/fulfillment/jd-serial': '#',
  '/fulfillment/jd-order': '▥',
  '/fulfillment/jd-return': '↩',
};

function toItem(node: NavigationNode): RailItem {
  return {
    path: node.path,
    label: node.label,
    glyph: GLYPHS[node.path] ?? '≡',
    external: node.external,
    badge: node.path === '/workbench/reviews' ? 'reviews-open' : undefined,
  };
}

/**
 * 全量轨道（默认顺序）。展示层调整（数据树不动）：
 * - 调度台并入「我的工作台」分组（它就是调度工作台，#105）
 * - 经营分析 + 管理驾驶舱 两个顶级单项合为「经营分析」分组（对应原型的经营分析组）
 * - 嵌套板块（京东工具）在父分组之后平铺为并列分组
 *
 * `openModules` 为后端下发的已开放业务模块（票 03）；传空集即保守态——未接通的模块不进轨道。
 */
export function buildRailGroups(
  openModules: ReadonlySet<BusinessModuleId>,
  navigation: readonly NavigationNode[] = appNavigation,
): RailGroup[] {
  const tree = moduleVisibleNavigationTree(visibleNavigationTree(navigation), openModules);
  const groups: RailGroup[] = [];
  const analyticsItems: RailItem[] = [];

  const pushSection = (section: NavigationNode, title: string) => {
    const items: RailItem[] = [];
    const nested: Array<{ node: NavigationNode; title: string }> = [];
    for (const child of section.children ?? []) {
      if (child.children?.length) nested.push({ node: child, title: child.label });
      else items.push(toItem(child));
    }
    if (items.length) groups.push({ key: section.path, title, items });
    for (const child of nested) pushSection(child.node, child.title);
  };

  let dashboardItem: RailItem | undefined;
  for (const node of tree) {
    if (node.children?.length) {
      pushSection(node, node.label);
    } else if (node.path === '/dashboard') {
      dashboardItem = toItem(node);
    } else {
      analyticsItems.push(toItem(node));
    }
  }
  // 调度台归入我的工作台组：在整棵树遍历完之后落位，不依赖 appNavigation 的节点顺序。
  if (dashboardItem) {
    const myWorkbench = groups.find((group) => group.key === '/workbench');
    (myWorkbench?.items ?? analyticsItems).push(dashboardItem);
  }
  if (analyticsItems.length) groups.push({ key: 'analytics', title: '经营分析', items: analyticsItems });
  return groups;
}

/** 岗位 → 分组优先顺序。不在表里的分组保持默认相对顺序排在后面；未知岗位不重排。 */
const ROLE_SECTION_PRIORITY: Record<string, readonly string[]> = {
  FULFILLMENT_OPS: ['/workbench', '/orders', '/operations', '/master-data', '/system', 'analytics'],
  SKU_OPS: ['/workbench', '/operations', '/master-data', '/system', 'analytics'],
  CUSTOMER_OPS: ['/workbench', '/orders', '/operations', 'analytics'],
  ORDER_OPS: ['/workbench', '/orders', '/operations', 'analytics'],
  FINANCE: ['/workbench', 'analytics', '/orders', '/operations'],
};

/**
 * 岗位重排后的轨道分组。
 *
 * `navigation` 默认即生产导航树；参数化只为让「运行期清单 → 轨道」这条接线可被端到端验证——
 * 生产树本票尚无受控节点（受控入口是票 04），没有这个缝就只能分别测两个纯函数，
 * 测不到它们有没有真的接上。
 */
export function railGroupsForRole(
  role: string | null,
  openModules: ReadonlySet<BusinessModuleId>,
  navigation: readonly NavigationNode[] = appNavigation,
): RailGroup[] {
  const groups = buildRailGroups(openModules, navigation);
  const priority = role ? ROLE_SECTION_PRIORITY[role] : undefined;
  if (!priority) return groups;
  const rank = (group: RailGroup) => {
    const index = priority.indexOf(group.key);
    return index === -1 ? Number.POSITIVE_INFINITY : index;
  };
  // sort 是稳定排序：未列入优先表的分组保持默认相对顺序（含京东工具紧随系统管理）。
  return [...groups].sort((a, b) => rank(a) - rank(b));
}
