/**
 * API 类型定义 —— 严格对照 docs/openapi.yaml schemas（snake_case）。
 * 所有标识符为字符串（避免 BIGINT 精度丢失）；数量为十进制字符串。
 */

// ---------- 枚举 ----------

export type SourceChannel = 'CAISHIXIAN' | 'JUFUBAO' | 'FEIXIANG' | 'ZHONGHUI' | 'WECOM';

export type OrderStatus =
  | 'RECEIVED'
  | 'VALIDATED'
  | 'SKU_MAPPED'
  | 'FULFILLING'
  | 'SHIPPED'
  | 'SYNCED'
  | 'CLOSED'
  | 'NEED_REVIEW'
  | 'OUT_OF_STOCK'
  | 'PROCUREMENT_PENDING'
  | 'FULFILLMENT_EXCEPTION'
  | 'SYNC_FAILED'
  | 'CANCELLED';

export type ProcessingStage =
  | 'NEED_REVIEW'
  | 'READY_TO_EXPORT'
  | 'PROCUREMENT_IN_PROGRESS'
  | 'WAITING_PROVIDER'
  | 'TRACKING_RECEIVED'
  | 'RETURN_FILE_READY'
  | 'COMPLETED'
  | 'EXCEPTION';

export type ProcessingHealth = 'BLUE' | 'YELLOW' | 'RED' | 'GREEN';

export type ShippingProgress = 'NOT_SHIPPED' | 'PARTIALLY_SHIPPED' | 'SHIPPED';

export type FulfillmentOutcome = 'IN_PROGRESS' | 'FULLY_FULFILLED' | 'PARTIALLY_FULFILLED' | 'CANCELLED';

export type ShipmentStatus = 'CREATED' | 'SHIPPED' | 'FAILED' | 'DELIVERED';

export type DemoRunStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export type Severity = 'YELLOW' | 'RED';

// ---------- 通用 ----------

export interface PageMeta {
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
}

export interface FieldError {
  field: string;
  code: string;
  message: string;
}

export interface ApiErrorBody {
  business_code?: string;
  message?: string;
  http_status?: number;
  request_id?: string;
  trace_id?: string;
  field_errors?: FieldError[];
  details?: Record<string, unknown>;
}

// ---------- 订单域 ----------

export interface Receiver {
  name: string;
  phone: string;
  province: string;
  city: string;
  district: string;
  town: string;
  address: string;
}

export interface Settlement {
  method: string;
  settlement_time?: string;
}

export interface OrderSummary {
  id: string;
  order_no: string;
  source_channel: SourceChannel;
  source_ref?: string;
  customer_id?: string;
  customer_name?: string;
  receiver_name?: string;
  order_status: OrderStatus;
  processing_stage: ProcessingStage;
  processing_health: ProcessingHealth;
  completed_count: number;
  total_count: number;
  attention_reason?: string;
  created_at: string;
  updated_at?: string;
  version: number;
}

export interface OrderPage extends PageMeta {
  items: OrderSummary[];
}

export interface OrderLineComponent {
  id: string;
  sku_id: string;
  product_name: string;
  specification: string;
  unit: string;
  quantity_per_bundle: string;
  total_quantity: string;
}

export interface OrderLine {
  id: string;
  line_no: number;
  line_type: 'SINGLE' | 'CUSTOM_BUNDLE';
  sku_id?: string;
  sku_code?: string;
  provider_id?: string;
  product_name: string;
  specification: string;
  unit: string;
  source_quantity: string;
  mapping_multiplier?: string;
  requested_quantity: string;
  processing_stage: ProcessingStage;
  exception_code?: string;
  components?: OrderLineComponent[];
}

export interface ReviewCase {
  id: string;
  case_no: string;
  case_type: string;
  responsible_team: string;
  reason_code: string;
  status: ReviewCaseStatus;
  order_id?: string;
  order_line_id?: string;
  subject_type: string;
  subject_id: string;
  detail: Record<string, unknown>;
  suggestions: Array<Record<string, unknown>>;
  allowed_actions: Array<
    | 'RESOLVE_CUSTOMER'
    | 'RESOLVE_SKU'
    | 'RESOLVE_CARRIER'
    | 'RESOLVE_IMPORT_DATA'
    | 'COMPLETE_SOURCE_FOLLOWUP'
    | 'CONFIRM_ORDER_DRAFT'
    | 'REJECT_ORDER_DRAFT'
    | 'CONFIRM_TRACKING_DRAFT'
    | 'REJECT_TRACKING_DRAFT'
    | 'OPEN_SKU_MAPPING'
    | 'RERUN_JD_SKU_MAPPING_CHECK'
    | 'RERUN_JD_STOCK_CHECK'
    | 'REINTERPRET'
    | 'REJECT'
    | 'RESOLVE_MANUALLY'
    | 'RESOLVE_JD_TRACKING_CONFLICT'
    | 'DISMISS'
  >;
  resolution?: Record<string, unknown>;
  resolved_by?: string;
  resolved_at?: string;
  version: number;
  created_at: string;
}

export interface OrderDetail extends OrderSummary {
  receiver: Receiver;
  settlement: Settlement;
  remark?: string;
  lines: OrderLine[];
  review_cases: ReviewCase[];
}

export interface OrderEvent {
  id: string;
  sequence_no: number;
  event_type_code: string;
  order_line_id?: string;
  fulfillment_id?: string;
  shipment_id?: string;
  procurement_ticket_id?: string;
  operator: string;
  payload: Record<string, unknown>;
  created_at: string;
}

export interface OrderVersion {
  version_no: number;
  source_version?: string;
  change_reason: string;
  triggered_by: string;
  snapshot: Record<string, unknown>;
  created_at: string;
}

