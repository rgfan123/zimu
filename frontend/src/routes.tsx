/**
 * 单一路由配置 —— 同时驱动侧边栏菜单与 <Routes>。
 * 后续票新增页面时：在此数组追加条目（并补对应 pages/ 目录组件），
 * 菜单与路由自动生效，无需改动 AppLayout / App。
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
import { AuditLogsPage } from '@/pages/system';
import AnalyticsPage from '@/pages/analytics/AnalyticsPage';
import DemoOrderPage from '@/pages/demo/DemoOrderPage';
import DashboardPage from '@/pages/dashboard/DashboardPage';
import { CategoriesPage, ProductsPage, SkuMappingsPage, SkusPage } from '@/pages/product';
import { FulfillmentTasksPage, JdWarehousePage, SalesOutboundPage, ShipmentsPage } from '@/pages/fulfillment';
import ProcurementTicketsPage from '@/pages/procurement';
import ManualReviewPage from '@/pages/workbench';
import ChannelMessagesPage from '@/pages/workbench/ChannelMessagesPage';
import { ConnectorsPage, SystemConfigPage } from '@/pages/system';

export interface AppRoute {
  /** 绝对路径；含子路由的父级不渲染自身页面 */
  path: string;
  label: string;
  icon?: ReactNode;
  /** 叶子路由渲染的组件 */
  element?: ReactNode;
  children?: AppRoute[];
  /** 外链（新标签打开），不注册为路由 */
  external?: string;
  /** 不在菜单展示（仍可路由） */
  hideInMenu?: boolean;
}

const iconFontSize = 16;

export const routeConfig: AppRoute[] = [
  {
    path: '/dashboard',
    label: '工作台',
    icon: <DashboardOutlined style={{ fontSize: iconFontSize }} />,
    element: <DashboardPage />,
  },
  {
    path: '/workbench',
    label: '作业中心',
    icon: <CheckSquareOutlined style={{ fontSize: iconFontSize }} />,
    children: [
      { path: '/workbench/reviews', label: '人工复核', element: <ManualReviewPage /> },
      { path: '/workbench/channel-messages', label: '企微消息', element: <ChannelMessagesPage /> },
      { path: '/fulfillment/tasks', label: '履约任务', element: <FulfillmentTasksPage /> },
      { path: '/procurement/tickets', label: '采购协同', element: <ProcurementTicketsPage /> },
      { path: '/fulfillment/sales-outbound', label: '文件作业', element: <SalesOutboundPage /> },
      { path: '/fulfillment/shipments', label: '发货记录', element: <ShipmentsPage /> },
      { path: '/fulfillment/jd-warehouse', label: '京东仓配', element: <JdWarehousePage /> },
    ],
  },
  {
    path: '/orders',
    label: '订单中心',
    icon: <UnorderedListOutlined style={{ fontSize: iconFontSize }} />,
    children: [
      { path: '/orders', label: '全部订单', element: <OrdersPage /> },
      { path: '/orders/pending', label: '待处理', element: <PendingOrdersPage /> },
      { path: '/orders/exceptions', label: '异常订单', element: <ExceptionOrdersPage /> },
      { path: '/orders/tracking', label: '订单追踪', element: <OrderTrackingPage /> },
      { path: '/orders/:orderId', label: '订单详情', hideInMenu: true, element: <OrderDetailPage /> },
    ],
  },
  {
    path: '/product',
    label: '主数据',
    icon: <DatabaseOutlined style={{ fontSize: iconFontSize }} />,
    children: [
      { path: '/product/products', label: '商品档案', element: <ProductsPage /> },
      { path: '/product/categories', label: '品类档案', element: <CategoriesPage /> },
      { path: '/product/skus', label: '内部 SKU', element: <SkusPage /> },
      { path: '/product/sku-mappings', label: 'SKU 映射', element: <SkuMappingsPage /> },
    ],
  },
  {
    path: '/analytics',
    label: '经营分析',
    icon: <BarChartOutlined style={{ fontSize: iconFontSize }} />,
    // 原型决策 D：数据中台为单屏 bento（全局筛选条 / 图↔文字双形态 / 下钻抽屉），不再拆四个页面
    element: <AnalyticsPage />,
  },
  {
    path: '/system',
    label: '系统管理',
    icon: <SettingOutlined style={{ fontSize: iconFontSize }} />,
    children: [
      { path: '/system/connectors', label: '渠道接入', element: <ConnectorsPage /> },
      { path: '/system/audit-logs', label: '操作审计', element: <AuditLogsPage /> },
      { path: '/system/config', label: '系统配置', element: <SystemConfigPage /> },
    ],
  },
  {
    path: '/demo/order',
    label: '模拟下单',
    icon: <RocketOutlined style={{ fontSize: iconFontSize }} />,
    element: <DemoOrderPage />,
  },
  {
    path: '/bi',
    label: '管理驾驶舱',
    icon: <GlobalOutlined style={{ fontSize: iconFontSize }} />,
    external: '/metabase',
  },
];

/** 摊平为叶子路由列表（绝对路径）。 */
export function flattenRoutes(routes: AppRoute[]): AppRoute[] {
  const result: AppRoute[] = [];
  const walk = (items: AppRoute[]) => {
    for (const r of items) {
      if (r.children?.length) walk(r.children);
      else if (!r.external) result.push(r);
    }
  };
  walk(routes);
  return result;
}

/** 段级模式匹配：':xxx' 视为通配，静态段越多越具体。 */
function matchRoutePattern(pattern: string, pathname: string): number {
  const pSegs = pattern.split('/').filter(Boolean);
  const sSegs = pathname.split('/').filter(Boolean);
  if (pSegs.length !== sSegs.length) return -1;
  let staticCount = 0;
  for (let i = 0; i < pSegs.length; i++) {
    if (pSegs[i].startsWith(':')) continue;
    if (pSegs[i] !== sSegs[i]) return -1;
    staticCount++;
  }
  return staticCount;
}

/** 当前路径命中的路由（静态段最多的模式优先），供顶栏标题 / 占位页使用。 */
export function useCurrentRoute(): AppRoute | undefined {
  const { pathname } = useLocation();
  const all = flattenRoutes(routeConfig);
  return all
    .map((r) => ({ route: r, score: matchRoutePattern(r.path, pathname) }))
    .filter((m) => m.score >= 0)
    .sort((a, b) => b.score - a.score || b.route.path.length - a.route.path.length)[0]
    ?.route;
}
