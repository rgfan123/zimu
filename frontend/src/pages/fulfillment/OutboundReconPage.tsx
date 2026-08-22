/**
 * 作业中心 · 出库信息对账（Ticket 01）：输入系统出库单号 / 京东单号 / 订单号，
 * 同一笔出库的系统内部事实与京东侧事实并排对照，差异行高亮并说明。
 *
 * 详情走真实路由：查询条件进 query string（query_type / query_value），刷新与分享链接
 * 可复现同一视图；加载态 / 空态 / 错误态分开呈现；表格配 scroll 防止窄屏撑破容器。
 */

import { useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  Result,
  Row,
  Select,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { ApiError, errorMessage } from '@/api/client';
import { outboundReconApi } from '@/api/endpoints';
import type {
  OutboundReconComparisonRow,
  OutboundReconJdSide,
  OutboundReconQueryType,
  OutboundReconView,
} from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { shipmentTimeLabel } from '@/presentation/shipment';
import { CHANNEL_LABELS } from '@/constants/labels';
import {
  cellText,
  jdStatusPresentation,
  queryTypeLabel,
  reconSummary,
  rowStatePresentation,
} from './outboundRecon';

const QUERY_TYPES: Array<{ value: OutboundReconQueryType; label: string; placeholder: string }> = [
  { value: 'OUTBOUND_ORDER_NO', label: '系统出库单号', placeholder: '例如 202608130001' },
  { value: 'JD_DELIVERY_NO', label: '京东单号', placeholder: '输入京东出库单号（deliveryNo）' },
  { value: 'ORDER_NO', label: '订单号', placeholder: '输入系统订单号' },
];

function isQueryType(value: string | null): value is OutboundReconQueryType {
  return value === 'OUTBOUND_ORDER_NO' || value === 'JD_DELIVERY_NO' || value === 'ORDER_NO';
}

function internalDescriptions(view: OutboundReconView) {
  const s = view.internal.summary as Record<string, unknown>;
  const receiver = (s.receiver ?? {}) as Record<string, unknown>;
  const provider = (s.provider ?? {}) as Record<string, unknown>;
  const jd = (s.jd_outbound ?? null) as Record<string, unknown> | null;
  return {
    items: [
      { key: 'shipment_no', label: '发货批次', children: cellText(s.shipment_no) },
      { key: 'outbound_order_no', label: '系统出库单号', children: cellText(s.outbound_order_no) },
      { key: 'order_no', label: '订单号', children: cellText(s.order_no) },
      { key: 'source', label: '来源', children: `${cellText(s.source_ref)}（${CHANNEL_LABELS[s.source_channel as keyof typeof CHANNEL_LABELS] ?? cellText(s.source_channel)}）` },
      { key: 'shipment_status', label: '发货状态', children: cellText(s.shipment_status) },
      { key: 'provider', label: '履约方', children: cellText(provider.name) },
      { key: 'receiver', label: '收件人', children: `${cellText(receiver.name)} ${cellText(receiver.phone)} ${cellText(receiver.address)}` },
      { key: 'created_at', label: '创建时间', children: shipmentTimeLabel(cellText(s.created_at) === '—' ? null : (s.created_at as string)) },
      { key: 'shipped_at', label: '发货时间', children: shipmentTimeLabel(cellText(s.shipped_at) === '—' ? null : (s.shipped_at as string)) },
      ...(jd
        ? [
            { key: 'sync_status', label: '京东集成状态', children: cellText(jd.sync_status) },
            { key: 'jd_delivery_no', label: '京东出库单号', children: cellText(jd.jd_delivery_no) },
            { key: 'submitted_at', label: '建单时间', children: shipmentTimeLabel(cellText(jd.submitted_at) === '—' ? null : (jd.submitted_at as string)) },
            ...(jd.last_error_code
              ? [{ key: 'last_error', label: '上次错误', children: `${cellText(jd.last_error_code)} ${cellText(jd.last_error_message)}` }]
              : []),
          ]
        : [{ key: 'no_jd', label: '京东集成', children: '未提交过京东出库单' }]),
    ],
  };
}

function jdDescriptions(jd: OutboundReconJdSide) {
  const s = (jd.summary ?? {}) as Record<string, unknown>;
  const carrier = (s.carrier ?? null) as Record<string, unknown> | null;
  return {
    items: [
      { key: 'erp_delivery_no', label: '商户出库引用', children: cellText(s.erp_delivery_no) },
      { key: 'delivery_no', label: '京东出库单号', children: cellText(s.delivery_no) },
      { key: 'warehouse_no', label: '京东仓库', children: cellText(s.warehouse_no) },
      { key: 'status', label: '京东状态码', children: `${cellText(s.status)} ${cellText(s.status_semantic) !== '—' ? `（${cellText(s.status_semantic)}）` : ''}` },
      { key: 'item_count', label: '明细行数', children: cellText(s.item_count) },
      { key: 'queried_at', label: '查询时间', children: shipmentTimeLabel(cellText(s.queried_at) === '—' ? null : (s.queried_at as string)) },
      ...(carrier
        ? [{ key: 'carrier', label: '承运/运单', children: `${cellText(carrier.carrier_name)} ${cellText(carrier.waybill_no)}` }]
        : []),
    ],
  };
}

const internalItemColumns: ColumnsType<Record<string, unknown>> = [
  { title: '行号', dataIndex: 'order_line', width: 70 },
  { title: '商品', dataIndex: 'goods_name', ellipsis: true, render: (v: unknown) => cellText(v) },
  { title: '京东商品编码', dataIndex: 'goods_no', width: 160, render: (v: unknown) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{cellText(v)}</span> },
  { title: '指令数量', dataIndex: 'plan_quantity', width: 100, align: 'right' },
  { title: '实发数量', dataIndex: 'shipped_quantity', width: 100, align: 'right' },
  { title: '单位', dataIndex: 'unit', width: 80 },
];

const jdItemColumns: ColumnsType<Record<string, unknown>> = [
  { title: '行号', dataIndex: 'order_line', width: 70 },
  { title: '京东商品编码', dataIndex: 'goods_no', width: 160, render: (v: unknown) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{cellText(v)}</span> },
  { title: '计划数量', dataIndex: 'plan_quantity', width: 100, align: 'right' },
  { title: '实发数量', dataIndex: 'real_quantity', width: 100, align: 'right', render: (v: unknown) => cellText(v) },
];

const comparisonColumns: ColumnsType<OutboundReconComparisonRow> = [
  { title: '字段', dataIndex: 'label', width: 200, fixed: 'left' },
  { title: '系统内部事实', dataIndex: 'internal_value', render: (v: unknown) => cellText(v) },
  { title: '京东侧事实', dataIndex: 'jd_value', render: (v: unknown, row) => (row.state === 'JD_UNAVAILABLE' ? '京东侧未取到' : row.state === 'JD_NOT_FOUND' ? '京东无记录' : cellText(v)) },
  {
    title: '差异',
    dataIndex: 'state',
    width: 240,
    render: (v: OutboundReconComparisonRow['state'], row) => {
      const presentation = rowStatePresentation(v);
      return (
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          <Tag color={presentation.tone} style={{ borderRadius: 6 }}>{presentation.label}</Tag>
          {row.note ? <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.note}</Typography.Text> : null}
        </Space>
      );
    },
  },
];