// ---------- 发货 / 运单 ----------

export interface Tracking {
  id: string;
  logistics_company_code: string;
  logistics_company_name: string;
  tracking_number: string;
  provider_tracking_batch_id?: string;
  received_at: string;
}

export interface ShipmentItem {
  fulfillment_id: string;
  order_line_id: string;
  product_name: string;
  instructed_quantity: string;
  shipped_quantity: string;
  unit: string;
}

export type JdOutboundSyncStatus = 'SUBMITTING' | 'SUBMITTED' | 'SYNC_FAILED';
export type JdClientMode = 'MOCK' | 'REAL';
export type JdOutboundAttemptMode = JdClientMode | 'UNKNOWN';

export interface ShipmentJdOutbound {
  erp_delivery_no: string;
  jd_delivery_no?: string | null;
  sync_status: JdOutboundSyncStatus;
  failure_phase?: 'VALIDATION' | 'SUBMIT' | null;
  retry_count: number;
  retryable: boolean;
  client_mode: JdOutboundAttemptMode;
  last_error_code?: string | null;
  last_error_message?: string | null;
  submitted_at?: string | null;
  tracking_query_status?: 'NOT_QUERIED' | 'PENDING' | 'PARTIAL' | 'TRACKED' | 'CONFLICT' | 'QUERY_FAILED' | 'TERMINAL_REVIEWED';
  tracking_query_attempt_count?: number;
  tracking_last_query_at?: string | null;
  tracking_last_error_code?: string | null;
  tracking_last_error_message?: string | null;
  updated_at?: string;
}

export interface ShipmentJdOutboundPreviewBlocker {
  code: string;
  path: string;
  source: string;
  correction_target: string;
  message: string;
}

export interface ShipmentJdOutboundPreview {
  shipment_id: string;
  shipment_version: number;
  erp_delivery_no: string;
  request_hash: string;
  submittable: boolean;
  request: Record<string, unknown>;
  validations: Array<{
    path: string;
    status: 'PASS' | 'BLOCKED' | 'OMITTED';
    source: string;
    message?: string | null;
  }>;
  blockers: ShipmentJdOutboundPreviewBlocker[];
  manual_correction_source?: string | null;
}

export interface ShipmentJdOutboundSubmitResult {
  shipment_id: string;
  erp_delivery_no: string;
  jd_delivery_no?: string;
  outbound_order_no: string;
  sync_status: 'SUBMITTED';
  retry_count: number;
  plan_quantity: number;
  goods_count: number;
}

export interface Shipment {
  id: string;
  shipment_no: string;
  order_id: string;
  provider_id?: string;
  outbound_order_no?: string;
  shipment_sequence: number;
  shipment_status: ShipmentStatus;
  receiver: Receiver;
  items: ShipmentItem[];
  tracking?: Tracking;
  jd_outbound?: ShipmentJdOutbound | null;
  shipped_at?: string | null;
  created_at: string;
  updated_at: string;
}

/** 订单详情中的京东履约白名单：不含重试、错误消息或请求诊断。 */
export interface OrderShipmentJdOutbound {
  erp_delivery_no: string;
  jd_delivery_no: string | null;
  sync_status: JdOutboundSyncStatus;
  failure_phase: 'VALIDATION' | 'SUBMIT' | null;
  tracking_query_status: 'NOT_QUERIED' | 'PENDING' | 'PARTIAL' | 'TRACKED' | 'CONFLICT' | 'QUERY_FAILED' | 'TERMINAL_REVIEWED';
  updated_at: string;
}

/** 订单详情中的 Shipment 白名单：不下发收件人快照。 */
export type OrderShipment = Omit<
  Shipment,
  'receiver' | 'provider_id' | 'outbound_order_no' | 'tracking' | 'jd_outbound' | 'shipped_at'
> & {
  provider_id: string;
  outbound_order_no: string;
  tracking: Tracking | null;
  jd_outbound: OrderShipmentJdOutbound | null;
  shipped_at: string | null;
};

// ---------- 工作台 ----------

export interface DashboardTrendPoint {
  business_date: string;
  order_count: number;
  shipped_order_count: number;
}

export interface DashboardAttentionItem {
  reason_code: string;
  count: number;
  severity: Severity;
}

export interface DashboardSummary {
  business_date: string;
  order_count: number;
  shipped_order_count: number;
  pending_review_count: number;
  trend: DashboardTrendPoint[];
  attention: DashboardAttentionItem[];
}

// ---------- 数据中台（B5 使用，类型先备好） ----------

/**
 * 渠道×日期指标（GET /api/v1/analytics/channels）。
 * openapi 声明字段为基础；metric_date 及 v_channel_daily 视图列按「渠道×日期」
 * 描述（契约 §4.7 / schema.sql v_channel_daily）作为可选扩展 —— 后端返回
 * 按天行时逐日图表可用，仅返回聚合行时退化为单周期口径。
 */
export interface ChannelMetric {
  source_channel: SourceChannel;
  order_count: number;
  canonical_quantity: string;
  shipped_quantity: string;
  /** 自然日（Asia/Shanghai）；视图 v_channel_daily.metric_date */
  metric_date?: string;
  order_line_count?: number;
  actual_shipped_quantity?: string;
  shipment_count?: number;
  exception_order_count?: number;
  out_of_stock_order_count?: number;
  sync_failed_count?: number;
}

/**
 * 渠道×商品/SKU/品类×日期指标（GET /api/v1/analytics/products）。
 * 扩展字段对应 v_product_daily；礼包已展开为组件（canonical 件数口径）。
 */
