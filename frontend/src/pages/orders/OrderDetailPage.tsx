/**
 * 订单详情：基本信息 + 主线进度 Steps + 商品明细（礼包可展开组件）+ 发货与运单 + 复核事项，
 * 右侧固定展示「订单事件时间线」（PRD §18，演示亮点）。
 * 取数：GET /api/v1/orders/{id}、/timeline、/shipments。
 */

import { useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Alert, Button, Card, Col, Descriptions, Empty, Result, Row, Skeleton, Space, Steps, Table, Tag, Typography } from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { ApiError, errorMessage } from '@/api/client';
import { ordersApi } from '@/api/endpoints';
import type { OrderLine, OrderStatus, Shipment } from '@/api/types';
import { reasonLabel } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import OrderTimeline from '@/components/OrderTimeline';
import StatusTag from '@/components/StatusTag';
import { reviewCaseSummary } from '@/presentation/publicReady';
import { shipmentTimeLabel } from '@/presentation/shipment';

/** 主线状态（CONTEXT.md OrderStatus 主线），异常分支不在此列。 */
const MAINLINE: OrderStatus[] = ['RECEIVED', 'VALIDATED', 'SKU_MAPPED', 'FULFILLING', 'SHIPPED', 'SYNCED'];
const MAINLINE_LABELS: Record<string, string> = {
  RECEIVED: '已接收',
  VALIDATED: '已校验',
  SKU_MAPPED: '已映射 SKU',
  FULFILLING: '履约中',
  SHIPPED: '已发货',
  SYNCED: '已回传',
};

const lineColumns: ColumnsType<OrderLine> = [
  { title: '行号', dataIndex: 'line_no', width: 70 },
  {
    title: '商品',
    dataIndex: 'product_name',
    ellipsis: true,
    render: (v: string, r) => (
      <Space size={4}>
        {v}
        {r.line_type === 'CUSTOM_BUNDLE' ? <Tag color="purple" style={{ borderRadius: 6 }}>礼包</Tag> : null}
      </Space>
    ),
  },
  { title: '规格', dataIndex: 'specification', width: 130, ellipsis: true, render: (v?: string) => v ?? '—' },
  { title: '单位', dataIndex: 'unit', width: 70, render: (v?: string) => v ?? '—' },
  { title: '来源数量', dataIndex: 'source_quantity', width: 90 },
  { title: '乘数', dataIndex: 'mapping_multiplier', width: 70, render: (v?: string) => v ?? '—' },
  { title: '请求数量', dataIndex: 'requested_quantity', width: 90 },
  {
    title: '处理阶段',
    dataIndex: 'processing_stage',
    width: 130,
    render: (v: OrderLine['processing_stage']) => <StatusTag kind="stage" value={v} />,
  },
  {
    title: '异常',
    dataIndex: 'exception_code',
    width: 140,
    ellipsis: true,
    render: (v?: string) => (v ? <Tag color="error">{reasonLabel(v)}</Tag> : '—'),
  },
];

const shipmentColumns: ColumnsType<Shipment> = [
  { title: '发货单号', dataIndex: 'shipment_no', width: 180 },
  { title: '批次', dataIndex: 'shipment_sequence', width: 72, align: 'right' },
  {
    title: '状态',
    dataIndex: 'shipment_status',
    width: 100,
    render: (value: Shipment['shipment_status']) => <StatusTag kind="shipmentStatus" value={value} />,
  },
  {
    title: '实发量',
    width: 90,
    align: 'right',
    render: (_, record) => record.items.reduce((total, item) => total + Number(item.shipped_quantity || 0), 0),
  },
  {
    title: '物流公司',
    width: 120,
    render: (_, record) => record.tracking?.logistics_company_name ?? '—',
  },
  {
    title: '运单号',
    width: 180,
    render: (_, record) => record.tracking?.tracking_number ?? '—',
  },
  {
    title: '发货时间',
    dataIndex: 'shipped_at',
    width: 160,
    render: (value?: string) => shipmentTimeLabel(value),
  },
];