/** 差异行整行浅色高亮（MISMATCH 红色调，INTERNAL_ONLY/JD_ONLY 琥珀色调，未取到灰）。 */
function comparisonRowClassName(row: OutboundReconComparisonRow): string {
  if (row.state === 'MISMATCH' || row.state === 'JD_UNAVAILABLE') return 'recon-row-mismatch';
  if (row.state === 'INTERNAL_ONLY' || row.state === 'JD_ONLY' || row.state === 'JD_NOT_FOUND') return 'recon-row-only';
  return '';
}

function ReconResult({ queryType, queryValue }: { queryType: OutboundReconQueryType; queryValue: string }) {
  const result = useAsync<OutboundReconView>(
    () => outboundReconApi.query({ query_type: queryType, query_value: queryValue }),
    [queryType, queryValue],
  );

  if (result.loading) {
    return (
      <Card size="small">
        <Skeleton active paragraph={{ rows: 8 }} />
      </Card>
    );
  }
  if (result.error) {
    const ambiguousNos =
      result.error instanceof ApiError && result.error.body.business_code === 'OUTBOUND_RECON_AMBIGUOUS'
        ? ((result.error.body.details?.outbound_order_nos ?? []) as unknown[])
        : [];
    return (
      <Card size="small">
        <Result
          status={result.error instanceof ApiError && result.error.status === 404 ? 'warning' : 'error'}
          title={result.error instanceof ApiError && result.error.status === 404 ? '系统内部没有这笔出库' : '查询未完成'}
          subTitle={errorMessage(result.error)}
          extra={[
            ambiguousNos.length > 0 ? (
              <Typography.Paragraph key="ambiguous" type="secondary" style={{ maxWidth: 560, margin: '0 auto 8px' }}>
                该订单号对应多个发货批次，请改用以下出库单号精确查询：
                <br />
                {ambiguousNos.map((no) => String(no)).join('、')}
              </Typography.Paragraph>
            ) : null,
            <Button key="retry" icon={<ReloadOutlined />} onClick={result.reload}>重试</Button>,
          ]}
        />
      </Card>
    );
  }
  if (!result.data) return null;

  const view = result.data;
  const summary = reconSummary(view);
  const jdBanner = jdStatusPresentation(view.jd.status, view.jd.message);
  const internal = internalDescriptions(view);
  const jd = jdDescriptions(view.jd);

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Typography.Text type="secondary">
        查询条件：{queryTypeLabel(view.query.type)}「{view.query.value}」· 审计请求 {view.audit.request_id ?? '—'}
      </Typography.Text>
      <Alert
        type={jdBanner.tone}
        showIcon
        message={jdBanner.title}
        description={jdBanner.description}
        action={<Button size="small" icon={<ReloadOutlined />} onClick={result.reload}>重新查询</Button>}
      />
      <Space wrap size={8}>
        <Tag color={summary.jdStatus === 'OK' ? 'success' : summary.jdStatus === 'NOT_FOUND' ? 'warning' : 'error'}>
          京东侧：{summary.jdStatus === 'OK' ? '已返回' : summary.jdStatus === 'NOT_FOUND' ? '无记录' : '未取到'}
        </Tag>
        <Tag>{`共 ${summary.totalRows} 个对齐字段`}</Tag>
        <Tag color={summary.mismatched > 0 ? 'error' : 'success'}>{`差异 ${summary.mismatched} 项`}</Tag>
        {summary.internalOnly > 0 ? <Tag color="warning">{`仅内部有 ${summary.internalOnly} 项`}</Tag> : null}
        {summary.jdOnly > 0 ? <Tag color="warning">{`仅京东有 ${summary.jdOnly} 项`}</Tag> : null}
      </Space>

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card size="small" title="系统内部事实" extra={<Tag color="blue">业务系统</Tag>}>
            <Descriptions size="small" column={1} items={internal.items} />
            <Typography.Title level={5} style={{ marginTop: 16, fontSize: 14 }}>内部明细</Typography.Title>
            <DataTable<Record<string, unknown>>
              rowKey={(row) => `${row.order_line}-${row.goods_no ?? row.goods_name}`}
              columns={internalItemColumns}
              dataSource={view.internal.items}
              size="small"
              pagination={false}
              scroll={{ x: 560 }}
              emptyText="无明细"
            />
            {view.internal.tracking ? (
              <>
                <Typography.Title level={5} style={{ marginTop: 16, fontSize: 14 }}>运单</Typography.Title>
                <Descriptions size="small" column={1} items={[{
                  key: 'tracking',
                  label: `${cellText(view.internal.tracking.logistics_company_name)}`,
                  children: cellText(view.internal.tracking.tracking_number),
                }]} />
              </>
            ) : null}
          </Card>
        </Col>
        <Col xs={24} lg={12}>
          <Card
            size="small"
            title="京东侧事实"
            extra={<Tag color={view.jd.client_mode === 'REAL' ? 'green' : 'default'}>{view.jd.client_mode === 'REAL' ? '真实连接' : '模拟模式'}</Tag>}
          >
            {view.jd.status === 'UNAVAILABLE' ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="京东侧未取到，本次不展示京东事实" />
            ) : view.jd.status === 'NOT_FOUND' ? (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="京东侧没有这笔出库记录" />
            ) : (
              <>
                <Descriptions size="small" column={1} items={jd.items} />
                <Typography.Title level={5} style={{ marginTop: 16, fontSize: 14 }}>京东明细（deliveryItemList）</Typography.Title>
                <DataTable<Record<string, unknown>>
                  rowKey={(row) => `${row.order_line}-${row.goods_no ?? ''}`}
                  columns={jdItemColumns}
                  dataSource={view.jd.items}
                  size="small"
                  pagination={false}
                  scroll={{ x: 480 }}
                  emptyText="京东未返回明细"
                />
              </>
            )}
          </Card>
        </Col>
      </Row>

      <Card size="small" title="逐字段差异对照">
        <DataTable<OutboundReconComparisonRow>
          rowKey={(row) => row.key}
          columns={comparisonColumns}
          dataSource={view.comparisons}
          size="middle"
          pagination={false}
          scroll={{ x: 860 }}
          rowClassName={comparisonRowClassName}
          emptyText="无对齐字段"
        />
      </Card>
    </Space>
  );
}