export interface ProductMetric {
  sku_id: string;
  sku_code: string;
  sku_name: string;
  canonical_quantity: string;
  shipped_quantity: string;
  metric_date?: string;
  source_channel?: SourceChannel;
  product_id?: string;
  product_code?: string;
  product_name?: string;
  category_id?: string;
  category_code?: string;
  category_name?: string;
  order_count?: number;
  shipment_count?: number;
  actual_shipped_quantity?: string;
  source_mappings?: Array<{
    source_sku_ref: string;
    source_product_name?: string;
    source_specification?: string;
    quantity_multiplier?: string | number;
  }>;
  jd_sku_codes?: string[];
}

/**
 * 履约状态计数（GET /api/v1/analytics/fulfillments）。
 * 扩展字段对应 v_fulfillment_daily（待出库/已出库/待运单/回传等状态计数）。
 */
export interface FulfillmentMetric {
  source_channel?: SourceChannel;
  provider_id: string;
  provider_code: string;
  shipment_count: number;
  shipped_quantity: string;
  average_tracking_hours: number;
  metric_date?: string;
  provider_name?: string;
  provider_type?: 'JD_WAREHOUSE' | 'THIRD_PARTY';
  fulfillment_count?: number;
  fulfilled_quantity?: string;
  not_shipped_count?: number;
  partially_shipped_count?: number;
  fully_shipped_count?: number;
  procurement_ticket_count?: number;
  out_of_stock_fulfillment_count?: number;
  awaiting_shipment_count?: number;
  shipped_shipment_count?: number;
  awaiting_tracking_count?: number;
  tracking_received_count?: number;
  awaiting_sync_count?: number;
  sync_failed_count?: number;
  synced_count?: number;
}

// ---------- 主数据 / 系统配置 ----------

/** 主数据通用行：商品/品类/SKU/两类 SKU 映射共用（openapi MasterDataRecord）。
 *  特定字段（规格、单位、乘数等）按 openapi 落在 attributes 附加属性。 */
export interface MasterDataRecord {
  id: string;
  code: string;
  name: string;
  active: boolean;
  version: number;
  attributes?: Record<string, unknown>;
  created_at?: string;
  updated_at?: string;
}

export interface MasterDataPage extends PageMeta {
  items: MasterDataRecord[];
}

/** SKU 响应属性：价格字段始终存在，null 仅表示未定价。 */
export interface SkuAttributes {
  [key: string]: unknown;
  product_id: string;
  provider_id: string;
  specification: string;
  unit: string;
  barcode?: string | null;
  purchase_price: string | null;
  retail_price: string | null;
  jd_emg_no?: string | null;
}

export interface SkuRecord extends Omit<MasterDataRecord, 'attributes'> {
  attributes: SkuAttributes;
}

export interface SkuPage extends Omit<MasterDataPage, 'items'> {
  items: SkuRecord[];
}

/** 主图上传结果：内容寻址引用与可访问 URL（openapi ProductImageUploadResult）。 */
export interface ProductImageUploadResult {
  file_ref: string;
  url: string;
}

export interface JdPiecesCandidate {
  provider_sku_code: string;
  sku_id: string;
  unit?: string | null;
  specification?: string | null;
  source_specification?: string | null;
  source_product_name?: string | null;
  candidate?: string | null;
  configured?: string | null;
}

export interface JdPiecesImportRow {
  provider_sku_code: string;
  jd_pieces_per_unit: string;
  status: string;
}

export interface JdPiecesImportResult {
  accepted_count: number;
  skipped_count: number;
  rows: JdPiecesImportRow[];
}

export interface JdReceiverAddressCandidate {
  shipment_id: string;
  expected_version: number;
  receiver_address_snapshot: string;
  source_channel: string;
  confirmed: boolean;
  confirmed_by?: string | null;
  province?: string | null;
  city?: string | null;
  county?: string | null;
  town?: string | null;
  detail_address?: string | null;
  candidate?: {
    province?: string | null;
    city?: string | null;
    county?: string | null;
    town?: string | null;
    detail_address?: string | null;
  } | null;
  candidate_incomplete: boolean;
}

export interface JdProviderConfigEntry {
  present: boolean;
  value?: string | boolean;
}

export interface FulfillmentProvider {
  id: string;
  provider_code: string;
  provider_name: string;
  provider_type: 'JD_WAREHOUSE' | 'THIRD_PARTY';
  tracking_sla_minutes: number;
  active: boolean;
  version: number;
  /** 京东标识状态投影（非京东履约方为空 map；pin 只含 present，永不回显明文）。 */
  jd_config: Record<string, JdProviderConfigEntry>;
}

export interface ConnectorConfig {
  source_channel: SourceChannel;
  client_mode: 'MOCK' | 'REAL';
  transport_mode: 'EXCEL' | 'API';
  enabled: boolean;
  endpoint?: string | null;
  credential_configured?: boolean;
  version: number;
}

export interface ConnectionTestResult {
  success: boolean;
  checked_at: string;
  latency_ms?: number;
  business_code?: string | null;
  message?: string | null;
}

export interface ProviderSkuReferenceComponent {
  provider_sku_code: string;
  quantity_per_bundle: string | number;
  provider_sku_name?: string;
}

export interface ProviderSkuReferenceRow {
  sheet_name: string;
  sheet_index: number;
  row_index: number;
  source_sku_ref: string;
  source_product_name: string;
  source_quantity: string | number;
  match_status: 'MATCHED' | 'NEED_REVIEW' | 'CONFLICT';
  reason_code: string;
  reason: string;
  quantity_multiplier?: string | number;
  provider_sku_code?: string;
  provider_sku_name?: string;
  bundle_components: ProviderSkuReferenceComponent[];
  candidates?: Array<{
    quantity_multiplier: string | number;
    provider_sku_code?: string;
    provider_sku_name?: string;
    bundle_components: ProviderSkuReferenceComponent[];
  }>;
}

