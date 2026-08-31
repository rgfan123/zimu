/**
 * 手工建单（V100 MANUAL 渠道）：运营柜台直录订单并当场路由出发货单。
 *
 * 一个按钮两步走：① POST /api/v1/orders/manual 建单（客户可选——缺省服务端归属
 * 「手工平台客户」MANUAL-PLATFORM，传了则精确绑定既有档案；系统 SKU 直选，
 * 建成即 SKU_MAPPED）→ ② 自动 POST /api/v1/orders/{id}/fulfillment-routing 生成发货单。
 * 两步进度可见；①成功②失败时如实呈现「订单已建 + 路由失败原因」，可用最新版本重试路由。
 * 京东出库单**不在这里提交**——发货单生成后前往发货记录，走既有的人工提交闸门
 * （REAL 模式弹「确认向真实京东提交这张出库单？」）。
 */

import { useCallback, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Alert, Button, Card, Form, Input, Select, Space, Steps, Typography, message } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import PageShell from '@/components/PageShell';
import { errorMessage, newRequestId } from '@/api/client';
import { customersApi, ordersApi, skusApi } from '@/api/endpoints';
import type { MasterDataRecord, SkuRecord } from '@/api/types';
import {
  MANUAL_QUANTITY_PATTERN,
  manualOrderCreateBody,
  manualOrderErrorText,
  manualOrderIdempotencyKey,
  type ManualOrderFormValues,
} from '@/api/manualOrderCreate';
import { useAsync } from '@/hooks/useAsync';

/** 建单成功后随流程携带的订单身份：路由重试与成功指路都只需要这三样 + 版本。 */
interface CreatedOrder {
  id: string;
  orderNo: string;
  sourceRef?: string;
  version: number;
}

type SubmitFlow =
  | { phase: 'IDLE' }
  | { phase: 'CREATING' }
  | { phase: 'ROUTING'; order: CreatedOrder }
  | { phase: 'ROUTE_FAILED'; order: CreatedOrder; reason: string; retrying: boolean }
  | { phase: 'DONE'; order: CreatedOrder; shipmentIds: string[] };

function customerLabel(record: MasterDataRecord): string {
  return `${record.code} · ${record.name}`;
}

function skuLabel(sku: SkuRecord): string {
  return [sku.code, sku.name, sku.attributes.specification].filter(Boolean).join(' · ');
}

/**
 * 系统 SKU 检索选择器（复用 SubstituteSkuAction 的服务端检索模式）：
 * skusApi.list 支持 query 模糊搜索（SKU 编码/商品名/历史别名），只列 active。
 */
function SkuOptionSelect({ value, onChange, disabled }: {
  value?: string;
  onChange?: (value?: string) => void;
  disabled?: boolean;
}) {
  const [options, setOptions] = useState<SkuRecord[]>([]);
  const [searching, setSearching] = useState(false);

  const search = useCallback(async (query: string) => {
    setSearching(true);
    try {
      const page = await skusApi.list({ query: query.trim() || undefined, size: 20 });
      setOptions(page.items.filter((sku) => sku.active));
    } catch {
      setOptions([]);
    } finally {
      setSearching(false);
    }
  }, []);

  return (
    <Select
      showSearch
      allowClear
      disabled={disabled}
      style={{ width: '100%' }}
      placeholder="搜索系统 SKU（编码 / 商品名）"
      filterOption={false}
      loading={searching}
      value={value ?? null}
      onSearch={search}
      onFocus={() => {
        if (options.length === 0) void search('');
      }}
      onChange={(next: string | null) => onChange?.(next ?? undefined)}
      options={options.map((sku) => ({ value: sku.id, label: skuLabel(sku) }))}
    />
  );
}

