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
  RocketOutlined,
  SettingOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons';
import OrderTrackingPage from '@/pages/orders/OrderTrackingPage';
import OrderDetailPage from '@/pages/orders/OrderDetailPage';
import ExceptionOrdersPage from '@/pages/orders/ExceptionOrdersPage';
import OrdersPage from '@/pages/orders/OrdersPage';
import PendingOrdersPage from '@/pages/orders/PendingOrdersPage';
import InventoryOverviewPage from '@/pages/inventory/InventoryOverviewPage';
import InventoryDetailsPage from '@/pages/inventory/InventoryDetailsPage';
import { AuditLogsPage } from '@/pages/system';
import AnalyticsPage from '@/pages/analytics/AnalyticsPage';
import DemoOrderPage from '@/pages/demo/DemoOrderPage';
import DashboardPage from '@/pages/dashboard/DashboardPage';
import { CategoriesPage, ProductsPage, SkuMappingsPage, SkusPage } from '@/pages/product';
import {
  FulfillmentTasksPage,
  JdBasicInfoQueryPage,
  JdOrderQueryPage,
  JdReturnQueryPage,
  JdSerialQueryPage,
  JdStockQueryPage,
  JdWarehousePage,
  SalesOutboundPage,
  ShipmentsPage,
} from '@/pages/fulfillment';
import ProcurementTicketsPage from '@/pages/procurement';
import ManualReviewPage from '@/pages/workbench';
import ChannelMessagesPage from '@/pages/workbench/ChannelMessagesPage';
import { ConnectorsPage, FulfillmentProvidersPage, SystemConfigPage } from '@/pages/system';
import { appNavigation, routeMatchScore, type NavigationNode } from '@/navigation';

export interface AppRoute extends Omit<NavigationNode, 'children'> {
  icon?: ReactNode;
  element?: ReactNode;
  children?: AppRoute[];
}

const iconFontSize = 16;

const routeElements: Readonly<Record<string, ReactNode>> = {
  '/dashboard': <DashboardPage />,
  '/workbench/reviews': <ManualReviewPage />,
  '/workbench/channel-messages': <ChannelMessagesPage />,
  '/fulfillment/tasks': <FulfillmentTasksPage />,
  '/procurement/tickets': <ProcurementTicketsPage />,
  '/fulfillment/sales-outbound': <SalesOutboundPage />,
  '/fulfillment/shipments': <ShipmentsPage />,
  '/orders': <OrdersPage />,
  '/orders/pending': <PendingOrdersPage />,
  '/orders/exceptions': <ExceptionOrdersPage />,
  '/orders/tracking': <OrderTrackingPage />,
  '/orders/:orderId': <OrderDetailPage />,
  '/inventory/overview': <InventoryOverviewPage />,
  '/inventory/details': <InventoryDetailsPage />,
  '/product/products': <ProductsPage />,
  '/product/categories': <CategoriesPage />,
  '/product/skus': <SkusPage />,
  '/product/sku-mappings': <SkuMappingsPage />,
  '/analytics': <AnalyticsPage />,
  '/system/connectors': <ConnectorsPage />,
  '/system/audit-logs': <AuditLogsPage />,
  '/system/config': <SystemConfigPage />,
  '/system/fulfillment-providers': <FulfillmentProvidersPage />,
  '/fulfillment/jd-warehouse': <JdWarehousePage />,
  '/fulfillment/jd-basicinfo': <JdBasicInfoQueryPage />,
  '/fulfillment/jd-stock': <JdStockQueryPage />,
  '/fulfillment/jd-serial': <JdSerialQueryPage />,
  '/fulfillment/jd-order': <JdOrderQueryPage />,
  '/fulfillment/jd-return': <JdReturnQueryPage />,
  '/demo/order': <DemoOrderPage />,
};

const routeIcons: Readonly<Record<string, ReactNode>> = {
  '/dashboard': <DashboardOutlined style={{ fontSize: iconFontSize }} />,
  '/workbench': <CheckSquareOutlined style={{ fontSize: iconFontSize }} />,
  '/orders': <UnorderedListOutlined style={{ fontSize: iconFontSize }} />,
  '/inventory': <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
  '/product': <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
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