export interface ProviderSkuReferencePreview {
  reference_sha256: string;
  source_sha256: string;
  source_channel: SourceChannel;
  summary: { total: number; matched: number; need_review: number; conflict: number };
  reference_quality: {
    provider_sku_count: number;
    blank_provider_codes: number;
    duplicate_provider_codes: number;
    conflicting_source_names: number;
    bundle_count: number;
    ambiguous_bundle_rows: number;
  };
  rows: ProviderSkuReferenceRow[];
}

// ---------- 履约中心 ----------

export interface Fulfillment {
  id: string;
  fulfillment_no: string;
  order_line_id: string;
  provider_id: string;
  requested_quantity: string;
  cumulative_shipped_quantity: string;
  cancelled_quantity: string;
  shipping_progress: ShippingProgress;
  outcome: FulfillmentOutcome;
  exception_code?: string;
  exception_reason?: string;
  version: number;
}

export interface FulfillmentPage extends PageMeta {
  items: Fulfillment[];
}

export interface FulfillmentDetail extends Fulfillment {
  shipments: Shipment[];
  procurement_tickets: ProcurementTicket[];
}

export interface ContinuationExportCommand {
  expected_version: number;
  instructed_quantity: string;
  remark: string;
}

export interface ContinuationExportResult {
  fulfillment_id: string;
  shipment_id: string;
  shipment_sequence: number;
  fulfillment_export_id: string;
  instructed_quantity: string;
  fulfillment_version: number;
}

export interface FulfillmentExportLine {
  export_line_no: number;
  shipment_id?: string;
  fulfillment_id?: string;
  order_line_id?: string;
  order_line_component_id?: string;
  raw_import_row_id?: string;
  outbound_order_no?: string;
  provider_sku_code: string;
  instructed_quantity: string;
  unit: string;
  item_amount: string;
}

export interface DownloadAudit {
  download_count: number;
  first_downloaded_at?: string;
  last_downloaded_at?: string;
  last_downloaded_by?: string;
}

export type ExportUsageStatus = 'GENERATED_NOT_DOWNLOADED' | 'DOWNLOADED_WAITING_RETURN' | 'RETURNED' | 'RETURN_OVERDUE';

export interface FulfillmentExport {
  id: string;
  export_batch_no: string;
  provider_id: string;
  export_kind: string;
  template_version: string;
  file_sha256?: string;
  tracking_due_at?: string;
  generated_at: string;
  usage_status: ExportUsageStatus;
  download_audit?: DownloadAudit;
  tracking_import_batch_id?: string;
  import_batch_id?: string;
}

export interface FulfillmentExportPage extends PageMeta {
  items: FulfillmentExport[];
}

export interface FulfillmentExportDetail extends FulfillmentExport {
  lines: FulfillmentExportLine[];
}

export interface ImportRowCounts {
  total: number;
  accepted: number;
  need_review: number;
  rejected: number;
}

export interface ImportBatch {
  id: string;
  batch_no: string;
  batch_type: 'SOURCE_ORDER' | 'PROVIDER_TRACKING';
  import_mode: 'NEW' | 'REVISION';
  parent_import_batch_id?: string;
  revision_no: number;
  source_channel?: SourceChannel;
  fulfillment_provider_id?: string;
  source_fulfillment_export_id?: string;
  template_family: string;
  template_version: string;
  template_fingerprint: string;
  original_file_name: string;
  content_sha256: string;
  status: string;
  confirmed_at?: string | null;
  confirmed_by?: string | null;
  row_counts: ImportRowCounts;
  generated_fulfillment_export_ids?: string[];
  generated_source_return_export_ids?: string[];
  /** 仅确认响应携带：京东 SDK 直连路由的建单发货批次（05）。 */
  outbound_routing?: {
    jd_sdk_shipment_ids?: string[];
  };
  received_at: string;
  processed_at?: string;
}

export interface TrackingImportBatch extends ImportBatch {
  business_results?: { shipped?: number; partial?: number; failed?: number };
  rows?: TrackingBatchRow[];
}

/** 回传批次逐行结果视图；raw_cells 仅由展示层白名单取值（结果/实际发货数量/快递公司/物流单号/异常原因）。 */
export interface TrackingBatchRow {
  id: string;
  sheet_name: string;
  sheet_index: number;
  row_index: number;
  raw_cells: Record<string, unknown>;
  source_order_ref?: string | null;
  status: RawRowStatus;
  order_id?: string | null;
  order_line_id?: string | null;
}

export type RawRowStatus = 'RECEIVED' | 'ACCEPTED' | 'NEED_REVIEW' | 'REJECTED';

/** 三平台订单刷新结果（POST /api/v1/platform-orders/refresh）。 */
export interface PlatformOrderRefreshResult {
  channels: Array<{
    channel: SourceChannel;
    status: 'OK' | 'FAILED' | 'SKIPPED';
    message?: string;
    /** 已生成导入批次（彩食鲜/飞象） */
    batch_no?: string;
    batch_id?: string;
    row_counts?: ImportRowCounts;
    /** 聚福宝 JSON 直连拉取订单数（缺收货人字段未导入） */
    order_count?: number;
    file_name?: string;
    script_output?: string;
    latency_ms?: number;
  }>;
  date_begin?: string;
  date_end?: string;
}

