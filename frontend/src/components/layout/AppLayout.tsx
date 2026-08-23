/**
 * 应用外壳（Issue #104 · ADR 0004 大胆版）：
 * 原型壳层 1:1 移植——244px 侧栏（品牌 → 岗位选择器 → 全局搜索 → 分组平铺导航带字形与真实徽标 → 共享身份 footer），
 * 无顶栏。AntD 只承载页面内容区；壳层为手写 CSS（shell.css），颜色全部由 saasTheme 注入。
 * 岗位只重排导航分组顺序与默认落地页，绝不隐藏任何入口（D1：岗位 ≠ 权限）。
 */

import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties } from 'react';
import { Link, Outlet, useNavigate } from 'react-router-dom';
import { useCurrentRoute } from '@/routes';
import { saasTheme, saasVisualTokens } from '@/theme/saasTheme';
import { railGroupsForRole } from '@/components/layout/shellRail';
import { useReviewsBadge } from '@/components/layout/useRailBadges';
import WorkbenchRoleSwitcher from '@/components/layout/WorkbenchRoleSwitcher';
import GlobalSearchOverlay from '@/components/layout/GlobalSearchOverlay';
import {
  readStoredWorkbenchRole,
  storeWorkbenchRole,
  workbenchRoleLabel,
  workbenchRoleLanding,
} from '@/workbenchRole';
import brandAvatarUrl from '@/assets/zimu-brand-avatar.png';
import './shell.css';

/** shell.css 的唯一取色入口：全部来自 saasTheme.ts，壳层 CSS 不写死色值。 */
const shellVars = {
  '--zs-canvas': saasVisualTokens.surface.canvas,
  '--zs-raised': saasVisualTokens.surface.raised,
  '--zs-border': saasVisualTokens.neutral[300],
  '--zs-border-2': saasTheme.token?.colorBorderSecondary ?? saasVisualTokens.neutral[300],
  '--zs-fill-2': saasVisualTokens.neutral[100],
  '--zs-fill-3': saasVisualTokens.neutral[50],
  '--zs-text': saasVisualTokens.text.primary,
  '--zs-text-2': saasVisualTokens.text.secondary,
  '--zs-text-3': saasVisualTokens.text.tertiary,
  '--zs-heading': saasTheme.token?.colorTextHeading ?? saasVisualTokens.text.primary,
  '--zs-brand': saasVisualTokens.brand.primary,
  '--zs-brand-bg': saasVisualTokens.brand.subtle,
  '--zs-brand-active': saasVisualTokens.brand.active,
  '--zs-error': saasVisualTokens.semantic.error,
  '--zs-error-bg': saasTheme.token?.colorErrorBg ?? saasVisualTokens.brand.subtle,
  '--zs-scrim': 'rgba(32, 38, 51, 0.4)',
  '--zs-sh2': saasTheme.token?.boxShadowSecondary ?? 'none',
} as CSSProperties;

export default function AppLayout() {
  const navigate = useNavigate();
  const route = useCurrentRoute();
  const [role, setRole] = useState<string | null>(() => readStoredWorkbenchRole());
  const [searchOpen, setSearchOpen] = useState(false);
  const reviewsBadge = useReviewsBadge(role);

  const groups = useMemo(() => railGroupsForRole(role), [role]);
  const roleLabel = workbenchRoleLabel(role);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setSearchOpen((value) => !value);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  const onSelectRole = (value: string) => {
    setRole(value);
    storeWorkbenchRole(value);
    // 岗位切换 = 换一份活干：跳该岗位的默认工作台。岗位绝不写进 URL（D3）。
    const landing = workbenchRoleLanding(value);
    if (landing) navigate(landing);
  };

  return (
    <div className="zs-shell" style={shellVars}>
      <aside className="zs-side">
        <div className="zs-brand">
          <img src={brandAvatarUrl} alt="" aria-hidden="true" draggable={false} width={28} height={28} />
          <span>子牧履约中台</span>
        </div>

        <WorkbenchRoleSwitcher role={role} onSelect={onSelectRole} />

        <button type="button" className="zs-search" onClick={() => setSearchOpen(true)}>
          <span>搜单号 / 运单号</span>
          <span className="kb">⌘K</span>
        </button>

        <nav className="zs-nav" aria-label="主导航">
          {groups.map((group) => (
            <div key={group.key}>
              <div className="grp">{group.title}</div>
              {group.items.map((item) => {
                if (item.external) {
                  return (
                    <a key={item.path} href={item.external} target="_blank" rel="noreferrer">
                      <span className="ic" aria-hidden="true">
                        {item.glyph}
                      </span>
                      <span className="nm">{item.label}</span>
                    </a>
                  );
                }
                const current = route?.path === item.path;
                const badge = item.badge === 'reviews-open' && reviewsBadge !== null && reviewsBadge > 0 ? reviewsBadge : null;
                const className = [current ? 'cur' : '', badge !== null ? 'attn' : ''].filter(Boolean).join(' ') || undefined;
                return (
                  <Link key={item.path} to={item.path} className={className} aria-current={current ? 'page' : undefined}>
                    <span className="ic" aria-hidden="true">
                      {item.glyph}
                    </span>
                    <span className="nm">{item.label}</span>
                    {badge !== null ? <span className="bg">{badge}</span> : null}
                  </Link>
                );
              })}
            </div>
          ))}
          {role ? <div className="zs-navnote">岗位只影响排序与默认落地页，不构成权限；全部功能对所有人可见。</div> : null}
        </nav>

        <div className="zs-user">
          <span className="av" aria-hidden="true">
            {roleLabel ? roleLabel.slice(0, 1) : '?'}
          </span>
          <div className="who">
            <b>{roleLabel ?? '未选择岗位'}</b>
            <span>共享网关身份</span>
          </div>
        </div>
      </aside>

      <main className="zs-main">
        <Outlet />
      </main>

      <GlobalSearchOverlay open={searchOpen} onClose={() => setSearchOpen(false)} />
    </div>
  );
}
