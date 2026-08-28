/**
 * 商品档案页成本表宽表投影。
 *
 * fields 是成本表 A..AU 的有序数组。这里唯一允许的取值方式是 fields[index]：
 * 不按 name 查找，不排序、过滤或去重，避免同名列丢失或列序漂移。
 */

import type { ColumnsType } from 'antd/es/table';
import type { MasterDataRecord, ProductArchiveSheet } from '@/api/types';
import { attr } from '@/pages/shared/MasterDataCrud';

const ARCHIVE_FIELDS = [
  { column: 'A', title: '产品名称' },
  { column: 'B', title: '产品状态' },
  { column: 'C', title: '规格（g）' },
  { column: 'D', title: '国条' },
  { column: 'E', title: '品牌' },
  { column: 'F', title: '肉类' },
  { column: 'G', title: '原料' },
  { column: 'H', title: '供应渠道' },
  { column: 'I', title: '包装形式' },
  { column: 'J', title: '加工要求' },
  { column: 'K', title: '净含量/g' },
  { column: 'L', title: '加工规格/g' },
  { column: 'M', title: '原料成本kg/元' },
  { column: 'N', title: '核算成本 /份' },
  { column: 'O', title: '原料利润' },
  { column: 'P', title: '成本+原料利润/kg' },
  { column: 'Q', title: '人工费' },
  { column: 'R', title: '人工 占比' },
  { column: 'S', title: '修割损耗率' },
  { column: 'T', title: '损耗成本/KG' },
  { column: 'U', title: '损耗后 成本/KG' },
  { column: 'V', title: '加工后 成本/KG' },
  { column: 'W', title: '加工后 成本/份' },
  { column: 'X', title: '盒/袋' },
  { column: 'Y', title: '贴纸/腰封' },
  { column: 'Z', title: '膜' },
  { column: 'AA', title: '签' },
  { column: 'AB', title: '泡沫箱/纸箱+冰袋' },
  { column: 'AC', title: '耗材/KG' },
  { column: 'AD', title: '耗材/份' },
  { column: 'AE', title: '耗材 占比' },
  { column: 'AF', title: '含耗材 成本/份' },
  { column: 'AG', title: '物流（原料进货）/kg' },
  { column: 'AH', title: '物流（成品送货）/kg' },
  { column: 'AI', title: '线下供货成本/份' },
  { column: 'AJ', title: '售价' },
  { column: 'AK', title: '（AK 列无表头）' },
  { column: 'AL', title: '账期比例' },
  { column: 'AM', title: '账期费用/份' },
  { column: 'AN', title: '扣点' },
  { column: 'AO', title: '扣点费用/份' },
  { column: 'AP', title: '总成本/KG' },
  { column: 'AQ', title: '扣完成本/份' },
  { column: 'AR', title: '供货价' },
  { column: 'AS', title: '毛利率' },
  { column: 'AT', title: '促销价格' },
  { column: 'AU', title: '大促' },
] as const;

export type ArchiveColumn = (typeof ARCHIVE_FIELDS)[number]['column'];

export const DEFAULT_ARCHIVE_COLUMNS: readonly ArchiveColumn[] = [
  'B', 'C', 'E', 'K', 'M', 'N', 'AF', 'AI', 'AJ', 'AR', 'AS',
];

export const ARCHIVE_COLUMN_OPTIONS = ARCHIVE_FIELDS.map((field) => ({
  label: `${field.column} ${field.title}`,
  value: field.column,
}));

const FIXED_TABLE_COLUMNS_WIDTH = 210 + 160 + 90 + 80 + 90;

function isNumericArchiveField(fieldIndex: number): boolean {
  return fieldIndex === 2 || fieldIndex >= 10;
}

function archiveFieldWidth(fieldIndex: number): number {
  if (fieldIndex === 0) return 180;
  return isNumericArchiveField(fieldIndex) ? 130 : 140;
}

export function productArchiveTableScrollX(
  visibleColumns: readonly ArchiveColumn[],
): number {
  const visible = new Set<ArchiveColumn>(visibleColumns);
  let width = FIXED_TABLE_COLUMNS_WIDTH;
  for (let fieldIndex = 0; fieldIndex < ARCHIVE_FIELDS.length; fieldIndex += 1) {
    if (visible.has(ARCHIVE_FIELDS[fieldIndex].column)) {
      width += archiveFieldWidth(fieldIndex);
    }
  }
  return width;
}

export function productArchivesByProduct(
  rows: ProductArchiveSheet[],
): Map<string, ProductArchiveSheet> {
  const byProduct = new Map<string, ProductArchiveSheet>();
  for (const row of rows) {
    if (row.matched_product_id !== null) {
      byProduct.set(String(row.matched_product_id), row);
    }
  }
  return byProduct;
}

function archiveFieldColumns(
  archiveByProduct: ReadonlyMap<string, ProductArchiveSheet>,
  visibleColumns: ReadonlySet<ArchiveColumn>,
  startIndex: number,
  endIndex: number,
): ColumnsType<MasterDataRecord> {
  const columns: ColumnsType<MasterDataRecord> = [];
  for (let fieldIndex = startIndex; fieldIndex < endIndex; fieldIndex += 1) {
    const field = ARCHIVE_FIELDS[fieldIndex];
    if (!visibleColumns.has(field.column)) continue;
    const numeric = isNumericArchiveField(fieldIndex);
    columns.push({
      title: field.title,
      key: `archive-${field.column}`,
      width: archiveFieldWidth(fieldIndex),
      align: numeric ? 'right' : 'left',
      render: (_: unknown, record: MasterDataRecord) => {
        const productId = attr(record, 'product_id');
        const archiveRow = productId === null || productId === undefined
          ? undefined
          : archiveByProduct.get(String(productId));
        const value = archiveRow?.fields[fieldIndex]?.value;
        if (value === null || value === undefined || value === '') {
          return '—';
        }
        return numeric
          ? <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>
          : value;
      },
    });
  }
  return columns;
}

export function productArchiveColumnGroups(
  archiveByProduct: ReadonlyMap<string, ProductArchiveSheet>,
  visibleColumns: readonly ArchiveColumn[] = DEFAULT_ARCHIVE_COLUMNS,
): ColumnsType<MasterDataRecord> {
  const visible = new Set<ArchiveColumn>(visibleColumns);
  const groups = [
    { title: '基础信息', key: 'archive-basic', startIndex: 0, endIndex: 12 },
    { title: '成本构成', key: 'archive-cost', startIndex: 12, endIndex: 34 },
    { title: '供货与售价', key: 'archive-supply-price', startIndex: 34, endIndex: 47 },
  ] as const;
  const columns: ColumnsType<MasterDataRecord> = [];
  for (const group of groups) {
    const children = archiveFieldColumns(
      archiveByProduct,
      visible,
      group.startIndex,
      group.endIndex,
    );
    if (children.length > 0) {
      columns.push({ title: group.title, key: group.key, children });
    }
  }
  return columns;
}