/** 只有刷新成功且后端明确返回批次 ID 时，才允许进入人工整批确认。 */
export function platformImportBatchId(
  channel: PlatformOrderRefreshResult['channels'][number],
): string | null {
  return channel.status === 'OK' && channel.batch_id ? channel.batch_id : null;
}

/**
 * 三平台刷新单个渠道结果的展示文案。
 * SKIPPED 由后端携带原因（如「距上次拉取不足…」的频控提示），无 message 时给出合规兜底文案；
 * 与页面内联三元等价，抽出以便单元测试覆盖（合规红线：每日每平台最多 2 次拉取）。
 */
export function platformChannelResultText(c: PlatformOrderRefreshResult['channels'][number]): string {
  if (c.status === 'OK' && c.batch_no) {
    return `批次 ${c.batch_no} · 已接收 ${c.row_counts?.accepted ?? 0} 行 / 待复核 ${c.row_counts?.need_review ?? 0} / 拒绝 ${c.row_counts?.rejected ?? 0}`;
  }
  if (c.status === 'OK' && c.order_count != null) {
    return `已拉取 ${c.order_count} 单（${c.message ?? ''}）`;
  }
  if (c.status === 'SKIPPED') {
    return `已跳过：${c.message ?? '达到每日拉取上限或拉取间隔不足'}`;
  }
  return c.message ?? '失败';
}

/** 来源文件原始行血缘；raw_cells 仅由展示层白名单取值，不得整体渲染。 */
export interface RawImportRow {
  id: string;
  sheet_name: string;
  sheet_index: number;
  row_index: number;
  raw_cells: Record<string, unknown> | unknown[];
  source_order_ref?: string | null;
  status: RawRowStatus;
  error_code?: string | null;
  error_detail?: Record<string, unknown> | null;
  order_id?: string | null;
  order_line_id?: string | null;
  /** 渠道模板解析投影（白名单：receiver_name/receiver_phone/receiver_address/product_name/quantity/specification/source_sku_ref），供确认明细核对解析是否正确。 */
  parsed?: Record<string, string>;
  /** 来源 SKU 归属的履约方（白名单：provider_type JD_WAREHOUSE/THIRD_PARTY + provider_name + 内部 SKU 规格默认值）；无映射为 null。 */
  sku_fulfillment?: {
    provider_type: 'JD_WAREHOUSE' | 'THIRD_PARTY';
    provider_name: string;
    sku_specification?: string | null;
  } | null;
}

export interface RawImportRowPage extends PageMeta {
  items: RawImportRow[];
}

/** 在线推送失败详情（push_error JSONB 的结构化字段）。 */
export interface SourceReturnPushError {
  code?: string;
  message?: string;
  /** true = 平台结果未知（outcome=unknown）：平台可能已受理也可能未受理，需先核实再决定是否重推。 */
  unknown_outcome?: boolean;
  /**
   * 平台原始响应全文（P2）。聚福宝 multi-send 平台无逐行响应结构（全有全无受理），
   * 失败时脚本把平台原始响应 code/message/request_id 全量透出，供人工按 request_id 在平台核对。
   */
  platform_response?: {
    code?: string;
    message?: string;
    request_id?: string;
    [key: string]: unknown;
  } | null;
}

export interface SourceReturnExport {
  id: string;
  import_batch_id: string;
  version_no: number;
  is_final: boolean;
  template_version: string;
  tracking_cutoff_at: string;
  file_sha256: string;
  generated_at: string;
  /** 在线推送状态（票 11 回传闸门）：NOT_PUSHED / PUSHING / SUCCESS / FAILED。 */
  push_status?: 'NOT_PUSHED' | 'PUSHING' | 'SUCCESS' | 'FAILED';
  pushed_at?: string | null;
  pushed_by?: string | null;
  push_platform_ref?: string | null;
  push_error?: SourceReturnPushError | null;
}

/** 在线回传（推送平台）是否支持该来源渠道：后端仅接入彩食鲜（CAISHIXIAN）与聚福宝（JUFUBAO）。 */
export function isOnlinePushChannel(channel: SourceChannel | undefined | null): boolean {
  return channel === 'CAISHIXIAN' || channel === 'JUFUBAO';
}

/**
 * P3：收集一页导出行需要解析来源渠道的去重批次 id（每页 ≤10 个），
 * 优先来源批次 import_batch_id；无来源批次时退回 SDK 直连的运单批次
 * tracking_import_batch_id（该路径渠道恒为 null，仅作兜底尝试，见 sourcePushButtonVisible）。
 */
export function collectPushChannelBatchIds(
  rows: Array<Pick<FulfillmentExport, 'import_batch_id' | 'tracking_import_batch_id'>>,
): string[] {
  const ids: string[] = [];
  const seen = new Set<string>();
  for (const row of rows) {
    const id = row.import_batch_id ?? row.tracking_import_batch_id;
    if (id && !seen.has(id)) {
      seen.add(id);
      ids.push(id);
    }
  }
  return ids;
}

/**
 * P3：该导出行是否显示「推送平台」按钮——仅当能解析出来源渠道且为在线回传渠道
 * （CAISHIXIAN/JUFUBAO）时显示；FEIXIANG/ZHONGHUI 无回传不显示。
 * 渠道未知（无批次 id / 渠道映射缺失 / 拉取失败）一律不显示（fail-closed，注释见页面
 * channelByBatch 加载处）；点击时的 isOnlinePushChannel 拦截与后端
 * PUSH_CHANNEL_UNSUPPORTED 仍为兜底双保险。
 */
export function sourcePushButtonVisible(
  record: Pick<FulfillmentExport, 'import_batch_id' | 'tracking_import_batch_id'>,
  channelByBatch: ReadonlyMap<string, SourceChannel | undefined | null>,
): boolean {
  const id = record.import_batch_id ?? record.tracking_import_batch_id;
  if (!id) return false;
  return isOnlinePushChannel(channelByBatch.get(id));
}

