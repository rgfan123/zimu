/**
 * 商品档案：以可履约 SKU 为运营主记录，同时展示所属商品、品类、履约方、规格与价格。
 */

import { useState } from 'react';
import { Button, Select, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Link } from 'react-router-dom';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { skusApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import { useCategoryOptions, useProductOptions, useProviderOptions } from './masterOptions';
import { displaySkuSpecification } from './productArchive';
import {
  COMMERCIAL_PRICE_PATTERN,
  buildSkuCreateBody,
  buildSkuUpdateBody,
  commercialPriceLabel,
} from './skuCommercialPrice';

export default function SkusPage() {
  const [providerId, setProviderId] = useState<string | undefined>();
  const providerOptions = useProviderOptions();
  const productOptions = useProductOptions();
  const categoryOptions = useCategoryOptions();
  const providerLabels = new Map(providerOptions.map(({ value, label }) => [String(value), label]));
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));

  const columns: ColumnsType<MasterDataRecord> = [
    { title: '商品 / SKU', key: 'identity', width: 210, render: (_, r) => <ProductIdentity name={r.name} code={r.code} /> },
    { title: '品类', key: 'category', width: 160, render: (_, r) => categoryLabels.get(String(attr(r, 'category_id'))) ?? '—' },
    { title: '规格', key: 'spec', width: 110, render: (_, r) => displaySkuSpecification(attr(r, 'specification')) },
    { title: '单位', key: 'unit', width: 70, render: (_, r) => String(attr(r, 'unit') ?? '—') },
    { title: '履约方', key: 'provider', width: 180, render: (_, r) => providerLabels.get(String(attr(r, 'provider_id'))) ?? '—' },
    {
      title: '进货价', key: 'purchase_price', width: 100, align: 'right',
      render: (_, r) => commercialPriceLabel(attr(r, 'purchase_price')),
    },
    {
      title: '零售价', key: 'retail_price', width: 100, align: 'right',
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
    { name: 'provider_id', label: '履约方', required: true, type: 'select', options: providerOptions },
    { name: 'product_id', label: '商品', required: true, type: 'select', options: productOptions },
    { name: 'specification', label: '规格', required: true, placeholder: '如 500g*2袋' },
    { name: 'unit', label: '单位', required: true, placeholder: '如 盒 / 袋' },
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
    { name: 'specification', label: '规格', required: true },
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
    <MasterDataCrud
      filters={
        <Space wrap>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>履约方</Typography.Text>
          <Select
            style={{ width: 220 }}
            placeholder="全部履约方"
            allowClear
            value={providerId}
            onChange={setProviderId}
            options={providerOptions}
          />
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            商品名称与品类使用基础信息维护；规格、履约方和价格在本档案维护。
          </Typography.Text>
          <Button size="small"><Link to="/product/products">管理商品名称</Link></Button>
          <Button size="small"><Link to="/product/categories">管理品类</Link></Button>
        </Space>
      }
      extraQuery={{ provider_id: providerId }}
      fetchPage={(q) => skusApi.list({ ...q, provider_id: providerId })}
      create={(v) => skusApi.create(buildSkuCreateBody(v))}
      update={(id, v) => skusApi.update(id, buildSkuUpdateBody(v))}
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
    />
  );
}
