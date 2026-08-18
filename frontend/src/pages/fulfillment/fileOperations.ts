import type { ExportUsageStatus, ImportRowCounts, RawImportRow, RawRowStatus, TrackingBatchRow } from '@/api/types';

export function summarizeImportBatch(counts: ImportRowCounts): string {
  return `共 ${counts.total} 行，已接收 ${counts.accepted} 行，待复核 ${counts.need_review} 行，拒绝 ${counts.rejected} 行`;
}

export function canReceiveTracking(exportKind: string, status: ExportUsageStatus): boolean {
  return exportKind === 'THIRD_PARTY' && (status === 'DOWNLOADED_WAITING_RETURN' || status === 'RETURN_OVERDUE');
}

export function canConfirmReferenceRow(row: {
  match_status: string;
  provider_sku_code?: string;
  quantity_multiplier?: string | number;
}): boolean {
  const multiplier = Number(row.quantity_multiplier);
  return row.match_status === 'MATCHED' && Boolean(row.provider_sku_code?.trim()) && Number.isFinite(multiplier) && multiplier > 0;
}

export interface ImportRowView {
  id: string;
  sheet: string;
  row: number;
  sourceOrderRef: string;
  sourceSkuRef: string;
  sourceProductName: string;
  /** 渠道模板解析投影（后端白名单）；用于核对基本信息是否解析正确 */
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  productName: string;
  quantity: string;
  specification: string;
  /** 履约归属类型（内部 SKU 主数据）；标签映射在页面层 PROVIDER_TYPE_LABELS，此处不透出中文以免双源 */
  fulfillmentType: 'JD_WAREHOUSE' | 'THIRD_PARTY' | null;
  reason: string;
  status: RawRowStatus;
  orderId: string;
  orderLineId: string;
}

const SOURCE_SKU_HEADERS = ['商品编号', '商品ID', '商品编码', '商品条码', '订单商品ID', 'SKU', 'SKU编码'];
const SOURCE_PRODUCT_HEADERS = ['商品名称', '品名', '产品名称'];

const IMPORT_REASON_BY_CODE: Record<string, string> = {
  IMPORT_VALIDATION: '来源文件的必填值或同单收货信息需要核对',
  QUANTITY_SCALE: '商品数量最多支持三位小数',
  CUSTOMER_MATCH: '客户身份尚未建立明确映射',
  CUSTOMER_MATCH_REQUIRED: '客户身份尚未建立明确映射',
  SKU_MATCH: '来源商品尚未建立 SKU 映射',
  JD_CODE_CONFLICT: 'SKU 映射或京东商品编码存在冲突',
  SKU_MAPPING_REQUIRED: '来源商品尚未建立 SKU 映射',
  SKU_MAPPING_CONFLICT: '来源商品对应多个 SKU，需要人工确认',
};

const SAFE_IMPORT_MESSAGES = new Set([
  '来源行缺少订单号、收货人、商品或数量必填值',
  '数量必须大于 0',
  '数量格式非法',
  '数量最多三位小数',
  '同一来源订单的收货人快照不一致',
]);

function objectCells(value: RawImportRow['raw_cells']): Record<string, unknown> {
  return !Array.isArray(value) && value && typeof value === 'object' ? value : {};
}

function firstText(cells: Record<string, unknown>, keys: string[]): string {
  for (const key of keys) {
    const value = cells[key];
    if (typeof value === 'string' && value.trim()) return value.trim();
    if (typeof value === 'number' && Number.isFinite(value)) return String(value);
  }
  return '—';
}

function importIssueReason(row: RawImportRow): string {
  if (row.status === 'ACCEPTED') {
    return row.order_id && row.order_line_id
      ? '已写入系统订单'
      : '已接收，但尚未建立系统订单关联';
  }
  const detail = row.error_detail ?? {};
  const safeMessage = typeof detail.message === 'string' ? detail.message.trim() : '';
  if (SAFE_IMPORT_MESSAGES.has(safeMessage)) return safeMessage;

  const lineException = typeof detail.order_line_exception === 'string' ? detail.order_line_exception : '';
  return IMPORT_REASON_BY_CODE[lineException]
    ?? IMPORT_REASON_BY_CODE[row.error_code ?? '']
    ?? (row.status === 'REJECTED' ? '该行未被接收，请核对源文件后重新导入' : '该行需要人工复核');
}

/** 把原始行收敛成文件作业可展示的安全字段，禁止 JSON dump 和 PII 透出。 */
export function presentImportRow(row: RawImportRow): ImportRowView {
  const cells = objectCells(row.raw_cells);
  const parsed = row.parsed ?? {};
  return {
    id: row.id,
    sheet: row.sheet_name || `Sheet ${row.sheet_index + 1}`,
    row: row.row_index,
    sourceOrderRef: row.source_order_ref?.trim() || '—',
    sourceSkuRef: firstText(cells, SOURCE_SKU_HEADERS),
    sourceProductName: firstText(cells, SOURCE_PRODUCT_HEADERS),
    // 解析投影优先展示后端白名单字段；缺失时回退原始单元格提取，保证核对信息可见
    receiverName: parsed.receiver_name ?? firstText(cells, ['收货人', '收货人姓名']),
    receiverPhone: parsed.receiver_phone ?? firstText(cells, ['联系电话', '收货人电话', '收货人手机号']),
    receiverAddress: parsed.receiver_address ?? firstText(cells, ['收货人地址', '收货地址', '详细地址']),
    productName: parsed.product_name ?? firstText(cells, SOURCE_PRODUCT_HEADERS),
    quantity: parsed.quantity ?? firstText(cells, ['下单数量', '数量', '可发货数量', '商品数量']),
    // 规格：来源文件未提供（解析兜底"来源未提供"）时回退内部 SKU 主数据规格默认值
    specification: parsed.specification && parsed.specification !== '来源未提供'
      ? parsed.specification
      : row.sku_fulfillment?.sku_specification ?? '—',
    fulfillmentType: row.sku_fulfillment?.provider_type ?? null,
    reason: importIssueReason(row),
    status: row.status,
    orderId: row.order_id ?? '—',
    orderLineId: row.order_line_id ?? '—',
  };
}

export interface TrackingBatchRowView {
  id: string;
  rowIndex: number;
  outboundOrderNo: string;
  result: string;
  actualQuantity: string;
  carrier: string;
  trackingNo: string;
  failureReason: string;
}

const TRACKING_RESULT_LABEL: Record<string, string> = {
  SHIPPED: '已发货',
  PARTIAL: '部分发货',
  FAILED: '失败',
};

/** 回传批次逐行结果视图：只取回传文件的业务列（结果/实发数量/快递/单号/异常原因），不透出收件人隐私。 */
export function presentTrackingBatchRow(row: TrackingBatchRow): TrackingBatchRowView {
  const cells = objectCells(row.raw_cells);
  const result = firstText(cells, ['结果']);
  return {
    id: row.id,
    rowIndex: row.row_index,
    // 后端 DTO 镜像字段名：source_order_ref 在回传批次中实际承载"出库单号"，非来源订单引用
    outboundOrderNo: row.source_order_ref?.trim() || '—',
    result: TRACKING_RESULT_LABEL[result] ?? (result || '—'),
    actualQuantity: firstText(cells, ['实际发货数量']),
    carrier: firstText(cells, ['快递公司']),
    trackingNo: firstText(cells, ['物流单号']),
    failureReason: firstText(cells, ['异常原因']),
  };
}