export type SourceReturnPushOutcomeKind = 'SUCCESS' | 'UNKNOWN' | 'REJECTED' | 'OTHER';

/**
 * 票 11：来源回填在线推送结果的运营提示——区分「平台明确拒绝（REJECTED）」与
 * 「结果未知（UNKNOWN，push_error.unknown_outcome=true）」。结果未知时必须先到平台
 * 核实是否已受理，再决定是否重推，避免重复受理造成重复发货。
 */
export function sourceReturnPushOutcome(pushed: SourceReturnExport | null): {
  kind: SourceReturnPushOutcomeKind;
  text: string;
} {
  if (!pushed) return { kind: 'OTHER', text: '推送状态未知' };
  switch (pushed.push_status) {
    case 'SUCCESS':
      return {
        kind: 'SUCCESS',
        text: `已推送到平台（${pushed.push_platform_ref ?? '已受理'}），平台收货后订单状态将同步为已发货`,
      };
    case 'FAILED':
      if (pushed.push_error?.unknown_outcome === true) {
        return {
          kind: 'UNKNOWN',
          text: '结果未知：请先到平台核实是否已受理，再决定是否重推；确认未受理后可再次点击「推送平台」重试',
        };
      }
      // P2：平台原始响应全文已并入 push_error.platform_response（含 request_id），
      // 提示里透出平台请求号便于人工在平台定位本次拒绝。
      const requestId = pushed.push_error?.platform_response?.request_id;
      return {
        kind: 'REJECTED',
        text: `推送失败：${pushed.push_error?.message ?? pushed.push_error?.code ?? '平台拒绝'}${requestId ? `（平台请求 ${requestId}）` : ''}`,
      };
    default:
      return { kind: 'OTHER', text: `推送状态：${pushed.push_status ?? '未知'}` };
  }
}

export interface ShipmentPage extends PageMeta {
  items: Shipment[];
}

export interface ShipmentJdSkuMappingGateResult {
  shipment_id: string;
  check_run_no: string;
  gate_status: 'PASSED' | 'BLOCKED';
  checked_mapping_count: number;
  blocking_issue_count: number;
  warning_count: number;
}

// ---------- 库存中心 / 京东实时库存判定 ----------

export type InventoryObservationStatus = 'OBSERVED' | 'NOT_OBSERVED';
export type InventoryFreshnessStatus = 'CURRENT' | 'STALE' | 'NOT_OBSERVED';
export type InventorySourceType = 'JD_ISC_QUERY_STOCK' | 'NORMALIZED_PROVIDER_SNAPSHOT' | 'UNKNOWN';
export type InventoryQuantityUnit = 'JD_PIECE' | 'INTERNAL_UNIT' | 'UNKNOWN';

export interface InventoryOverviewItem {
  provider_id: string;
  provider_code: string;
  provider_name: string;
  provider_type: 'JD_WAREHOUSE' | 'THIRD_PARTY';
  sku_id: string;
  sku_code: string;
  product_name: string;
  specification: string;
  unit: string;
  quantity_unit: InventoryQuantityUnit | null;
  warehouse_code: string | null;
  observation_status: InventoryObservationStatus;
  total_quantity: string | null;
  available_quantity: string | null;
  unavailable_quantity: string | null;
  observed_at: string | null;
  observation_age_seconds: number | null;
  freshness_status: InventoryFreshnessStatus;
  source_type: InventorySourceType | null;
}

export interface InventoryCoverage {
  provider_count: number;
  observed_provider_count: number;
  sku_count: number;
  observed_sku_count: number;
  warehouse_count: number;
  latest_observed_at: string | null;
  stale_count: number;
  oldest_observed_at: string | null;
  partial: boolean;
  freshness_policy: string;
}

export interface InventoryOverviewResponse {
  items: InventoryOverviewItem[];
  page: number;
  size: number;
  total_elements: number;
  total_pages: number;
  coverage: InventoryCoverage;
}

export type InventoryDetailCapabilityGroup =
  | 'BATCH_AND_SHELF_LIFE'
  | 'INVENTORY_FLOW'
  | 'SERIAL_NUMBER';

export interface InventoryDetailContext {
  provider_id: string;
  provider_code: string;
  provider_name: string;
  provider_type: 'JD_WAREHOUSE' | 'THIRD_PARTY';
  sku_id: string;
  sku_code: string;
  product_name: string;
  specification: string;
  unit: string;
  provider_sku_code: string | null;
  warehouse_code: string | null;
}

export interface InventoryDetailObservation {
  observation_status: InventoryObservationStatus;
  total_quantity: string | null;
  available_quantity: string | null;
  unavailable_quantity: string | null;
  quantity_unit: InventoryQuantityUnit | null;
  observed_at: string | null;
  observation_age_seconds: number | null;
  expires_at: string | null;
  freshness_status: InventoryFreshnessStatus;
  source_type: InventorySourceType | null;
  data_mode: 'CACHED_SNAPSHOT' | 'NO_OBSERVATION';
}

export interface InventoryDetailCapability {
  group: InventoryDetailCapabilityGroup;
  label: string;
  integration_status: 'INTEGRATED' | 'NOT_INTEGRATED' | 'CONTEXT_MISSING';
  runtime_mode: 'REAL' | 'MOCK' | 'UNKNOWN' | 'NOT_APPLICABLE';
  source_type: 'JD_ISC_READ_ONLY' | null;
  explanation: string;
  tools: Array<{ code: string; label: string }>;
}

