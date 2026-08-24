/**
 * 筛选区：把「一张筛选卡 + Space wrap 的控件行」收敛成统一外观。
 *
 * 覆盖 FulfillmentTasks / Shipments / JdWarehouse 等页面的筛选卡样板
 * （size=small 的 Card + 圆角阴影 + wrap 控件行）。children 放筛选控件
 * （Select / Input / DatePicker…），actions 右对齐放查询/刷新等操作。
 *
 * 只负责容器，不规定筛选状态如何存放（useState / useSearchParams 由页面自定）。
 */

import type { ReactNode } from 'react';
import { Card, Flex, Space, theme } from 'antd';

export interface FilterBarProps {
  /** 筛选控件行（自动换行） */
  children: ReactNode;
  /** 右对齐操作（查询 / 刷新按钮等，可选） */
  actions?: ReactNode;
}

export default function FilterBar({ children, actions }: FilterBarProps) {
  const { token } = theme.useToken();

  return (
    <Card
      size="small"
      style={{ borderRadius: token.borderRadiusLG, boxShadow: token.boxShadow }}
    >
      <Flex wrap gap={12} align="center" justify={actions ? 'space-between' : 'flex-start'}>
        <Space wrap>
          {children}
        </Space>
        {actions ? <Space wrap>{actions}</Space> : null}
      </Flex>
    </Card>
  );
}
