/**
 * 履约中心 · 京东基础信息查询（GET /api/v1/jd-basicinfo/*）。
 * 只读查询页：客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围 7 个接口。
 * 服务端已剔除联系人、电话、地址等个人信息，本页只展示白名单业务字段；
 * 未授权（业务码 2001）明确提示「权限未开通」，不当作系统错误。
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
import { CloudServerOutlined, SearchOutlined } from '@ant-design/icons';
import PageShell from '@/components/PageShell';
import { errorMessage } from '@/api/client';
import { jdBasicInfoApi, type BasicInfoQuery } from '@/api/basicinfoEndpoints';
import type { JdQueryResult } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { jdConnectionSemantic } from '@/pages/shared/semanticStatus';

interface ParamField {
  name: keyof BasicInfoQuery;
  label: string;
  placeholder?: string;
  kind?: 'text' | 'number';
}

interface QueryOption {
  key: string;
  label: string;
  description: string;
  fields: ParamField[];
  run: (query: BasicInfoQuery) => Promise<JdQueryResult>;
  /** 白名单：归一化字段名（小写去符号）-> 展示名。 */
  whitelist: Record<string, string>;
}

/** 与 backend 白名单字段展示口径一致：服务端脱敏后，页面只透出这些业务字段。 */
const QUERIES: QueryOption[] = [
  {
    key: 'customers',
    label: '客户查询',
    description: '按客户编码/名称分页查询客户档案（联系人、电话、地址已在服务端剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'customer_no', label: '客户编码', placeholder: '精确匹配客户编码' },
      { name: 'customer_name', label: '客户名称', placeholder: '模糊匹配客户名称' },
      { name: 'page_size', label: '每页条数', kind: 'number' },
      { name: 'current_page', label: '页码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.customers(q),
    whitelist: {
      customerno: '客户编码',
      customername: '客户名称',
      ownerno: '事业部编码',
      sellerno: '商家编码',
      sellername: '商家名称',
      customertype: '客户类型',
      transfertype: '移库类型',
      warehousename: '仓库名称',
      isdirectdelivery: '是否直发',
      remark: '备注',
      licenseunitno: '证照单位编码',
      licenseunit: '证照单位',
    },
  },
  {
    key: 'sellers',
    label: '商家查询',
    description: '按当前授权商家（pin）查询商家关联的店铺与仓库。',
    fields: [],
    run: () => jdBasicInfoApi.sellers(),
    whitelist: {
      ownerno: '事业部编码',
      shopnos: '店铺编码列表',
      warehousenos: '仓库编码列表',
    },
  },
  {
    key: 'shops',
    label: '店铺查询',
    description: '按店铺编码/ERP 店铺编码查询店铺信息（店铺地址等个人信息已剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'shop_no', label: '店铺编码', placeholder: '京东店铺编码' },
      { name: 'erp_shop_no', label: 'ERP 店铺编码', placeholder: 'ERP 侧店铺编码' },
    ],
    run: (q) => jdBasicInfoApi.shops(q),
    whitelist: {
      ownerno: '事业部编码',
      shopno: '店铺编码',
      shopname: '店铺名称',
      erpshopno: 'ERP 店铺编码',
      salesplatformsourceno: '销售平台来源编码',
      type: '店铺类型',
      status: '状态',
      salesplatformshopno: '销售平台店铺编码',
      customercode: '客户编码',
      outboundrules: '出库规则',
      biztype: '业务类型',
    },
  },
  {
    key: 'shop-goods',
    label: '店铺商品查询',
    description: '按店铺/商品编码分页查询店铺商品映射关系。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'shop_no', label: '店铺编码', placeholder: '京东店铺编码' },
      { name: 'goods_no', label: '商品编码', placeholder: '京东商品编码' },
      { name: 'erp_goods_no', label: 'ERP 商品编码', placeholder: 'ERP 侧商品编码' },
      { name: 'sales_platform_goods_no', label: '销售平台商品编码', placeholder: '销售平台商品编码' },
      { name: 'shop_goods_no_min', label: '店铺商品编码起始', placeholder: '从该编码开始向后取' },
      { name: 'page_size', label: '每页条数', kind: 'number' },
      { name: 'current_page', label: '页码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.shopGoods(q),
    whitelist: {
      goodsno: '商品编码',
      shopgoodsno: '店铺商品编码',
      erpgoodssign: 'ERP 商品标识',
      erpgoodsno: 'ERP 商品编码',
      ownerno: '事业部编码',
      salesplatformgoodsno: '销售平台商品编码',
      shopname: '店铺名称',
      shopgoodsname: '店铺商品名称',
      shopno: '店铺编码',
    },
  },
  {
    key: 'suppliers',
    label: '供应商查询',
    description: '按供应商编码列表查询供应商档案（联系人、电话、地址已在服务端剔除）。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'supplier_nos', label: '供应商编码', placeholder: '多个用英文逗号分隔' },
      { name: 'isv_supplier_nos', label: 'ISV 供应商编码', placeholder: '多个用英文逗号分隔' },
    ],
    run: (q) => jdBasicInfoApi.suppliers(q),
    whitelist: {
      ownerno: '事业部编码',
      ownername: '事业部名称',
      erpsupplierno: 'ERP 供应商编码',
      supplierno: '供应商编码',
      suppliername: '供应商名称',
      suppliertype: '供应商类型',
      status: '状态',
      medicineenterprisenature: '医药企业性质',
      socialcreditcode: '统一社会信用代码',
    },
  },
  {
    key: 'goods-categories',
    label: '商品类目查询',
    description: '按类目编码逐级查询商品类目（一级/二级/三级）。',
    fields: [
      { name: 'first_category_code', label: '一级类目编码', kind: 'number' },
      { name: 'second_category_code', label: '二级类目编码', kind: 'number' },
      { name: 'third_category_code', label: '三级类目编码', kind: 'number' },
    ],
    run: (q) => jdBasicInfoApi.goodsCategories(q),
    whitelist: {
      firstcategorycode: '一级类目编码',
      firstcategoryname: '一级类目名称',
      secondcategorycode: '二级类目编码',
      secondcategoryname: '二级类目名称',
      thirdcategorycode: '三级类目编码',
      thirdcategoryname: '三级类目名称',
    },
  },
  {
    key: 'warehouse-coverages',
    label: '仓库覆盖范围',
    description: '按收货区域查询可覆盖的仓库编码。',
    fields: [
      { name: 'owner_no', label: '事业部编码', placeholder: '如 EBU000000000001' },
      { name: 'province', label: '省', placeholder: '如 浙江省' },
      { name: 'city', label: '市', placeholder: '如 杭州市' },
      { name: 'county', label: '区/县', placeholder: '如 西湖区' },
      { name: 'town', label: '镇/街道', placeholder: '如 文新街道' },
      { name: 'detail_address', label: '详细地址', placeholder: '可选，用于精确匹配覆盖范围' },
    ],
    run: (q) => jdBasicInfoApi.warehouseCoverages(q),
    whitelist: {
      warehouseno: '仓库编码',
    },
  },
];

