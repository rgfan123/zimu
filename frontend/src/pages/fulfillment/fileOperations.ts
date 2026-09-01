import type { ConfirmBlockedRow, ConfirmReadiness, ExportUsageStatus, ImportRowCounts, RawImportRow, RawImportRowErrorDetail, RawRowStatus, TrackingBatchRow } from '@/api/types';

export function summarizeImportBatch(counts: ImportRowCounts): string {
  return `共 ${counts.total} 行，已接收 ${counts.accepted} 行，待复核 ${counts.need_review} 行，拒绝 ${counts.rejected} 行`;
}

/** 确认按钮的可用性判据来源。 */
export interface ConfirmGate {
  enabled: boolean;
  label: string;
  /** 不可用时给出理由；可用时为空串 */
  disabledReason: string;
}

/**
 * 确认按钮的可用性与文案。
 *
 * 判据只认后端返回的 confirm_readiness——它就是后端确认闸门本身用的那份投影。
 * 后端没给（旧响应、非来源批次）时保守禁用并说明原因，不自己按 row_counts 猜，
 * 猜出来的口径和闸门分叉就会出现「点了才发现被拒」。
 */
export function confirmGateOf(readiness: ConfirmReadiness | undefined, confirmed: boolean): ConfirmGate {
  if (!readiness) {
    return { enabled: false, label: '确认发货', disabledReason: '批次就绪状态未知，请刷新后重试' };
  }
  if (!readiness.confirmable) {
    if (readiness.blocked_rows > 0) {
      return {
        enabled: false,
        label: '确认发货',
        disabledReason: `${readiness.blocked_rows} 行待处理，且没有可发货的行`,
      };
    }
    return {
      enabled: false,
      label: '确认发货',
      disabledReason: confirmed ? '本批次已全部确认，没有待发货的行' : '批次没有可发货的已接收行',
    };
  }
  const label = confirmed
    ? `补做确认（${readiness.pending_rows} 行待发货）`
    : `确认发货（${readiness.pending_rows} 行）`;
  return { enabled: true, label, disabledReason: '' };
}

/**
 * 部分确认的提示语：说清这次发几行、跳过几行。
 *
 * 阻断行是被跳过而不是被丢弃，所以必须同时说明它们留在批次里等补做——
 * 否则「部分确认」在用户眼里就和静默丢单没区别。
 */
export function confirmScopeHint(readiness: ConfirmReadiness | undefined): string {
  if (!readiness || !readiness.confirmable) return '';
  const shipping = `确认后 ${readiness.pending_rows} 行将写入系统订单并生成履约文件`;
  if (readiness.blocked_rows === 0) return `${shipping}。`;
  return `${shipping}；${readiness.blocked_rows} 行因待处理被跳过，仍留在本批次，处理完后可再次确认补做。`;
}

/** 阻断行按原因归并，让用户看到「为什么」而不是只有一个数字。 */
export function groupBlockedRows(
  blockers: ConfirmBlockedRow[],
): Array<{ reason: string; count: number; sampleRefs: string[] }> {
  const groups = new Map<string, { reason: string; count: number; sampleRefs: string[] }>();
  for (const blocker of blockers) {
    const reason = blocker.reason?.trim() || blocker.error_code?.trim() || '未说明原因';
    const existing = groups.get(reason);
    const ref = blocker.source_order_ref?.trim();
    if (existing) {
      groups.set(reason, {
        reason,
        count: existing.count + 1,
        sampleRefs: ref && existing.sampleRefs.length < 3 ? [...existing.sampleRefs, ref] : existing.sampleRefs,
      });
      continue;
    }
    groups.set(reason, { reason, count: 1, sampleRefs: ref ? [ref] : [] });
  }
  return [...groups.values()];
}

export function canReceiveTracking(exportKind: string, status: ExportUsageStatus): boolean {
  return exportKind === 'THIRD_PARTY' && (status === 'DOWNLOADED_WAITING_RETURN' || status === 'RETURN_OVERDUE');
}

