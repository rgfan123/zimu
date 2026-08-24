/**
 * 履约中心 · 京东序列号查询（只读 SDK 作业面，GET /api/v1/jd-serial/*）。
 * 四个只读查询：序列号查询（mall）/ 条件查询（condition）/ 流向查询（flow）/ 内部查询（inside）。
 * 页面仅作为“系统管理 → 京东工具”的原始查询入口，不作为库存一级业务板块。
 * 后端 MOCK 模式（app.jd.client-mode=MOCK，默认）返回稳定假数据，business_code=MOCK_SUCCESS。
 * 未授权（如业务码 2001）时明确提示「权限未开通」，不当作系统错误。
 */

import { useState } from 'react';
import { Alert, Button, Card, Descriptions, Form, Input, InputNumber, Select, Space, Tag, Typography, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import PageShell from '@/components/PageShell';
import { apiRequest, errorMessage } from '@/api/client';
import type { JdQueryResult } from '@/api/types';
import { jdSerialQueryPrefill, type JdSerialQueryKind } from './jdQueryPrefill';
import { READ_ONLY_TAG_COLOR } from '@/pages/shared/semanticStatus';

type QueryKind = JdSerialQueryKind;

interface FieldDef {
  name: string;
  label: string;
  type: 'text' | 'number';
  placeholder?: string;
  required?: boolean;
}

interface QueryDef {
  label: string;
  description: string;
  path: string;
  fields: FieldDef[];
}

const QUERY_DEFS: Record<QueryKind, QueryDef> = {
  mall: {
    label: '序列号查询',
    description: '按订单/时间范围分页查询京东商城序列号明细（queryJDMallSerialByPage）。',
    path: 'mall',
    fields: [
      { name: 'order_no', label: '订单号', type: 'text', placeholder: '京东订单号或系统单号' },
      { name: 'enterprise_order_no', label: '企业订单号', type: 'text', placeholder: '可选' },
      { name: 'owner_no', label: '事业部编码', type: 'text', placeholder: '留空使用配置的事业部' },
      { name: 'start_date', label: '开始日期', type: 'text', placeholder: 'yyyy-MM-dd' },
      { name: 'end_date', label: '结束日期', type: 'text', placeholder: 'yyyy-MM-dd' },
      { name: 'page_size', label: '每页条数', type: 'number' },
      { name: 'current_page', label: '当前页', type: 'number' },
    ],
  },
  condition: {
    label: '序列号条件查询',
    description: '按事业部、仓库、业务类型等条件分页查询序列号（queryPageSerialByOwnerNoAndCondition）。',
    path: 'condition',
    fields: [
      { name: 'biz_type', label: '业务类型', type: 'number', placeholder: '如 10=出库' },
      { name: 'query_type', label: '查询类型', type: 'number' },
      { name: 'owner_no', label: '事业部编码', type: 'text', placeholder: '留空使用配置的事业部' },
      { name: 'warehouse_no', label: '仓库编码', type: 'text' },
      { name: 'start_date', label: '开始日期', type: 'text', placeholder: 'yyyy-MM-dd' },
      { name: 'end_date', label: '结束日期', type: 'text', placeholder: 'yyyy-MM-dd' },
      { name: 'current_page', label: '当前页', type: 'number' },
      { name: 'page_size', label: '每页条数', type: 'number' },
    ],
  },
  flow: {
    label: '序列号流向查询',
    description: '按商品编码 + 序列号查询出入库流向（querySerialBySkuAndSerial）。',
    path: 'flow',
    fields: [
      { name: 'goods_no', label: '商品编码', type: 'text', placeholder: '必填', required: true },
      { name: 'serial_no', label: '序列号', type: 'text', placeholder: '必填', required: true },
      { name: 'query_type', label: '查询类型', type: 'number' },
    ],
  },
  inside: {
    label: '序列号内部查询',
    description: '按商品编码分页查询在库序列号（queryInStockSidBySku）。',
    path: 'inside',
    fields: [
      { name: 'goods_no', label: '商品编码', type: 'text', placeholder: '必填', required: true },
      { name: 'query_type', label: '查询类型', type: 'number' },
      { name: 'page_size', label: '每页条数', type: 'number' },
      { name: 'current_page', label: '当前页', type: 'number' },
    ],
  },
};

/** 白名单：normalized 字段名（去符号、小写）→ 中文标签。页面只展示这些已确认的业务字段。 */
const FIELD_LABELS: Record<string, string> = {
  sku: 'SKU',
  sn: '序列号',
  serial: '序列号',
  ownerno: '事业部编码',
  ownername: '事业部名称',
  orderno: '订单号',
  operatetime: '操作时间',
  state: '状态',
  packagenumber: '包裹号',
  enterpriseorderno: '企业订单号',
  totalnum: '总条数',
  goodsno: '商品编码',
  biztype: '业务类型',
  biztypename: '业务类型名称',
  warehouseno: '仓库编码',
  warehousename: '仓库名称',
  createtime: '创建时间',
  outorderno: '出库单号',
  outwarehouseno: '出库仓编码',
  outwarehousename: '出库仓名称',
  outordertype: '出库单类型',
  outtime: '出库时间',
  intoorderno: '入库单号',
  intowarehouseno: '入库仓编码',
  intowarehousename: '入库仓名称',
  inordertype: '入库单类型',
  intotime: '入库时间',
  status: '状态',
  currentpage: '当前页',
  pagesize: '每页条数',
  serialnos: '序列号列表',
};

const KIND_WHITELISTS: Record<QueryKind, Set<string>> = {
  mall: new Set(['sku', 'sn', 'ownerno', 'ownername', 'orderno', 'operatetime', 'state', 'packagenumber', 'enterpriseorderno', 'totalnum']),
  condition: new Set(['orderno', 'goodsno', 'serial', 'biztype', 'biztypename', 'warehouseno', 'warehousename', 'createtime', 'totalnum']),
  flow: new Set(['goodsno', 'serial', 'outorderno', 'outwarehouseno', 'outwarehousename', 'outordertype', 'outtime', 'intoorderno', 'intowarehouseno', 'intowarehousename', 'inordertype', 'intotime', 'status']),
  inside: new Set(['totalnum', 'currentpage', 'pagesize', 'serialnos']),
};

const KIND_ORDER: QueryKind[] = ['mall', 'condition', 'flow', 'inside'];

const DEFAULT_VALUES: Partial<Record<QueryKind, Record<string, number>>> = {
  mall: { page_size: 20, current_page: 1 },
  condition: { current_page: 1, page_size: 20 },
  inside: { page_size: 20, current_page: 1 },
};

interface DisplayRow {
  label: string;
  value: string;
}

/** 业务码 2001（或消息含权限字样）视为「权限未开通」，不是系统错误。 */
function isPermissionDenied(result: JdQueryResult): boolean {
  if (result.business_code === '2001') return true;
  return /权限|permission|authoriz/i.test(result.message ?? '');
}

function normalizeKey(key: string): string {
  return key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
}

function displayValue(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw !== 'object') return String(raw);
  if (Array.isArray(raw) && raw.every((item) => typeof item !== 'object' || item === null)) {
    return raw.length ? raw.join(', ') : null;
  }
  return null;
}

