/**
 * 履约中心 · Shipment（GET /api/v1/shipments + 详情）。
 * 一次出库/发货批次，可包含同一订单、同一履约方、同一收货地址下的多条订单行；
 * 缺货后的后续批次生成新 Shipment / 出库单号 / 运单（CONTEXT.md 发货）。
 */

import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Card, Checkbox, Descriptions, Drawer, Empty, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import LongCode from '@/components/LongCode';
import PageShell from '@/components/PageShell';
import { errorMessage } from '@/api/client';
import { jdWarehouseApi, providersApi, shipmentsApi } from '@/api/endpoints';
import type { JdClientStatus, JdReceiverAddressCandidate, Shipment, ShipmentJdOutboundPreview, ShipmentStatus } from '@/api/types';
import { CHANNEL_LABELS, SHIPMENT_STATUS_COLORS, SHIPMENT_STATUS_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import { PageState } from '@/pages/shared/PageState';
import SourceSyncPanel from '@/pages/fulfillment/SourceSyncPanel';
import { shipmentTimeLabel } from '@/presentation/shipment';
import {
  jdReceiverAddressBatchIdempotencyKey,
  jdReceiverAddressBatchItems,
  jdReceiverAddressCandidateText,
  jdReceiverAddressConfirmedText,
  jdReceiverAddressDefaults,
  jdReceiverAddressStatus,
  jdReceiverAddressStatusLabel,
  jdReceiverAddressStatusTone,
  type JdReceiverAddressFields,
} from './jdReceiverAddress';
import { canSubmitJdOutbound, jdOutboundConfirmationDetail, jdOutboundConfirmationTitle, jdOutboundPresentation, jdOutboundRuntimeGate } from './shipmentJdOutbound';

function num(v: string | number | undefined | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

/**
 * 京东结构化收货地址批量确认（jd-real-sdk-switch 04）。
 * 候选来自来源表格（省/市/区/详细地址），只用于人工确认；未确认不参与建单。
 */
function JdReceiverAddressPanel() {
  const [onlyMissing, setOnlyMissing] = useState(true);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  const [edits, setEdits] = useState<Record<string, Partial<JdReceiverAddressFields>>>({});
  const [editingRow, setEditingRow] = useState<JdReceiverAddressCandidate | null>(null);
  const [form] = Form.useForm();
  const [submitting, setSubmitting] = useState(false);

  const candidates = useAsync<JdReceiverAddressCandidate[]>(
    () => shipmentsApi.jdReceiverAddressCandidates({ only_missing: onlyMissing }),
    [onlyMissing],
  );

  const openEditor = (row: JdReceiverAddressCandidate) => {
    setEditingRow(row);
    form.setFieldsValue(jdReceiverAddressDefaults(row));
  };

  const saveEditor = async () => {
    if (!editingRow) return;
    try {
      const values = await form.validateFields();
      setEdits((prev) => ({ ...prev, [editingRow.shipment_id]: values }));
      setEditingRow(null);
    } catch {
      // 表单校验失败时保持弹窗打开
    }
  };

  const importSelected = async () => {
    const rows = (candidates.data ?? []).filter((row) => selectedRowKeys.includes(row.shipment_id));
    const { items, skipped } = jdReceiverAddressBatchItems(rows, edits);
    if (skipped.length > 0) {
      message.warning(`以下发货单缺少必填层级，已取消导入：${skipped.map((s) => `#${s.shipment_id}`).join('、')}。请逐条编辑补齐后重试。`);
      return;
    }
    if (items.length === 0) {
      message.warning('请先勾选要确认的发货单');
      return;
    }
    setSubmitting(true);
    try {
      const result = await shipmentsApi.confirmJdReceiverAddresses(
        { items },
        { idempotencyKey: jdReceiverAddressBatchIdempotencyKey(items) },
      );
      message.success(`已确认 ${result.confirmed_count} 个发货批次的结构化收货地址`);
      setSelectedRowKeys([]);
      setEdits({});
      candidates.reload();
    } catch (err) {
      message.error(errorMessage(err));
      candidates.reload();
    } finally {
      setSubmitting(false);
    }
  };

  const columns: ColumnsType<JdReceiverAddressCandidate> = [
    {
      title: '发货单号',
      dataIndex: 'shipment_id',
      width: 150,
      render: (value: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>,
    },
    {
      title: '来源渠道',
      dataIndex: 'source_channel',
      width: 110,
      render: (value?: string) =>
        value ? (CHANNEL_LABELS[value as keyof typeof CHANNEL_LABELS] ?? value) : '—',
    },
    { title: '原始地址', dataIndex: 'receiver_address_snapshot', width: 240, ellipsis: true },
    {
      title: '候选（省/市/区/详细）',
      key: 'candidate',
      width: 250,
      render: (_, row) => {
        const text = jdReceiverAddressCandidateText(row);
        return text ? <span>{text}</span> : <Tag color="warning">来源层级缺失 · 需人工填写</Tag>;
      },
    },
    {
      title: '已确认值',
      key: 'confirmed',
      width: 260,
      render: (_, row) => {
        const status = jdReceiverAddressStatus(row);
        const text = jdReceiverAddressConfirmedText(row);
        return (
          <Space size={6} wrap>
            <Tag color={jdReceiverAddressStatusTone(status)}>{jdReceiverAddressStatusLabel(status)}</Tag>
            {text ? (
              <span>
                {text}
                {row.confirmed_by ? <span style={{ color: '#7a8699' }}>（{row.confirmed_by}）</span> : null}
              </span>
            ) : null}
          </Space>
        );
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 70,
      fixed: 'right',
      render: (_, row) => <Typography.Link onClick={() => openEditor(row)}>编辑</Typography.Link>,
    },
  ];

  return (
    <Card
      size="small"
      title="京东收货地址批量确认"
      style={{ borderRadius: 10, boxShadow: '0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)' }}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="info"
          showIcon
          message="候选来自来源表格的省/市/区/详细地址，只用于人工确认；未确认前不参与建单，系统不从自由文本猜测。乡镇按履约方策略可选留空。"
        />
        <Space wrap>
          <Checkbox
            checked={!onlyMissing}
            onChange={(event) => {
              setOnlyMissing(!event.target.checked);
              setSelectedRowKeys([]);
            }}
          >
            显示已确认
          </Checkbox>
          <Button icon={<ReloadOutlined />} onClick={candidates.reload}>
            刷新
          </Button>
          <Button
            type="primary"
            loading={submitting}
            disabled={selectedRowKeys.length === 0}
            onClick={importSelected}
          >
            批量确认选中（{selectedRowKeys.length}）
          </Button>
        </Space>
        {candidates.error ? (
          <Alert type="error" showIcon message="候选加载失败" description={errorMessage(candidates.error)} />
        ) : null}
        <Table<JdReceiverAddressCandidate>
          rowKey={(row) => row.shipment_id}
          size="small"
          columns={columns}
          dataSource={candidates.data ?? []}
          loading={candidates.loading}
          rowSelection={{
            selectedRowKeys,
            onChange: (keys) => setSelectedRowKeys(keys.map(String)),
          }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          scroll={{ x: 1100 }}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无待确认发货单" /> }}
        />
      </Space>

      <Modal
        title={`编辑收货地址 · 发货单 ${editingRow?.shipment_id ?? ''}`}
        open={Boolean(editingRow)}
        onOk={saveEditor}
        onCancel={() => setEditingRow(null)}
        okText="保存"
        cancelText="取消"
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Form form={form} layout="vertical" preserve={false}>
            <Form.Item name="province" label="省" rules={[{ required: true, message: '请填写省份' }]}>
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item name="city" label="市" rules={[{ required: true, message: '请填写城市' }]}>
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item name="county" label="区/县" rules={[{ required: true, message: '请填写区县' }]}>
              <Input maxLength={64} />
            </Form.Item>
            <Form.Item name="town" label="乡镇（可选，按履约方策略）">
              <Input maxLength={64} placeholder="可留空" />
            </Form.Item>
            <Form.Item
              name="detail_address"
              label="详细地址"
              rules={[{ required: true, message: '请填写详细地址' }]}
            >
              <Input maxLength={255} />
            </Form.Item>
          </Form>
          <Alert
            type="info"
            showIcon
            message="保存只更新本页确认值；仍需勾选该行并执行「批量确认」才会写入，未确认不参与建单。"
          />
        </Space>
      </Modal>
    </Card>
  );
}

export default function ShipmentsPage() {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [providerId, setProviderId] = useState<string | undefined>();
  const [status, setStatus] = useState<ShipmentStatus | undefined>();
  const [selected, setSelected] = useState<Shipment | null>(null);
  const [jdSubmitting, setJdSubmitting] = useState(false);

  const providers = useAsync(() => providersApi.list(), []);
  const providerById = useMemo(
    () => new Map((providers.data ?? []).map((provider) => [provider.id, provider])),
    [providers.data],
  );
  const providerName = useMemo(() => {
    return (id?: string) => (id ? providerById.get(id)?.provider_name ?? id : '—');
  }, [providerById]);

  const list = useAsync(
    () => shipmentsApi.list({ page, size, provider_id: providerId, shipment_status: status }),
    [page, size, providerId, status],
  );

  const detail = useAsync<Shipment | null>(
    () => (selected ? shipmentsApi.detail(selected.id) : Promise.resolve(null)),
    [selected?.id],
  );
  const isJdShipment = selected
    ? providerById.get(selected.provider_id ?? '')?.provider_type === 'JD_WAREHOUSE'
    : false;
  const jdPreview = useAsync<ShipmentJdOutboundPreview | null>(
    () => (selected && isJdShipment
      ? shipmentsApi.previewJdOutbound(selected.id)
      : Promise.resolve(null)),
    [selected?.id, isJdShipment],
  );
  const jdRuntime = useAsync<JdClientStatus | null>(
    () => (selected && isJdShipment ? jdWarehouseApi.status() : Promise.resolve(null)),
    [selected?.id, isJdShipment],
  );
  /**
   * 来源回传入口的显示条件：已发货 + 已有正式运单号。
   *
   * <p>刻意<b>不</b>在这里判渠道支不支持在线回传——那是服务端 check 的结论（会连同
   * 平台当前事实一起给出），前端另写一份判断迟早会和后端漂移。
   */
  const canSyncToSource = Boolean(
    detail.data
      && detail.data.shipment_status === 'SHIPPED'
      && detail.data.tracking?.tracking_number,
  );
  const jdPresentation = jdOutboundPresentation(detail.data?.jd_outbound);
  const jdRuntimeGate = jdOutboundRuntimeGate(jdRuntime.data);
  const jdClientMode = detail.data?.jd_outbound?.client_mode ?? jdRuntimeGate.mode;
  const jdConfirmation = jdOutboundConfirmationDetail(
    jdPreview.data,
    detail.data?.jd_outbound?.erp_delivery_no,
  );
  const jdConfirmationTitle = jdOutboundConfirmationTitle(
    jdRuntimeGate.mode,
    jdRuntimeGate.confirmation,
    jdConfirmation.erpDeliveryNo,
  );
  const canSubmitJd = canSubmitJdOutbound({
    selectedShipmentId: selected?.id,
    detailShipmentId: detail.data?.id,
    previewShipmentId: jdPreview.data?.shipment_id,
    isJdShipment,
    presentationAllowsSubmit: jdPresentation.canSubmit,
    detailLoading: detail.loading,
    detailError: Boolean(detail.error),
    previewSubmittable: jdPreview.data?.submittable === true,
    previewLoading: jdPreview.loading,
    previewError: Boolean(jdPreview.error),
    runtimeReady: jdRuntimeGate.ready,
    runtimeLoading: jdRuntime.loading,
    runtimeError: Boolean(jdRuntime.error),
    submitting: jdSubmitting,
  });

  const submitJdOutbound = async () => {
    if (!selected || !canSubmitJd) return;
    setJdSubmitting(true);
    try {
      const result = await shipmentsApi.submitJdOutbound(selected.id);
      message.success(`京东出库单 ${result.erp_delivery_no} 已提交`);
      detail.reload();
      jdPreview.reload();
      list.reload();
    } catch (err) {
      message.error(errorMessage(err));
      detail.reload();
      jdPreview.reload();
    } finally {
      setJdSubmitting(false);
    }
  };

  const columns: ColumnsType<Shipment> = [
    { title: '发货单号', dataIndex: 'shipment_no', width: 160, render: (v: string) => <LongCode value={v} width={140} /> },
    { title: '订单号', dataIndex: 'order_no', width: 180, render: (v?: string, r?: Shipment) => (
      v ? <LongCode value={v} to={`/orders/${r?.order_id}`} width={160} /> : <span style={{ fontVariantNumeric: 'tabular-nums' }}>{r?.order_id}</span>
    ) },
    { title: '出库单号', dataIndex: 'outbound_order_no', width: 140, render: (v?: string) => v ?? '—' },
    { title: '批次', dataIndex: 'shipment_sequence', width: 70, align: 'right' },
    {
      title: '状态',
      dataIndex: 'shipment_status',
      width: 100,
      render: (v: ShipmentStatus) => <Tag color={SHIPMENT_STATUS_COLORS[v]}>{SHIPMENT_STATUS_LABELS[v] ?? v}</Tag>,
    },
    {
      title: '京东建单',
      key: 'jd_outbound',
      width: 130,
      render: (_, r) => {
        const presentation = jdOutboundPresentation(r.jd_outbound);
        return <Tag color={presentation.statusTone}>{presentation.statusLabel}</Tag>;
      },
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

  /** 页面级错误态：列表或履约方目录加载失败时整块切换为 PageState，重试语义与替换前一致（reload）。 */
  if (err) {
    return (
      <PageState
        state="error"
        message="Shipment 加载失败"
        description={errorMessage(err)}
        onRetry={list.reload}
      />
    );
  }

  return (
    <PageShell
      title="发货记录"
      description="一次出库/发货批次，可包含同一订单、同一履约方、同一收货地址下的多条订单行；缺货后的后续批次生成新 Shipment / 出库单号 / 运单。"
      actions={
        <Space size={12}>
          {/* 低频专用查询的上下文入口（Issue #98/#111）：出库信息对账已从菜单隐藏，由发货记录承载发现路径，指向工作台对账入口；旧 /fulfillment/outbound-recon 仍可直达。 */}
          <Link to="/workbench/recon">出库信息对账</Link>
          <Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>
        </Space>
      }
    >
      <FilterBar>
        <span style={{ color: '#7a8699', fontSize: 13 }}>履约方</span>
        <Select style={{ width: 200 }} placeholder="全部履约方" allowClear value={providerId} onChange={setProviderId}
          options={(providers.data ?? []).map((p) => ({ value: p.id, label: p.provider_name }))} />
        <span style={{ color: '#7a8699', fontSize: 13 }}>状态</span>
        <Select style={{ width: 140 }} placeholder="全部" allowClear value={status} onChange={setStatus}
          options={(Object.keys(SHIPMENT_STATUS_LABELS) as ShipmentStatus[]).map((k) => ({ value: k, label: SHIPMENT_STATUS_LABELS[k] }))} />
      </FilterBar>

      <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
        <DataTable<Shipment>
          rowKey="id"
          columns={columns}
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          size="middle"
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

      <JdReceiverAddressPanel />

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
                { key: 'o', label: '订单号', children: detail.data.order_no
                  ? <LongCode value={detail.data.order_no} to={`/orders/${detail.data.order_id}`} width={200} />
                  : detail.data.order_id },
                { key: 'p', label: '履约方', children: providerName(detail.data.provider_id) },
                { key: 'ob', label: '出库单号', children: detail.data.outbound_order_no ?? '—' },
                { key: 's', label: '状态', children: <Tag color={SHIPMENT_STATUS_COLORS[detail.data.shipment_status]}>{SHIPMENT_STATUS_LABELS[detail.data.shipment_status] ?? detail.data.shipment_status}</Tag> },
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
            {isJdShipment ? (
              <Card
                size="small"
                title="京东出库建单"
                extra={(
                  <Space size={6}>
                    <Tag color={jdPresentation.statusTone}>{jdPresentation.statusLabel}</Tag>
                    <Tag color={jdClientMode === 'REAL' ? 'blue' : 'default'}>
                      {jdClientMode === 'REAL'
                        ? '真实京东'
                        : jdClientMode === 'MOCK'
                          ? '模拟环境'
                          : detail.data?.jd_outbound
                            ? '历史环境未记录'
                            : '环境待确认'}
                    </Tag>
                  </Space>
                )}
              >
                <Space direction="vertical" size={10} style={{ width: '100%' }}>
                  {detail.data.jd_outbound ? (
                    <Descriptions
                      size="small"
                      column={2}
                      items={[
                        { key: 'erp', label: '商户出库号', children: detail.data.jd_outbound.erp_delivery_no },
                        { key: 'jd', label: '京东出库号', children: detail.data.jd_outbound.jd_delivery_no ?? '—' },
                        { key: 'retry', label: '尝试次数', children: detail.data.jd_outbound.retry_count },
                        { key: 'error', label: '最近结果', children: detail.data.jd_outbound.last_error_code ?? '正常' },
                      ]}
                    />
                  ) : null}
                  {detail.data.jd_outbound?.last_error_message ? (
                    <Alert
                      type={detail.data.jd_outbound.retryable ? 'warning' : 'error'}
                      showIcon
                      message={detail.data.jd_outbound.last_error_message}
                    />
                  ) : null}
                  {jdPreview.error ? (
                    <Alert type="error" showIcon message="建单预检失败" description={errorMessage(jdPreview.error)} />
                  ) : null}
                  {jdPreview.data && !jdPreview.data.submittable ? (
                    <Alert
                      type="warning"
                      showIcon
                      message="当前不可提交"
                      description={jdPreview.data.blockers.map((blocker) => blocker.message).join('；')}
                    />
                  ) : null}
                  {!jdRuntimeGate.ready ? (
                    <Alert
                      type="error"
                      showIcon
                      message={jdRuntimeGate.mode === 'REAL' ? '真实京东连接尚未就绪' : '京东运行环境尚未确认'}
                      description={jdRuntimeGate.mode === 'REAL'
                        ? '凭据或租户配置未通过 readiness，系统已禁止建单。'
                        : '运行状态读取失败或尚未完成，系统已默认禁止建单。'}
                    />
                  ) : null}
                  <Popconfirm
                    title={jdConfirmationTitle}
                    description={(
                      <Space direction="vertical" size={6} style={{ maxWidth: 420 }}>
                        <span>系统会再次执行 SKU 映射、数量换算和实时库存门禁。</span>
                        {jdConfirmation.erpDeliveryNo ? (
                          <span>商户出库号：<span style={{ fontVariantNumeric: 'tabular-nums' }}>{jdConfirmation.erpDeliveryNo}</span></span>
                        ) : null}
                        {jdPreview.data?.submittable && jdConfirmation.cargos.length > 0 ? (
                          <div style={{ background: '#f7f8fa', borderRadius: 8, padding: '8px 10px' }}>
                            <div style={{ fontWeight: 600, marginBottom: 4 }}>本次将提交以下 SKU×数量：</div>
                            <ul style={{ margin: 0, paddingInlineStart: 16 }}>
                              {jdConfirmation.cargos.map((cargo) => (
                                <li key={`${cargo.orderLine}-${cargo.goodsNo}`}>
                                  {cargo.goodsName}
                                  {cargo.goodsNo ? `（SKU ${cargo.goodsNo}）` : ''}
                                  {' '}× {Number.isFinite(cargo.planQuantity) ? cargo.planQuantity.toLocaleString('zh-CN') : '—'} 件
                                </li>
                              ))}
                            </ul>
                          </div>
                        ) : null}
                      </Space>
                    )}
                    okText="确认提交"
                    cancelText="取消"
                    onConfirm={submitJdOutbound}
                    disabled={!canSubmitJd}
                  >
                    <Button type="primary" loading={jdSubmitting} disabled={!canSubmitJd}>
                      {jdPresentation.actionLabel}
                    </Button>
                  </Popconfirm>
                </Space>
              </Card>
            ) : null}
            {canSyncToSource ? (
              <SourceSyncPanel
                shipmentId={detail.data.id}
                onSynced={() => {
                  detail.reload();
                  list.reload();
                }}
              />
            ) : null}
            {detail.data.receiver ? (
              <Descriptions
                size="small"
                column={1}
                title="收货人"
                items={[
                  { key: 'n', label: '姓名', children: detail.data.receiver.name },
                  {
                    key: 'a',
                    label: '地址',
                    children: [
                      detail.data.receiver.province,
                      detail.data.receiver.city,
                      detail.data.receiver.district,
                      detail.data.receiver.town,
                      detail.data.receiver.address,
                    ]
                      .filter(Boolean)
                      .join(' '),
                  },
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
    </PageShell>
  );
}
