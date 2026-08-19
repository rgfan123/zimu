export interface DisplayRow {
  label: string;
  value: string;
}

/** detail 里缺字段时的统一呈现，代替静默丢弃整行。 */
export const SOURCE_NOT_PROVIDED = '来源未提供';

/** SKU 映射类复核事项的 reason_code 全集；抽屉对它们逐条展示来源商品明细。 */
export const SKU_MAPPING_REASON_CODES: readonly string[] = [
  'SKU_MAPPING_REQUIRED',
  'SKU_MAPPING_CONFLICT',
  'SOURCE_SKU_MAPPING_REQUIRED',
  'PROVIDER_SKU_MAPPING_REQUIRED',
];

export function isSkuMappingReasonCode(reasonCode: string): boolean {
  return SKU_MAPPING_REASON_CODES.includes(reasonCode);
}

const EVENT_FIELD_LABELS: Record<string, string> = {
  sku_code: 'SKU',
  product_name: '商品',
  specification: '规格',
  quantity: '数量',
  requested_quantity: '申请数量',
  shipped_quantity: '发货数量',
  available_quantity: '可用数量',
  outbound_order_no: '出库单号',
  shipment_no: '发货单号',
  tracking_number: '运单号',
  logistics_company: '物流公司',
  provider_code: '履约方',
  provider_name: '履约方',
  source_ref: '来源单号',
  source_channel: '来源渠道',
  carrier: '承运商',
  reason: '原因',
  change_reason: '变更原因',
  revision_no: '修订号',
};

const REVIEW_FIELD_LABELS: Record<string, string> = {
  shipment_id: '发货批次编号',
  check_run_no: '映射核对批次',
  source_customer_ref: '来源客户编号',
  customer_name: '客户',
  line_no: '订单行',
  missing_source_sku_refs: '待映射来源商品',
  source_sku_ref: '来源商品编号',
  product_name: '商品',
  requested_quantity: '申请数量',
  provider_code: '履约方',
  source_version: '来源版本',
  change_reason: '变更原因',
  carrier: '承运商',
  resolution_type: '处理结果',
  customer_id: '已确认客户编号',
  sku_id: '已确认 SKU 编号',
  source_channel: '来源渠道',
  quantity_multiplier: '数量换算',
  remark: '处理依据',
  note: '处理依据',
  // ---- SKU 映射复核放行的「来源原始商品信息」----
  // 逐条说明为什么不是 PII：
  // source_product_name/source_specification/source_unit：来源渠道提供的商品目录属性
  //   （名称/规格/计量单位），是运营对应内部 SKU 所必需的描述性业务数据；不是个人
  //   姓名、联系方式或地址，且与既有白名单 product_name（商品）同属商品目录信息，
  //   订单行快照本就公开展示。
  // source_quantity：来源渠道给的商品数量，与既有白名单 requested_quantity（申请数量）
  //   同类业务数值，不包含任何个人身份数据。
  // source_sheet_name/source_row_index：来源 Excel/结构化载荷中该行所在位置，是文件结构
  //   元数据（工作表名 + 行号），不指向任何个人数据。
  source_product_name: '来源商品名称',
  source_specification: '来源规格',
  source_unit: '来源单位',
  source_quantity: '来源数量',
  source_sheet_name: '来源工作表',
  source_row_index: '来源行号',
};

const AUDIT_FIELD_LABELS: Record<string, string> = {
  order_no: '订单号',
  source_ref: '来源单号',
  source_channel: '来源渠道',
  product_name: '商品',
  provider_code: '履约方',
  provider_name: '履约方',
  export_id: '导出任务编号',
  export_batch_no: '导出批次',
  import_batch_id: '导入批次编号',
  batch_no: '批次号',
  file_name: '文件名',
  row_count: '数据行数',
  item_count: '明细数',
  result: '处理结果',
  status: '状态',
};

const JD_FIELD_LABELS: Record<string, string> = {
  ownerno: '事业部编码',
  ownername: '事业部名称',
  warehouseno: '仓库编码',
  warehousename: '仓库名称',
  warehouseorderno: '京东出库单号',
  erpdeliveryno: '系统出库单号',
  goodsno: '商品编码',
  goodsname: '商品名称',
  status: '业务状态',
  statusname: '业务状态',
  carriername: '承运商',
  waybillno: '运单号',
  trackingno: '运单号',
  availablequantity: '可用数量',
};

