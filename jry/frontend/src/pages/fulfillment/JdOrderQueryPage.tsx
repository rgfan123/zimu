/**
 * 京东工具 · 专业单据查询（GET /api/v1/jd-order/*，全部只读）。
 * 8 个查询：调整单 / 销毁单 / 异常单 / 采购单 / 加工单 / 作业关联 / 配送时效 / 同城轨迹。
 * 结果只展示白名单字段（收件人、电话、地址等个人字段不出现）；
 * 业务码 2001 或消息含权限字样时明确提示「权限未开通」，不当作系统错误。
 * 后端 MOCK 模式（app.jd.client-mode=MOCK，默认）返回稳定假数据，business_code=MOCK_SUCCESS。
 */

import { useState } from 'react';
import {
  App as AntApp,
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
} from 'antd';
import { FileSearchOutlined, SearchOutlined } from '@ant-design/icons';
import { apiRequest, errorMessage, type QueryValue } from '@/api/client';
import type { JdQueryResult } from '@/api/types';
import { READ_ONLY_TAG_COLOR, TOOL_CATEGORY_TAG_COLOR } from '@/pages/shared/semanticStatus';
import { saasVisualTokens } from '@/theme/saasTheme';

type QueryKind =
  | 'adjustment'
  | 'destroy'
  | 'exception'
  | 'purchase'
  | 'processed'
  | 'operateRelation'
  | 'deliveryTime'
  | 'cityTrack';

interface FieldDef {
  name: string;
  label: string;
  kind: 'text' | 'int';
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
    key: 'adjustment',
    label: '调整单',
    path: '/api/v1/jd-order/adjustments',
    fields: [
      { name: 'adjustment_no', label: '调整单号', kind: 'text' },
      { name: 'erp_adjustment_no', label: 'ERP 调整单号', kind: 'text' },
      { name: 'start_time', label: '开始时间', kind: 'text', placeholder: '如 2026-08-01 00:00:00' },
      { name: 'end_time', label: '结束时间', kind: 'text', placeholder: '如 2026-08-13 23:59:59' },
      { name: 'status', label: '状态', kind: 'int' },
      { name: 'biz_type', label: '业务类型', kind: 'int' },
    ],
  },
  {
    key: 'destroy',
    label: '销毁单',
    path: '/api/v1/jd-order/destroy-orders',
    fields: [
      { name: 'destroy_no', label: '销毁单号', kind: 'text' },
      { name: 'erp_destroy_no', label: 'ERP 销毁单号', kind: 'text' },
      { name: 'destroy_item_list_flag', label: '返回销毁明细标志', kind: 'int' },
      { name: 'destroy_batch_item_list_flag', label: '返回批次明细标志', kind: 'int' },
      { name: 'return_destroy_data_flag', label: '返回销毁数据标志', kind: 'int' },
    ],
  },
  {
    key: 'exception',
    label: '异常单',
    path: '/api/v1/jd-order/exceptions',
    fields: [
      { name: 'order_type', label: '单据类型', kind: 'text' },
      { name: 'biz_type', label: '业务类型', kind: 'text' },
      { name: 'erp_order_no', label: 'ERP 订单号', kind: 'text' },
      { name: 'order_no', label: '订单号', kind: 'text' },
      { name: 'exception_code', label: '异常编码', kind: 'text' },
      { name: 'start_date', label: '开始日期', kind: 'text', placeholder: '如 2026-08-01' },
      { name: 'end_date', label: '结束日期', kind: 'text', placeholder: '如 2026-08-13' },
      { name: 'current_page', label: '页码', kind: 'int' },
      { name: 'page_size', label: '每页条数', kind: 'int' },
    ],
  },
  {
    key: 'purchase',
    label: '采购单',
    path: '/api/v1/jd-order/purchase-orders',
    fields: [
      { name: 'purchase_no', label: '采购单号', kind: 'text' },
      { name: 'erp_purchase_no', label: 'ERP 采购单号', kind: 'text' },
      { name: 'batch_purchase_no', label: '批次采购单号', kind: 'text' },
      { name: 'purchase_item_flag', label: '采购明细标志', kind: 'int' },
      { name: 'quality_inspection_item_flag', label: '质检明细标志', kind: 'int' },
      { name: 'quality_inspection_err_item_flag', label: '质检异常明细标志', kind: 'int' },
      { name: 'purchase_bat_attr_flag', label: '批次属性标志', kind: 'int' },
      { name: 'purchase_item_reject_flag', label: '拒收明细标志', kind: 'int' },
      { name: 'serial_no_model_flag', label: '序列号标志', kind: 'int' },
      { name: 'purchase_book_flag', label: '采购册标志', kind: 'int' },
    ],
  },
  {
    key: 'processed',
    label: '加工单',
    path: '/api/v1/jd-order/processed-orders',
    fields: [
      { name: 'processed_no', label: '加工单号', kind: 'text' },
      { name: 'erp_processed_no', label: 'ERP 加工单号', kind: 'text' },
    ],
  },
  {
    key: 'operateRelation',
    label: '作业关联',
    path: '/api/v1/jd-order/operate-relations',
    fields: [
      { name: 'erp_order_no', label: 'ERP 订单号', kind: 'text' },
      { name: 'order_type', label: '单据类型', kind: 'text' },
    ],
  },
  {
    key: 'deliveryTime',
    label: '配送时效',
    path: '/api/v1/jd-order/delivery-times',
    fields: [
      { name: 'waybill_no', label: '运单号', kind: 'text' },
      { name: 'customer_code', label: '客户编码', kind: 'text' },
      { name: 'shunt', label: '分单标识', kind: 'text' },
      { name: 'dynamic_time_flag', label: '动态时效标志', kind: 'text' },
    ],
  },
  {
    key: 'cityTrack',
    label: '同城轨迹',
    path: '/api/v1/jd-order/city-tracks',
    fields: [
      { name: 'delivery_no', label: '配送单号', kind: 'text' },
      { name: 'customer_code', label: '客户编码', kind: 'text' },
    ],
  },
];

