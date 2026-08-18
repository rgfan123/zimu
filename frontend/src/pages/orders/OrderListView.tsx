/**
 * 订单列表页（共用组件）：筛选栏 + 服务端分页表格。
 * 四个订单页面（全部订单 / 待处理 / 异常订单 / 订单追踪）通过 defaultFilters 区分。
 */

import { useMemo, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, Col, DatePicker, Input, Progress, Row, Select, Space, Table, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { providersApi, type OrderListQuery } from '@/api/endpoints';
import type { OrderSummary } from '@/api/types';
import { CHANNEL_LABELS, ORDER_STATUS_LABELS, PROCESSING_HEALTH_LABELS, PROCESSING_STAGE_LABELS } from '@/constants/labels';
import { usePagedOrders } from '@/hooks/usePagedOrders';
import { useAsync } from '@/hooks/useAsync';
import StatusTag from '@/components/StatusTag';

const { RangePicker } = DatePicker;

export interface OrderListViewProps {
  /** 页面级默认筛选（进入页面时生效，可被用户修改） */
  defaultFilters?: Partial<OrderListQuery>;
  /** 页面说明提示 */
  tip?: ReactNode;
}

export default function OrderListView({ defaultFilters = {}, tip }: OrderListViewProps) {
  const navigate = useNavigate();
  const { data, loading, error, page, size, setPage, setSize, applyFilters, reload } = usePagedOrders(defaultFilters);

  const [channel, setChannel] = useState<string | undefined>(defaultFilters.source_channel);
  const [status, setStatus] = useState<string | undefined>(defaultFilters.order_status);
  const [stage, setStage] = useState<string | undefined>(defaultFilters.processing_stage);
  const [health, setHealth] = useState<string | undefined>(defaultFilters.processing_health);
  const [providerId, setProviderId] = useState<string | undefined>(defaultFilters.provider_id);
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [keyword, setKeyword] = useState<string>('');
  const providers = useAsync(() => providersApi.list(), []);

  const columns = useMemo<ColumnsType<OrderSummary>>(
    () => [
      {
        title: '订单号',
        dataIndex: 'order_no',
        width: 200,
        render: (value: string, record) => (
          <Typography.Link strong onClick={() => navigate(`/orders/${record.id}`)}>
            {value}
          </Typography.Link>
        ),
      },
      {
        title: '来源渠道',
        dataIndex: 'source_channel',
        width: 110,
        render: (v: OrderSummary['source_channel']) => <StatusTag kind="channel" value={v} />,
      },
      { title: '客户', dataIndex: 'customer_name', width: 140, ellipsis: true, render: (v?: string) => v ?? '—' },
      { title: '收货人', dataIndex: 'receiver_name', width: 100, ellipsis: true, render: (v?: string) => v ?? '—' },
      {
        title: '订单状态',
        dataIndex: 'order_status',
        width: 110,
        render: (v: OrderSummary['order_status']) => <StatusTag kind="orderStatus" value={v} />,
      },
      {
        title: '处理阶段',
        dataIndex: 'processing_stage',
        width: 130,
        render: (v: OrderSummary['processing_stage']) => <StatusTag kind="stage" value={v} />,
      },
      {
        title: '健康度',
        dataIndex: 'processing_health',
        width: 90,
        render: (v: OrderSummary['processing_health']) => <StatusTag kind="health" value={v} />,
      },
      {
        title: '进度',
        key: 'progress',
        width: 130,
        render: (_, record) => (
          <Space size={6}>
            <Progress
              percent={record.total_count ? Math.round((record.completed_count / record.total_count) * 100) : 0}
              size="small"
              showInfo={false}
              style={{ width: 64 }}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {record.completed_count}/{record.total_count}
            </Typography.Text>
          </Space>
        ),
      },
      {
        title: '创建时间',
        dataIndex: 'created_at',
        width: 170,
        render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm'),
      },
      {
        title: '操作',
        key: 'action',
        width: 80,
        fixed: 'right',
        render: (_, record) => (
          <Typography.Link onClick={() => navigate(`/orders/${record.id}`)}>详情</Typography.Link>
        ),
      },
    ],
    [navigate],
  );

  const handleSearch = () => {
    const patch: Partial<OrderListQuery> = {
      source_channel: channel,
      order_status: status,
      processing_stage: stage,
      processing_health: health,
      provider_id: providerId,
      query: keyword.trim() || undefined,
    };
    if (dateRange?.[0] && dateRange[1]) {
      patch.date_from = dateRange[0].format('YYYY-MM-DD');
      patch.date_to = dateRange[1].format('YYYY-MM-DD');
    } else {
      patch.date_from = undefined;
      patch.date_to = undefined;
    }
    applyFilters(patch);
  };

  const handleReset = () => {
    setChannel(defaultFilters.source_channel);
    setStatus(defaultFilters.order_status);
    setStage(defaultFilters.processing_stage);
    setHealth(defaultFilters.processing_health);
    setProviderId(defaultFilters.provider_id);
    setDateRange(null);
    setKeyword('');
    applyFilters({ source_channel: undefined, order_status: undefined, processing_stage: undefined, processing_health: undefined, provider_id: undefined, query: undefined, date_from: undefined, date_to: undefined });
  };

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {tip ? <Alert type="info" showIcon message={tip} /> : null}
      {error ? (
        <Alert
          type="error"
          showIcon
          message="订单列表加载失败"
          description={errorMessage(error)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={reload}>
              重试
            </Button>
          }
        />
      ) : null}
      {providers.error ? (
        <Alert
          type="warning"
          showIcon
          message="履约方目录加载失败"
          description={errorMessage(providers.error)}
          action={(
            <Button size="small" icon={<ReloadOutlined />} onClick={providers.reload}>
              重试
            </Button>
          )}
        />
      ) : null}

      <Card size="small">
        <Row gutter={[12, 12]} align="middle">
          <Col>
            <Select
              allowClear
              aria-label="履约方"
              placeholder="履约方"
              style={{ width: 160 }}
              value={providerId}
              onChange={setProviderId}
              loading={providers.loading}
              disabled={providers.error !== null}
              options={(providers.data ?? []).map((provider) => ({
                value: provider.id,
                label: provider.provider_name,
              }))}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="来源渠道"
              style={{ width: 130 }}
              value={channel}
              onChange={setChannel}
              options={Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value, label }))}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="订单状态"
              style={{ width: 140 }}
              value={status}
              onChange={setStatus}
              options={Object.entries(ORDER_STATUS_LABELS).map(([value, label]) => ({ value, label }))}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="处理阶段"
              style={{ width: 150 }}
              value={stage}
              onChange={setStage}
              options={Object.entries(PROCESSING_STAGE_LABELS).map(([value, label]) => ({ value, label }))}
            />
          </Col>
          <Col>
            <Select
              allowClear
              placeholder="健康度"
              style={{ width: 120 }}
              value={health}
              onChange={setHealth}
              options={Object.entries(PROCESSING_HEALTH_LABELS).map(([value, label]) => ({ value, label }))}
            />
          </Col>
          <Col>
            <RangePicker
              value={dateRange}
              onChange={(range) => setDateRange(range as [Dayjs | null, Dayjs | null] | null)}
              placeholder={['创建起始日', '创建结束日']}
            />
          </Col>
          <Col flex="auto">
            <Input.Search
              allowClear
              placeholder="订单号 / 来源单号 / 客户名"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              onSearch={handleSearch}
              enterButton={<SearchOutlined />}
            />
          </Col>
          <Col>
            <Button onClick={handleReset}>重置</Button>
          </Col>
        </Row>
      </Card>

      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <Table<OrderSummary>
          rowKey="id"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={loading}
          size="middle"
          scroll={{ x: 1240 }}
          onRow={(record) => ({
            onClick: () => navigate(`/orders/${record.id}`),
            style: { cursor: 'pointer' },
          })}
          pagination={{
            current: page + 1,
            pageSize: size,
            total: data?.total_elements ?? 0,
            showSizeChanger: true,
            pageSizeOptions: [20, 50, 100],
            showTotal: (total) => `共 ${total} 条`,
            onChange: (p, s) => {
              setPage(p - 1);
              setSize(s);
            },
          }}
        />
      </Card>
    </Space>
  );
}
