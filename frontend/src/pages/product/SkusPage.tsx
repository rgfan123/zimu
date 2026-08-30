/**
 * 商品档案：以可履约 SKU 为运营主记录，同时展示所属商品、品类、履约方、规格与价格。
 */

import { useState } from 'react';
import { Button, Input, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { CloudUploadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { MainImageThumb } from '@/pages/shared/MainImage';
import { skusApi } from '@/api/endpoints';
import type { MasterDataRecord, SkuFulfillmentReadiness, SkuReadinessReason } from '@/api/types';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { useCategoryOptions, useProviderOptions } from './masterOptions';
import { displaySkuSpecification } from './productArchive';
import {
  COMMERCIAL_PRICE_PATTERN,
  buildProductWithInitialSkuBody,
  buildSkuUpdateBody,
  commercialPriceLabel,
} from './skuCommercialPrice';
import { leadTimeLabel, listingPeriodLabel, marginLabel } from './productArchiveFields';
import PlatformUploadModal from './PlatformUploadModal';

const READINESS_REASON_OPTIONS: { value: SkuReadinessReason; label: string }[] = [
  { value: 'PRODUCT_INACTIVE', label: '商品已停用' },
  { value: 'SKU_INACTIVE', label: 'SKU 已停用' },
  { value: 'PROVIDER_INACTIVE', label: '履约方已停用' },
  { value: 'SPECIFICATION_REQUIRED', label: '规格缺失或待维护' },
  { value: 'UNIT_REQUIRED', label: '库存单位缺失' },
  { value: 'PROVIDER_MAPPING_REQUIRED', label: '缺少履约方商品映射' },
  { value: 'PROVIDER_MAPPING_INACTIVE', label: '履约方映射已停用' },
  { value: 'UNIT_CONVERSION_REQUIRED', label: '京东件数换算缺失' },
  { value: 'BARCODE_CONFLICT', label: '条码冲突' },
  { value: 'REVIEW_REQUIRED', label: '需要人工复核' },
];

function skuReadiness(record: MasterDataRecord): SkuFulfillmentReadiness | undefined {
  const value = attr(record, 'readiness');
  if (!value || typeof value !== 'object') return undefined;
  return value as SkuFulfillmentReadiness;
}

export default function SkusPage() {
  const [providerId, setProviderId] = useState<string | undefined>();
  const [searchQuery, setSearchQuery] = useState<string | undefined>();
  const [readinessReason, setReadinessReason] = useState<SkuReadinessReason | undefined>();
  const [platformUploadOpen, setPlatformUploadOpen] = useState(false);
  const providerOptions = useProviderOptions();
  const categoryOptions = useCategoryOptions();
  const providerLabels = new Map(providerOptions.map(({ value, label }) => [String(value), label]));
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));

  const columns: ColumnsType<MasterDataRecord> = [
    { title: '商品 / SKU', key: 'identity', width: 210, render: (_, r) => <ProductIdentity name={r.name} code={r.code} /> },
    {
      title: '京东EMG编号',
      key: 'jd_emg_no',
      width: 160,
      render: (_, r) => {
        const emg = attr(r, 'jd_emg_no');
        return emg ? <Tag style={{ marginInlineEnd: 0 }}>{String(emg)}</Tag> : '—';
      },
    },
    {
      title: '主图',
      key: 'main_image',
      width: 70,
      render: (_, r) => <MainImageThumb ref={attr(r, 'product_main_image_ref') as string | null | undefined} />,
    },
    { title: '品类', key: 'category', width: 150, render: (_, r) => categoryLabels.get(String(attr(r, 'category_id'))) ?? '—' },
    { title: '品牌', key: 'brand', width: 110, render: (_, r) => String(attr(r, 'product_brand_name') ?? '—') },
    { title: '规格', key: 'spec', width: 110, render: (_, r) => displaySkuSpecification(attr(r, 'specification')) },
    {
      title: '包装身份', key: 'packaging', width: 170,
      render: (_, r) => {
        const value = attr(r, 'net_content_value');
        const contentUnit = attr(r, 'net_content_unit');
        const count = attr(r, 'package_count');
        const packageUnit = attr(r, 'package_unit');
        return value && contentUnit && count && packageUnit
          ? `${String(value)}${String(contentUnit)} × ${String(count)}${String(packageUnit)}`
          : '—';
      },
    },
    { title: '单位', key: 'unit', width: 70, render: (_, r) => String(attr(r, 'unit') ?? '—') },
    {
      title: '履约就绪', key: 'readiness', width: 190,
      render: (_, r) => {
        const readiness = skuReadiness(r);
        if (!readiness) return '未评估';
        if (readiness.ready) return <Tag color="success">可履约</Tag>;
        const firstIssue = readiness.issues[0];
        return (
          <Space direction="vertical" size={0}>
            <Tag color="warning">阻断</Tag>
            {firstIssue ? (
              <Tooltip title={firstIssue.action}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {firstIssue.message}
                </Typography.Text>
              </Tooltip>
            ) : null}
          </Space>
        );
      },
    },
    { title: '履约方', key: 'provider', width: 170, render: (_, r) => providerLabels.get(String(attr(r, 'provider_id'))) ?? '—' },
    {
      title: '毛利', key: 'margin', width: 100, align: 'right',
      render: (_, r) => marginLabel(attr(r, 'margin')),
    },
    {
      title: '标签', key: 'tags', width: 200,
      render: (_, r) => {
        const tags = attr(r, 'product_tags');
        if (!Array.isArray(tags) || tags.length === 0) return '—';
        return (
          <span>
            {tags.map((tag) => (
              <Tag key={String(tag)} style={{ marginInlineEnd: 4 }}>{String(tag)}</Tag>
            ))}
          </span>
        );
      },
    },
    {
      title: '原料', key: 'ingredients', width: 160, ellipsis: true,
      render: (_, r) => String(attr(r, 'product_ingredients') ?? '—'),
    },
    {
      title: '上市周期', key: 'listing_period', width: 180,
      render: (_, r) => listingPeriodLabel(attr(r, 'product_listed_from'), attr(r, 'product_listed_until')),
    },
    {
      title: '发货时效', key: 'lead_time', width: 110,
      render: (_, r) => leadTimeLabel(attr(r, 'product_lead_time_hours')),
    },
    {
      title: '进货价', key: 'purchase_price', width: 90, align: 'right',
      render: (_, r) => commercialPriceLabel(attr(r, 'purchase_price')),
    },
    {
      title: '零售价', key: 'retail_price', width: 90, align: 'right',
      render: (_, r) => commercialPriceLabel(attr(r, 'retail_price')),
    },
    {
      title: '条码', key: 'barcode', width: 130,
      render: (_, r) => {
        const barcode = attr(r, 'barcode');
        return barcode ? <Tag style={{ marginInlineEnd: 0 }}>{String(barcode)}</Tag> : '—';
      },
    },
  ];

  const createFields: CrudField[] = [
    { name: 'product_code', label: '商品编码', required: true, placeholder: '如 P-1001' },
    { name: 'product_name', label: '商品名称', required: true },
    { name: 'brand_name', label: '品牌', placeholder: '无品牌可留空' },
    { name: 'category_id', label: '品类', required: true, type: 'select', options: categoryOptions },
    { name: 'provider_id', label: '履约方', required: true, type: 'select', options: providerOptions },
    { name: 'specification', label: '规格展示', required: true, placeholder: '如 500g×2袋' },
    { name: 'net_content_value', label: '净含量', placeholder: '如 500 / 1' },
    { name: 'net_content_unit', label: '净含量单位', placeholder: '如 g / kg / 件' },
    { name: 'package_count', label: '包装件数', placeholder: '如 2', pattern: /^[1-9][0-9]*$/, patternMessage: '请输入正整数' },
    { name: 'package_unit', label: '包装单位', placeholder: '如 袋 / 盒 / 件' },
    { name: 'unit', label: '库存计数单位', required: true, placeholder: '如 件 / 袋' },
    { name: 'barcode', label: '条码', placeholder: '可选' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '未填写即未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    { name: 'specification', label: '规格展示', required: true },
    { name: 'net_content_value', label: '净含量', placeholder: '清空全部包装字段可移除结构化身份' },
    { name: 'net_content_unit', label: '净含量单位' },
    { name: 'package_count', label: '包装件数', pattern: /^[1-9][0-9]*$/, patternMessage: '请输入正整数' },
    { name: 'package_unit', label: '包装单位' },
    { name: 'unit', label: '库存计数单位', required: true },
    { name: 'barcode', label: '条码', placeholder: '可选（清空则删除）' },
    {
      name: 'purchase_price',
      label: '进货价（元）',
      placeholder: '清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    {
      name: 'retail_price',
      label: '零售价（元）',
      placeholder: '清空则设为未定价',
      pattern: COMMERCIAL_PRICE_PATTERN,
      patternMessage: '请输入非负金额，最多两位小数',
    },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  return (
    <>
    <MasterDataCrud
      filters={
        <Space wrap>
          <Input.Search
            style={{ width: 260 }}
            placeholder="搜索 SKU 编码 / 商品名称"
            allowClear
            onSearch={(value) => setSearchQuery(value.trim() || undefined)}
          />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
          <Select
            style={{ width: 200 }}
            placeholder="全部履约方"
            allowClear
            value={providerId}
            onChange={setProviderId}
            options={providerOptions}
          />
          <Select
            style={{ width: 230 }}
            placeholder="按阻断原因筛选"
            allowClear
            value={readinessReason}
            onChange={setReadinessReason}
            options={READINESS_REASON_OPTIONS}
          />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            新建会同时创建商品及首个 SKU；后续可在基础信息中维护完整商品资料。
          </Typography.Text>
          <Button size="small" type="primary" ghost icon={<CloudUploadOutlined />}
                  onClick={() => setPlatformUploadOpen(true)}>
            上架
          </Button>
          <Button size="small"><Link to="/product/products">管理商品名称</Link></Button>
          <Button size="small"><Link to="/product/categories">管理品类</Link></Button>
        </Space>
      }
      extraQuery={{ provider_id: providerId, query: searchQuery, readiness_reason: readinessReason }}
      fetchPage={(q) => skusApi.list({
        ...q,
        provider_id: providerId,
        query: searchQuery,
        readiness_reason: readinessReason,
      })}
      create={(v) => skusApi.createWithProduct(buildProductWithInitialSkuBody(v))}
      update={(id, v) => skusApi.update(id, buildSkuUpdateBody(v))}
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
    />
      <PlatformUploadModal
        open={platformUploadOpen}
        onClose={() => setPlatformUploadOpen(false)}
        query={searchQuery}
        providerId={providerId}
      />
    </>
  );
}