const MAX_ROWS = 40;

interface DisplayRow {
  label: string;
  value: string;
}

function normalizeKey(key: string): string {
  return key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
}

function scalarValue(raw: unknown): string | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === 'string') return raw.trim() === '' ? null : raw;
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  return null;
}

/** 递归收集白名单字段；数组逐条展开，列表结果会重复出现同一标签。 */
function collectWhitelisted(value: unknown, whitelist: Record<string, string>, rows: DisplayRow[]): void {
  if (rows.length >= MAX_ROWS || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectWhitelisted(item, whitelist, rows);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= MAX_ROWS) return;
    const label = whitelist[normalizeKey(key)];
    const scalar = scalarValue(raw);
    if (label && scalar !== null) {
      rows.push({ label, value: scalar });
    } else if (typeof raw === 'object') {
      collectWhitelisted(raw, whitelist, rows);
    }
  }
}

export default function JdBasicInfoQueryPage() {
  const sdkStatus = useAsync(() => jdBasicInfoApi.status(), []);
  const [form] = Form.useForm<BasicInfoQuery>();
  const [option, setOption] = useState<QueryOption>(QUERIES[0]);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<JdQueryResult | null>(null);

  const switchOption = (key: string) => {
    const next = QUERIES.find((q) => q.key === key) ?? QUERIES[0];
    setOption(next);
    form.resetFields();
    setResult(null);
  };

  const runQuery = async () => {
    const values = await form.validateFields();
    setLoading(true);
    try {
      const outcome = await option.run(values);
      setResult(outcome);
      if (outcome.success) message.success(`${option.label}完成`);
      else if (outcome.business_code === '2001') message.warning('该接口权限未开通');
      else message.warning('查询未完成');
    } catch (err) {
      setResult(null);
      message.error(errorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const rows: DisplayRow[] = [];
  if (result?.success) collectWhitelisted(result.data, option.whitelist, rows);

  const mode = sdkStatus.data?.client_mode;
  const liveReady = sdkStatus.data?.live_ready ?? false;
  const modeTag =
    sdkStatus.loading ? (
      <Tag>正在确认连接状态</Tag>
    ) : mode === 'REAL' && liveReady ? (
      <Tag color={jdConnectionSemantic(liveReady, mode)}>真实连接已就绪</Tag>
    ) : mode === 'REAL' ? (
      <Tag color={jdConnectionSemantic(liveReady, mode)}>真实连接未就绪</Tag>
    ) : mode === 'MOCK' ? (
      <Tag>模拟模式（不代表真实权限）</Tag>
    ) : (
      <Tag>连接状态未知</Tag>
    );

  return (
    <PageShell
      icon={<CloudServerOutlined />}
      title="京东基础信息查询"
      description="只读查询客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围；结果已脱敏，只展示白名单业务字段。"
      actions={modeTag}
    >
      <Card size="small">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          {sdkStatus.error ? (
            <Alert
              type="error"
              showIcon
              message="连接状态加载失败"
              description={errorMessage(sdkStatus.error)}
              action={<Button size="small" onClick={sdkStatus.reload}>重试</Button>}
            />
          ) : null}
          <Space align="start" size={12} style={{ width: '100%' }} wrap>
            <Select
              style={{ width: 220 }}
              value={option.key}
              onChange={switchOption}
              options={QUERIES.map((q) => ({ value: q.key, label: q.label }))}
            />
            <Form<BasicInfoQuery>
              form={form}
              layout="inline"
              style={{ rowGap: 12 }}
              onFinish={runQuery}
            >
              {option.fields.map((field) => (
                <Form.Item key={field.name} name={field.name} label={field.label}>
                  {field.kind === 'number' ? (
                    <InputNumber style={{ width: 140 }} min={1} placeholder={field.placeholder ?? ''} />
                  ) : (
                    <Input style={{ width: 180 }} placeholder={field.placeholder ?? ''} allowClear />
                  )}
                </Form.Item>
              ))}
              <Form.Item>
                <Button
                  type="primary"
                  icon={<SearchOutlined />}
                  loading={loading}
                  disabled={!sdkStatus.data}
                  onClick={runQuery}
                >
                  查询
                </Button>
              </Form.Item>
            </Form>
          </Space>
          <Typography.Text type="secondary">{option.description}</Typography.Text>
          {result && !result.success && result.business_code === '2001' ? (
            <Alert
              type="warning"
              showIcon
              message="权限未开通"
              description="当前京东账号未开通该查询接口的权限（业务码 2001），请联系管理员在京东物流开放平台申请后重试；这不是系统故障。"
            />
          ) : null}
          {result && !result.success && result.business_code !== '2001' ? (
            <Alert
              type="warning"
              showIcon
              message="查询未完成"
              description={result.message ? `业务码 ${result.business_code}：${result.message}` : `业务码 ${result.business_code}`}
            />
          ) : null}
          {result?.success ? (
            <Alert
              type="success"
              showIcon
              message={`${option.label}完成${mode === 'MOCK' ? '（模拟数据，不代表真实权限）' : ''}`}
              description={
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text type="secondary">请求 ID：{result.request_id ?? '—'}</Typography.Text>
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
                </Space>
              }
            />
          ) : null}
        </Space>
      </Card>
    </PageShell>
  );
}