function scalarValue(value: unknown): string | null {
  if (typeof value === 'string') {
    const trimmed = value.trim();
    return trimmed || null;
  }
  if (typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  if (Array.isArray(value)) {
    const values = value.map(scalarValue);
    return values.every((item) => item !== null) && values.length
      ? (values as string[]).join('、')
      : null;
  }
  return null;
}

function approvedRows(
  payload: Record<string, unknown> | null | undefined,
  labels: Record<string, string>,
): DisplayRow[] {
  if (!payload) return [];
  return Object.entries(payload).flatMap(([key, raw]) => {
    const label = labels[key];
    const value = scalarValue(raw);
    return label && value !== null ? [{ label, value }] : [];
  });
}

export function safeEventPayloadRows(payload: Record<string, unknown>): DisplayRow[] {
  return approvedRows(payload, EVENT_FIELD_LABELS);
}

export function reviewCaseSummary(reviewCase: {
  reason_code: string;
  detail: Record<string, unknown>;
}): string {
  const rows = approvedRows(reviewCase.detail, REVIEW_FIELD_LABELS);
  return rows.length
    ? rows.map(({ label, value }) => `${label}：${value}`).join('；')
    : '请前往人工复核工作台查看并处理';
}

export function safeReviewDetailRows(detail: Record<string, unknown>): DisplayRow[] {
  return approvedRows(detail, REVIEW_FIELD_LABELS);
}

/**
 * SKU 映射复核抽屉的固定展示字段（含来源文件位置）。只读取白名单内的键（fail-closed），
 * 缺失/空白时以「来源未提供」呈现，而不是整行消失——静默丢弃正是本票要消灭的行为。
 */
const SKU_MAPPING_DETAIL_FIELDS: ReadonlyArray<readonly [keyof typeof REVIEW_FIELD_LABELS, string]> = [
  ['source_channel', '来源渠道'],
  ['line_no', '订单行'],
  ['source_sheet_name', '来源工作表'],
  ['source_row_index', '来源行号'],
  ['missing_source_sku_refs', '待映射来源商品'],
  ['source_product_name', '来源商品名称'],
  ['source_specification', '来源规格'],
  ['source_unit', '来源单位'],
  ['source_quantity', '来源数量'],
];

export function skuMappingDetailRows(detail: Record<string, unknown>): DisplayRow[] {
  return SKU_MAPPING_DETAIL_FIELDS.map(([key, label]) => ({
    label,
    value: scalarValue(detail[key]) ?? SOURCE_NOT_PROVIDED,
  }));
}

export interface SkuMappingEvidenceItem {
  sourceSkuRef: string | null;
  productName: string | null;
  specification: string | null;
  unit: string | null;
  quantity: string | null;
}

/** 结构化证据单元格：标量缺失/空白时统一呈现「来源未提供」。 */
export function skuMappingEvidenceCell(value: unknown): string {
  return scalarValue(value) ?? SOURCE_NOT_PROVIDED;
}

/**
 * SKU 映射复核的结构化证据：逐个被阻断商品一行，绝不合并成一串编号。
 * 优先读后端 evidence_items（对象数组，仅取固定白名单同源字段、标量强制、fail-closed）；
 * 缺失时从白名单字段 missing_source_sku_refs 退化为逐编号一行，其余单元格由调用方
 * 以「来源未提供」呈现。
 */
export function skuMappingEvidenceItems(detail: Record<string, unknown>): SkuMappingEvidenceItem[] {
  const rawItems = Array.isArray(detail.evidence_items) ? detail.evidence_items : [];
  const parsed = rawItems
    .filter((raw): raw is Record<string, unknown> => Boolean(raw) && typeof raw === 'object')
    .map((item) => ({
      sourceSkuRef: scalarValue(item.source_sku_ref),
      productName: scalarValue(item.product_name),
      specification: scalarValue(item.specification),
      unit: scalarValue(item.unit),
      quantity: scalarValue(item.quantity),
    }));
  if (parsed.length) return parsed;
  const refs = Array.isArray(detail.missing_source_sku_refs)
    ? detail.missing_source_sku_refs
        .map(scalarValue)
        .filter((value): value is string => value !== null)
    : [];
  return refs.map((sourceSkuRef) => ({
    sourceSkuRef,
    productName: null,
    specification: null,
    unit: null,
    quantity: null,
  }));
}

export function safeAuditPayloadRows(
  payload: Record<string, unknown> | null | undefined,
): DisplayRow[] {
  return approvedRows(payload, AUDIT_FIELD_LABELS);
}

export function displayOperator(operator: string | null | undefined): string {
  if (!operator || /^(system|seed-runner|demo-runner|jd-client)$/i.test(operator)) {
    return '系统';
  }
  return operator;
}

const AUDIT_SERVICE_LABELS: Record<string, string> = {
  order: '订单',
  fulfillment: '履约',
  'provider-tracking': '运单回传',
  'source-file-import': '来源订单导入',
  'source-return-export': '来源回填',
  'fulfillment-export': '履约导出',
  'jd.isc': '京东仓配',
  MasterDataService: '基础资料',
  ProcurementService: '采购',
  FulfillmentStockDecisionService: '履约库存',
  OperationalAlertService: '异常提醒',
  ReviewCaseResolutionService: '人工复核',
  demo: '演示流程',
};

export function auditServiceLabel(service: string): string {
  if (service.startsWith('connector.')) return '渠道连接';
  return AUDIT_SERVICE_LABELS[service] ?? '业务服务';
}

const AUDIT_OPERATION_LABELS: Record<string, string> = {
  'order.create': '创建订单',
  'order.revise': '修订订单',
  'source-orders.upload': '来源订单导入',
  'file.upload': '回传文件导入',
  'file.download': '文件下载',
  'tracking.accept': '接收运单',
  'procurement.receipt': '登记采购结果',
  'procurement.retry': '重试采购',
  'procurement.cancel_remaining': '取消剩余采购',
  'operational_alert.acknowledge': '确认异常提醒',
  queryOwners: '查询授权事业部',
  queryWarehouses: '查询京东仓库',
  queryProducts: '查询京东商品',
  queryStock: '查询京东库存',
  queryOutboundOrder: '查询京东发货信息',
  queryTracking: '查询京东运单',
  createOutboundOrder: '创建京东出库单',
  cancelOutboundOrder: '取消京东出库单',
  updateConfig: '更新渠道配置',
  testConnection: '检查渠道连接',
  'demo.run': '运行演示流程',
};

export function auditOperationLabel(operation: string): string {
  if (operation.startsWith('review_case.')) return '处理人工复核';
  if (operation.startsWith('master_data.')) return '维护基础资料';
  return AUDIT_OPERATION_LABELS[operation] ?? '其他业务操作';
}

function collectJdRows(value: unknown, rows: DisplayRow[]): void {
  if (rows.length >= 24 || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (const item of value) collectJdRows(item, rows);
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= 24) return;
    const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    const label = JD_FIELD_LABELS[normalized];
    const scalar = scalarValue(raw);
    if (label && scalar !== null) {
      rows.push({ label, value: scalar });
    } else if (typeof raw === 'object') {
      collectJdRows(raw, rows);
    }
  }
}

