/**
 * 路由装配：navigation.ts 提供无 React 的生产导航树；本文件只按 path 绑定页面与图标，
 * 供侧边栏和 <Routes> 共同消费。
 *
 * PRD §22 导航结构 + 地图 Notes「模拟下单」演示页 + 「BI」外链。
 */

import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import {
  BarChartOutlined,
  CheckSquareOutlined,
  DatabaseOutlined,
  DashboardOutlined,
  GlobalOutlined,
  RobotOutlined,
  RocketOutlined,
  SettingOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import OrderDetailPage from '@/pages/orders/OrderDetailPage';
import OrdersPage from '@/pages/orders/OrdersPage';
import ManualOrderCreatePage from '@/pages/orders/ManualOrderCreatePage';
import InventoryOverviewPage from '@/pages/inventory/InventoryOverviewPage';
import InventoryDetailsPage from '@/pages/inventory/InventoryDetailsPage';
import RawMaterialInventoryPage from '@/pages/inventory/RawMaterialInventoryPage';
import { AuditLogsPage } from '@/pages/system';
import AnalyticsPage from '@/pages/analytics/AnalyticsPage';
import DemoOrderPage from '@/pages/demo/DemoOrderPage';
import DashboardPage from '@/pages/dashboard/DashboardPage';
import { BundlesPage, CategoriesPage, ProductsPage, SkuMappingsPage, SkusPage } from '@/pages/product';
import {
  FulfillmentTasksPage,
  JdToolsPage,
  OutboundReconPage,
  SalesOutboundPage,
  ShipmentsPage,
} from '@/pages/fulfillment';
import ProcurementTicketsPage, { ProcurementPriceComparePage } from '@/pages/procurement';
import { ReviewQueueCompatRoute, AlertsQueuePage, ShippingWorkbenchPage, ReconWorkbenchPage, ProcurementWorkbenchPage } from '@/pages/workbench';
import ChannelMessagesPage from '@/pages/workbench/ChannelMessagesPage';
import BusinessFollowUpsPage from '@/pages/workbench/BusinessFollowUpsPage';
import ZhonghuiChannelPage from '@/pages/upload/ZhonghuiChannelPage';
import {
  ConnectorsPage,
  FulfillmentProvidersPage,
  McpExposurePage,
  OperatorsPage,
  SystemConfigPage,
  WecomBotsPage,
} from '@/pages/system';
import {
  AgentCostPage,
  AgentCreatePage,
  AgentDetailPage,
  AgentRunsPage,
  AgentsListPage,
  ReplyPolicyPage,
  FulfillmentFilePage,
  RunDetailPage,
} from '@/pages/agents';
import { appNavigation, routeMatchScore, type NavigationNode } from '@/navigation';

export interface AppRoute extends Omit<NavigationNode, 'children'> {
  icon?: ReactNode;
  element?: ReactNode;
  children?: AppRoute[];
}

const iconFontSize = 16;

const routeElements: Readonly<Record<string, ReactNode>> = {
  '/dashboard': <DashboardPage />,
  '/workbench/reviews': <ReviewQueueCompatRoute />,
  '/workbench/alerts': <AlertsQueuePage />,
  '/workbench/channel-messages': <ChannelMessagesPage />,
  '/workbench/business-followups': <BusinessFollowUpsPage />,
  '/workbench/shipping': <ShippingWorkbenchPage />,
  '/workbench/procurement': <ProcurementWorkbenchPage />,
  '/workbench/recon': <ReconWorkbenchPage />,
  '/fulfillment/tasks': <FulfillmentTasksPage />,
  '/procurement/tickets': <ProcurementTicketsPage />,
  '/procurement/price-compare': <ProcurementPriceComparePage />,
  '/fulfillment/sales-outbound': <SalesOutboundPage />,
  '/fulfillment/shipments': <ShipmentsPage />,
  '/fulfillment/outbound-recon': <OutboundReconPage />,
  '/orders': <OrdersPage />,
  // V100 手工建单：MANUAL 渠道柜台直录 + 当场路由出发货单。
  '/orders/manual-create': <ManualOrderCreatePage />,
  // 预设视图已并入「全部订单」页内切换；旧 URL 直达同一合并组件，由 OrdersPage 按 pathname 解析预设。
  '/orders/pending': <OrdersPage />,
  '/orders/exceptions': <OrdersPage />,
  '/orders/tracking': <OrdersPage />,
  '/orders/:orderId': <OrderDetailPage />,
  '/agents': <AgentsListPage />,
  '/agents/runs': <AgentRunsPage />,
  '/agents/reply-policies': <ReplyPolicyPage />,
  '/agents/cost': <AgentCostPage />,
  '/agents/fulfillment-file': <FulfillmentFilePage />,
  '/agents/new': <AgentCreatePage />,
  '/agents/:slug': <AgentDetailPage />,
  '/agents/runs/:runId': <RunDetailPage />,
  '/agents/:slug/evals': <AgentDetailPage />,
  '/inventory/overview': <InventoryOverviewPage />,
  '/inventory/details': <InventoryDetailsPage />,
  // 票 06：原料库存（受运行期清单控制；未接通时菜单里没有它，但 URL 直达照常渲染出未接通态）。
  '/inventory/raw-materials': <RawMaterialInventoryPage />,
  '/product/products': <ProductsPage />,
  '/product/categories': <CategoriesPage />,
  '/product/skus': <SkusPage />,
  '/product/sku-mappings': <SkuMappingsPage />,
  '/product/bundles': <BundlesPage />,
  // 商品档案「上架」二级页：由 PlatformUploadModal 选定渠道后进入，不占主导航位。
  '/upload-platform/zhonghui': <ZhonghuiChannelPage />,
  '/analytics': <AnalyticsPage />,
  '/system/connectors': <ConnectorsPage />,
  '/system/audit-logs': <AuditLogsPage />,
  '/system/config': <SystemConfigPage />,
  // 票 05：MCP 开放面只读核对（菜单隐藏，入口在 Agent 列表页；URL 直达照常可用）。
  '/system/mcp-exposure': <McpExposurePage />,
  '/system/fulfillment-providers': <FulfillmentProvidersPage />,
  '/system/operators': <OperatorsPage />,
  '/system/wecom-bots': <WecomBotsPage />,
  '/system/jd-tools': <JdToolsPage />,
  // 六个京东查询工具并入「京东工具」页内 Tab；旧 URL 直达同一页并定位到对应 Tab。
  '/fulfillment/jd-warehouse': <JdToolsPage />,
  '/fulfillment/jd-basicinfo': <JdToolsPage />,
  '/fulfillment/jd-stock': <JdToolsPage />,
  '/fulfillment/jd-serial': <JdToolsPage />,
  '/fulfillment/jd-order': <JdToolsPage />,
  '/fulfillment/jd-return': <JdToolsPage />,
  '/demo/order': <DemoOrderPage />,
};

const routeIcons: Readonly<Record<string, ReactNode>> = {
  '/dashboard': <DashboardOutlined style={{ fontSize: iconFontSize }} />,
  '/workbench': <CheckSquareOutlined style={{ fontSize: iconFontSize }} />,
  '/orders': <UnorderedListOutlined style={{ fontSize: iconFontSize }} />,
  '/agents': <RobotOutlined style={{ fontSize: iconFontSize }} />,
  '/inventory': <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
  '/product': <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
  '/master-data': <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
  '/analytics': <BarChartOutlined style={{ fontSize: iconFontSize }} />,
  '/system': <SettingOutlined style={{ fontSize: iconFontSize }} />,
  '/demo/order': <RocketOutlined style={{ fontSize: iconFontSize }} />,
  '/bi': <GlobalOutlined style={{ fontSize: iconFontSize }} />,
};

function bindNavigationRoutes(routes: readonly NavigationNode[], depth = 0): AppRoute[] {
  return routes.map((route) => {
    const children = route.children?.length ? bindNavigationRoutes(route.children, depth + 1) : undefined;
    const element = children ? undefined : routeElements[route.path];
    if (!children && !route.external && element === undefined) {
      throw new Error(`Missing route element for ${route.path}`);
    }
    return { ...route, icon: depth === 0 ? routeIcons[route.path] : undefined, element, children };
  });
}

export const routeConfig: AppRoute[] = bindNavigationRoutes(appNavigation);

/** 摊平为叶子路由列表（绝对路径）。 */
export function flattenRoutes(routes: readonly AppRoute[]): AppRoute[] {
  const result: AppRoute[] = [];
  const walk = (items: readonly AppRoute[]) => {
    for (const r of items) {
      if (r.children?.length) walk(r.children);
      else if (!r.external) result.push(r);
    }
  };
  walk(routes);
  return result;
}

/** 当前路径命中的路由（静态段最多的模式优先），供顶栏标题 / 占位页使用。 */
export function useCurrentRoute(): AppRoute | undefined {
  const { pathname } = useLocation();
  const all = flattenRoutes(routeConfig);
  return all
    .map((r) => ({ route: r, score: routeMatchScore(r.path, pathname) }))
    .filter((m) => m.score >= 0)
    .sort((a, b) => b.score - a.score || b.route.path.length - a.route.path.length)[0]
    ?.route;
}
