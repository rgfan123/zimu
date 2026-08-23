/**
 * 全局搜索 overlay（Issue #104 · ADR 0004 诚实版）：
 * 跨对象搜索（运单/批次/客户）后端尚无端点——界面如实说明，回车直通订单页按关键词查询。
 * 后端补全局搜索端点后，本组件原位升级为分组结果面板。
 */

import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';

interface GlobalSearchOverlayProps {
  open: boolean;
  onClose: () => void;
}

export default function GlobalSearchOverlay({ open, onClose }: GlobalSearchOverlayProps) {
  const navigate = useNavigate();
  const inputRef = useRef<HTMLInputElement>(null);
  const [value, setValue] = useState('');

  useEffect(() => {
    if (open) {
      setValue('');
      inputRef.current?.focus();
    }
  }, [open]);

  if (!open) return null;

  const submit = () => {
    const query = value.trim();
    if (!query) return;
    onClose();
    navigate(`/orders?query=${encodeURIComponent(query)}`);
  };

  return (
    <div
      className="zs-so"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
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
            if (event.key === 'Escape') onClose();
          }}
        />
        <div className="zs-so-hint">
          跨对象全局搜索（运单 / 批次 / 客户手机号）尚未接入后端；当前回车会带你到订单页按此关键词查询。
        </div>
      </div>
    </div>
  );
}