/**
 * 结果展示白名单：键名归一化（去符号 + 小写）后按查询类型匹配，未收录字段一律不展示。
 * 字段名同时覆盖 Mock 响应（如 status、promiseTime、eclpNo）与真实 SDK DTO 响应；
 * 收件人、电话、地址等个人字段（receiverInfo / transporterPhone 等）不在白名单内。
 */
const FIELD_LABELS: Record<QueryKind, Record<string, string>> = {
  adjustment: {
    adjustmentno: '调整单号',
    erpadjustmentno: 'ERP 调整单号',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    status: '状态',
    biztype: '业务类型',
    createtime: '创建时间',
  },
  destroy: {
    destroyno: '销毁单号',
    erpdestroyno: 'ERP 销毁单号',
    status: '状态',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    deliverymode: '配送方式',
    destroytype: '销毁类型',
    destroymode: '销毁方式',
    destroyreason: '销毁原因',
    destroycompanyno: '销毁公司编码',
    createuser: '创建人',
  },
  exception: {
    totalnum: '总条数',
    orderno: '订单号',
    erporderno: 'ERP 订单号',
    exceptioncode: '异常编码',
    exceptionmessage: '异常信息',
    exceptionreason: '异常原因',
    solution: '解决方案',
    pausetime: '暂停时间',
    erpcreatetime: 'ERP 创建时间',
    createtime: '创建时间',
    ordertype: '单据类型',
    sellername: '商家名称',
    ownername: '事业部名称',
    warehousename: '仓库名称',
    status: '状态',
  },
  purchase: {
    purchaseno: '采购单号',
    erppurchaseno: 'ERP 采购单号',
    status: '状态',
    ownerno: '事业部编码',
    warehouseno: '仓库编码',
    supplierno: '供应商编码',
    purchaseorderstatus: '采购单状态',
    createtime: '创建时间',
    completetime: '完成时间',
    storagestatus: '入库状态',
    productname: '商品名称',
    billingmode: '计费方式',
    receiveboxnumber: '收货箱数',
    totalapplyprice: '申请总金额',
    totalrealprice: '实付总金额',
    createuser: '创建人',
    erpwarehouseno: 'ERP 仓库编码',
    grossweight: '毛重',
    volume: '体积',
  },
  processed: {
    processedno: '加工单号',
    erpprocessedno: 'ERP 加工单号',
    status: '状态',
    processedtype: '加工类型',
    ownerno: '事业部编码',
    ownername: '事业部名称',
    warehouseno: '仓库编码',
    warehousename: '仓库名称',
    sellerno: '商家编码',
    sellername: '商家名称',
    processstatus: '加工状态',
    updatetime: '更新时间',
  },
  operateRelation: {
    eclpno: 'ECLP 单号',
    orderno: '订单号',
    erporderno: 'ERP 订单号',
    ordertype: '单据类型',
  },
  deliveryTime: {
    waybillno: '运单号',
    promisetime: '承诺时效',
    trendspredicttime: '预测时效',
  },
  cityTrack: {
    deliveryno: '配送单号',
    waybillno: '运单号',
    city: '城市',
    status: '状态',
    transportername: '配送员',
    longitude: '经度',
    latitude: '纬度',
  },
};

