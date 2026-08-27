/**
 * API 类型定义 —— 严格对照 docs/openapi.yaml schemas（snake_case）。
 * 所有标识符为字符串（避免 BIGINT 精度丢失）；数量为十进制字符串。
 */

// ---------- 枚举 ----------

export type SourceChannel = 'CAISHIXIAN' | 'JUFUBAO' | 'FEIXIANG' | 'ZHONGHUI' | 'WANGQI' | 'DAZHE' | 'WANQI' | 'WECOM';

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
  method: string | null;
  settlement_time?: string | null;
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
  /** 关联对象业务编号（ORDER/ORDER_LINE → 订单号，SHIPMENT → 发货单号）；无业务编号为 null */
  subject_no?: string | null;
  /** 关联订单业务单号（队列「关联订单」列用） */
  order_no?: string | null;
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
  /** 业务订单号（UIUX-05 #139：列表/抽屉不再用内部主键当业务标识） */
  order_no?: string;
  customer_name?: string;
  receiver_name?: string;
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
}

export interface SkuRecord extends Omit<MasterDataRecord, 'attributes'> {
  attributes: SkuAttributes;
}

export interface SkuPage extends Omit<MasterDataPage, 'items'> {
  items: SkuRecord[];
}

export type ProductBundleStatus = 'DRAFT' | 'ACTIVE' | 'INACTIVE';

export interface ProductBundleItem {
  sku_id: string;
  quantity_per_bundle: string;
  sku_code?: string;
  product_name?: string;
  specification?: string;
  unit?: string;
  emg_code_snapshot?: string | null;
  source_text_snapshot?: string | null;
}

export interface ProductBundleAttributes {
  barcode?: string | null;
  description?: string | null;
  status: ProductBundleStatus;
  fulfillment_provider_id?: string | null;
  items: ProductBundleItem[];
}

export interface ProductBundleRecord extends Omit<MasterDataRecord, 'attributes'> {
  attributes: ProductBundleAttributes;
}

export interface ProductBundlePage extends Omit<MasterDataPage, 'items'> {
  items: ProductBundleRecord[];
}

export interface ProductBundleItemInput {
  sku_id: string;
  quantity_per_bundle: string;
  emg_code_snapshot?: string;
}

export interface ProductBundleCreateInput {
  bundle_code: string;
  bundle_name: string;
  barcode?: string;
  description?: string;
  status?: ProductBundleStatus;
  items: ProductBundleItemInput[];
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
  /** 企微群 chatid（Issue #83）：标识符非凭据，按既有投影回显；未登记/已清除为 null。 */
  wecom_group_chat_id: string | null;
  /** 回传提醒间隔分钟（Issue #84）：未配置/已清除为 null（默认 = tracking_sla_minutes）。 */
  wecom_reminder_interval_minutes: number | null;
}

/** 机器人可达会话（配置推送目标用）：群来自机器人收到过消息的群，单聊来自运营人员绑定的 userid。 */
export interface KnownWecomChat {
  chat_id: string;
  chat_type: 'group' | 'single';
  /** 人起的会话备注名（企微协议不下发群名，帧里只有 chatid）；未起为 null。 */
  display_name: string | null;
  /** 单聊 = 运营人员姓名（自动兜底名）；群聊恒为 null。 */
  label: string | null;
  event_count: number;
  last_seen_at: string | null;
  /** 服务该会话的 Agent（agent_definitions.agent_slug）；未绑定为 null。 */
  agent_slug: string | null;
  /** 回复权限：FULL=自由回复（缺省），RECEIPTS_ONLY=仅业务消息（回执/回填/清单照发）。 */
  reply_mode: 'FULL' | 'RECEIPTS_ONLY';
}