export default function ManualOrderCreatePage() {
  const [form] = Form.useForm<ManualOrderFormValues>();
  const navigate = useNavigate();
  const [flow, setFlow] = useState<SubmitFlow>({ phase: 'IDLE' });
  // 草稿指纹：同一草稿重复点击是重放，重开一单换新指纹（见 manualOrderIdempotencyKey）。
  const [draftNonce, setDraftNonce] = useState(() => newRequestId());
  const [customerQuery, setCustomerQuery] = useState('');

  // 客户主数据支持服务端分页搜索（GET /api/v1/customers?query=），这里直接接 query；
  // 首页 50 条 + 按需检索，不做全量拉取。
  const customers = useAsync(
    () => customersApi.list({ query: customerQuery.trim() || undefined, page: 0, size: 50 }),
    [customerQuery],
  );
  const activeCustomers = useMemo(
    () => (customers.data?.items ?? []).filter((record) => record.active),
    [customers.data],
  );

  const submitting = flow.phase === 'CREATING' || flow.phase === 'ROUTING';

  /** 选中客户后，档案里的联系人/电话只预填**空着的**收货字段（可改，不覆盖已录内容）。 */
  const prefillReceiverFromCustomer = (customerCode?: string) => {
    if (!customerCode) return;
    const record = activeCustomers.find((candidate) => candidate.code === customerCode);
    const contactName = record?.attributes?.contact_name;
    const contactPhone = record?.attributes?.contact_phone;
    const current = form.getFieldsValue();
    const patch: { name?: string; phone?: string } = {};
    if (typeof contactName === 'string' && contactName && !current.receiver?.name?.trim()) patch.name = contactName;
    if (typeof contactPhone === 'string' && contactPhone && !current.receiver?.phone?.trim()) patch.phone = contactPhone;
    if (Object.keys(patch).length > 0) {
      form.setFieldsValue({ receiver: { ...current.receiver, ...patch } });
    }
  };

  const handleSubmit = async (values: ManualOrderFormValues) => {
    if (flow.phase !== 'IDLE') return;
    let body;
    try {
      body = manualOrderCreateBody(values);
    } catch (invalid) {
      message.error(invalid instanceof Error ? invalid.message : '表单内容不完整');
      return;
    }

    setFlow({ phase: 'CREATING' });
    let order: CreatedOrder;
    try {
      const detail = await ordersApi.createManual(body, {
        idempotencyKey: manualOrderIdempotencyKey(draftNonce, body),
      });
      order = { id: detail.id, orderNo: detail.order_no, sourceRef: detail.source_ref, version: detail.version };
    } catch (err) {
      setFlow({ phase: 'IDLE' });
      message.error(manualOrderErrorText(err));
      return;
    }

    setFlow({ phase: 'ROUTING', order });
    try {
      const routed = await ordersApi.fulfillmentRouting(order.id, order.version);
      setFlow({
        phase: 'DONE',
        order: { ...order, version: routed.order_version },
        shipmentIds: routed.shipment_ids,
      });
    } catch (err) {
      setFlow({ phase: 'ROUTE_FAILED', order, reason: manualOrderErrorText(err), retrying: false });
    }
  };

  /** 路由重试先重读订单拿**当前**版本（VERSION_CONFLICT 后旧版本必然再失败），再按新版本换幂等键重试。 */
  const retryRouting = async () => {
    if (flow.phase !== 'ROUTE_FAILED' || flow.retrying) return;
    const failed = flow;
    setFlow({ ...failed, retrying: true });
    try {
      const fresh = await ordersApi.detail(failed.order.id);
      const routed = await ordersApi.fulfillmentRouting(failed.order.id, fresh.version);
      setFlow({
        phase: 'DONE',
        order: { ...failed.order, version: routed.order_version },
        shipmentIds: routed.shipment_ids,
      });
    } catch (err) {
      setFlow({ ...failed, reason: manualOrderErrorText(err), retrying: false });
    }
  };

  const resetForNextOrder = () => {
    form.resetFields();
    setDraftNonce(newRequestId());
    setFlow({ phase: 'IDLE' });
  };

  /** 发货记录列表不支持按订单定位的 URL 参数，跳转前用消息钉住单号，供列表里人工对号。 */
  const goSubmitJdOutbound = () => {
    if (flow.phase !== 'DONE') return;
    message.info(
      `订单 ${flow.order.orderNo} 的 ${flow.shipmentIds.length} 张发货单已生成，请在列表中点开详情提交京东出库单`,
      6,
    );
    navigate('/fulfillment/shipments');
  };

  const stepItems = useMemo(() => {
    const createStatus = flow.phase === 'CREATING'
      ? 'process' as const
      : flow.phase === 'IDLE' ? 'wait' as const : 'finish' as const;
    const routeStatus = flow.phase === 'ROUTING'
      ? 'process' as const
      : flow.phase === 'DONE'
        ? 'finish' as const
        : flow.phase === 'ROUTE_FAILED' ? 'error' as const : 'wait' as const;
    return [
      { title: '创建手工订单', status: createStatus },
      { title: '路由生成发货单', status: routeStatus },
    ];
  }, [flow.phase]);

  return (
    <PageShell
      title="手工建单"
      description="运营直录 MANUAL 渠道订单：客户可选（默认手工平台客户）+ 系统 SKU 直选，建单成功后自动生成发货单；京东出库单仍需前往发货单详情人工提交。"
    >
      {flow.phase !== 'IDLE' ? (
        <Card size="small">
          <Steps size="small" items={stepItems} />
        </Card>
      ) : null}

      {flow.phase === 'DONE' ? (
        <Card size="small">
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type="success"
              showIcon
              message={`订单 ${flow.order.orderNo} 已建单，生成 ${flow.shipmentIds.length} 张发货单`}
              description={(
                <Space direction="vertical" size={4}>
                  {flow.order.sourceRef ? <span>来源单号：{flow.order.sourceRef}</span> : null}
                  <span>京东仓发货单不会自动出库：请前往发货记录点开新发货单详情，人工提交京东出库单。</span>
                </Space>
              )}
            />
            <Space wrap>
              <Button type="primary" onClick={goSubmitJdOutbound}>前往发货单提交京东出库</Button>
              <Link to={`/orders/${flow.order.id}`}>查看订单详情</Link>
              <Button onClick={resetForNextOrder}>继续录下一单</Button>
            </Space>
          </Space>
        </Card>
      ) : null}

      {flow.phase === 'ROUTE_FAILED' ? (
        <Card size="small">
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Alert
              type="warning"
              showIcon
              message={`订单 ${flow.order.orderNo} 已创建，但发货单尚未生成`}
              description={(
                <Space direction="vertical" size={4}>
                  <span>路由失败原因：{flow.reason}</span>
                  <span>订单不会丢失；处理完阻断原因后可直接重试路由（自动按订单当前版本重试）。</span>
                </Space>
              )}
            />
            <Space wrap>
              <Button type="primary" loading={flow.retrying} onClick={retryRouting}>重试路由</Button>
              <Link to={`/orders/${flow.order.id}`}>查看订单详情</Link>
              <Button onClick={resetForNextOrder}>录下一单</Button>
            </Space>
          </Space>
        </Card>
      ) : null}

      {flow.phase === 'DONE' || flow.phase === 'ROUTE_FAILED' ? null : (
        <Form<ManualOrderFormValues>
          form={form}
          layout="vertical"
          disabled={submitting}
          onFinish={handleSubmit}
          initialValues={{ items: [{}] }}
        >
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Card size="small" title="客户">
              <Form.Item
                name="customer_code"
                label="客户（可选）"
                extra="不选则归属『手工平台客户』——手工单默认不关联客户档案；选了则精确绑定该既有档案（须已登记且启用）。"
                style={{ maxWidth: 480, marginBottom: 0 }}
              >
                <Select
                  showSearch
                  allowClear
                  placeholder="搜索客户（编码 / 名称）"
                  filterOption={false}
                  loading={customers.loading}
                  onSearch={setCustomerQuery}
                  onChange={(value: string | null) => prefillReceiverFromCustomer(value ?? undefined)}
                  options={activeCustomers.map((record) => ({ value: record.code, label: customerLabel(record) }))}
                  notFoundContent={customers.error ? '客户列表加载失败，请重试检索' : undefined}
                />
              </Form.Item>
            </Card>

            <Card size="small" title="收货信息">
              <Space wrap size={12} style={{ width: '100%' }} align="start">
                <Form.Item
                  name={['receiver', 'name']}
                  label="收货人姓名"
                  rules={[{ required: true, whitespace: true, message: '请填写收货人姓名' }]}
                  style={{ width: 220 }}
                >
                  <Input maxLength={128} placeholder="李四" />
                </Form.Item>
                <Form.Item
                  name={['receiver', 'phone']}
                  label="收货电话"
                  rules={[{ required: true, whitespace: true, message: '请填写收货电话' }]}
                  style={{ width: 220 }}
                >
                  <Input maxLength={64} placeholder="139..." />
                </Form.Item>
              </Space>
              <Form.Item
                name={['receiver', 'address']}
                label="收货地址"
                extra="整段录入即可；京东仓发货前另有结构化地址确认闸门。"
                rules={[{ required: true, whitespace: true, message: '请填写收货地址' }]}
                style={{ marginBottom: 0 }}
              >
                <Input.TextArea maxLength={1000} rows={2} placeholder="省市区 + 详细地址" />
              </Form.Item>
            </Card>

            <Card size="small" title="商品行">
              <Form.List name="items">
                {(fields, { add, remove }) => (
                  <Space direction="vertical" size={8} style={{ width: '100%' }}>
                    {fields.map((field) => (
                      <Space key={field.key} align="baseline" wrap style={{ width: '100%' }}>
                        <Form.Item
                          name={[field.name, 'sku_id']}
                          rules={[{ required: true, message: '请选择 SKU' }]}
                          style={{ width: 420, marginBottom: 0 }}
                        >
                          <SkuOptionSelect disabled={submitting} />
                        </Form.Item>
                        <Form.Item
                          name={[field.name, 'quantity']}
                          rules={[
                            { required: true, message: '请填写数量' },
                            { pattern: MANUAL_QUANTITY_PATTERN, message: '数量必须为正整数' },
                          ]}
                          style={{ width: 140, marginBottom: 0 }}
                        >
                          <Input placeholder="数量（正整数）" maxLength={9} />
                        </Form.Item>
                        {fields.length > 1 ? (
                          <Button
                            type="text"
                            icon={<DeleteOutlined />}
                            aria-label="删除本行"
                            onClick={() => remove(field.name)}
                          />
                        ) : null}
                      </Space>
                    ))}
                    <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({})}>
                      添加商品行
                    </Button>
                  </Space>
                )}
              </Form.List>
            </Card>

            <Card size="small" title="备注（可选）">
              <Form.Item name="remark" style={{ marginBottom: 0 }}>
                <Input.TextArea maxLength={2000} rows={2} placeholder="给履约与对账留的说明，随订单存档" />
              </Form.Item>
            </Card>

            <Space>
              <Button type="primary" htmlType="submit" loading={submitting}>
                {flow.phase === 'ROUTING' ? '正在生成发货单…' : flow.phase === 'CREATING' ? '正在建单…' : '建单并生成发货单'}
              </Button>
              <Typography.Text type="secondary">
                提交后依次执行：创建订单 → 生成发货单；重复点击不会重复建单。
              </Typography.Text>
            </Space>
          </Space>
        </Form>
      )}

      {customers.error && flow.phase === 'IDLE' ? (
        <Alert type="error" showIcon message="客户列表加载失败" description={errorMessage(customers.error)} />
      ) : null}
    </PageShell>
  );
}