/** 京东 ISC 查询域账号未开权限的典型业务码（LOP 通用无权限码）。 */
const PERMISSION_DENIED_CODE = '2001';

interface DisplayRow {
  label: string;
  value: string;
}

/** 业务码 2001（或消息含权限字样）视为「权限未开通」，不是系统错误。 */
function isPermissionDenied(result: JdQueryResult): boolean {
  if (result.business_code === PERMISSION_DENIED_CODE) return true;
  return /权限|permission|authoriz/i.test(result.message ?? '');
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
  if (rows.length >= 40 || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectRows(item, labels, rows);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= 40) return;
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

function buildParams(fields: FieldDef[], values: Record<string, string>): Record<string, QueryValue> {
  const params: Record<string, QueryValue> = {};
  for (const field of fields) {
    // InputNumber 回填的是 number，统一转字符串再处理。
    const raw = String(values[field.name] ?? '').trim();
    if (!raw) continue;
    if (field.kind === 'int') {
      const parsed = Number(raw);
      if (Number.isFinite(parsed)) params[field.name] = String(Math.trunc(parsed));
    } else {
      params[field.name] = raw;
    }
  }
  return params;
}

export default function JdOrderQueryPage() {
  const { message: messageApi } = AntApp.useApp();
  const [kind, setKind] = useState<QueryKind>('adjustment');
  const [form] = Form.useForm<Record<string, string>>();
  const [formValues, setFormValues] = useState<Record<string, string>>({});
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
        messageApi.success('查询完成');
      } else if (isPermissionDenied(queryResult)) {
        messageApi.warning('订单查询权限未开通');
      } else {
        messageApi.warning('查询未完成，请查看下方提示');
      }
    } catch (err) {
      setResult(null);
      messageApi.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const rows: DisplayRow[] = [];
  if (result?.success) collectRows(result.data, labels, rows);

  const permissionDenied = result !== null && !result.success && isPermissionDenied(result);
  const mockMode = result?.business_code === 'MOCK_SUCCESS';

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card size="small" styles={{ body: { padding: '16px 18px' } }}>
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Space align="start" size={12} style={{ width: '100%' }}>
            <FileSearchOutlined style={{ color: saasVisualTokens.brand.primary, fontSize: 20, marginTop: 3 }} />
            <div>
              <Typography.Title level={5} style={{ margin: 0 }}>京东专业单据查询</Typography.Title>
              <Typography.Text type="secondary">
                调整单 / 销毁单 / 异常单 / 采购单 / 加工单 / 作业关联 / 配送时效 / 同城轨迹；全部为只读渠道查询，不计入公司总订单，也不产生任何写操作。
              </Typography.Text>
            </div>
            <div style={{ flex: 1 }} />
            <Space size={4}>
              <Tag color={TOOL_CATEGORY_TAG_COLOR}>系统渠道工具</Tag>
              <Tag color={READ_ONLY_TAG_COLOR}>只读</Tag>
            </Space>
          </Space>

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
            style={{ maxWidth: 720 }}
            onValuesChange={(_, all) => setFormValues(all as Record<string, string>)}
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
              message="订单查询权限未开通"
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
              message={mockMode ? '模拟查询完成（不代表真实权限）' : '查询完成'}
              description={
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  {rows.length ? (
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
        <Typography.Text type="secondary">
          结果仅展示白名单业务字段，收件人、电话、地址等个人数据不会出现；页面为调试与对账用，业务调用请走受审计的履约用例。
        </Typography.Text>
      </Card>
    </Space>
  );
}