export function canConfirmReferenceRow(row: {
  match_status: string;
  provider_sku_code?: string;
  quantity_multiplier?: number;
}): boolean {
  const multiplier = row.quantity_multiplier;
  return row.match_status === 'MATCHED'
    && Boolean(row.provider_sku_code?.trim())
    && Number.isInteger(multiplier)
    && multiplier! > 0;
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
  /**
   * 重复订单良性跳过（A12 预检：REJECTED + ORDER_ALREADY_EXISTS）。这类行没有复核事项
   * 可办，展示层不得把它指向人工复核。注意这只覆盖后端确认闸门 BENIGN 口径两个码中的
   * 重复单一支；另一支 SOURCE_ORDER_ALREADY_FULFILLED（来源侧已履约，落 NEED_REVIEW）
   * 同样无复核事项，其呈现待后续单独处理。
   */
  duplicateSkipped: boolean;
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
 * - 重复订单预检码（`SourceImportService.markRejected` 直写 error_code，A12 整组跳过）：
 *   ORDER_ALREADY_EXISTS；
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
  QUANTITY_SCALE: '商品数量必须是 int32 正整数，且乘算结果不能越界',
  // 解析期：来源行门禁（不可发货的来源事实，需回源头处理而非改文件）
  SOURCE_LINE_REF_REQUIRED: '来源行缺少子订单 ID，无法定位到唯一来源行',
  SOURCE_ORDER_TYPE_BLOCKED: '来源行不是可发货的实体销售订单',
  SOURCE_ORDER_STATUS_BLOCKED: '来源子订单状态不是明确的待发货状态',
  SOURCE_ORDER_ALREADY_FULFILLED: '来源行已有发货、收货或物流事实，不再重复发货',
  SOURCE_ORDER_REFUND_BLOCKED: '来源行存在退款事实，已停止发货',
  SOURCE_ORDER_AFTER_SALES_BLOCKED: '来源行存在售后事实，已停止发货',
  SOURCE_LINE_REF_DUPLICATE: '同一来源订单内子订单 ID 重复，需在来源文件内去重',
  // 重复订单整组跳过（良性非失败）：文案与后端 markRejected 落库 message 同句，两侧口径一致
  ORDER_ALREADY_EXISTS: '相同来源渠道与来源单号的订单已存在，本行已跳过（非失败）',
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
    quantity: parsed.quantity == null
      ? firstText(cells, ['下单数量', '数量', '可发货数量', '商品数量'])
      : String(parsed.quantity),
    // 规格：来源文件未提供（解析兜底"来源未提供"）时回退内部 SKU 主数据规格默认值
    specification: parsed.specification && parsed.specification !== '来源未提供'
      ? parsed.specification
      : row.sku_fulfillment?.sku_specification ?? '—',
    fulfillmentType: row.sku_fulfillment?.provider_type ?? null,
    reason: importIssueReason(row),
    status: row.status,
    duplicateSkipped: row.status === 'REJECTED' && row.error_code === 'ORDER_ALREADY_EXISTS',
    orderId: row.order_id ?? '—',
    orderLineId: row.order_line_id ?? '—',
    jdCargos: cargos.map((cargo) => ({
      productName: cargo.product_name,
      providerSkuCode: cargo.provider_sku_code,
      planQuantity: cargo.plan_quantity,
    })),
  };
}

/** 确认明细「状态」列文案：重复跳过（良性）必须与真拒绝分开呈现，否则重复上传整批会被读成导入失败。 */
export function importRowStatusLabel(row: Pick<ImportRowView, 'status' | 'duplicateSkipped'>): string {
  if (row.duplicateSkipped) return '重复跳过';
  if (row.status === 'ACCEPTED') return '已接收';
  if (row.status === 'REJECTED') return '已拒绝';
  return '待复核';
}

/**
 * 确认明细「操作」列的行级动作投影。
 *
 * 2026-08-31 生产实证（中汇批次 66）：重复上传整批被 A12 置为 REJECTED/ORDER_ALREADY_EXISTS，
 * 这类行没有复核事项（review_cases 为空），此前对所有非 ACCEPTED 行一律给「前往人工复核」，
 * 点过去无事可办，用户把良性跳过误判成导入失败。重复跳过行给说明文案而非复核入口；
 * 其余非接收行保持复核入口（fail-closed：未来出现未登记的 REJECTED 码时仍走复核）。
 */
export type ImportRowAction =
  | { kind: 'VIEW_ORDER' }
  | { kind: 'ORDER_LINK_MISSING' }
  | { kind: 'DUPLICATE_SKIPPED'; text: string; tooltip: string }
  | { kind: 'REVIEW' };

export function importRowAction(
  row: Pick<ImportRowView, 'status' | 'duplicateSkipped' | 'orderId' | 'sourceOrderRef'>,
): ImportRowAction {
  if (row.status === 'ACCEPTED') {
    return row.orderId === '—' ? { kind: 'ORDER_LINK_MISSING' } : { kind: 'VIEW_ORDER' };
  }
  if (row.duplicateSkipped) {
    // 已存在订单没有可直达的 ID（跳过发生在成单之前），指路方式是按来源单号去订单列表检索
    const ref = row.sourceOrderRef === '—' ? '' : `（${row.sourceOrderRef}）`;
    return {
      kind: 'DUPLICATE_SKIPPED',
      text: '重复订单已跳过',
      tooltip: `该行与已存在订单同渠道同来源单号${ref}，本次导入已自动跳过，无需人工复核；可在订单列表按来源单号搜索查看已存在订单。`,
    };
  }
  return { kind: 'REVIEW' };
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
