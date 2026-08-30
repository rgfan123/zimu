/**
 * 主数据 · 商品（GET/POST /api/v1/products，PATCH /api/v1/products/{id}）。
 * 品类 ID 落在 MasterDataRecord.attributes.category_id（openapi 附加属性）；
 * 商品档案字段（标签/原料/上市周期/发货时效/主图）见 productArchiveFields.ts。
 */

import { useEffect, useState } from 'react';
import type { ColumnsType } from 'antd/es/table';
import { Tag } from 'antd';
import { Link } from 'react-router-dom';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { MainImageThumb } from '@/pages/shared/MainImage';
import { productsApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';
import { useCategoryOptions } from './masterOptions';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import {
  LEAD_TIME_HOURS_PATTERN,
  buildProductCreateBody,
  buildProductUpdateBody,
} from './productArchiveFields';

export default function ProductsPage() {
  const categoryOptions = useCategoryOptions();
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));
  const [tagCandidates, setTagCandidates] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    productsApi
      .tags()
      .then((tags) => {
        if (!cancelled) setTagCandidates(tags);
      })
      .catch(() => {
        // 候选加载失败不阻塞表单；用户仍可自由输入标签。
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const columns: ColumnsType<MasterDataRecord> = [
    { title: '商品', key: 'identity', render: (_, r) => <ProductIdentity name={r.name} code={r.code} /> },
    {
      title: '品类',
      key: 'category',
      width: 200,
      render: (_, r) => {
        const id = attr(r, 'category_id');
        if (!id) return '—';
        return <Tag style={{ marginInlineEnd: 0 }}>{categoryLabels.get(String(id)) ?? '品类资料加载中…'}</Tag>;
      },
    },
    {
      title: '主图',
      key: 'main_image',
      width: 80,
      render: (_, r) => <MainImageThumb ref={attr(r, 'main_image_ref') as string | null | undefined} />,
    },
    {
      title: '标签',
      key: 'tags',
      width: 220,
      render: (_, r) => {
        const tags = attr(r, 'tags');
        if (!Array.isArray(tags) || tags.length === 0) return '—';
        return (
          <span>
            {tags.map((tag) => (
              <Tag key={String(tag)} style={{ marginInlineEnd: 4 }}>
                {String(tag)}
              </Tag>
            ))}
          </span>
        );
      },
    },
    { title: '版本', dataIndex: 'version', width: 70, align: 'right' },
  ];
  const categoryIdField: CrudField = {
    name: 'category_id',
    label: '品类',
    required: true,
    type: 'select',
    options: categoryOptions,
  };
  const tagsOptions = tagCandidates.map((tag) => ({ value: tag, label: tag }));

  const createFields: CrudField[] = [
    { name: 'product_code', label: '商品编码', required: true, placeholder: '如 P-1001' },
    { name: 'product_name', label: '商品名称', required: true },
    categoryIdField,
    { name: 'ingredients', label: '原料', type: 'textarea', placeholder: '如 羔羊肉、孜然、食用盐' },
    { name: 'tags', label: '商品标签', type: 'tags', options: tagsOptions, placeholder: '输入后回车，可复用已有标签' },
    { name: 'listing_period', label: '上市周期', type: 'date-range' },
    {
      name: 'lead_time_hours',
      label: '发货时效（小时）',
      placeholder: '如 24 / 48',
      pattern: LEAD_TIME_HOURS_PATTERN,
      patternMessage: '请输入正整数小时数',
    },
    { name: 'main_image_ref', label: '主图', type: 'upload' },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    { name: 'product_name', label: '商品名称', required: true },
    categoryIdField,
    { name: 'ingredients', label: '原料', type: 'textarea', placeholder: '清空则删除' },
    { name: 'tags', label: '商品标签', type: 'tags', options: tagsOptions, placeholder: '清空全部标签即删除' },
    {
      name: 'listing_period',
      label: '上市周期',
      type: 'date-range',
      loadValue: (r) => {
        const from = attr(r, 'listed_from');
        const to = attr(r, 'listed_until');
        if (!from && !to) return undefined;
        return { ...(from ? { from: String(from) } : {}), ...(to ? { to: String(to) } : {}) };
      },
    },
    {
      name: 'lead_time_hours',
      label: '发货时效（小时）',
      placeholder: '清空则删除',
      pattern: LEAD_TIME_HOURS_PATTERN,
      patternMessage: '请输入正整数小时数',
    },
    { name: 'main_image_ref', label: '主图', type: 'upload' },
    { name: 'active', label: '启用', type: 'switch' },
  ];

  return (
    <MasterDataCrud
      filters={(
        <span className="zs-admin-toolbar-note">
          商品价格在「<Link to="/product/skus">商品档案（SKU）</Link>」维护，来源为成本核算表。
        </span>
      )}
      fetchPage={(q) => productsApi.list(q)}
      create={(v) => productsApi.create(buildProductCreateBody(v))}
      update={(id, v) => productsApi.update(id, buildProductUpdateBody(v))}
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
    />
  );
}
