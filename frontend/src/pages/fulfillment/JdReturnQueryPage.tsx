/**
 * 履约中心 · 京东退货退供查询（GET /api/v1/jd-return/*，只读）。
 * 退货入库单列表 / 详情、退供单查询：接口选择 + 参数表单 + 白名单字段结果展示。
 * 联系方式等个人信息由后端在 HTTP 边界脱敏，本页只展示白名单业务字段；
 * 未授权（业务码 2001）时明确提示「权限未开通」，不当作系统错误。
 * 导航由主 agent 接线，本文件不注册路由。
 */

import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, Select, Space, Tag, Typography, message } from 'antd';
import { SearchOutlined, SwapOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { apiRequest, errorMessage } from '@/api/client';
import type { QueryValue } from '@/api/client';
import { jdWarehouseApi } from '@/api/endpoints';
import type { JdQueryResult } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { READ_ONLY_TAG_COLOR } from '@/pages/shared/semanticStatus';

type QueryKind = 'rtwList' | 'rtwDetail' | 'returnToSupplier';

const KIND_OPTIONS: { value: QueryKind; label: string }[] = [
  { value: 'rtwList', label: '退货入库单列表' },
  { value: 'rtwDetail', label: '退货入库单详情' },
  { value: 'returnToSupplier', label: '退供单查询' },
];

/** 参数表单按接口切换；空值会被 apiRequest 忽略，不发给后端。 */
const KIND_FIELDS: Record<QueryKind, { name: string; label: string; required?: boolean; placeholder?: string }[]> = {
  rtwList: [
    { name: 'return_to_warehouse_no', label: '退货入库单号' },
    { name: 'erp_return_to_warehouse_no', label: '系统退货入库单号' },
    { name: 'delivery_no', label: '送货单号' },
    { name: 'out_store_no', label: '出库单号' },
  ],
  rtwDetail: [
    {
      name: 'erp_return_to_warehouse_no',
      label: '系统退货入库单号',
      required: true,
      placeholder: '如 ZM-RTW-001（列表行的系统退货入库单号可点击直达）',
    },
  ],
  returnToSupplier: [
    {
      name: 'erp_return_to_supplier_no',
      label: '系统退供单号',
      required: true,
      placeholder: '如 ZM-RTS-001',
    },
  ],
};

const FLAG_OPTIONS = [
  { value: 1, label: '返回' },
  { value: 0, label: '不返回' },
];

/** 结果展示字段白名单：归一化后的字段名（小写、去下划线）→ 中文标签。 */
const FIELD_LABELS: Record<string, string> = {
  // 退货入库单（列表 / 详情）
  returntowarehouseno: '退货入库单号',
  erpreturntowarehouseno: '系统退货入库单号',
  deliveryno: '送货单号',
  outstoreno: '出库单号',
  ownerno: '事业部编码',
  warehouseno: '仓库编码',
  source: '来源',
  returnreason: '退货原因',
  status: '状态',
  createtime: '创建时间',
  updatetime: '更新时间',
  createuser: '创建人',
  productscode: '品类编码',
  billingmode: '计费方式',
  receiveboxnum: '收货箱数',
  logicalinventoryfactor: '逻辑库存系数',
  erpdeliveryno: '系统送货单号',
  twicewaybill: '二次运单号',
  packageno: '包裹号',
  salesplatformno: '销售平台编码',
  salesplatformname: '销售平台名称',
  erpshopname: '系统店铺名称',
  goodsno: '商品编码',
  erpgoodsno: '系统商品编码',
  orderline: '订单行',
  goodslevel: '商品等级',
  planquantity: '计划数量',
  realquantity: '实际数量',
  receivedweight: '实收重量',
  // 退供单
  returntosupplierno: '退供单号',
  erpreturntosupplierno: '系统退供单号',
  supplierno: '供应商编码',
  deliverymode: '交货方式',
  operatortime: '操作时间',
  operatoruser: '操作人',
  remark: '备注',
  productsname: '品类名称',
  billingmodename: '计费方式名称',
  tcorderno: '运输中心单号',
  batchno: '批次号',
  serialno: '序列号',
};

interface Entry {
  label: string;
  value: string;
}

const jdReturnApi = {
  rtwOrders: (params: Record<string, QueryValue>) =>
    apiRequest<JdQueryResult>('/api/v1/jd-return/rtw-orders', { params }),
  rtwOrderDetail: (erpReturnToWarehouseNo: string) =>
    apiRequest<JdQueryResult>(
      `/api/v1/jd-return/rtw-orders/${encodeURIComponent(erpReturnToWarehouseNo)}`,
    ),
  returnToSupplier: (erpReturnToSupplierNo: string) =>
    apiRequest<JdQueryResult>(
      `/api/v1/jd-return/return-to-suppliers/${encodeURIComponent(erpReturnToSupplierNo)}`,
    ),
};

function normalizeKey(key: string): string {
  return key.toLowerCase().replace(/_/g, '');
}

function collectEntries(value: unknown, prefix: string, out: Entry[]): void {
  if (value === null || value === undefined) return;
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      if (item !== null && typeof item === 'object') {
        collectEntries(item, `${prefix}${index + 1}`, out);
      }
    });
    return;
  }
  if (typeof value === 'object') {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      const label = FIELD_LABELS[normalizeKey(key)];
      if (!label) continue;
      if (item !== null && typeof item === 'object') {
        collectEntries(item, `${prefix}${label} · `, out);
      } else {
        out.push({ label: `${prefix}${label}`, value: String(item) });
      }
    }
    return;
  }
  out.push({ label: prefix, value: String(value) });
}

