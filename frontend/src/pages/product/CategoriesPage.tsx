/**
 * 主数据 · 品类（GET/POST /api/v1/categories，PATCH /api/v1/categories/{id}）。
 */

import type { ColumnsType } from 'antd/es/table';
import MasterDataCrud, { type CrudField } from '@/pages/shared/MasterDataCrud';
import { categoriesApi } from '@/api/endpoints';
import type { MasterDataRecord } from '@/api/types';

const columns: ColumnsType<MasterDataRecord> = [
  { title: '品类编码', dataIndex: 'code', width: 180 },
  { title: '品类名称', dataIndex: 'name' },
  { title: '版本', dataIndex: 'version', width: 70, align: 'right' },
];

const createFields: CrudField[] = [
  { name: 'code', label: '品类编码', required: true, placeholder: '如 MEAT/BEEF' },
  { name: 'name', label: '品类名称', required: true },
  { name: 'active', label: '启用', type: 'switch' },
];

const updateFields: CrudField[] = [
  { name: 'name', label: '品类名称', required: true },
  { name: 'active', label: '启用', type: 'switch' },
];

export default function CategoriesPage() {
  return (
    <MasterDataCrud
      fetchPage={(q) => categoriesApi.list(q)}
      create={(v) =>
        categoriesApi.create({
          code: String(v.code),
          name: String(v.name),
          active: typeof v.active === 'boolean' ? v.active : undefined,
        })
      }
      update={(id, v) =>
        categoriesApi.update(id, {
          expected_version: Number(v.expected_version),
          name: typeof v.name === 'string' ? v.name : undefined,
          active: typeof v.active === 'boolean' ? v.active : undefined,
        })
      }
      columns={columns}
      createFields={createFields}
      updateFields={updateFields}
    />
  );
}