/** 内部运营人员（Issue #89）：姓名、企微 userid、所属责任团队；只做映射与责任归属，不做登录/权限。 */
export interface Operator {
  id: string;
  display_name: string;
  /** 责任团队（服务端 trim + 大写归一，如 ORDER_OPS / CUSTOMER_OPS / SKU_OPS）。 */
  responsible_team: string;
  /** 企微 userid；null = 未绑定（需要推送时由解析 seam 明确提示，不静默跳过）。 */
  wecom_userid: string | null;
  active: boolean;
  version: number;
  created_at?: string;
  updated_at?: string;
}

export interface OperatorPage extends PageMeta {
  items: Operator[];
}

/** 责任团队解析结果（Issue #89）：active 人员、可推送 userid 与未绑定人员的显式诊断。 */
export interface OperatorTeamResolution {
  responsible_team: string;
  members: Array<{ display_name: string; wecom_userid: string | null }>;
  pushable_user_ids: string[];
  unbound_member_names: string[];
  status: 'PUSHABLE' | 'PARTIALLY_BOUND' | 'ALL_UNBOUND' | 'NO_MEMBERS';
  pushable: boolean;
}

export interface ConnectorConfig {
  source_channel: SourceChannel;
  client_mode: 'MOCK' | 'REAL';
  transport_mode: 'EXCEL' | 'API';
  enabled: boolean;
  endpoint?: string | null;
  /** 渠道账号用户名：非敏感标识符，按既有投影原样回显；未配置为 null/undefined。 */
  username?: string | null;
  credential_configured?: boolean;
  /** 渠道账号密码是否已配置：与 credential_configured 同源做法，只投影存在性标记，永不回显明文。 */
  password_configured?: boolean;
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
  /** 业务订单号 / 客户名 / 收货人名（UIUX-05 #139） */
  order_no?: string;
  customer_name?: string;
  receiver_name?: string;
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

/** 履约导出企微出站状态（Issue #84）：状态行存在时返回；JD/未登记导出为 undefined。 */
export interface FulfillmentExportWecomState {
  status: 'PENDING' | 'ACTIVE' | 'COMPLETED' | 'MANUALLY_STOPPED' | 'FAILED' | 'UNKNOWN' | 'LEGACY';
  chat_id?: string | null;
  tracking_sla_minutes: number;
  reminder_interval_minutes: number;
  initial_sent_at?: string | null;
  tracking_due_at?: string | null;
  next_reminder_at?: string | null;
  last_reminded_at?: string | null;
  reminder_count: number;
  last_error?: string | null;
  version: number;
  stopped?: {
    by: string;
    reason: string;
    at: string;
  };
}

export interface FulfillmentExport {
  id: string;
  export_batch_no: string;
  provider_id: string;
  export_kind: string;
  template_version: string;
  file_sha256?: string;
  /** 权威回传截止：新第三方导出以企微发送 ack 派生，未发送时为 null（不展示假的到期时间）。 */
  tracking_due_at?: string | null;
  generated_at: string;
  usage_status: ExportUsageStatus;
  download_audit?: DownloadAudit;
  tracking_import_batch_id?: string;
  import_batch_id?: string;
  /** 企微出站状态（Issue #84）；JD 导出无此字段。 */
  wecom?: FulfillmentExportWecomState;
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
  recorded_source_channel_display_name?: string | null;
  effective_source_channel_display_name?: string | null;
  source_channel_display_name?: string | null;
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
  settlement_missing: boolean;
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
    /** finish() 总会写入；前端只按 business_code+status 封闭映射展示，不得直接渲染 message。 */
    business_code?: string;
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

/**
 * `raw_import_rows.error_detail` 的已知键。
 *
 * **故意不加索引签名**：本类型存在的唯一理由就是让键名拼错在编译期炸掉。曾经前端读
 * 单数 `order_line_exception`、后端写复数 `order_line_exceptions`，名字对不上导致该
 * 分支恒为空、无声退化到粗粒度 error_code 文案——加了 `[key: string]: unknown` 就等于
 * 把这个缺陷放回来。后端新增键不会让编译失败（线上 JSON 的多余属性不触发 TS 的
 * excess property check），所以不需要索引签名兜底。
 *
 * **值一律 `unknown`**：这是不可信的线上数据，且三个写入方各写各的形状。类型只守键名，
 * 值形状必须在读取处运行时校验（见 `fileOperations.ts` 的 `lineExceptionReason`
 * 与 `SAFE_IMPORT_MESSAGES`）。标成 `string[]` 会让 `typeof code === 'string'` 这类
 * 守卫显得多余而被后人删掉，正是要避免的。
 *
 * 写入方与实际形状：
 * - `message`（string）：`SourceFileParser` 逐行校验、`SourceImportService.markReview`、
 *   `StructuredOrderRow.reviewRequired`（Connector 结构化导入）
 * - `order_line_exceptions`（string[]）：`SourceImportService:202-204`，该来源行拆出的
 *   全部订单行异常码；一行拆多行时会同时带多个不同的码
 * - `review_case_reason`（string）：`ReviewCaseResolutionService:565-577`，复核部分闭环后
 *   回写仍未闭环的原因码
 */
export interface RawImportRowErrorDetail {
  message?: unknown;
  order_line_exceptions?: unknown;
  review_case_reason?: unknown;
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
  error_detail?: RawImportRowErrorDetail | null;
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
  /**
   * 该来源行将/已发送京东 SDK cargoInfos 的精确发货数量（与建单预览/提交共用同一换算）；
   * 第三方/无京东履约行为空数组。product_name 即 SDK goodsName 口径的来源商品名快照。
   */
  jd_cargos?: Array<{
    product_name: string;
    provider_sku_code: string;
    plan_quantity: number;
  }>;
}

export interface RawImportRowPage extends PageMeta {
  items: RawImportRow[];
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

// ---------- 采购比价 Agent（01 票：不可比候选降级展示） ----------

export type ProcurementPriceBasis = 'sku_commercial_price' | 'provider_sku';

/** 不可比候选的剔除理由标签：价格离群 / 价格缺失 / 映射失效。 */
export type ProcurementPriceExclusionReason = 'price_outlier' | 'price_missing' | 'mapping_stale';

export interface ProcurementPriceCandidate {
  provider_code: string;
  price?: string | null;
  price_basis?: ProcurementPriceBasis | null;
  note?: string | null;
}

export interface ProcurementPriceExcludedCandidate extends ProcurementPriceCandidate {
  exclusion_reason: ProcurementPriceExclusionReason;
  exclusion_reason_detail?: string | null;
}

export interface ProcurementPriceInventory {
  available?: string | null;
  shortage?: string | null;
}

export interface ProcurementPriceRecommendation {
  target_sku?: string;
  requested_quantity?: string | null;
  inventory?: ProcurementPriceInventory | null;
  /** 可比候选（参与推荐与「可比候选」组展示）。 */
  candidates: ProcurementPriceCandidate[];
  /** 被剔除候选（降级展示，不是删除）：理由标签与可读说明可见。 */
  excluded_candidates: ProcurementPriceExcludedCandidate[];
  recommendation?: { provider_code: string; reason: string } | null;
  missing_fields: string[];
  confidence: number;
  requires_human: boolean;
}

export interface ProcurementPriceRunResult {
  recommendation?: ProcurementPriceRecommendation | null;
  provider: string;
  model: string;
  prompt_version: string;
  error?: string | null;
}

export interface ProcurementPriceCompareCommand {
  procurement_ticket_id?: string;
  sku_id?: string;
  quantity?: string;
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

// ---------- 出库信息内外事实并排（GET /api/v1/outbound-recon） ----------

export type OutboundReconQueryType = 'OUTBOUND_ORDER_NO' | 'JD_DELIVERY_NO' | 'ORDER_NO';

/** 京东侧查询结果状态：OK 已返回；NOT_FOUND 京东没有这笔；UNAVAILABLE 查询失败/超时未取到。 */
export type OutboundReconJdStatus = 'OK' | 'NOT_FOUND' | 'UNAVAILABLE';

/** 逐字段差异状态。JD_UNAVAILABLE / JD_NOT_FOUND 表示整侧未取到/无记录，不是字段为空。 */
export type OutboundReconRowState =
  | 'MATCH'
  | 'MISMATCH'
  | 'INTERNAL_ONLY'
  | 'JD_ONLY'
  | 'EMPTY'
  | 'JD_UNAVAILABLE'
  | 'JD_NOT_FOUND';

export interface OutboundReconComparisonRow {
  key: string;
  label: string;
  internal_value: unknown;
  jd_value: unknown;
  internal_present: boolean;
  jd_present: boolean;
  state: OutboundReconRowState;
  note: string | null;
}

export interface OutboundReconInternalSide {
  summary: Record<string, unknown>;
  items: Array<Record<string, unknown>>;
  tracking: Record<string, unknown> | null;
}

export interface OutboundReconJdSide {
  status: OutboundReconJdStatus;
  business_code: string | null;
  message: string | null;
  client_mode: 'MOCK' | 'REAL';
  summary: Record<string, unknown> | null;
  items: Array<Record<string, unknown>>;
}

export interface OutboundReconView {
  query: { type: OutboundReconQueryType; value: string };
  audit: { request_id: string | null; operator: string };
  internal: OutboundReconInternalSide;
  jd: OutboundReconJdSide;
  comparisons: OutboundReconComparisonRow[];
  matched_count: number;
  mismatch_count: number;
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

export type ChannelMessageType = 'text' | 'mixed' | 'image' | 'voice' | 'file' | 'video';

export interface ChannelMessageSummary {
  id: string;
  corp_id: string;
  connection_id: string;
  bot_id: string;
  message_id: string;
  chat_id: string;
  chat_type: 'group' | 'single';
  sender_user_id: string;
  message_type: ChannelMessageType;
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

export type MessageTaskFailureCode =
  | MessageFailureCode
  | 'WECOM_TRACKING_FILE_CHAT_UNSUPPORTED'
  | 'WECOM_TRACKING_FILE_PAYLOAD_INVALID'
  | 'WECOM_TRACKING_FILE_DOWNLOAD_FAILED'
  | 'WECOM_TRACKING_FILE_TOO_LARGE'
  | 'WECOM_TRACKING_FILE_INVALID'
  | 'WECOM_TRACKING_FILE_PROCESSING_FAILED';

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
  last_error?: MessageTaskFailureCode | null;
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

/* ── 中汇 PMS 上传通道（契约 components.schemas 的 StatusView/CaptchaView/... 投影） ── */

/** 非机密就绪投影：客户端模式、默认关闭的写门闩、凭据与会话状态。 */
export interface ZhonghuiPmsStatus {
  client_mode: 'MOCK' | 'REAL';
  write_mode: 'OFF' | 'ON';
  /** 仅当 client_mode=REAL 且 write_mode=ON 时为 true。 */
  external_writes_enabled: boolean;
  credentials_configured: boolean;
  /** 仅当两道写门闩与全部 REAL 凭据都就绪时为 true。 */
  live_ready: boolean;
  authenticated: boolean;
}

export interface ZhonghuiPmsCaptcha {
  captcha_no: string;
  /** Base64 PNG，不含 data URI 前缀。 */
  img: string;
}

export interface ZhonghuiPmsLoginResult {
  success: boolean;
  business_code: string;
  message: string;
}

export interface ZhonghuiPmsBrand {
  brand_id: string;
  brand_name: string;
}

export interface ZhonghuiPmsCertification {
  certification_id: string;
  certification_name: string;
  commencement_date: string;
  inspection_end_date: string;
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

export interface ZhonghuiPmsBatchUploadItem {
  sku_id: string;
  sku_code: string | null;
  goods_name: string | null;
  success: boolean;
  business_code: string | null;
  message: string | null;
  goods_id?: string | null;
  pms_status?: string | null;
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