export interface JdQueryPresentation {
  title: string;
  description: string;
  tone: 'success' | 'warning';
  rows: DisplayRow[];
}

export function jdQueryPresentation(
  mode: 'MOCK' | 'REAL',
  kind: 'owners' | 'warehouses' | 'outbound',
  result: { success: boolean; data?: unknown },
): JdQueryPresentation {
  const subject = kind === 'owners' ? '事业部' : kind === 'warehouses' ? '仓库权限' : '发货信息';
  const rows: DisplayRow[] = [];
  if (result.success) collectJdRows(result.data, rows);
  if (mode === 'MOCK') {
    return result.success
      ? {
          title: `模拟${subject}查询完成（不代表真实权限）`,
          description: '当前结果由模拟数据生成，仅用于流程演示，不能证明真实京东权限或业务状态。',
          tone: 'success',
          rows,
        }
      : {
          title: `模拟${subject}查询未完成`,
          description: '模拟查询暂未完成，请稍后重试；若持续发生，请联系管理员。',
          tone: 'warning',
          rows: [],
        };
  }
  return result.success
    ? {
        title: `真实${subject}查询完成`,
        description: '京东仓配已返回结果，页面仅展示经确认的业务字段。',
        tone: 'success',
        rows,
      }
    : {
        title: `真实${subject}查询未完成`,
        description: '请检查京东仓配授权状态后重试；若持续发生，请联系管理员。',
        tone: 'warning',
        rows: [],
      };
}
