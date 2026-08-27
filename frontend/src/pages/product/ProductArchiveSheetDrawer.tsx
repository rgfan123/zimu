/**
 * 商品档案·成本表全列留存（只读抽屉）。
 *
 * 这张表存在的理由就是**列序**：后端把成本表一行存成 jsonb 数组（不是对象，jsonb 对象不保序），
 * 读接口原样返回。所以这里渲染 fields 时**绝不排序、不过滤、不重命名**——按拿到的顺序铺开即可。
 *
 * 列语义（2026-08-27 用户拍板）：AI 线下供货成本/份 = 成本（按份 500g 口径）；
 * AJ 售价 = 不含运费的售价。两者都只做展示，不参与任何换算。
 */

import { useCallback, useEffect, useState } from 'react';
import { Drawer, Space, Table, Typography } from 'antd';
import { productsApi } from '@/api/endpoints';
import type { ProductArchiveSheet, ProductArchiveSheetField } from '@/api/types';
import { AdminEmpty, AdminFailureAlert, AdminLoading } from '@/pages/shared/AdminVisualComponents';
import '@/pages/shared/adminSurface.css';

const FIELD_COLUMNS = [
  { title: '列', dataIndex: 'column', width: 64 },
  { title: '字段', dataIndex: 'name', width: 200 },
  {
    title: '值',
    dataIndex: 'value',
    render: (value: string | null) =>
      value === null || value === undefined ? (
        <Typography.Text type="secondary">—</Typography.Text>
      ) : (
        <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>
      ),
  },
];

export default function ProductArchiveSheetDrawer({
  open,
  productId,
  title,
  onClose,
}: {
  open: boolean;
  productId: string | null;
  title: string;
  onClose: () => void;
}) {
  const [rows, setRows] = useState<ProductArchiveSheet[] | null>(null);
  const [error, setError] = useState<unknown>(null);

  const load = useCallback(() => {
    if (!productId) return;
    setRows(null);
    setError(null);
    productsApi
      .archiveSheet(productId)
      .then(setRows)
      .catch((cause: unknown) => setError(cause));
  }, [productId]);

  useEffect(() => {
    if (open && productId) load();
  }, [open, productId, load]);

  return (
    <Drawer title={`成本档案 · ${title}`} width={720} open={open} onClose={onClose} destroyOnClose>
      {error ? (
        <AdminFailureAlert error={error} title="成本档案读取失败" onRetry={load} />
      ) : rows === null ? (
        <AdminLoading description="正在读取成本档案…" />
      ) : rows.length === 0 ? (
        <AdminEmpty description="该商品尚未挂接成本表行（档案已入库，等人工挂接）" />
      ) : (
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          {rows.map((row) => (
            <div className="admin-detail-section" key={row.id}>
              <Typography.Text className="admin-detail-section__heading" strong>
                {row.source_file_name} · {row.sheet_name} 第 {row.row_no} 行 · {row.product_name}
              </Typography.Text>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 4 }}>
                共 {row.fields.length} 列，按原表列序展示；空单元格显示为 —。
              </Typography.Paragraph>
              <Table<ProductArchiveSheetField>
                rowKey="column"
                size="small"
                pagination={false}
                columns={FIELD_COLUMNS}
                dataSource={row.fields}
                locale={{ emptyText: <AdminEmpty description="该行没有任何列" /> }}
              />
            </div>
          ))}
        </Space>
      )}
    </Drawer>
  );
}