function collectWhitelistedRows(kind: QueryKind, value: unknown, rows: DisplayRow[], seen: Set<string>): void {
  if (rows.length >= 24 || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectWhitelistedRows(kind, item, rows, seen);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= 24) return;
    const normalized = normalizeKey(key);
    if (KIND_WHITELISTS[kind].has(normalized)) {
      const label = FIELD_LABELS[normalized];
      const scalar = displayValue(raw);
      if (label && scalar !== null) {
        const dedupe = `${label}|${scalar}`;
        if (!seen.has(dedupe)) {
          seen.add(dedupe);
          rows.push({ label, value: scalar });
        }
      }
    } else if (typeof raw === 'object') {
      collectWhitelistedRows(kind, raw, rows, seen);
    }
  }
}

interface QueryState {
  kind: QueryKind;
  result: JdQueryResult;
}

export default function JdSerialQueryPage() {
  const [searchParams] = useSearchParams();
  const prefill = jdSerialQueryPrefill(searchParams);
  const [kind, setKind] = useState<QueryKind>(prefill.kind);
  const [form] = Form.useForm();
  const [sdkResult, setSdkResult] = useState<QueryState | null>(null);
  const [loading, setLoading] = useState(false);

  const def = QUERY_DEFS[kind];

  const handleKindChange = (next: QueryKind) => {
    setKind(next);
    setSdkResult(null);
  };

  const handleQuery = async (values: Record<string, unknown>) => {
    const params: Record<string, string | number> = {};
    for (const [name, value] of Object.entries(values)) {
      if (value === undefined || value === null || value === '') continue;
      params[name] = typeof value === 'number' ? value : String(value);
    }
    setLoading(true);
    try {
      const result = await apiRequest<JdQueryResult>(`/api/v1/jd-serial/${def.path}`, { params });
      setSdkResult({ kind, result });
      if (result.success) {
        message.success(`${def.label}完成`);
      } else if (isPermissionDenied(result)) {
        message.warning('权限未开通，请先为当前京东账号开通序列号查询接口权限');
      } else {
        message.warning(`${def.label}未完成（业务码 ${result.business_code}）`);
      }
    } catch (err) {
      message.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const rows: DisplayRow[] = [];
  if (sdkResult?.result.success) {
    collectWhitelistedRows(sdkResult.kind, sdkResult.result.data, rows, new Set());
  }
  const permissionDenied = sdkResult ? isPermissionDenied(sdkResult.result) : false;
  const mockMode = sdkResult?.result.business_code === 'MOCK_SUCCESS';

  return (
    <PageShell
      icon={<SearchOutlined />}
      title="京东序列号查询"
      description="只读查询域：序列号查询 / 条件查询 / 流向查询 / 内部查询；不会在此页面发起任何写操作。"
      actions={<Tag color={READ_ONLY_TAG_COLOR}>只读</Tag>}
    >
      <Card size="small">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space size={12} wrap>
            <Select<QueryKind>
              style={{ width: 180 }}
              value={kind}
              onChange={handleKindChange}
              options={KIND_ORDER.map((key) => ({ value: key, label: QUERY_DEFS[key].label }))}
            />
            <Typography.Text type="secondary">{def.description}</Typography.Text>
          </Space>

          <Form
            key={kind}
            form={form}
            layout="inline"
            initialValues={{
              ...DEFAULT_VALUES[kind],
              ...(kind === prefill.kind ? prefill.values : {}),
            }}
            onFinish={handleQuery}
            style={{ rowGap: 12 }}
          >
            {def.fields.map((field) => (
              <Form.Item
                key={field.name}
                name={field.name}
                label={field.label}
                rules={field.required ? [{ required: true, message: `请填写${field.label}` }] : undefined}
              >
                {field.type === 'number' ? (
                  <InputNumber min={1} style={{ width: 130 }} placeholder={field.placeholder} />
                ) : (
                  <Input style={{ width: 190 }} placeholder={field.placeholder} />
                )}
              </Form.Item>
            ))}
            <Form.Item>
              <Button type="primary" htmlType="submit" icon={<SearchOutlined />} loading={loading}>
                查询
              </Button>
            </Form.Item>
          </Form>

          {sdkResult ? (
            sdkResult.result.success ? (
              <Alert
                type="success"
                showIcon
                message={
                  <Space size={8}>
                    <span>{def.label}完成</span>
                    {mockMode ? <Tag>模拟数据（不代表真实权限）</Tag> : null}
                  </Space>
                }
                description={
                  rows.length ? (
                    <Descriptions
                      size="small"
                      column={{ xs: 1, sm: 2 }}
                      items={rows.map((row, index) => ({
                        key: `${row.label}-${index}`,
                        label: row.label,
                        children: row.value,
                      }))}
                    />
                  ) : (
                    <Typography.Text type="secondary">本次结果没有可展示的业务字段。</Typography.Text>
                  )
                }
              />
            ) : permissionDenied ? (
              <Alert
                type="warning"
                showIcon
                message="权限未开通"
                description="当前京东账号尚未开通该序列号查询接口的调用权限（业务码 2001）。请在京东商家后台或联系管理员完成接口授权后重试；这不是系统故障。"
              />
            ) : (
              <Alert
                type="error"
                showIcon
                message={`${def.label}未完成`}
                description={`业务码 ${sdkResult.result.business_code}：${sdkResult.result.message ?? '未知错误'}。请核对查询条件后重试；如持续失败请联系管理员。`}
              />
            )
          ) : null}
        </Space>
      </Card>
    </PageShell>
  );
}