export default function OrderDetailPage() {
  const { orderId = '' } = useParams();
  const navigate = useNavigate();

  const detailQuery = useAsync(() => ordersApi.detail(orderId), [orderId]);
  const timelineQuery = useAsync(() => ordersApi.timeline(orderId), [orderId]);
  const shipmentsQuery = useAsync(() => ordersApi.shipments(orderId), [orderId]);

  const detail = detailQuery.data;
  const notFound = detailQuery.error instanceof ApiError && detailQuery.error.status === 404;

  const mainlineIndex = useMemo(() => (detail ? MAINLINE.indexOf(detail.order_status) : -1), [detail]);
  const isException = detail ? mainlineIndex < 0 && detail.order_status !== 'CLOSED' : false;

  if (notFound) {
    return (
      <Result
        status="404"
        title="订单不存在"
        subTitle="请核对订单号，或返回订单列表重新选择。"
        extra={
          <Button type="primary" onClick={() => navigate('/orders')}>
            返回订单列表
          </Button>
        }
      />
    );
  }

  if (detailQuery.error) {
    return (
      <Alert
        type="error"
        showIcon
        message="订单详情加载失败"
        description={errorMessage(detailQuery.error)}
        action={
          <Button size="small" icon={<ReloadOutlined />} onClick={detailQuery.reload}>
            重试
          </Button>
        }
      />
    );
  }

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Space size={12}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
          返回
        </Button>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {detail?.order_no ?? '订单详情'}
        </Typography.Title>
        {detail ? (
          <>
            <StatusTag kind="orderStatus" value={detail.order_status} />
            <StatusTag kind="health" value={detail.processing_health} />
          </>
        ) : null}
      </Space>

      <Card
        size="small"
        title="订单信息"
        style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
      >
        {detailQuery.loading || !detail ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            {isException ? (
              <Alert
                type="warning"
                showIcon
                message={`订单处于异常分支：${reasonLabel(detail.order_status)}`}
                description="该订单需要人工介入处理，请查看下方复核事项并前往人工复核工作台处理。"
              />
            ) : (
              <Steps
                size="small"
                current={detail.order_status === 'CLOSED' ? MAINLINE.length : mainlineIndex}
                items={MAINLINE.map((s, i) => ({
                  title: MAINLINE_LABELS[s],
                  status: i < mainlineIndex || detail.order_status === 'CLOSED' ? 'finish' : i === mainlineIndex ? 'process' : 'wait',
                }))}
              />
            )}
            <Descriptions
              size="small"
              column={{ xs: 1, sm: 2, xl: 4 }}
              items={[
                { key: 'order_no', label: '订单号', children: detail.order_no },
                { key: 'channel', label: '来源渠道', children: <StatusTag kind="channel" value={detail.source_channel} /> },
                { key: 'source_ref', label: '来源单号', children: detail.source_ref ?? '—' },
                { key: 'customer', label: '客户', children: detail.customer_name ?? '—' },
                {
                  key: 'receiver',
                  label: '收货人',
                  children: `${detail.receiver.name} · ${detail.receiver.phone}`,
                },
                {
                  key: 'address',
                  label: '收货地址',
                  span: 2,
                  children: [detail.receiver.province, detail.receiver.city, detail.receiver.district, detail.receiver.town, detail.receiver.address]
                    .filter(Boolean)
                    .join(' '),
                },
                { key: 'settlement', label: '结账方式', children: detail.settlement.method },
                { key: 'progress', label: '行进度', children: `${detail.completed_count}/${detail.total_count}` },
                { key: 'created_at', label: '创建时间', children: dayjs(detail.created_at).format('YYYY-MM-DD HH:mm:ss') },
                { key: 'updated_at', label: '更新时间', children: detail.updated_at ? dayjs(detail.updated_at).format('YYYY-MM-DD HH:mm:ss') : '—' },
                { key: 'version', label: '数据版本', children: detail.version },
                { key: 'remark', label: '备注', span: 3, children: detail.remark ?? '—' },
              ]}
            />
            {detail.review_cases.length ? (
              <Alert
                type="warning"
                showIcon
                message={`存在 ${detail.review_cases.length} 条复核事项`}
                description={detail.review_cases
                  .map((reviewCase) => `${reviewCase.case_no}（${reasonLabel(reviewCase.reason_code)}）：${reviewCaseSummary(reviewCase)}`)
                  .join('；')}
                action={
                  <Button size="small" onClick={() => navigate('/workbench/reviews')}>
                    前往人工复核
                  </Button>
                }
              />
            ) : null}
          </Space>
        )}
      </Card>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card
              size="small"
              title="商品明细"
              style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
              styles={{ body: { padding: '4px 8px' } }}
            >
              <Table<OrderLine>
                rowKey="id"
                size="small"
                columns={lineColumns}
                dataSource={detail?.lines ?? []}
                loading={detailQuery.loading}
                pagination={false}
                scroll={{ x: 980 }}
                expandable={{
                  expandedRowRender: (record) => (
                    <Table
                      rowKey="id"
                      size="small"
                      pagination={false}
                      title={() => '礼包组件快照'}
                      dataSource={record.components ?? []}
                      columns={[
                        { title: '组件商品', dataIndex: 'product_name' },
                        { title: '规格', dataIndex: 'specification', render: (v?: string) => v ?? '—' },
                        { title: '单位', dataIndex: 'unit', width: 80 },
                        { title: '每份数量', dataIndex: 'quantity_per_bundle', width: 100 },
                        { title: '合计数量', dataIndex: 'total_quantity', width: 100 },
                      ]}
                    />
                  ),
                  rowExpandable: (record) => !!record.components?.length,
                }}
              />
            </Card>

            <Card
              size="small"
              title="发货与运单"
              extra={shipmentsQuery.data?.length ? <Tag color="blue">{shipmentsQuery.data.length} 个批次</Tag> : null}
              style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
              styles={{ body: { padding: '4px 8px' } }}
            >
              {shipmentsQuery.error ? (
                <Alert
                  type="error"
                  showIcon
                  message="发货与运单加载失败"
                  description={errorMessage(shipmentsQuery.error)}
                  action={<Button size="small" icon={<ReloadOutlined />} onClick={shipmentsQuery.reload}>重试</Button>}
                />
              ) : (
                <Table<Shipment>
                  rowKey="id"
                  size="small"
                  columns={shipmentColumns}
                  dataSource={shipmentsQuery.data ?? []}
                  loading={shipmentsQuery.loading}
                  pagination={false}
                  scroll={{ x: 920 }}
                  locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无发货记录" /> }}
                  expandable={{
                    expandedRowRender: (shipment) => (
                      <Table
                        rowKey={(item) => `${item.fulfillment_id}:${item.order_line_id}`}
                        size="small"
                        pagination={false}
                        dataSource={shipment.items}
                        columns={[
                          { title: '商品', dataIndex: 'product_name' },
                          { title: '指令量', dataIndex: 'instructed_quantity', width: 100, align: 'right' },
                          { title: '实发量', dataIndex: 'shipped_quantity', width: 100, align: 'right' },
                          { title: '单位', dataIndex: 'unit', width: 80 },
                        ]}
                      />
                    ),
                    rowExpandable: (shipment) => shipment.items.length > 0,
                  }}
                />
              )}
            </Card>
          </Space>
        </Col>

        <Col xs={24} xl={8}>
          <div style={{ position: 'sticky', top: 76 }}>
            <Card
              size="small"
              title="订单事件时间线"
              extra={
                timelineQuery.data?.length ? <Tag color="blue" style={{ borderRadius: 6 }}>{timelineQuery.data.length} 条事件</Tag> : null
              }
              style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
            >
              <OrderTimeline events={timelineQuery.data ?? []} loading={timelineQuery.loading} maxHeight={620} />
            </Card>
          </div>
        </Col>
      </Row>
    </Space>
  );
}