function tableColumns(data: Record<string, unknown>[]): { key: string; title: string }[] {
  const seen = new Set<string>();
  const columns: { key: string; title: string }[] = [];
  for (const item of data) {
    for (const key of Object.keys(item)) {
      const normalized = normalizeKey(key);
      if (FIELD_LABELS[normalized] && !seen.has(normalized)) {
        seen.add(normalized);
        columns.push({ key, title: FIELD_LABELS[normalized] });
      }
    }
  }
  return columns;
}

export default function JdReturnQueryPage() {
  const [kind, setKind] = useState<QueryKind>('rtwList');
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<JdQueryResult | null>(null);
  const sdkStatus = useAsync(() => jdWarehouseApi.status(), []);

  const execute = async (target: QueryKind, values: Record<string, unknown>) => {
    const params = values as Record<string, QueryValue>;
    setLoading(true);
    try {
      const outcome =
        target === 'rtwList'
          ? await jdReturnApi.rtwOrders(params)
          : target === 'rtwDetail'
            ? await jdReturnApi.rtwOrderDetail(String(values.erp_return_to_warehouse_no ?? ''))
            : await jdReturnApi.returnToSupplier(String(values.erp_return_to_supplier_no ?? ''));
      setResult(outcome);
    } catch (err) {
      setResult(null);
      message.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const runQuery = async () => {
    const values = await form.validateFields().catch(() => null);
    if (!values) return;
    await execute(kind, values);
  };

  const openDetail = async (erpReturnToWarehouseNo: string) => {
    setKind('rtwDetail');
    form.setFieldsValue({ erp_return_to_warehouse_no: erpReturnToWarehouseNo });
    await execute('rtwDetail', { erp_return_to_warehouse_no: erpReturnToWarehouseNo });
  };

  const permissionDenied = result !== null && !result.success && result.business_code === '2001';
  const failed = result !== null && !result.success && !permissionDenied;
  const rows = Array.isArray(result?.data) ? (result.data as Record<string, unknown>[]) : [];
  const entries: Entry[] = [];
  if (result?.success && result.data !== null && typeof result.data === 'object' && !Array.isArray(result.data)) {
    collectEntries(result.data, '', entries);
  }
  const columns: ColumnsType<Record<string, unknown>> = tableColumns(rows).map((column) => ({
    title: column.title,
    dataIndex: column.key,
    key: column.key,
    render: (value: unknown) => {
      if (value === null || value === undefined || typeof value === 'object') return '—';
      const normalized = normalizeKey(column.key);
      if (normalized === 'erpreturntowarehouseno' && String(value)) {
        return (
          <Typography.Link onClick={() => openDetail(String(value))}>{String(value)}</Typography.Link>
        );
      }
      return String(value);
    },
  }));

  return (
    <PageShell
      icon={<SwapOutlined />}
      title="京东退货退供查询"
      description="退货入库单列表 / 详情、退供单查询；只读，不创建或修改任何单据。"
      actions={
        sdkStatus.data ? (
          <Tag color={sdkStatus.data.client_mode === 'REAL' ? READ_ONLY_TAG_COLOR : 'default'}>
            {sdkStatus.data.client_mode === 'REAL' ? '真实连接' : '模拟模式'}
          </Tag>
        ) : null
      }
    >
      <FilterBar
        actions={
          <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={runQuery}>
            查询
          </Button>
        }
      >
        <Form
          form={form}
          layout="inline"
          style={{ rowGap: 12 }}
          onFinish={runQuery}
        >
          <Form.Item label="接口" style={{ marginBottom: 0 }}>
            <Select<QueryKind>
              value={kind}
              style={{ width: 200 }}
              options={KIND_OPTIONS}
              onChange={(next) => {
                setKind(next);
                form.resetFields();
                setResult(null);
              }}
            />
          </Form.Item>
          {KIND_FIELDS[kind].map((field) => (
            <Form.Item
              key={field.name}
              name={field.name}
              label={field.label}
              style={{ marginBottom: 0 }}
              rules={field.required ? [{ required: true, message: `请输入${field.label}` }] : undefined}
            >
              <Input style={{ width: 240 }} placeholder={field.placeholder} allowClear />
            </Form.Item>
          ))}
          {kind !== 'rtwDetail' ? (
            <Form.Item label="返回明细" style={{ marginBottom: 0 }} tooltip="不填则使用京东接口默认行为；详情 / 退供单后端默认返回明细与批次。">
              <Select style={{ width: 120 }} options={FLAG_OPTIONS} allowClear placeholder="默认" />
            </Form.Item>
          ) : null}
        </Form>
        <Typography.Text type="secondary" style={{ display: 'block', width: '100%' }}>
          结果只展示白名单业务字段；联系方式等个人信息已由后端脱敏，不在此页展示。
        </Typography.Text>
      </FilterBar>

      {permissionDenied ? (
        <Alert
          type="warning"
          showIcon
          message="权限未开通"
          description={
            <Space direction="vertical" size={4}>
              <Typography.Text>
                当前应用尚未在京东开放平台开通该查询接口的访问权限（业务码 2001）。
              </Typography.Text>
              <Typography.Text type="secondary">
                请联系管理员在京东开放平台申请开通相应接口权限后重试；这不是系统故障。
              </Typography.Text>
              {result?.request_id ? (
                <Typography.Text type="secondary" style={{ fontVariantNumeric: 'tabular-nums' }}>
                  requestId：{result.request_id}
                </Typography.Text>
              ) : null}
            </Space>
          }
        />
      ) : null}

      {failed ? (
        <Alert
          type="error"
          showIcon
          message="查询失败"
          description={
            <Space direction="vertical" size={4}>
              <Typography.Text>{result?.message || '京东服务暂时不可用，请稍后重试'}</Typography.Text>
              <Typography.Text type="secondary">
                业务码：{result?.business_code ?? '未知'}
                {result?.request_id ? `；requestId：${result.request_id}` : ''}
              </Typography.Text>
            </Space>
          }
        />
      ) : null}

      {result?.success ? (
        <Card size="small" title={`查询成功（业务码 ${result.business_code}）`} extra={result.request_id ? `requestId：${result.request_id}` : null}>
          {result.data === null || result.data === undefined ? (
            <Typography.Text type="secondary">本次结果没有可展示的业务字段。</Typography.Text>
          ) : Array.isArray(result.data) ? (
            <DataTable<Record<string, unknown>>
              rowKey={(_record, index) => `${index}`}
              columns={columns}
              dataSource={rows}
              loading={loading}
              size="middle"
              pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
            />
          ) : entries.length ? (
            <Descriptions
              size="small"
              column={{ xs: 1, sm: 2 }}
              bordered
              items={entries.map((entry, index) => ({
                key: `${entry.label}-${index}`,
                label: entry.label,
                children: entry.value,
              }))}
            />
          ) : (
            <Typography.Text type="secondary">本次结果没有可展示的业务字段。</Typography.Text>
          )}
        </Card>
      ) : null}
    </PageShell>
  );
}
