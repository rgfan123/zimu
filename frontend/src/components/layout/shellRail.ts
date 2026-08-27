/**
 * 侧栏轨道视图模型（Issue #104 · ADR 0004）。
 * 数据单一事实源仍是 navigation.ts 的 appNavigation；本模块只做展示层：
 * 分组拍平、字形、岗位重排与徽标声明。岗位只重排顺序，绝不隐藏任何分组（D1）。
 */

import { appNavigation, visibleNavigationTree, type NavigationNode } from '@/navigation';

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
 */
export function buildRailGroups(): RailGroup[] {
  const tree = visibleNavigationTree(appNavigation as readonly NavigationNode[]);
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
  FULFILLMENT_OPS: ['/workbench', '/orders', '/operations', '/settings', 'analytics'],
  SKU_OPS: ['/workbench', '/operations', '/settings', 'analytics'],
  CUSTOMER_OPS: ['/workbench', '/orders', '/operations', 'analytics'],
  ORDER_OPS: ['/workbench', '/orders', '/operations', 'analytics'],
  FINANCE: ['/workbench', 'analytics', '/orders', '/operations'],
};

export function railGroupsForRole(role: string | null): RailGroup[] {
  const groups = buildRailGroups();
  const priority = role ? ROLE_SECTION_PRIORITY[role] : undefined;
  if (!priority) return groups;
  const rank = (group: RailGroup) => {
    const index = priority.indexOf(group.key);
    return index === -1 ? Number.POSITIVE_INFINITY : index;
  };
  // sort 是稳定排序：未列入优先表的分组保持默认相对顺序（含京东工具紧随系统管理）。
  return [...groups].sort((a, b) => rank(a) - rank(b));
}
