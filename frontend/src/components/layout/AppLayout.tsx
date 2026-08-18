/**
 * 应用框架：侧边栏（PRD §22 导航树）+ 顶栏（页面标题 / 业务日期 / 环境标识）。
 * 菜单与路由共用 routeConfig 单一配置源。
 */

import { useEffect, useMemo, useState } from 'react';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Button, Layout, Menu, Tag, Typography, theme } from 'antd';
import type { MenuProps } from 'antd';
import { MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { flattenRoutes, routeConfig, useCurrentRoute, type AppRoute } from '@/routes';
import {
  NAVIGATION_GROUP_SUFFIX,
  navigationContextFromRoutes,
  navigationOpenKeys,
  visibleNavigationTree,
} from '@/navigation';
import brandAvatarUrl from '@/assets/zimu-brand-avatar.png';

const { Sider, Header, Content } = Layout;

/** 分组节点 key 与叶子路径可能重名（如 订单管理 组 / 全部订单 叶均为 /orders），
 *  菜单 key 加 '~' 后缀区分，路由选中态仍用叶子路径。 */
function buildMenuItems(routes: AppRoute[]): NonNullable<MenuProps['items']> {
  return routes.map((r) => {
      if (r.external) {
        return {
          key: r.path,
          icon: r.icon,
          label: (
            <a href={r.external} target="_blank" rel="noreferrer">
              {r.label}
            </a>
          ),
        };
      }
      if (r.children?.length) {
        return {
          key: `${r.path}${NAVIGATION_GROUP_SUFFIX}`,
          icon: r.icon,
          label: r.label,
          children: buildMenuItems(r.children),
        };
      }
      return { key: r.path, icon: r.icon, label: r.label };
  });
}

export default function AppLayout() {
  // 原型决策 D：数据密集型 ERP 默认使用图标窄轨，给 12 列运营看板留出完整宽度。
  const [collapsed, setCollapsed] = useState(false);
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const route = useCurrentRoute();
  const { token } = theme.useToken();

  const menuItems = useMemo(() => buildMenuItems(visibleNavigationTree(routeConfig)), []);
  const selectedKeys = useMemo(() => [route?.path ?? ''], [route]);
  const currentNavigation = navigationContextFromRoutes(routeConfig, pathname, route?.label ?? '');
  const defaultOpenKeys = useMemo(() => navigationOpenKeys(routeConfig, pathname), [pathname]);
  const [openKeys, setOpenKeys] = useState<string[]>(defaultOpenKeys);
  useEffect(() => {
    if (defaultOpenKeys.length) setOpenKeys(defaultOpenKeys);
  }, [defaultOpenKeys]);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={216}
        collapsedWidth={64}
        collapsed={collapsed}
        trigger={null}
        theme="light"
        style={{
          borderRight: `1px solid ${token.colorBorderSecondary}`,
          position: 'sticky',
          top: 0,
          height: '100vh',
          overflow: 'auto',
        }}
      >
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '0 16px',
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            overflow: 'hidden',
          }}
        >
          <img
            src={brandAvatarUrl}
            alt=""
            aria-hidden="true"
            draggable={false}
            width={32}
            height={32}
            style={{
              width: 32,
              height: 32,
              borderRadius: 9,
              objectFit: 'cover',
              boxSizing: 'border-box',
              flexShrink: 0,
              border: `1px solid ${token.colorBorderSecondary}`,
              background: token.colorBgContainer,
            }}
          />
          {!collapsed ? (
            <div style={{ lineHeight: 1.2, whiteSpace: 'nowrap' }}>
              <div style={{ fontWeight: 700, fontSize: 15, color: token.colorTextHeading }}>子牧履约中台</div>
              <div style={{ fontSize: 11, color: token.colorTextTertiary }}>Fulfillment & Logistics Hub</div>
            </div>
          ) : null}
        </div>
        <Menu
          mode="inline"
          items={menuItems}
          selectedKeys={selectedKeys}
          openKeys={collapsed ? [] : openKeys}
          onOpenChange={(keys) => setOpenKeys(keys.map(String))}
          onClick={({ key }) => {
            // 只对真实存在的叶子路由导航；分组 key（带 ~ 后缀）与外链不导航
            const target = flattenRoutes(routeConfig).find((r) => r.path === key);
            if (target) navigate(key);
          }}
          style={{ borderInlineEnd: 'none', padding: '8px 0' }}
        />
        {!collapsed ? (
          <div style={{ position: 'absolute', bottom: 12, left: 0, right: 0, textAlign: 'center' }}>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              子牧订单履约中台
            </Typography.Text>
          </div>
        ) : null}
      </Sider>

      <Layout>
        <Header
          style={{
            background: token.colorBgContainer,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            padding: '0 20px',
            height: 56,
            lineHeight: '56px',
            display: 'flex',
            alignItems: 'center',
            gap: 16,
            position: 'sticky',
            top: 0,
            zIndex: 10,
          }}
        >
          <Button
            type="text"
            aria-label={collapsed ? '展开菜单' : '收起菜单'}
            icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            onClick={() => setCollapsed((c) => !c)}
            style={{ fontSize: 16 }}
          />
          <div style={{ flex: 1, lineHeight: 1.25 }}>
            <Typography.Text type="secondary" style={{ display: 'block', fontSize: 12 }}>
              {currentNavigation.section}
            </Typography.Text>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {currentNavigation.page}
            </Typography.Title>
          </div>
          <Tag
            bordered={false}
            style={{
              borderRadius: 4,
              marginInlineEnd: 0,
              color: token.colorTextSecondary,
              background: token.colorFillTertiary,
            }}
          >
            业务运营
          </Tag>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            {dayjs().format('YYYY-MM-DD dddd')}
          </Typography.Text>
        </Header>

        <Content style={{ padding: 20, background: token.colorBgLayout }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
