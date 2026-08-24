/**
 * 数据表格：antd Table 的默认行为收敛层。
 *
 * 收敛的默认行为（页面不写也对）：
 * - 空态：默认渲染「暂无数据」Empty；页面可用 emptyText 覆盖为业务文案。
 * - 错误态：error 非空时在表格上方渲染错误 Alert（附重试按钮，若传 onRetry）；
 *   表格本身照常渲染，由页面决定 dataSource（与既有「错误提示 + 空表」的页面一致）。
 * - 横向滚动：默认 scroll={{ x: 960 }}，窄屏不撑破容器（此前 5 个页面漏配 scroll）。
 * - loading：透传 antd Table 的 loading（全站多数页面的既有等待观感）。
 *
 * 刻意不做成万能表格：分页 / 行选择 / 列宽 / fixed 等差异由页面通过
 * TableProps 原样传入，组件只保证以上四类默认行为一致。
 */

import type { ReactNode } from 'react';
import { Alert, Button, Empty, Space, Table } from 'antd';
import type { TableProps } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';

/** 默认横向滚动宽度：约等于既有页面最常使用的 900–1100 区间下限。 */
export const DEFAULT_TABLE_SCROLL: NonNullable<TableProps<unknown>['scroll']> = { x: 960 };

/** 默认空态：与全站既有 `Empty.PRESENTED_IMAGE_SIMPLE + 自定义文案` 的空态观感一致。 */
export const DEFAULT_EMPTY_TEXT: ReactNode = (
  <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />
);

export interface DataTableProps<T extends object> extends Omit<TableProps<T>, 'locale' | 'scroll'> {
  /** 错误态：非空时在表格上方渲染错误提示 */
  error?: unknown;
  /** 错误提示里的重试回调；不传则不渲染重试按钮 */
  onRetry?: () => void;
  /** 错误提示标题，缺省「数据加载失败」 */
  errorTitle?: string;
  /** 空态文案/节点，缺省「暂无数据」 */
  emptyText?: ReactNode;
  /** 横向滚动配置，缺省 DEFAULT_TABLE_SCROLL */
  scroll?: TableProps<T>['scroll'];
}

export default function DataTable<T extends object>({
  error,
  onRetry,
  errorTitle = '数据加载失败',
  emptyText,
  scroll,
  loading = false,
  ...rest
}: DataTableProps<T>) {
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {error ? (
        <Alert
          type="error"
          showIcon
          message={errorTitle}
          description={errorMessage(error)}
          action={
            onRetry ? (
              <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>
                重试
              </Button>
            ) : undefined
          }
        />
      ) : null}
      <Table<T>
        {...rest}
        loading={loading}
        scroll={scroll ?? DEFAULT_TABLE_SCROLL}
        locale={{ emptyText: emptyText ?? DEFAULT_EMPTY_TEXT }}
      />
    </Space>
  );
}
