/**
 * 应用外壳（Issue #104，原型形态契约 ADR 0001/0002）：
 * 244px 固定侧栏（品牌 → 岗位选择器 → 分组导航 → 共享身份 footer），无顶栏。
 * 岗位只决定默认落地工作台（D1），全站菜单对所有岗位一致，不隐藏、不加锁。
 */

import { useMemo, useState } from 'react';
import { Outlet, useNavigate } from 'react-router-dom';
import { Layout, Menu, theme } from 'antd';
import type { MenuProps } from 'antd';
import { flattenRoutes, routeConfig, useCurrentRoute, type AppRoute } from '@/routes';
import { visibleNavigationTree } from '@/navigation';
import {
  readStoredWorkbenchRole,
  storeWorkbenchRole,
  workbenchRoleLabel,
  workbenchRoleLanding,
} from '@/workbenchRole';
import WorkbenchRoleSwitcher from '@/components/layout/WorkbenchRoleSwitcher';
import brandAvatarUrl from '@/assets/zimu-brand-avatar.png';

const { Sider, Content } = Layout;

type MenuItems = NonNullable<MenuProps['items']>;

/**
 * 原型导航是「分组标题 + 平铺链接」，没有折叠层级：一级板块渲染为 Menu 分组，
 * 嵌套板块（京东工具）拍平为并列分组。分组 key 加 `group:` 前缀，避免与叶子路径重名
 * （取代已删除的 NAVIGATION_GROUP_SUFFIX hack）。
 */
function buildMenuItems(routes: AppRoute[]): MenuItems {
  const items: MenuItems = [];
  const pushGroups = (section: AppRoute) => {
    const leaves: MenuItems = [];
    const nested: AppRoute[] = [];
    for (const child of section.children ?? []) {
      if (child.children?.length) {
        nested.push({ ...child, label: `${section.label} · ${child.label}` });
      } else {
        leaves.push({ key: child.path, label: child.label });
      }
    }
    // 先落父分组自身条目，再落嵌套分组，保证「京东工具」排在「系统管理」之后。
    if (leaves.length) items.push({ type: 'group', key: `group:${section.path}`, label: section.label, children: leaves });
    for (const child of nested) pushGroups(child);
  };

  for (const route of routes) {
    if (route.external) {
      items.push({
        key: route.path,
        icon: route.icon,
        label: (
          <a href={route.external} target="_blank" rel="noreferrer">
            {route.label}
          </a>
        ),
      });
    } else if (route.children?.length) {
      pushGroups(route);
    } else {
      items.push({ key: route.path, icon: route.icon, label: route.label });
    }
  }
  return items;
}

export default function AppLayout() {
  const navigate = useNavigate();
  const route = useCurrentRoute();
  const { token } = theme.useToken();
  const [role, setRole] = useState<string | null>(() => readStoredWorkbenchRole());

  const menuItems = useMemo(() => buildMenuItems(visibleNavigationTree(routeConfig)), []);
  const selectedKeys = useMemo(() => [route?.path ?? ''], [route]);

  const onSelectRole = (value: string) => {
    setRole(value);
    storeWorkbenchRole(value);
    // 岗位切换 = 换一份活干：跳该岗位的默认工作台。岗位绝不写进 URL（D3）。
    const landing = workbenchRoleLanding(value);
    if (landing) navigate(landing);
  };

  const roleLabel = workbenchRoleLabel(role);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={244}
        collapsible={false}
        trigger={null}
        theme="light"
        className="app-shell-sider"
        style={{
          borderRight: `1px solid ${token.colorBorderSecondary}`,
          position: 'sticky',
          top: 0,
          height: '100vh',
        }}
      >
        <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '16px 16px 12px' }}>
            <img
              src={brandAvatarUrl}
              alt=""
              aria-hidden="true"
              draggable={false}
              width={28}
              height={28}
              style={{
                width: 28,
                height: 28,
                borderRadius: 8,
                objectFit: 'cover',
                flexShrink: 0,
                border: `1px solid ${token.colorBorderSecondary}`,
                background: token.colorBgContainer,
              }}
            />
            <span style={{ fontSize: 15, fontWeight: 600, color: token.colorTextHeading, whiteSpace: 'nowrap' }}>
              子牧履约中台
            </span>
          </div>

          <WorkbenchRoleSwitcher role={role} onSelect={onSelectRole} />

          <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 4px 12px' }}>
            <Menu
              mode="inline"
              items={menuItems}
              selectedKeys={selectedKeys}
              onClick={({ key }) => {
                // 只对真实存在的叶子路由导航；分组 key（group: 前缀）与外链不导航
                const target = flattenRoutes(routeConfig).find((r) => r.path === key);
                if (target) navigate(key);
              }}
              style={{ borderInlineEnd: 'none' }}
            />
          </div>

          <div
            style={{
              borderTop: `1px solid ${token.colorBorderSecondary}`,
              padding: '10px 14px',
              display: 'flex',
              alignItems: 'center',
              gap: 9,
            }}
          >
            <span
              aria-hidden="true"
              style={{
                width: 26,
                height: 26,
                flex: 'none',
                borderRadius: '50%',
                background: token.colorFillSecondary,
                color: token.colorTextSecondary,
                display: 'grid',
                placeItems: 'center',
                fontSize: 11.5,
                fontWeight: 600,
              }}
            >
              {roleLabel ? roleLabel.slice(0, 1) : '?'}
            </span>
            <div style={{ flex: 1, minWidth: 0, lineHeight: 1.3 }}>
              <div
                style={{
                  fontSize: 13,
                  fontWeight: 500,
                  color: token.colorText,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                }}
              >
                {roleLabel ?? '未选择岗位'}
              </div>
              <div style={{ fontSize: 11, color: token.colorTextTertiary }}>共享网关身份</div>
            </div>
          </div>
        </div>
      </Sider>

      <Content style={{ background: token.colorBgLayout }}>
        <div style={{ maxWidth: 1420, padding: '26px 30px 72px' }}>
          <Outlet />
        </div>
      </Content>
    </Layout>
  );
}
