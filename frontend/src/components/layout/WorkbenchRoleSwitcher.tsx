/**
 * 岗位选择器（Issue #104，原型 .ws 控件）：品牌下方 34px 下拉。
 * 受控组件：选中即回调，由外壳负责持久化与跳转；本组件不发请求、不写 URL（D1/D3）。
 * 键盘可达：ArrowUp/Down/Home/End 在选项间移动焦点，Esc 关闭并交还焦点给触发按钮。
 */

import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { theme } from 'antd';
import { WORKBENCH_ROLE_OPTIONS, workbenchRoleLabel } from '@/workbenchRole';

const LISTBOX_ID = 'workbench-role-listbox';

interface WorkbenchRoleSwitcherProps {
  role: string | null;
  onSelect: (value: string) => void;
}

export default function WorkbenchRoleSwitcher({ role, onSelect }: WorkbenchRoleSwitcherProps) {
  const { token } = theme.useToken();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const selected = listRef.current?.querySelector<HTMLButtonElement>('button[aria-selected="true"]');
    const first = listRef.current?.querySelector<HTMLButtonElement>('button[role="option"]');
    (selected ?? first)?.focus();

    const onPointerDown = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const onListKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return;
    event.preventDefault();
    const options = [...(listRef.current?.querySelectorAll<HTMLButtonElement>('button[role="option"]') ?? [])];
    if (!options.length) return;
    const current = options.indexOf(document.activeElement as HTMLButtonElement);
    let next = 0;
    if (event.key === 'ArrowDown') next = current < 0 ? 0 : (current + 1) % options.length;
    else if (event.key === 'ArrowUp') next = current <= 0 ? options.length - 1 : current - 1;
    else if (event.key === 'End') next = options.length - 1;
    options[next]?.focus();
  };

  const label = workbenchRoleLabel(role);

  return (
    <div ref={containerRef} style={{ position: 'relative', padding: '0 12px 10px' }}>
      <button
        ref={triggerRef}
        type="button"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? LISTBOX_ID : undefined}
        onClick={() => setOpen((value) => !value)}
        style={{
          width: '100%',
          height: token.controlHeight,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '0 10px',
          background: token.colorFillTertiary,
          border: `1px solid ${token.colorBorderSecondary}`,
          borderRadius: token.borderRadius,
          cursor: 'pointer',
          textAlign: 'left',
        }}
      >
        <span
          aria-hidden="true"
          style={{
            width: 20,
            height: 20,
            flex: 'none',
            borderRadius: 5,
            background: token.colorPrimaryBg,
            color: token.colorPrimary,
            display: 'grid',
            placeItems: 'center',
            fontSize: 11,
            fontWeight: 600,
          }}
        >
          {label ? label.slice(0, 1) : '?'}
        </span>
        <span
          style={{
            flex: 1,
            minWidth: 0,
            fontSize: 13.5,
            fontWeight: 500,
            color: label ? token.colorText : token.colorTextTertiary,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {label ?? '请选择岗位'}
        </span>
        <span style={{ color: token.colorTextTertiary, fontSize: 10, flex: 'none' }}>▾</span>
      </button>

      {open ? (
        <div
          ref={listRef}
          id={LISTBOX_ID}
          role="listbox"
          aria-label="切换岗位视图"
          onKeyDown={onListKeyDown}
          style={{
            position: 'absolute',
            left: 12,
            right: 12,
            top: 'calc(100% - 6px)',
            zIndex: 40,
            background: token.colorBgElevated,
            border: `1px solid ${token.colorBorder}`,
            borderRadius: token.borderRadiusLG,
            boxShadow: token.boxShadowSecondary,
            padding: 4,
          }}
        >
          <div style={{ fontSize: 10.5, color: token.colorTextTertiary, padding: '6px 9px 4px', letterSpacing: '0.06em' }}>
            切换岗位视图
          </div>
          {WORKBENCH_ROLE_OPTIONS.map((option) => {
            const selected = option.value === role;
            return (
              <button
                key={option.value}
                type="button"
                role="option"
                aria-selected={selected}
                onClick={() => {
                  setOpen(false);
                  triggerRef.current?.focus();
                  onSelect(option.value);
                }}
                style={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  padding: '7px 9px',
                  border: 0,
                  background: selected ? token.colorPrimaryBg : 'none',
                  borderRadius: 6,
                  cursor: 'pointer',
                  fontSize: 13.5,
                  fontWeight: selected ? 600 : 400,
                  color: selected ? token.colorPrimaryActive : token.colorTextSecondary,
                  textAlign: 'left',
                }}
              >
                <span style={{ flex: 1 }}>{option.label}</span>
                {selected ? <span style={{ fontSize: 11 }}>✓</span> : null}
              </button>
            );
          })}
          <div style={{ fontSize: 11, color: token.colorTextTertiary, padding: '6px 9px 7px', lineHeight: 1.45 }}>
            Phase 1 无登录：岗位只切前端视图与默认落地页，不构成身份、权限或数据隔离。
          </div>
        </div>
      ) : null}
    </div>
  );
}
