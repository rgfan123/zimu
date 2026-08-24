/**
 * 系统 · 审计记录（GET /api/v1/audit-logs + 详情）。
 * 列表展示业务概览，详情仅展示经服务端脱敏且在前端白名单内的业务字段。
 */

import { useState } from 'react';
import { Button, DatePicker, Descriptions, Drawer, Input, Space, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { auditLogsApi } from '@/api/endpoints';
import type { AuditLog } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import {
  AdminCategoryTag,
  AdminEmpty,
  AdminFailureAlert,
  AdminStatusTag,
} from '@/pages/shared/AdminVisualComponents';
import {
  auditOperationLabel,
  auditServiceLabel,
  displayOperator,
  safeAuditPayloadRows,
} from '@/presentation/publicReady';
import '@/pages/shared/adminSurface.css';

function dataScopeTag(scope: AuditLog['data_scope']) {
  return <AdminCategoryTag category={scope === 'DEMO' ? 'MOCK' : 'REAL'}>{scope === 'DEMO' ? '演示' : '业务'}</AdminCategoryTag>;
}

export default function AuditLogsPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [operator, setOperator] = useState<string | undefined>();
  const [service, setService] = useState<string | undefined>();
  const [operation, setOperation] = useState<string | undefined>();
  const [businessCode, setBusinessCode] = useState<string | undefined>();
  const [dates, setDates] = useState<[dayjs.Dayjs, dayjs.Dayjs] | null>(null);
  const [selected, setSelected] = useState<AuditLog | null>(null);

  const list = useAsync(
    () =>
      auditLogsApi.list({
        page,
        size,
        operator,
        service,
        operation,
        business_code: businessCode,
        date_from: dates?.[0].format('YYYY-MM-DD'),
        date_to: dates?.[1].format('YYYY-MM-DD'),
      }),
    [page, size, operator, service, operation, businessCode, dates?.[0]?.format('YYYY-MM-DD'), dates?.[1]?.format('YYYY-MM-DD')],
  );

  const detail = useAsync<AuditLog | null>(
    () => (selected ? auditLogsApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );

  const columns: ColumnsType<AuditLog> = [
    { title: '时间', dataIndex: 'created_at', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '操作人', dataIndex: 'operator', width: 120, render: (v: string) => displayOperator(v) },
    {
      title: '数据域',
      dataIndex: 'data_scope',
      width: 90,
      render: (v: AuditLog['data_scope']) => dataScopeTag(v),
    },
    { title: '业务模块', dataIndex: 'service', width: 150, render: (v: string) => auditServiceLabel(v) },
    { title: '业务操作', dataIndex: 'operation', width: 180, ellipsis: true, render: (v: string) => auditOperationLabel(v) },
    {
      title: '订单号',
      dataIndex: 'order_id',
      width: 160,
      render: (v?: string) => (v ? <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> : '—'),
    },
    {
      title: '结果',
      dataIndex: 'http_status',
      width: 100,
      render: (v?: number | null) =>
        v ? <AdminStatusTag status={v < 400 ? 'AUDIT_SUCCESS' : 'AUDIT_INCOMPLETE'} /> : '—',
    },
    {
      title: '耗时',
      dataIndex: 'latency_ms',
      width: 90,
      align: 'right',
      render: (v?: number | null) => (v != null ? `${v} ms` : '—'),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, r) => <Typography.Link onClick={() => setSelected(r)}>详情</Typography.Link>,
    },
  ];

  return (
    <div className="admin-page">
      <PageShell title="操作审计">
        {list.error ? (
          <AdminFailureAlert error={list.error} title="审计记录加载失败" onRetry={list.reload} />
        ) : null}

        <FilterBar
          actions={<Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>}
        >
          <Input style={{ width: 150 }} placeholder="操作员" allowClear value={operator} onChange={(e) => setOperator(e.target.value || undefined)} />
          <Input style={{ width: 150 }} placeholder="业务模块" allowClear value={service} onChange={(e) => setService(e.target.value || undefined)} />
          <Input style={{ width: 190 }} placeholder="业务操作" allowClear value={operation} onChange={(e) => setOperation(e.target.value || undefined)} />
          <Input style={{ width: 150 }} placeholder="处理结果编码" allowClear value={businessCode} onChange={(e) => setBusinessCode(e.target.value || undefined)} />
          <DatePicker.RangePicker size="middle" value={dates} onChange={(d) => setDates(d as [dayjs.Dayjs, dayjs.Dayjs] | null)} />
        </FilterBar>

        <div className="admin-surface" style={{ padding: '4px 8px' }}>
          <DataTable<AuditLog>
            rowKey="id"
            columns={columns}
            dataSource={list.data?.items ?? []}
            loading={list.loading}
            size="middle"
            scroll={{ x: 1280 }}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: list.data?.total_elements ?? 0,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 条`,
              onChange: (p, s) => {
                setPage(p - 1);
                setSize(s);
              },
            }}
          />
        </div>
      </PageShell>

      <Drawer
        title="审计详情"
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        width={640}
        styles={{ body: { padding: '16px 20px' } }}
      >
        {detail.data ? (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Space wrap>
              {dataScopeTag(detail.data.data_scope)}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {displayOperator(detail.data.operator)} · {auditServiceLabel(detail.data.service)} · {auditOperationLabel(detail.data.operation)} · {detail.data.created_at}
              </Typography.Text>
            </Space>
            <div>
              <Typography.Text strong style={{ fontSize: 13 }}>
                请求业务摘要
              </Typography.Text>
              {safeAuditPayloadRows(detail.data.request_payload).length ? (
                <Descriptions
                  size="small"
                  column={1}
                  items={safeAuditPayloadRows(detail.data.request_payload).map((row, index) => ({
                    key: `${row.label}-${index}`,
                    label: row.label,
                    children: row.value,
                  }))}
                />
              ) : (
                <AdminEmpty description="没有可展示的业务字段" />
              )}
            </div>
            <div>
              <Typography.Text strong style={{ fontSize: 13 }}>
                处理结果摘要
              </Typography.Text>
              {safeAuditPayloadRows(detail.data.response_payload).length ? (
                <Descriptions
                  size="small"
                  column={1}
                  items={safeAuditPayloadRows(detail.data.response_payload).map((row, index) => ({
                    key: `${row.label}-${index}`,
                    label: row.label,
                    children: row.value,
                  }))}
                />
              ) : (
                <AdminEmpty description="没有可展示的业务字段" />
              )}
            </div>
          </Space>
        ) : detail.loading ? (
          <AdminEmpty description="加载中…" />
        ) : detail.error ? (
          <AdminFailureAlert error={detail.error} title="审计详情加载失败" onRetry={detail.reload} />
        ) : null}
      </Drawer>
    </div>
  );
}
