import type { ExportUsageStatus, ImportRowCounts, RawImportRow, RawImportRowErrorDetail, RawRowStatus, TrackingBatchRow } from '@/api/types';

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
  /** 系统订单关联仅用于「查看系统订单」操作链接，不再作为独立展示列 */
  orderId: string;
  orderLineId: string;
  /** 将/已发送京东 SDK 的精确发货数量（后端 jd_cargos 白名单投影）；第三方/无京东行为空数组 */
  jdCargos: Array<{
    productName: string;
    providerSkuCode: string;
    planQuantity: number;
  }>;
}

const SOURCE_SKU_HEADERS = ['商品编号', '商品ID', '商品编码', '商品条码', '订单商品ID', 'SKU', 'SKU编码'];
const SOURCE_PRODUCT_HEADERS = ['商品名称', '品名', '产品名称'];

/**
 * 导入行阻断原因码 → 中文文案。覆盖后端能写进 `raw_import_rows.error_code`
 * 与 `error_detail.order_line_exceptions[]` 的全部取值：
 *
 * - 解析期拒绝码（`SourceFileParser` 直写 error_code）：IMPORT_VALIDATION、QUANTITY_SCALE、
 *   SOURCE_* 七个万齐来源行门禁码；
 * - 订单行异常码（`order_line_exceptions[]` 原码，及 `SourceImportService.importErrorCode`
 *   映射后的 error_code）：SKU_MAPPING_REQUIRED→SKU_MATCH、SKU_MAPPING_CONFLICT→JD_CODE_CONFLICT；
 * - 结构化导入 Connector 的 `StructuredOrderRow.reviewRequired` code：JUFUBAO_*；
 * - 复核事项部分闭环后 `ReviewCaseResolutionService` 回写的 review reason_code：
 *   CUSTOMER_MATCH_REQUIRED→CUSTOMER_MATCH，以及 ELSE 分支原样落库的映射族原因码。
 *
 * fail-closed：未登记的码一律落兜底句，禁止把后端英文码或自由文本直接透给 operator。
 */
const IMPORT_REASON_BY_CODE: Record<string, string> = {
  // 解析期：必填值 / 数量格式
  IMPORT_VALIDATION: '来源文件的必填值或同单收货信息需要核对',
  QUANTITY_SCALE: '商品数量最多支持三位小数',
  // 解析期：来源行门禁（不可发货的来源事实，需回源头处理而非改文件）
  SOURCE_LINE_REF_REQUIRED: '来源行缺少子订单 ID，无法定位到唯一来源行',
  SOURCE_ORDER_TYPE_BLOCKED: '来源行不是可发货的实体销售订单',
  SOURCE_ORDER_STATUS_BLOCKED: '来源子订单状态不是明确的待发货状态',
  SOURCE_ORDER_ALREADY_FULFILLED: '来源行已有发货、收货或物流事实，不再重复发货',
  SOURCE_ORDER_REFUND_BLOCKED: '来源行存在退款事实，已停止发货',
  SOURCE_ORDER_AFTER_SALES_BLOCKED: '来源行存在售后事实，已停止发货',
  SOURCE_LINE_REF_DUPLICATE: '同一来源订单内子订单 ID 重复，需在来源文件内去重',
  // 客户 / SKU 映射族（error_code 与 order_line_exceptions 两种口径同时登记）
  CUSTOMER_MATCH: '客户身份尚未建立明确映射',
  CUSTOMER_MATCH_REQUIRED: '客户身份尚未建立明确映射',
  SKU_MATCH: '来源商品尚未建立 SKU 映射',
  JD_CODE_CONFLICT: 'SKU 映射或京东商品编码存在冲突',
  SKU_MAPPING_REQUIRED: '来源商品尚未建立 SKU 映射',
  SKU_MAPPING_CONFLICT: '来源商品对应多个 SKU，需要人工确认',
  SOURCE_SKU_MAPPING_REQUIRED: '来源商品尚未建立 SKU 映射',
  PROVIDER_SKU_MAPPING_REQUIRED: '内部 SKU 尚未建立履约方商品编码映射',
  MAPPING_MULTIPLIER: '来源 SKU 映射缺少有效的数量换算倍数',
  // 结构化导入（Connector transform 判定来源证据不足以建单）
  JUFUBAO_RECEIVER_REQUIRED: '来源订单缺少完整收货信息',
  JUFUBAO_QUANTITY_INVALID: '来源订单商品数量缺失或不是正整数',
  JUFUBAO_CREATED_TIME_REQUIRED: '来源订单缺少有效的创建时间',
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

/**
 * 后端写的是复数数组 `error_detail.order_line_exceptions`（`SourceImportService` 逐行
 * 收集该来源行拆出的全部订单行异常码），一行拆多行时可能同时带多个不同的码，
 * 因此逐个取文案并去重后合并展示——只取首个会瞒掉另一半阻断原因。
 * 未登记的码按 fail-closed 丢弃，全部未登记时返回空串交由 error_code 兜底。
 */
function lineExceptionReason(detail: RawImportRowErrorDetail): string {
  const codes = detail.order_line_exceptions;
  if (!Array.isArray(codes)) return '';
  const reasons: string[] = [];
  for (const code of codes) {
    if (typeof code !== 'string') continue;
    const reason = IMPORT_REASON_BY_CODE[code];
    if (reason && !reasons.includes(reason)) reasons.push(reason);
  }
  return reasons.join('；');
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

  const lineExceptions = lineExceptionReason(detail);
  if (lineExceptions) return lineExceptions;

  return IMPORT_REASON_BY_CODE[row.error_code ?? '']
    ?? (row.status === 'REJECTED' ? '该行未被接收，请核对源文件后重新导入' : '该行需要人工复核');
}

/** 把原始行收敛成文件作业可展示的安全字段，禁止 JSON dump 和 PII 透出。 */
export function presentImportRow(row: RawImportRow): ImportRowView {
  const cells = objectCells(row.raw_cells);
  const parsed = row.parsed ?? {};
  const cargos = Array.isArray(row.jd_cargos) ? row.jd_cargos : [];
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
    jdCargos: cargos.map((cargo) => ({
      productName: cargo.product_name,
      providerSkuCode: cargo.provider_sku_code,
      planQuantity: cargo.plan_quantity,
    })),
  };
}

/** 京东「发货数量」单元格文案：单货品直接「N 件」；多货品必须带商品名逐行列出；无京东货品为「—」。 */
export function presentJdCargos(cargos: ImportRowView['jdCargos']): string {
  if (cargos.length === 0) return '—';
  if (cargos.length === 1) return `${cargos[0].planQuantity} 件`;
  return cargos.map((cargo) => `${cargo.productName}: ${cargo.planQuantity} 件`).join('\n');
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
