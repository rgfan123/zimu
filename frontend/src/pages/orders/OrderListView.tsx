/**
 * 订单列表页（共用组件）：页内预设视图切换（Segmented）+ 筛选栏 + 服务端分页表格。
 * 全部订单 / 待处理 / 异常订单 / 订单追踪 四个视图共用本组件，仅预设筛选不同；
 * 旧菜单直达路径仍保留，由 orderPresetFromPathname 映射到对应预设（书签不失效）。
 */

import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Alert, Button, Card, DatePicker, Input, Progress, Segmented, Select, Space, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { providersApi, type OrderListQuery } from '@/api/endpoints';
import type { OrderSummary } from '@/api/types';
import { CHANNEL_LABELS, ORDER_STATUS_LABELS, PROCESSING_HEALTH_LABELS, PROCESSING_STAGE_LABELS } from '@/constants/labels';
import { usePagedOrders } from '@/hooks/usePagedOrders';
import { useAsync } from '@/hooks/useAsync';
import { PageState } from '@/pages/shared/PageState';
import { formatDateTime, formatMonthDayTime } from '@/format/dateTime';
import StatusTag from '@/components/StatusTag';
import LongCode from '@/components/LongCode';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';

const { RangePicker } = DatePicker;

/** 订单中心页内预设视图；与旧菜单路径一一对应，供 URL 直达兼容。 */
export type OrderPreset = 'all' | 'pending' | 'exceptions' | 'tracking';

const ORDER_PRESETS: readonly OrderPreset[] = ['all', 'pending', 'exceptions', 'tracking'];

interface OrderPresetDef {
  label: string;
  filters: Partial<OrderListQuery>;
  tip?: string;
  path: string;
}

export const ORDER_PRESET_DEFS: Record<OrderPreset, OrderPresetDef> = {
  all: { label: '全部订单', filters: {}, path: '/orders' },
  pending: {
    label: '待处理',
    filters: { processing_stage: 'NEED_REVIEW' },
    path: '/orders/pending',
    tip: '待处理订单 = 需要人工介入的订单行（默认按处理阶段「待复核」筛选，可切换为其他阶段/健康度）。',
  },
  exceptions: {
    label: '异常订单',
    filters: { order_status: 'FULFILLMENT_EXCEPTION' },
    path: '/orders/exceptions',
    tip: '异常订单包含履约异常 / 缺货 / 采购待处理 / 回传失败 / 待复核等分支，可在筛选栏切换订单状态查看。',
  },
  tracking: {
    label: '订单追踪',
    filters: { order_status: 'SHIPPED' },
    path: '/orders/tracking',
    tip: '订单追踪默认展示「已发货」订单；点击订单号进入详情页查看分批发货明细与运单信息。',
  },
};

export const ORDER_PRESET_OPTIONS: { label: string; value: OrderPreset }[] = ORDER_PRESETS.map((preset) => ({
  label: ORDER_PRESET_DEFS[preset].label,
  value: preset,
}));

/** 旧直达路径 → 预设；未知路径一律回落「全部订单」。 */
export function orderPresetFromPathname(pathname: string): OrderPreset {
  const matched = ORDER_PRESETS.find((preset) => ORDER_PRESET_DEFS[preset].path === pathname);
  return matched ?? 'all';
}

export interface OrderListViewProps {
  /** 当前预设视图（全部 / 待处理 / 异常 / 追踪） */
  preset: OrderPreset;
  /** 用户切换预设时回调（父级负责导航到对应 URL） */
  onPresetChange?: (preset: OrderPreset) => void;
}

