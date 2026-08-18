/**
 * 履约中心 · 履约任务（GET /api/v1/fulfillments + 详情）。
 * 每行 = 一条履约单元（订单行 → 履约方）；缺货采购为其补货分支（详情抽屉内展示采购工单）。
 */

import { useMemo, useState } from 'react';
import { Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, Modal, Select, Space, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { errorMessage } from '@/api/client';
import { fulfillmentsApi, providersApi } from '@/api/endpoints';
import type { ContinuationExportResult, Fulfillment, FulfillmentDetail, FulfillmentOutcome, ShippingProgress } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import {
  FULFILLMENT_OUTCOME_SEMANTIC,
  SHIPPING_PROGRESS_SEMANTIC,
} from '@/pages/shared/semanticStatus';
import {
  buildContinuationExportCommand,
  canCreateContinuationExport,
  continuationExportResultMessage,
} from './continuationExportActions';

const PROGRESS_LABELS: Record<ShippingProgress, string> = {
  NOT_SHIPPED: '未发货',
  PARTIALLY_SHIPPED: '部分发货',
  SHIPPED: '已发货',
};

const OUTCOME_LABELS: Record<FulfillmentOutcome, string> = {
  IN_PROGRESS: '进行中',
  FULLY_FULFILLED: '全部履约',
  PARTIALLY_FULFILLED: '部分履约',
  CANCELLED: '已取消',
};

function num(v: string | number | undefined | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

export default function FulfillmentTasksPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [providerId, setProviderId] = useState<string | undefined>();
  const [progress, setProgress] = useState<ShippingProgress | undefined>();
  const [outcome, setOutcome] = useState<FulfillmentOutcome | undefined>();
  const [selected, setSelected] = useState<Fulfillment | null>(null);
  const [continuationOpen, setContinuationOpen] = useState(false);
  const [continuationSubmitting, setContinuationSubmitting] = useState(false);
  const [continuationResult, setContinuationResult] = useState<ContinuationExportResult | null>(null);
  const [continuationForm] = Form.useForm<{ instructed_quantity: string; remark: string }>();

  const providers = useAsync(() => providersApi.list(), []);
  const providerById = useMemo(() => {
    return new Map((providers.data ?? []).map((provider) => [provider.id, provider]));
  }, [providers.data]);
  const providerName = (id?: string) => (id ? providerById.get(id)?.provider_name ?? id : '—');

  const list = useAsync(
    () => fulfillmentsApi.list({ page, size, provider_id: providerId, shipping_progress: progress, outcome }),
    [page, size, providerId, progress, outcome],
  );

  const detail = useAsync(() => (selected ? fulfillmentsApi.detail(selected.id) : Promise.resolve(null)), [selected?.id]);
  const detailData = detail.data as FulfillmentDetail | null;
  const canCreateContinuation = detailData
    ? canCreateContinuationExport(
      'BUSINESS',
      detailData.shipping_progress,
      providerById.get(detailData.provider_id)?.provider_type,
    )
    : false;

  const closeDetail = () => {
    setSelected(null);
    setContinuationOpen(false);
    setContinuationResult(null);
    continuationForm.resetFields();
  };

  const submitContinuation = async () => {
    if (!detailData) return;
    try {
      const values = await continuationForm.validateFields();
      setContinuationSubmitting(true);
      const result = await fulfillmentsApi.createContinuationExport(
        detailData.id,
        buildContinuationExportCommand(
          detailData.version,
          values.instructed_quantity,
          values.remark,
        ),
      );
      setContinuationResult(result);
      message.success(continuationExportResultMessage(result));
      setContinuationOpen(false);
      continuationForm.resetFields();
      detail.reload();
      list.reload();
    } catch (err) {
      if (!(err && typeof err === 'object' && 'errorFields' in err)) {
        message.error(errorMessage(err));
      }
    } finally {
      setContinuationSubmitting(false);
    }
  };

  const columns: ColumnsType<Fulfillment> = [
    { title: '履约单号', dataIndex: 'fulfillment_no', width: 170, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '履约方', dataIndex: 'provider_id', width: 150, render: (v?: string) => providerName(v) },
    { title: '申请数量', dataIndex: 'requested_quantity', width: 100, align: 'right', render: num },
    { title: '累计已发', dataIndex: 'cumulative_shipped_quantity', width: 100, align: 'right', render: num },
    { title: '已取消', dataIndex: 'cancelled_quantity', width: 90, align: 'right', render: num },
    { title: '发货进度', dataIndex: 'shipping_progress', width: 100, render: (v: ShippingProgress) => <Tag color={SHIPPING_PROGRESS_SEMANTIC[v]}>{PROGRESS_LABELS[v]}</Tag> },
    { title: '结果', dataIndex: 'outcome', width: 100, render: (v: FulfillmentOutcome) => <Tag color={FULFILLMENT_OUTCOME_SEMANTIC[v]}>{OUTCOME_LABELS[v]}</Tag> },
    {
      title: '异常',
      dataIndex: 'exception_code',
      width: 120,
      render: (v?: string, r?: Fulfillment) => (v ? <Tag color="error">{v}</Tag> : r?.exception_reason ? <Tag color="error">异常</Tag> : '—'),
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      fixed: 'right',
      render: (_, r) => (
        <Typography.Link onClick={() => { setSelected(r); setContinuationResult(null); }}>详情</Typography.Link>
      ),
    },
  ];

  const err = list.error || providers.error;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {err ? (
        <Alert
          type="error"
          showIcon
          message="履约任务加载失败"
          description={errorMessage(err)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={list.reload}>
              重试
            </Button>
          }
        />
      ) : null}

      <Card size="small">
        <Space wrap>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
          <Select style={{ width: 200 }} placeholder="全部履约方" allowClear value={providerId} onChange={setProviderId}
            options={(providers.data ?? []).map((p) => ({ value: p.id, label: p.provider_name }))} />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>发货进度</Typography.Text>
          <Select style={{ width: 140 }} placeholder="全部" allowClear value={progress} onChange={setProgress}
            options={(Object.keys(PROGRESS_LABELS) as ShippingProgress[]).map((k) => ({ value: k, label: PROGRESS_LABELS[k] }))} />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>结果</Typography.Text>
          <Select style={{ width: 140 }} placeholder="全部" allowClear value={outcome} onChange={setOutcome}
            options={(Object.keys(OUTCOME_LABELS) as FulfillmentOutcome[]).map((k) => ({ value: k, label: OUTCOME_LABELS[k] }))} />
          <Button icon={<ReloadOutlined />} onClick={list.reload}>
            刷新
          </Button>
        </Space>
      </Card>

      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <Table<Fulfillment>
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          size="middle"
          scroll={{ x: 980 }}
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
        title={`履约单 ${selected?.fulfillment_no ?? ''}`}
        open={Boolean(selected)}
        onClose={closeDetail}
        width={560}
        styles={{ body: { padding: '16px 20px' } }}
      >
        {detailData ? (
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Descriptions
              size="small"
              column={2}
              items={[
                { key: 'p', label: '履约方', children: providerName(detailData.provider_id) },
                { key: 'q', label: '申请数量', children: num(detailData.requested_quantity) },
                { key: 's', label: '累计已发', children: num(detailData.cumulative_shipped_quantity) },
                { key: 'c', label: '已取消', children: num(detailData.cancelled_quantity) },
                {
                  key: 'st',
                  label: '发货进度',
                  children: <Tag color={SHIPPING_PROGRESS_SEMANTIC[detailData.shipping_progress]}>{PROGRESS_LABELS[detailData.shipping_progress]}</Tag>,
                },
                {
                  key: 'o',
                  label: '结果',
                  children: <Tag color={FULFILLMENT_OUTCOME_SEMANTIC[detailData.outcome]}>{OUTCOME_LABELS[detailData.outcome]}</Tag>,
                },
              ]}
            />
            {detailData.exception_reason ? (
              <Alert type="warning" showIcon message={`异常原因：${detailData.exception_reason}`} />
            ) : null}

            {continuationResult ? (
              <Alert
                type="success"
                showIcon
                message={continuationExportResultMessage(continuationResult)}
                description={`续发数量 ${num(continuationResult.instructed_quantity)}，履约版本已更新为 ${continuationResult.fulfillment_version}`}
              />
            ) : null}

            {canCreateContinuation ? (
              <div>
                <Button type="primary" onClick={() => setContinuationOpen(true)}>
                  创建续发批次
                </Button>
              </div>
            ) : null}

            <div>
              <Typography.Text strong>发货批次（{detailData.shipments?.length ?? 0}）</Typography.Text>
              <Table<FulfillmentDetail['shipments'][number]>
                rowKey="id"
                size="small"
                style={{ marginTop: 8 }}
                pagination={false}
                columns={[
                  { title: '发货单号', dataIndex: 'shipment_no', render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
                  { title: '出库单号', dataIndex: 'outbound_order_no', render: (v?: string) => v ?? '—' },
                  { title: '状态', dataIndex: 'shipment_status', render: (v: string) => <Tag>{v}</Tag> },
                  { title: '运单号', dataIndex: 'tracking', render: (t?: { tracking_number?: string }) => t?.tracking_number ?? '—' },
                ]}
                dataSource={detailData.shipments ?? []}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无发货批次" /> }}
              />
            </div>

            <div>
              <Typography.Text strong>关联采购工单（{detailData.procurement_tickets?.length ?? 0}）</Typography.Text>
              <Table
                rowKey="id"
                size="small"
                style={{ marginTop: 8 }}
                pagination={false}
                columns={[
                  { title: '工单号', dataIndex: 'ticket_no', render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
                  { title: '状态', dataIndex: 'status', render: (v: string) => <Tag>{v}</Tag> },
                  { title: '申请', dataIndex: 'requested_quantity', align: 'right', render: num },
                  { title: '已到货', dataIndex: 'fulfilled_quantity', align: 'right', render: num },
                  { title: '剩余', dataIndex: 'remaining_quantity', align: 'right', render: num },
                ]}
                dataSource={detailData.procurement_tickets ?? []}
                locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="无采购工单（未缺货）" /> }}
              />
            </div>
          </Space>
        ) : detail.loading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="加载中…" />
        ) : detail.error ? (
          <Alert type="error" showIcon message={errorMessage(detail.error)} />
        ) : null}
      </Drawer>

      <Modal
        title="创建续发批次"
        open={continuationOpen}
        okText="创建批次并生成导出"
        cancelText="取消"
        confirmLoading={continuationSubmitting}
        onOk={submitContinuation}
        onCancel={() => {
          setContinuationOpen(false);
          continuationForm.resetFields();
        }}
        destroyOnClose
      >
        <Alert
          type="info"
          showIcon
          message="续发会新建独立发货批次和第三方履约导出"
          description="数量不得超过后台计算的剩余可续发数量；如履约已被其他人更新，请刷新后重试。"
          style={{ marginBottom: 16 }}
        />
        <Form form={continuationForm} layout="vertical" preserve={false}>
          <Form.Item
            name="instructed_quantity"
            label="续发数量"
            rules={[
              { required: true, message: '请输入续发数量' },
              { pattern: /^(?:0|[1-9]\d{0,14})(?:\.\d{1,3})?$/, message: '请输入最多 15 位整数、3 位小数的数量' },
              {
                validator: (_, value: string | undefined) => (
                  value && Number(value) > 0
                    ? Promise.resolve()
                    : Promise.reject(new Error('续发数量必须大于 0'))
                ),
              },
            ]}
          >
            <Input inputMode="decimal" placeholder="例如 2.500" />
          </Form.Item>
          <Form.Item
            name="remark"
            label="续发依据"
            rules={[
              { required: true, whitespace: true, message: '请输入续发依据' },
              { max: 1000, message: '续发依据不得超过 1000 字' },
            ]}
          >
            <Input.TextArea rows={4} maxLength={1000} showCount placeholder="请记录采购到货或人工核对依据" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