export interface OutboundReconPageProps {
  /** 页面正文顶部额外横幅（如 /workbench/recon 入口注入的「金额对账未纳入本期」口径说明）。 */
  notice?: ReactNode;
}

export default function OutboundReconPage({ notice }: OutboundReconPageProps = {}) {
  const [searchParams, setSearchParams] = useSearchParams();
  const urlType = searchParams.get('query_type');
  const urlValue = searchParams.get('query_value') ?? '';
  const activeType = isQueryType(urlType) ? urlType : 'OUTBOUND_ORDER_NO';
  const [type, setType] = useState<OutboundReconQueryType>(activeType);
  const [value, setValue] = useState(urlValue);

  const activeTypeMeta = QUERY_TYPES.find((item) => item.value === activeType) ?? QUERY_TYPES[0];
  const hasQuery = Boolean(urlValue.trim());

  const submitQuery = () => {
    const trimmed = value.trim();
    if (!trimmed) return;
    setSearchParams({ query_type: type, query_value: trimmed }, { replace: false });
  };

  return (
    <PageShell
      title="出库信息对账"
      description="输入系统出库单号 / 京东单号 / 订单号，把系统内部事实与京东侧事实并排对照；不一致只标记不自动处置，由运营判断。"
    >
      {notice}
      <FilterBar>
        <Space.Compact style={{ width: '100%', maxWidth: 760 }}>
          <Select<OutboundReconQueryType>
            value={type}
            onChange={setType}
            options={QUERY_TYPES.map((item) => ({ value: item.value, label: item.label }))}
            style={{ width: 150 }}
          />
          <Input
            value={value}
            onChange={(event) => setValue(event.target.value)}
            onPressEnter={submitQuery}
            placeholder={activeTypeMeta.placeholder}
            allowClear
          />
          <Button type="primary" icon={<SearchOutlined />} disabled={!value.trim()} onClick={submitQuery}>
            查询
          </Button>
        </Space.Compact>
      </FilterBar>

      {hasQuery ? (
        <ReconResult queryType={activeType} queryValue={urlValue.trim()} />
      ) : (
        <Card size="small">
          <Empty description="输入单号开始查询；查询条件保存在链接中，刷新或分享即可复现同一视图" />
        </Card>
      )}
    </PageShell>
  );
}