export default function OrderListView({ preset, onPresetChange }: OrderListViewProps) {
  const navigate = useNavigate();
  const presetDef = ORDER_PRESET_DEFS[preset];
  const { data, loading, error, page, size, setPage, setSize, applyFilters, reload } = usePagedOrders(presetDef.filters);

  const [channel, setChannel] = useState<string | undefined>(presetDef.filters.source_channel);
  const [status, setStatus] = useState<string | undefined>(presetDef.filters.order_status);
  const [stage, setStage] = useState<string | undefined>(presetDef.filters.processing_stage);
  const [health, setHealth] = useState<string | undefined>(presetDef.filters.processing_health);
  const [providerId, setProviderId] = useState<string | undefined>(presetDef.filters.provider_id);
  const [dateRange, setDateRange] = useState<[Dayjs | null, Dayjs | null] | null>(null);
  const [keyword, setKeyword] = useState<string>('');
  const providers = useAsync(() => providersApi.list(), []);

  const columns = useMemo<ColumnsType<OrderSummary>>(
    () => [
      {
        title: '订单号',
        dataIndex: 'order_no',
        width: 200,
        render: (value: string, record) => <LongCode value={value} to={`/orders/${record.id}`} width={170} />,
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
        title: '来源下单时间',
        dataIndex: 'source_ordered_at',
        width: 110,
        render: (v?: string | null) => formatMonthDayTime(v),
      },
      {
        title: '创建时间',
        dataIndex: 'created_at',
        width: 170,
        render: (v: string) => formatDateTime(v),
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

  /** 快捷区间：均以「创建日」为口径，闭区间且含今日。 */
  const datePresets = useMemo(() => {
    const today = dayjs();
    return [
      { label: '今日', value: [today.startOf('day'), today.endOf('day')] as [Dayjs, Dayjs] },
      {
        label: '昨日',
        value: [today.subtract(1, 'day').startOf('day'), today.subtract(1, 'day').endOf('day')] as [Dayjs, Dayjs],
      },
      { label: '近一周', value: [today.subtract(6, 'day').startOf('day'), today.endOf('day')] as [Dayjs, Dayjs] },
      { label: '近一月', value: [today.subtract(29, 'day').startOf('day'), today.endOf('day')] as [Dayjs, Dayjs] },
      {
        label: '近半年',
        value: [today.subtract(6, 'month').add(1, 'day').startOf('day'), today.endOf('day')] as [Dayjs, Dayjs],
      },
      {
        label: '近一年',
        value: [today.subtract(1, 'year').add(1, 'day').startOf('day'), today.endOf('day')] as [Dayjs, Dayjs],
      },
    ];
  }, []);

  const searchWithRange = (range: [Dayjs | null, Dayjs | null] | null, overrides: Partial<OrderListQuery> = {}) => {
    const patch: Partial<OrderListQuery> = {
      source_channel: channel,
      order_status: status,
      processing_stage: stage,
      processing_health: health,
      provider_id: providerId,
      query: keyword.trim() || undefined,
      ...overrides,
    };
    if (range?.[0] && range[1]) {
      patch.date_from = range[0].format('YYYY-MM-DD');
      patch.date_to = range[1].format('YYYY-MM-DD');
    } else {
      patch.date_from = undefined;
      patch.date_to = undefined;
    }
    applyFilters(patch);
  };

  const handleSearch = () => searchWithRange(dateRange);

  /** 选定区间即查询：快捷项与手选都不必再点一次查询。传入新值而非读 state，避开这一轮的过期闭包。 */
  const handleDateChange = (range: [Dayjs | null, Dayjs | null] | null) => {
    setDateRange(range);
    searchWithRange(range);
  };

  /** 下拉改动即生效（UIUX-07 #141）：与日期区间同口径，传入新值避开过期闭包。 */
  const handleFilterChange = (patch: Partial<OrderListQuery>) => {
    if (patch.source_channel !== undefined) setChannel(patch.source_channel);
    if (patch.order_status !== undefined) setStatus(patch.order_status);
    if (patch.processing_stage !== undefined) setStage(patch.processing_stage);
    if (patch.processing_health !== undefined) setHealth(patch.processing_health);
    if (patch.provider_id !== undefined) setProviderId(patch.provider_id);
    searchWithRange(dateRange, patch);
  };

  const handleReset = () => {
    // 重置回当前预设的默认筛选（并清空查询词与日期区间）
    setChannel(presetDef.filters.source_channel);
    setStatus(presetDef.filters.order_status);
    setStage(presetDef.filters.processing_stage);
    setHealth(presetDef.filters.processing_health);
    setProviderId(presetDef.filters.provider_id);
    setDateRange(null);
    setKeyword('');
    applyFilters({
      source_channel: presetDef.filters.source_channel,
      order_status: presetDef.filters.order_status,
      processing_stage: presetDef.filters.processing_stage,
      processing_health: presetDef.filters.processing_health,
      provider_id: presetDef.filters.provider_id,
      query: undefined,
      date_from: undefined,
      date_to: undefined,
    });
  };

  /** 页面级错误态：订单列表加载失败时内容区整块切换为 PageState（保留页头与预设切换器），重试语义与替换前一致（reload）。 */
  if (error) {
    return (
      <PageShell title={presetDef.label}>
        {onPresetChange ? (
          <Segmented
            options={ORDER_PRESET_OPTIONS}
            value={preset}
            onChange={(value) => onPresetChange(value as OrderPreset)}
            aria-label="订单视图预设"
          />
        ) : null}
        {presetDef.tip ? <Alert type="info" showIcon message={presetDef.tip} /> : null}
        <PageState
          state="error"
          message="订单列表加载失败"
          description={errorMessage(error)}
          onRetry={reload}
        />
      </PageShell>
    );
  }

  return (
    <PageShell title={presetDef.label}>
      {onPresetChange ? (
        <Segmented
          options={ORDER_PRESET_OPTIONS}
          value={preset}
          onChange={(value) => onPresetChange(value as OrderPreset)}
          aria-label="订单视图预设"
        />
      ) : null}
      {presetDef.tip ? <Alert type="info" showIcon message={presetDef.tip} /> : null}
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

      <FilterBar>
        <Select
          allowClear
          aria-label="履约方"
          placeholder="履约方"
          style={{ width: 160 }}
          value={providerId}
          onChange={(value) => handleFilterChange({ provider_id: value })}
          loading={providers.loading}
          disabled={providers.error !== null}
          options={(providers.data ?? []).map((provider) => ({
            value: provider.id,
            label: provider.provider_name,
          }))}
        />
        <Select
          allowClear
          placeholder="来源渠道"
          style={{ width: 130 }}
          value={channel}
          onChange={(value) => handleFilterChange({ source_channel: value })}
          options={Object.entries(CHANNEL_LABELS).map(([value, label]) => ({ value, label }))}
        />
        <Select
          allowClear
          placeholder="订单状态"
          style={{ width: 140 }}
          value={status}
          onChange={(value) => handleFilterChange({ order_status: value })}
          options={Object.entries(ORDER_STATUS_LABELS).map(([value, label]) => ({ value, label }))}
        />
        <Select
          allowClear
          placeholder="处理阶段"
          style={{ width: 150 }}
          value={stage}
          onChange={(value) => handleFilterChange({ processing_stage: value })}
          options={Object.entries(PROCESSING_STAGE_LABELS).map(([value, label]) => ({ value, label }))}
        />
        <Select
          allowClear
          placeholder="健康度"
          style={{ width: 120 }}
          value={health}
          onChange={(value) => handleFilterChange({ processing_health: value })}
          options={Object.entries(PROCESSING_HEALTH_LABELS).map(([value, label]) => ({ value, label }))}
        />
        <RangePicker
          value={dateRange}
          presets={datePresets}
          onChange={(range) => handleDateChange(range as [Dayjs | null, Dayjs | null] | null)}
          placeholder={['创建起始日', '创建结束日']}
        />
        <div style={{ flex: 1, minWidth: 240 }}>
          <Input.Search
            allowClear
            placeholder="订单号 / 来源单号 / 客户名"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onSearch={handleSearch}
            enterButton={<SearchOutlined />}
            style={{ width: '100%' }}
          />
        </div>
        <Button onClick={handleReset}>重置</Button>
      </FilterBar>

      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <DataTable<OrderSummary>
          rowKey="id"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={loading}
          size="middle"
          scroll={{ x: 1350 }}
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
    </PageShell>
  );
}
