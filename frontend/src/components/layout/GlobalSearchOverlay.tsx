/**
 * 全局搜索 overlay（Issue #104 · ADR 0004 诚实版）：
 * 跨对象搜索（运单/批次/客户）后端尚无端点——界面如实说明，回车直通订单页按关键词查询。
 * 后端补全局搜索端点后，本组件原位升级为分组结果面板。
 * 焦点契约：打开聚焦输入框；面板内只有一个可聚焦元素，Tab 被圈定在输入框上；
 * 任何关闭路径（Esc / 提交 / 点遮罩）都把焦点交还触发按钮。
 */

import { useEffect, useRef, useState } from 'react';
import type { RefObject } from 'react';
import { useNavigate } from 'react-router-dom';

interface GlobalSearchOverlayProps {
  open: boolean;
  onClose: () => void;
  /** 关闭后交还焦点的触发控件（侧栏搜索按钮）。 */
  returnFocusRef: RefObject<HTMLButtonElement | null>;
}

export default function GlobalSearchOverlay({ open, onClose, returnFocusRef }: GlobalSearchOverlayProps) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [value, setValue] = useState('');

  const close = () => {
    onClose();
    returnFocusRef.current?.focus();
  };

  useEffect(() => {
    if (!open) return;
    setValue('');
    inputRef.current?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
        returnFocusRef.current?.focus();
      } else if (event.key === 'Tab') {
        // 面板内唯一可聚焦元素是输入框：把 Tab 圈定在它上面，避免焦点跑到遮罩后面。
        event.preventDefault();
        inputRef.current?.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
    // returnFocusRef 是稳定 ref 容器；onClose 由父组件以稳定引用传入。
  }, [open, onClose, returnFocusRef]);

  if (!open) return null;

  const submit = () => {
    const query = value.trim();
    if (!query) return;
    close();
    navigate(`/orders?query=${encodeURIComponent(query)}`);
  };

  return (
    <div
      className="zs-so"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <div className="zs-so-panel" role="dialog" aria-modal="true" aria-label="全局搜索">
        <input
          ref={inputRef}
          className="zs-so-input"
          placeholder="搜订单号 / 平台单号，回车直达订单查询"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') submit();
          }}
        />
        <div className="zs-so-hint">
          跨对象全局搜索（运单 / 批次 / 客户手机号）尚未接入后端；当前回车会带你到订单页按此关键词查询。
        </div>
      </div>
    </div>
  );
}
