/**
 * 页面外壳：标题 + 说明 + 操作区（右上），下方统一 16px 纵向间距的内容区。
 *
 * 覆盖 46 个页面里最常手搓的两段样板：`<Space direction="vertical" size={16}
 * style={{ width: '100%' }}>` 外层，以及 JdWarehouse / JdStockQuery / OutboundRecon
 * 等页面重复的「图标 + 标题 + 说明 + 右侧操作」头部卡。
 *
 * 刻意不做成万能页面包装：只负责头部与纵向间距，正文由页面自己组合
 * （筛选区 / 表格 / 抽屉等），避免把所有页面差异都塞进 props。
 */

import type { ReactNode } from 'react';
import { Card, Space, Typography } from 'antd';
import { saasVisualTokens } from '@/theme/saasTheme';

export interface PageShellProps {
  /** 页面标题 */
  title: ReactNode;
  /** 标题下方的说明文字 */
  description?: ReactNode;
  /** 操作区，渲染在标题行右侧（导出 / 刷新按钮、状态标签等） */
  actions?: ReactNode;
  /** 标题前的图标（可选，如 CloudServerOutlined） */
  icon?: ReactNode;
  /** 页面正文，紧接在头部下方，统一纵向间距 */
  children?: ReactNode;
}

export default function PageShell({ title, description, actions, icon, children }: PageShellProps) {
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" styles={{ body: { padding: '16px 18px' } }}>
        <Space align="start" size={12} style={{ width: '100%' }}>
          {icon ? (
            <span
              aria-hidden="true"
              style={{ color: saasVisualTokens.brand.primary, fontSize: 20, marginTop: 3, lineHeight: 1 }}
            >
              {icon}
            </span>
          ) : null}
          <div style={{ minWidth: 0 }}>
            <Typography.Title level={5} style={{ margin: 0 }}>
              {title}
            </Typography.Title>
            {description ? (
              <Typography.Text type="secondary" style={{ display: 'block', marginTop: 2 }}>
                {description}
              </Typography.Text>
            ) : null}
          </div>
          <div style={{ flex: 1 }} />
          {actions ? (
            <Space wrap size={8}>
              {actions}
            </Space>
          ) : null}
        </Space>
      </Card>
      {children}
    </Space>
  );
}
