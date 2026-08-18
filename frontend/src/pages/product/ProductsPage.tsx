/**
 * 主数据 · 商品（GET/POST /api/v1/products，PATCH /api/v1/products/{id}）。
 * 品类 ID 落在 MasterDataRecord.attributes.category_id（openapi 附加属性）。
 */

import type { ColumnsType } from 'antd/es/table';
import { Tag } from 'antd';
import MasterDataCrud, { attr, type CrudField } from '@/pages/shared/MasterDataCrud';
import { productsApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';
import { useCategoryOptions } from './masterOptions';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';

export default function ProductsPage() {
  const categoryOptions = useCategoryOptions();
  const categoryLabels = new Map(categoryOptions.map(({ value, label }) => [String(value), label]));
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
    { title: '版本', dataIndex: 'version', width: 70, align: 'right' },
  ];
  const categoryIdField: CrudField = {
    name: 'category_id',
    label: '品类',
    required: true,
    type: 'select',
    options: categoryOptions,
  };

  const createFields: CrudField[] = [
    { name: 'product_code', label: '商品编码', required: true, placeholder: '如 P-1001' },
    { name: 'product_name', label: '商品名称', required: true },
    categoryIdField,
    { name: 'active', label: '启用', type: 'switch' },
  ];

  const updateFields: CrudField[] = [
    { name: 'product_name', label: '商品名称', required: true },
    categoryIdField,
    { name: 'active', label: '启用', type: 'switch' },
  ];

  return (
    <MasterDataCrud
      fetchPage={(q) => productsApi.list(q)}
      create={(v) =>
        productsApi.create({
          product_code: String(v.product_code),
          product_name: String(v.product_name),
          category_id: String(v.category_id),
          active: typeof v.active === 'boolean' ? v.active : undefined,
        })
      }
      update={(id, v) =>
        productsApi.update(id, {
          expected_version: Number(v.expected_version),
          product_name: typeof v.product_name === 'string' ? v.product_name : undefined,
          category_id: typeof v.category_id === 'string' ? v.category_id : undefined,
          active: typeof v.active === 'boolean' ? v.active : undefined,
        })
      }
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
    />
  );
}
