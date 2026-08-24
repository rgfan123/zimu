/**
 * Issue #64：复核队列 / 运营提醒两个路由页共用的「筛选 + 列表」承载结构。
 * FilterBar（筛选控件行 + 右对齐刷新）+ DataTable（分页 / 空态 / 错误条）一次实现，
 * 两页不再各自手写同一套样板；筛选控件与列定义仍由页面提供。
 * 页码为 0 起内部约定（与既有队列分页一致）；分页状态归属页面（page/size 不进 URL）。
 */

import type { ReactNode } from 'react';
import { Button, Card, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import { ATTENTION_COLORS } from '@/pages/shared/semanticStatus';

export interface QueueTableProps<T> {
  rowKey: string;
  columns: ColumnsType<T>;
  items: T[];
  loading: boolean;
  error: Error | null;
  errorTitle: string;
  emptyText: ReactNode;
  total: number;
  /** 0 起页码（与列表 API 的 page 口径一致）。 */
  page: number;
  pageSize: number;
  onPageChange: (nextPage: number, nextSize: number) => void;
  /** 筛选控件行（状态 / 事项类型 / 责任团队等 Select）。 */
  filterControls: ReactNode;
  onReload: () => void;
}

export default function QueueTable<T extends object>({
  rowKey,
  columns,
  items,
  loading,
  error,
  errorTitle,
  emptyText,
  total,
  page,
  pageSize,
  onPageChange,
  filterControls,
  onReload,
}: QueueTableProps<T>) {
  return (
    <>
      <FilterBar actions={<Button icon={<ReloadOutlined />} onClick={onReload}>刷新</Button>}>
        {filterControls}
        <Typography.Text strong style={{ color: ATTENTION_COLORS.waiting }}>{total} 项</Typography.Text>
      </FilterBar>
      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <DataTable<T>
          rowKey={rowKey}
          loading={loading}
          columns={columns}
          dataSource={items}
          // 两页既有口径均为 x:900（区别于 DataTable 默认 x=960），保持拆分前的横向滚动行为。
          scroll={{ x: 900 }}
          error={error}
          errorTitle={errorTitle}
          emptyText={emptyText}
          pagination={{
            current: page + 1,
            pageSize,
            total,
            showSizeChanger: true,
            showTotal: (value) => `共 ${value} 项`,
            onChange: onPageChange,
          }}
        />
      </Card>
    </>
  );
}
