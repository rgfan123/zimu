/**
 * 履约中心 · Shipment（GET /api/v1/shipments + 详情）。
 * 一次出库/发货批次，可包含同一订单、同一履约方、同一收货地址下的多条订单行；
 * 缺货后的后续批次生成新 Shipment / 出库单号 / 运单（CONTEXT.md 发货）。
 */

import { useMemo, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Select, Space, Table, Tag, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { providersApi, shipmentsApi } from '@/api/endpoints';
import type { Shipment, ShipmentStatus } from '@/api/types';
import { SHIPMENT_STATUS_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import { shipmentTimeLabel } from '@/presentation/shipment';

function num(v: string | number | undefined | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

const STATUS_COLORS: Record<ShipmentStatus, string> = {
  CREATED: 'processing',
  SHIPPED: 'success',
  FAILED: 'error',
  DELIVERED: 'green',
};

export default function ShipmentsPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [providerId, setProviderId] = useState<string | undefined>();
  const [status, setStatus] = useState<ShipmentStatus | undefined>();
  const [selected, setSelected] = useState<Shipment | null>(null);

  const providers = useAsync(() => providersApi.list(), []);
  const providerName = useMemo(() => {
    const map = new Map((providers.data ?? []).map((p) => [p.id, p.provider_name]));
    return (id?: string) => (id ? map.get(id) ?? id : '—');
  }, [providers.data]);

  const list = useAsync(
    () => shipmentsApi.list({ page, size, provider_id: providerId, shipment_status: status }),
    [page, size, providerId, status],
  );

  const detail = useAsync<Shipment | null>(
    () => (selected ? shipmentsApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );

  const columns: ColumnsType<Shipment> = [
    { title: '发货单号', dataIndex: 'shipment_no', width: 160, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '订单号', dataIndex: 'order_id', width: 180, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '出库单号', dataIndex: 'outbound_order_no', width: 140, render: (v?: string) => v ?? '—' },
    { title: '批次', dataIndex: 'shipment_sequence', width: 70, align: 'right' },
    {
      title: '状态',
      dataIndex: 'shipment_status',
      width: 100,
      render: (v: ShipmentStatus) => <Tag color={STATUS_COLORS[v]}>{SHIPMENT_STATUS_LABELS[v] ?? v}</Tag>,
    },
    {
      title: '运单',
      key: 'tracking',
      width: 200,
      render: (_, r) =>
        r.tracking ? (
          <span style={{ fontSize: 12 }}>
            {r.tracking.logistics_company_name} ·{' '}
            <span style={{ fontVariantNumeric: 'tabular-nums' }}>{r.tracking.tracking_number}</span>
          </span>
        ) : (
          '—'
        ),
    },
    {
      title: '发货时间',
      dataIndex: 'shipped_at',
      width: 170,
      render: (v?: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{shipmentTimeLabel(v)}</span>,
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, r) => <Typography.Link onClick={() => setSelected(r)}>详情</Typography.Link>,
    },
  ];

  const err = list.error || providers.error;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {err ? (
        <Alert
          type="error"
          showIcon
          message="Shipment 加载失败"
          description={errorMessage(err)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={list.reload}>
              重试
            </Button>
          }
        />
      ) : null}

      <Card size="small" style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}>
        <Space wrap>
          <span style={{ color: '#7a8699', fontSize: 13 }}>履约方</span>
          <Select style={{ width: 200 }} placeholder="全部履约方" allowClear value={providerId} onChange={setProviderId}
            options={(providers.data ?? []).map((p) => ({ value: p.id, label: p.provider_name }))} />
          <span style={{ color: '#7a8699', fontSize: 13 }}>状态</span>
          <Select style={{ width: 140 }} placeholder="全部" allowClear value={status} onChange={setStatus}
            options={(Object.keys(SHIPMENT_STATUS_LABELS) as ShipmentStatus[]).map((k) => ({ value: k, label: SHIPMENT_STATUS_LABELS[k] }))} />
          <Button icon={<ReloadOutlined />} onClick={list.reload}>
            刷新
          </Button>
        </Space>
      </Card>

      <Card size="small" style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }} styles={{ body: { padding: '4px 8px' } }}>
        <Table<Shipment>
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          size="middle"
          scroll={{ x: 1100 }}
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
      </Card>

      <Drawer
        title={`发货单 ${selected?.shipment_no ?? ''}`}
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        width={620}
        styles={{ body: { padding: '16px 20px' } }}
      >
        {detail.data ? (
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Descriptions
              size="small"
              column={2}
              items={[
                { key: 'o', label: '订单号', children: detail.data.order_id },
                { key: 'p', label: '履约方', children: providerName(detail.data.provider_id) },
                { key: 'ob', label: '出库单号', children: detail.data.outbound_order_no ?? '—' },
                { key: 's', label: '状态', children: <Tag color={STATUS_COLORS[detail.data.shipment_status]}>{SHIPMENT_STATUS_LABELS[detail.data.shipment_status]}</Tag> },
                {
                  key: 't',
                  label: '运单',
                  children: detail.data.tracking
                    ? `${detail.data.tracking.logistics_company_name} · ${detail.data.tracking.tracking_number}`
                    : '—',
                },
                { key: 'sa', label: '发货时间', children: shipmentTimeLabel(detail.data.shipped_at) },
              ]}
            />
            {detail.data.receiver ? (
              <Descriptions
                size="small"
                column={1}
                title="收货人"
                items={[
                  { key: 'n', label: '姓名', children: detail.data.receiver.name },
                  { key: 'a', label: '地址', children: `${detail.data.receiver.province}${detail.data.receiver.city}${detail.data.receiver.district}${detail.data.receiver.town}${detail.data.receiver.address}` },
                  { key: 'p', label: '电话', children: detail.data.receiver.phone },
                ]}
              />
            ) : null}
            <div>
              <Typography.Text strong>明细行（{detail.data.items?.length ?? 0}）</Typography.Text>
              <Table
                rowKey="fulfillment_id"
                size="small"
                style={{ marginTop: 8 }}
                pagination={false}
                columns={[
                  { title: '履约单', dataIndex: 'fulfillment_id', render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
                  { title: '商品', dataIndex: 'product_name', ellipsis: true },
                  { title: '指令数量', dataIndex: 'instructed_quantity', align: 'right', render: num },
                  { title: '实发数量', dataIndex: 'shipped_quantity', align: 'right', render: num },
                  { title: '单位', dataIndex: 'unit', width: 70 },
                ]}
                dataSource={detail.data.items ?? []}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无明细行" /> }}
              />
            </div>
          </Space>
        ) : detail.loading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="加载中…" />
        ) : detail.error ? (
          <Alert type="error" showIcon message={errorMessage(detail.error)} />
        ) : null}
      </Drawer>
    </Space>
  );
}