export interface InventoryDetailsResponse {
  context: InventoryDetailContext;
  observation: InventoryDetailObservation;
  query_time: string;
  freshness_policy: string;
  capabilities: InventoryDetailCapability[];
}

export interface ShipmentJdStockObservation {
  sku_id: string;
  goods_no: string;
  warehouse_code: string;
  required_quantity: string;
  quantity_unit: 'JD_PIECE';
  observation_status: 'OBSERVED' | 'OBSERVED_ZERO' | 'NOT_OBSERVED';
  stock_quantity?: string;
  usable_quantity?: string;
}

export interface ShipmentJdStockCheckResult {
  shipment_id: string;
  shipment_version: number;
  preview_hash: string;
  target_warehouse_code: string;
  stock_status: 'PASSED' | 'BLOCKED';
  observation_status: 'OBSERVED' | 'OBSERVED_ZERO' | 'NOT_OBSERVED';
  observed_at: string;
  not_reserved: true;
  blockers: Array<{ code: string; message: string }>;
  items: ShipmentJdStockObservation[];
  review_case?: { id: string; reason_code: 'JD_STOCK_BLOCKED'; status: 'OPEN' };
}

// ---------- 采购工单 ----------

export type ProcurementStatus = 'PENDING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'CANCELLED';

export interface ProcurementTicketItem {
  id: string;
  sku_id: string;
  component_sku_id?: string;
  requested_quantity: string;
  fulfilled_quantity: string;
  remaining_quantity: string;
}

export interface ProcurementReceipt {
  id: string;
  receipt_no: string;
  ticket_id: string;
  result: ProcurementStatus;
  expected_ship_time?: string;
  source_ref?: string;
  remark?: string;
  received_by: string;
  received_at: string;
  items: { ticket_item_id: string; available_quantity: string }[];
}

export interface ProcurementTicket {
  id: string;
  ticket_no: string;
  fulfillment_id: string;
  retry_of_ticket_id?: string;
  status: ProcurementStatus;
  requested_quantity: string;
  fulfilled_quantity: string;
  remaining_quantity: string;
  items: ProcurementTicketItem[];
  receipts: ProcurementReceipt[];
  version: number;
  created_at: string;
}

export interface ProcurementTicketPage extends PageMeta {
  items: ProcurementTicket[];
}

// ---------- 复核队列 / 审计 ----------

export type ReviewCaseStatus = 'OPEN' | 'RESOLVED' | 'DISMISSED';

export interface ResolveCustomerReviewCommand {
  expected_version: number;
  customer_id: string;
  source_channel: SourceChannel;
  source_customer_ref: string;
  remark: string;
}

export interface ResolveSkuReviewCommand {
  expected_version: number;
  sku_id: string;
  source_channel: SourceChannel;
  source_sku_ref: string;
  quantity_multiplier: string;
  remark: string;
}

export interface VersionedNoteCommand {
  expected_version: number;
  note: string;
}

export interface ReviewCasePage extends PageMeta {
  items: ReviewCase[];
}

export type OperationalAlertStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED';

export interface OperationalAlert {
  id: string;
  alert_no: string;
  alert_type: string;
  severity: Severity;
  status: OperationalAlertStatus;
  order_id?: string;
  order_line_id?: string;
  fulfillment_id?: string;
  shipment_id?: string;
  message: string;
  detail: Record<string, unknown>;
  acknowledged_by?: string;
  acknowledged_at?: string;
  resolved_at?: string;
  version: number;
  created_at: string;
}

export interface OperationalAlertPage extends PageMeta {
  items: OperationalAlert[];
}

export interface JdQueryResult {
  success: boolean;
  business_code: string;
  message?: string;
  request_id?: string;
  data?: unknown;
}

export interface JdClientStatus {
  client_mode: 'MOCK' | 'REAL';
  credentials_configured: boolean;
  tenant_configured: boolean;
  live_ready: boolean;
}

// ---------- 中汇好泰 PMS 商品录入（pms_openapi.md） ----------

/** GET /api/v1/zhonghui-pms/status —— 连接模式、凭据与登录态。 */
export interface ZhonghuiPmsStatus {
  client_mode: 'MOCK' | 'REAL';
  credentials_configured: boolean;
  live_ready: boolean;
  authenticated: boolean;
}

/** GET /api/v1/zhonghui-pms/captcha —— 登录图片验证码（img 为 Base64 PNG）。 */
export interface ZhonghuiPmsCaptcha {
  captcha_no: string;
  img: string;
}

/** POST /api/v1/zhonghui-pms/login —— 验证码登录结果（token 只在服务端内存）。 */
export interface ZhonghuiPmsLoginResult {
  success: boolean;
  business_code: string;
  message?: string;
}

/** GET /api/v1/zhonghui-pms/options —— 可用品牌与资质（批量上传覆盖字段候选）。 */
export interface ZhonghuiPmsBrand {
  brand_id: string;
  brand_name: string;
}

export interface ZhonghuiPmsCertification {
  certification_id: string;
  certification_name: string;
  commencement_date?: string;
  inspection_end_date?: string;
}

export interface ZhonghuiPmsLogistics {
  logist_id: string;
  logist_name: string;
}

export interface ZhonghuiPmsOptions {
  brands: ZhonghuiPmsBrand[];
  certifications: ZhonghuiPmsCertification[];
  logistics: ZhonghuiPmsLogistics[];
}

