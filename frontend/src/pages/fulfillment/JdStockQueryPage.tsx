/**
 * 京东工具 · 京东库存原始查询（GET /api/v1/jd-stock/*，全部只读）。
 * 7 个查询：库存快照 / 库存汇总 / 批次异动 / 级别异动 / 效期商品 / 效期库存 / 店铺库存流水。
 * 结果只展示白名单字段；业务码 2001（权限未开通）单独提示，不当作系统错误。
 */

import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tag,
  Typography,
  message,
} from 'antd';
import { CloudServerOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useSearchParams } from 'react-router-dom';
import PageShell from '@/components/PageShell';
import { apiRequest, errorMessage, type QueryValue } from '@/api/client';
import type { JdQueryResult } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { PageState } from '@/pages/shared/PageState';
import { jdConnectionSemantic } from '@/pages/shared/semanticStatus';
import { jdStockQueryPrefill, type JdStockQueryKind } from './jdQueryPrefill';

type QueryKind = JdStockQueryKind;

interface FieldDef {
  name: string;
  label: string;
  kind: 'text' | 'int' | 'list';
  placeholder?: string;
}

interface QueryDef {
  key: QueryKind;
  label: string;
  path: string;
  fields: FieldDef[];
}

const QUERIES: QueryDef[] = [
  {
    key: 'snapshot',
    label: '库存快照',
    path: '/api/v1/jd-stock/snapshot',
    fields: [
      { name: 'goods_no', label: '商品编码', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'goods_level', label: '商品级别', kind: 'list' },
      { name: 'isv_sku', label: 'ISV SKU', kind: 'list' },
      { name: 'seller_goods_sign', label: '商家商品标识', kind: 'list' },
      { name: 'stock_type', label: '库存类型', kind: 'list' },
      { name: 'above_zero', label: '仅查大于 0', kind: 'int' },
      { name: 'cursor', label: '游标（翻页）', kind: 'text' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'summary',
    label: '库存汇总',
    path: '/api/v1/jd-stock/summary',
    fields: [
      { name: 'goods_no', label: '商品编码', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'goods_level', label: '商品级别', kind: 'list' },
      { name: 'isv_sku', label: 'ISV SKU', kind: 'list' },
      { name: 'stock_type', label: '库存类型', kind: 'list' },
      { name: 'above_zero', label: '仅查大于 0', kind: 'int' },
    ],
  },
  {
    key: 'batchChanges',
    label: '批次异动',
    path: '/api/v1/jd-stock/batch-changes',
    fields: [
      { name: 'warehouse_no', label: '仓库编码', kind: 'text' },
      { name: 'batch_change_no', label: '批次异动单号', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'start_date', label: '开始日期', kind: 'text', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', kind: 'text', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'levelChanges',
    label: '级别异动',
    path: '/api/v1/jd-stock/level-changes',
    fields: [
      { name: 'order_no', label: '级别异动单号', kind: 'list', placeholder: '多个用逗号分隔' },
      { name: 'pre_change_level', label: '异动前级别', kind: 'text' },
      { name: 'changed_level', label: '异动后级别', kind: 'text' },
      { name: 'start_date', label: '开始日期', kind: 'text', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', kind: 'text', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'shelfLifeGoods',
    label: '效期商品',
    path: '/api/v1/jd-stock/shelf-life-goods',
    fields: [
      { name: 'order_type', label: '单据类型', kind: 'text' },
      { name: 'check_order_no', label: '效期盘点单号', kind: 'text' },
      { name: 'start_time', label: '开始时间', kind: 'text', placeholder: '如 2026-08-01 00:00:00' },
      { name: 'end_time', label: '结束时间', kind: 'text', placeholder: '如 2026-08-13 23:59:59' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'shelfLifeInventory',
    label: '效期库存',
    path: '/api/v1/jd-stock/shelf-life-inventory',
    fields: [
      { name: 'warehouse_no', label: '仓库编码', kind: 'text' },
      { name: 'goods_no', label: '商品编码', kind: 'text' },
      { name: 'erp_goods_no', label: 'ERP 商品编码', kind: 'text' },
      { name: 'goods_level', label: '商品级别', kind: 'text' },
      { name: 'status', label: '状态', kind: 'int' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'shopStockFlow',
    label: '店铺库存流水',
    path: '/api/v1/jd-stock/shop-stock-flow',
    fields: [
      { name: 'shop_no', label: '店铺编码', kind: 'text' },
      { name: 'warehouse_no', label: '仓库编码', kind: 'text' },
      { name: 'goods_no', label: '商品编码', kind: 'text' },
      { name: 'start_date', label: '开始日期', kind: 'text', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', kind: 'text', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
];

/** 结果展示白名单：键名归一化（去符号 + 小写）后按查询类型匹配，未收录字段一律不展示。 */
const FIELD_LABELS: Record<QueryKind, Record<string, string>> = {
  snapshot: {
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    stocktype: '库存类型',
    availablequantity: '可用数量',
    onwayquantity: '在途数量',
    occupiedquantity: '占用数量',
    totalquantity: '总数量',
    snapshotstatus: '快照状态',
    snapshottime: '快照时间',
    cursor: '游标',
    total: '总条数',
    totalnum: '总条数',
  },
  summary: {
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    stocktype: '库存类型',
    availablequantity: '可用数量',
    totalquantity: '总数量',
    updatetime: '更新时间',
    total: '总条数',
    totalnum: '总条数',
  },
  batchChanges: {
    batchchangeno: '批次异动单号',
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    goodslevel: '商品级别',
    changenum: '异动数量',
    prechangelot: '异动前批次',
    prechangeproductdate: '异动前生产日期',
    prechangeexpiredate: '异动前效期',
    changedboxno: '异动后箱号',
    changedlot: '异动后批次',
    changedproductdate: '异动后生产日期',
    changedexpiredate: '异动后效期',
    changetime: '异动时间',
    total: '总条数',
    totalnum: '总条数',
  },
  levelChanges: {
    orderno: '级别异动单号',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    prechangelevel: '异动前级别',
    changedlevel: '异动后级别',
    changetime: '异动时间',
    total: '总条数',
    totalnum: '总条数',
  },
  shelfLifeGoods: {
    checkorderno: '效期盘点单号',
    warehouseno: '仓库编码',
    ownerno: '事业部编码',
    createtime: '创建时间',
    goodsno: '商品编码',
    goodsname: '商品名称',
    expiredate: '到期日期',
    productdate: '生产日期',
    status: '状态',
    total: '总条数',
    totalnum: '总条数',
  },
  shelfLifeInventory: {
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    goodsname: '商品名称',
    goodslevel: '商品级别',
    lotno: '批次号',
    productdate: '生产日期',
    expiredate: '到期日期',
    availablequantity: '可用数量',
    status: '状态',
    total: '总条数',
    totalnum: '总条数',
  },
  shopStockFlow: {
    shopno: '店铺编码',
    warehouseno: '仓库编码',
    goodsno: '商品编码',
    shopgoodsno: '店铺商品编码',
    erpgoodsno: 'ERP 商品编码',
    salesplatformgoodsno: '平台商品编码',
    salesplatformorderno: '平台订单号',
    bizno: '业务单号',
    biztype: '业务类型',
    stocknum: '库存数量',
    occupynum: '占用数量',
    stockchangenum: '库存变动',
    occupystockchangenum: '占用变动',
    createtime: '流水时间',
    total: '总条数',
    totalnum: '总条数',
  },
};

/** 京东 ISC 库存查询域账号未开权限的典型业务码（LOP 通用无权限码）。 */
const PERMISSION_DENIED_CODE = '2001';

interface DisplayRow {
  label: string;
  value: string;
}

const RESULT_ROW_LIMIT = 40;

interface JdStockClientStatus {
  client_mode: 'MOCK' | 'REAL';
  credentials_configured: boolean;
  tenant_configured: boolean;
  live_ready: boolean;
}

function scalarValue(value: unknown): string | null {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed || null;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (Array.isArray(value)) {
    const values = value.map(scalarValue);
    return values.every((item) => item !== null) && values.length
      ? (values as string[]).join('、')
      : null;
  }
  return null;
}

function collectRows(value: unknown, labels: Record<string, string>, rows: DisplayRow[]): void {
  if (rows.length > RESULT_ROW_LIMIT || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectRows(item, labels, rows);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length > RESULT_ROW_LIMIT) return;
    const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    const label = labels[normalized];
    const scalar = scalarValue(raw);
    if (label && scalar !== null) {
      rows.push({ label, value: scalar });
    } else if (typeof raw === 'object') {
      collectRows(raw, labels, rows);
    }
  }
}

function buildParams(fields: FieldDef[], values: Record<string, string | number>): Record<string, QueryValue> {
  const params: Record<string, QueryValue> = {};
  for (const field of fields) {
    // InputNumber 回填的是 number，统一转字符串再处理。
    const raw = String(values[field.name] ?? '').trim();
    if (!raw) continue;
    if (field.kind === 'list') {
      const items = raw
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean);
      if (items.length) params[field.name] = items;
    } else if (field.kind === 'int') {
      const parsed = Number(raw);
      if (Number.isFinite(parsed)) params[field.name] = String(Math.trunc(parsed));
    } else {
      params[field.name] = raw;
    }
  }
  return params;
}

export default function JdStockQueryPage() {
  const [searchParams] = useSearchParams();
  const prefill = jdStockQueryPrefill(searchParams);
  const sdkStatus = useAsync(() => apiRequest<JdStockClientStatus>('/api/v1/jd-stock/status'), []);
  const [kind, setKind] = useState<QueryKind>(prefill.kind);
  const [form] = Form.useForm<Record<string, string | number>>();
  const [formValues, setFormValues] = useState<Record<string, string | number>>(prefill.values);
  const [result, setResult] = useState<JdQueryResult | null>(null);
  const [loading, setLoading] = useState(false);

  const def = QUERIES.find((query) => query.key === kind) ?? QUERIES[0];
  const labels = FIELD_LABELS[kind];

  const changeKind = (next: QueryKind) => {
    setKind(next);
    form.resetFields();
    setFormValues({});
    setResult(null);
  };

  const runQuery = async () => {
    setLoading(true);
    try {
      const queryResult = await apiRequest<JdQueryResult>(def.path, {
        params: buildParams(def.fields, formValues),
      });
      setResult(queryResult);
      if (queryResult.success) {
        message.success('查询完成');
      } else if (queryResult.business_code === PERMISSION_DENIED_CODE) {
        message.warning('库存查询权限未开通');
      } else {
        message.warning('查询未完成，请查看下方提示');
      }
    } catch (err) {
      setResult(null);
      message.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const rows: DisplayRow[] = [];
  if (result?.success) collectRows(result.data, labels, rows);
  const rowsTruncated = rows.length > RESULT_ROW_LIMIT;
  const displayedRows = rows.slice(0, RESULT_ROW_LIMIT);

  const permissionDenied = result !== null && !result.success && result.business_code === PERMISSION_DENIED_CODE;
  const mode = sdkStatus.data?.client_mode;

  return (
    <PageShell
      icon={<CloudServerOutlined />}
      title="京东库存原始查询"
      description="库存快照 / 汇总 / 批次异动 / 级别异动 / 效期商品 / 效期库存 / 店铺库存流水；全部为只读查询，不产生任何写操作。"
      actions={
        <Tag color={jdConnectionSemantic(Boolean(sdkStatus.data?.live_ready), mode)}>
          {sdkStatus.loading
            ? '正在确认连接状态'
            : sdkStatus.data?.live_ready
              ? '真实连接已就绪'
              : mode === 'REAL'
                ? '真实连接未就绪'
                : mode === 'MOCK'
                  ? '模拟模式（不代表真实权限）'
                  : '连接状态未知'}
        </Tag>
      }
    >
      <Card size="small">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          {sdkStatus.error ? (
            <PageState
              state="error"
              message="京东库存查询连接状态加载失败"
              description={errorMessage(sdkStatus.error)}
              onRetry={sdkStatus.reload}
            />
          ) : null}
          {mode === 'REAL' && sdkStatus.data && !sdkStatus.data.live_ready ? (
            <Alert
              type="warning"
              showIcon
              message="真实连接尚未就绪"
              description="京东授权或租户信息尚未完整，请联系管理员完成配置后再试。"
            />
          ) : null}

          <Space.Compact style={{ width: '100%', maxWidth: 720 }}>
            <Select
              value={kind}
              onChange={changeKind}
              style={{ width: 180 }}
              options={QUERIES.map((query) => ({ value: query.key, label: query.label }))}
            />
            <Button
              type="primary"
              icon={<SearchOutlined />}
              loading={loading}
              onClick={runQuery}
              style={{ borderTopLeftRadius: 0, borderBottomLeftRadius: 0 }}
            >
              查询
            </Button>
          </Space.Compact>

          <Form
            form={form}
            key={kind}
            layout="vertical"
            initialValues={kind === prefill.kind ? prefill.values : {}}
            style={{ maxWidth: 720 }}
            onValuesChange={(_, all) => setFormValues(all as Record<string, string | number>)}
          >
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              {def.fields.map((field) => (
                <Form.Item key={field.name} name={field.name} label={field.label} style={{ marginBottom: 8 }}>
                  {field.kind === 'int' ? (
                    <InputNumber
                      min={0}
                      style={{ width: '100%' }}
                      placeholder={field.placeholder ?? '整数'}
                    />
                  ) : (
                    <Input placeholder={field.placeholder ?? `请输入${field.label}`} allowClear />
                  )}
                </Form.Item>
              ))}
            </Space>
          </Form>

          {permissionDenied ? (
            <Alert
              type="warning"
              showIcon
              message="库存查询权限未开通"
              description="该账号尚未开通本查询对应的京东物流 ISC 接口权限（业务码 2001）。请在京东物流开放平台开通后再试，或联系管理员处理；这不是系统故障。"
            />
          ) : result && !result.success ? (
            <Alert
              type="error"
              showIcon
              message="查询未完成"
              description={
                <Space direction="vertical" size={4}>
                  <Typography.Text>业务码：{result.business_code ?? '未知'}</Typography.Text>
                  {result.message ? <Typography.Text>{result.message}</Typography.Text> : null}
                </Space>
              }
            />
          ) : result && result.success ? (
            <Alert
              type="success"
              showIcon
              message={mode === 'MOCK' ? '模拟查询完成（不代表真实权限）' : '查询完成'}
              description={
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  {rowsTruncated ? (
                    <Alert
                      type="warning"
                      showIcon
                      message={`仅展示前 ${RESULT_ROW_LIMIT} 条，请调整查询条件或使用分页参数继续查询。`}
                    />
                  ) : null}
                  {displayedRows.length ? (
                    <Descriptions
                      size="small"
                      column={{ xs: 1, sm: 2 }}
                      items={displayedRows.map((row, index) => ({
                        key: `${row.label}-${index}`,
                        label: row.label,
                        children: row.value,
                      }))}
                    />
                  ) : (
                    <Typography.Text type="secondary">本次结果没有可公开展示的业务字段。</Typography.Text>
                  )}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    请求标识：{result.request_id ?? '—'}　|　业务码：{result.business_code ?? '—'}
                  </Typography.Text>
                </Space>
              }
            />
          ) : null}
        </Space>
      </Card>

      <Card size="small">
        <Space>
          <Typography.Text type="secondary">
            结果仅展示白名单业务字段；页面为调试与对账用，业务调用请走受审计的履约用例。
          </Typography.Text>
          <div style={{ flex: 1 }} />
          <Button icon={<ReloadOutlined />} onClick={sdkStatus.reload}>
            刷新连接状态
          </Button>
        </Space>
      </Card>
    </PageShell>
  );
}
