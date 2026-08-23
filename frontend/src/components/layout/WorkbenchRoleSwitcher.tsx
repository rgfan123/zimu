/**
 * 岗位选择器（Issue #104，原型 .ws 控件 1:1 移植，样式见 shell.css）。
 * 受控组件：选中即回调，由外壳负责持久化与跳转；本组件不发请求、不写 URL（D1/D3）。
 * 键盘可达：ArrowUp/Down/Home/End 在选项间移动焦点，Esc 关闭并交还焦点给触发按钮。
 */

import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { WORKBENCH_ROLE_OPTIONS, workbenchRoleLabel } from '@/workbenchRole';

const LISTBOX_ID = 'workbench-role-listbox';

interface WorkbenchRoleSwitcherProps {
  role: string | null;
  onSelect: (value: string) => void;
}

export default function WorkbenchRoleSwitcher({ role, onSelect }: WorkbenchRoleSwitcherProps) {
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
    <div ref={containerRef} className="zs-ws">
      <button
        ref={triggerRef}
        type="button"
        className="zs-ws-btn"
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? LISTBOX_ID : undefined}
        onClick={() => setOpen((value) => !value)}
      >
        <span className="av" aria-hidden="true">
          {label ? label.slice(0, 1) : '?'}
        </span>
        <span className={label ? 'nm' : 'nm empty'}>{label ?? '请选择岗位'}</span>
        <span className="cv">▾</span>
      </button>

      {open ? (
        <div
          ref={listRef}
          id={LISTBOX_ID}
          className="zs-ws-pop"
          role="listbox"
          aria-label="切换岗位视图"
          onKeyDown={onListKeyDown}
        >
          <div className="h">切换岗位视图</div>
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
              >
                <span style={{ flex: 1 }}>{option.label}</span>
                {selected ? <span className="ck">✓</span> : null}
              </button>
            );
          })}
          <div className="note">Phase 1 无登录：岗位只切前端视图与默认落地页，不构成身份、权限或数据隔离。</div>
        </div>
      ) : null}
    </div>
  );
}