/** POST /api/v1/zhonghui-pms/batch-uploads —— 逐商品结果。 */
export interface ZhonghuiPmsBatchUploadItem {
  sku_id: string;
  sku_code: string;
  goods_name: string;
  success: boolean;
  business_code: string;
  message: string;
  /** 商品列表校验后确认的 PMS 商品 id（十进制字符串，创建后查询 goodsInfos 回填）。 */
  goods_id?: string | null;
  /** PMS 商品审核/上架状态文本（如 待平台审核/待上架）。 */
  pms_status?: string | null;
  /** 非阻断提示（如 商品缺少主图）。 */
  warning?: string | null;
}

export interface ZhonghuiPmsBatchUploadResult {
  batch_id: string;
  batch_no: string;
  status: 'PENDING' | 'COMPLETED';
  total: number;
  succeeded: number;
  failed: number;
  items: ZhonghuiPmsBatchUploadItem[];
}

/** GET /api/v1/zhonghui-pms/upload-batches/{id} —— 批次详情（恢复/审计）。 */
export interface ZhonghuiPmsUploadBatchDetail {
  batch_id: string;
  batch_no: string;
  status: 'PENDING' | 'COMPLETED';
  total: number;
  succeeded: number;
  failed: number;
  created_by: string;
  created_at?: string | null;
  completed_at?: string | null;
  items: ZhonghuiPmsBatchUploadItem[];
}

export interface AuditLog {
  id: string;
  data_scope: 'BUSINESS' | 'DEMO';
  operator: string;
  actor_type: 'HUMAN' | 'AGENT' | 'SYSTEM' | 'EXTERNAL';
  service: string;
  operation: string;
  order_id?: string;
  request_id?: string | null;
  trace_id?: string | null;
  request_payload?: Record<string, unknown> | null;
  response_payload?: Record<string, unknown> | null;
  http_status?: number | null;
  business_code?: string | null;
  latency_ms?: number | null;
  created_at: string;
}

export interface AuditLogPage extends PageMeta {
  items: AuditLog[];
}

// ---------- 企业微信消息证据 ----------

export interface ChannelMessageSummary {
  id: string;
  corp_id: string;
  connection_id: string;
  bot_id: string;
  message_id: string;
  chat_id: string;
  chat_type: 'group' | 'single';
  sender_user_id: string;
  message_type: string;
  content_preview: string;
  received_at: string;
}

export interface ChannelMessageDetail extends Omit<ChannelMessageSummary, 'content_preview'> {
  content: string;
  quote_type?: string | null;
  quote_content?: string | null;
  raw_payload_ref: string;
  submission_id?: string | null;
  media_refs?: Array<{
    id: string;
    media_type: string;
    content_type?: string | null;
    size_bytes?: number | null;
  }>;
}

export interface ChannelMessagePage extends PageMeta {
  items: ChannelMessageSummary[];
}

// ---------- 消息提交与解释历史 ----------

export type MessageFailureCode =
  | 'MODEL_NOT_CONFIGURED'
  | 'MODEL_CALL_FAILED'
  | 'MODEL_OUTPUT_INVALID';

export interface MessageInterpretation {
  version: number;
  intent: string;
  provider: string;
  model: string;
  prompt_version: string;
  error?: MessageFailureCode | null;
  created_at: string;
}

export type MessageTaskStatusCode =
  | 'PENDING'
  | 'RUNNING'
  | 'FINALIZING'
  | 'SUCCEEDED'
  | 'FAILED';

export interface MessageTaskStatus {
  id: string;
  task_type: string;
  status: MessageTaskStatusCode;
  attempts: number;
  max_attempts: number;
  last_error?: MessageFailureCode | null;
  created_at: string;
}

export interface MessageSubmissionDetail {
  id: string;
  submission_no: string;
  status: string;
  source_message_id: string;
  current_intent?: string | null;
  latest_error?: MessageFailureCode | null;
  interpretations: MessageInterpretation[];
  latest_task?: MessageTaskStatus | null;
  created_at: string;
}

// ---------- Demo ----------

export interface DemoScenario {
  scenario_code: string;
  scenario_name: string;
  description: string;
}

export interface DemoRun {
  id: string;
  run_no: string;
  scenario_code: string;
  status: DemoRunStatus;
  data_scope: 'DEMO';
  order_id: string;
  order?: OrderSummary & {
    receiver_phone?: string;
    receiver_address?: string;
    lines?: Array<{
      line_no: number;
      product_name: string;
      sku_code?: string | null;
      specification: string;
      quantity: string;
      unit: string;
      processing_stage: 'COMPLETED';
    }>;
  };
  timeline?: OrderEvent[];
  error?: ApiErrorBody;
  started_at: string;
  finished_at?: string;
  extracted_order?: OrderAssistantDraft;
}

export type OrderAssistantStatus = 'COLLECTING' | 'READY_TO_CONFIRM' | 'CONFIRMED';

export interface OrderAssistantDraft {
  confirmed?: true;
  source: 'WECOM';
  source_ref: string;
  customer: { customer_name?: string | null; customer_code?: string | null };
  receiver: { receiver_name?: string | null; receiver_phone?: string | null; address?: string | null };
  required_delivery_time?: string | null;
  items: Array<{
    product_name?: string | null;
    sku_code?: string | null;
    specification?: string | null;
    quantity?: string | number | null;
    unit?: string | null;
  }>;
  settlement: { settlement_method?: string | null; settlement_time?: string | null };
  remark?: string | null;
  evidence_refs?: string[];
}

export interface OrderAssistantSession {
  session_id: string;
  status: OrderAssistantStatus;
  draft: OrderAssistantDraft;
  missing_fields: string[];
  messages: Array<{ role: 'user' | 'assistant'; content: string }>;
  order_result?: DemoRun | null;
  created_at: string;
  updated_at: string;
}

export interface OrderAssistantConfig {
  service_ready: boolean;
  demo_mode: boolean;
}
