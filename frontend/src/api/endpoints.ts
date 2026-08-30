/**
 * 类型化端点 —— 与 docs/openapi.yaml paths 一一对应（本票用到的子集）。
 * 命名空间边界（contract §2）：
 *   /api/v1  管理后台（BUSINESS）
 *   /demo/v1 模拟下单页（DEMO）
 *   /internal/v1 受信任内部接入 —— 前端不调用。
 */

import { apiRequest, type QueryValue, newRequestId } from './client';
import { ApiError } from './client';
import type {
  AgentDetail,
  AgentEvalCaseItem,
  AgentListResponse,
  AgentVersionItem,
  RunDetail,
  RunListResponse,
  TokenUsageSummaryResponse,
  FulfillmentFileRunResult,
  ImportBatchProgress,
  MetaAgentOutcome,
} from './agentTypes';
import type {
  AuditLog,
  AuditLogPage,
  BusinessFollowUp,
  BusinessFollowUpCreateInput,
  BusinessFollowUpDecisionInput,
  BusinessFollowUpOrganizeInput,
  BusinessFollowUpPage,
  BusinessFollowUpSummary,
  ChannelMessageDetail,
  ChannelMessagePage,
  MessageSubmissionDetail,
  ChannelMetric,
  ConnectionTestResult,
  ConnectorConfig,
  ConnectorPullScheduleSlot,
  DashboardSummary,
  DemoRun,
  DemoScenario,
  ContinuationExportCommand,
  ContinuationExportResult,
  FulfillmentDetail,
  FulfillmentExportDetail,
  FulfillmentExportPage,
  FulfillmentExportWecomState,
  FulfillmentMetric,
  FulfillmentPage,
  FulfillmentProvider,
  KnownWecomChat,
  ImportBatch,
  PendingConfirmationBatch,
  InventoryDetailsResponse,
  InventoryOverviewResponse,
  JdQueryResult,  JdClientStatus,
  JdPiecesCandidate,
  JdPiecesImportResult,
  JdReceiverAddressCandidate,
  MasterDataPage,
  MasterDataRecord,
  OutboundReconQueryType,
  OutboundReconView,
  ProductArchiveSheet,
  ProductArchiveSheetPage,
  ProductBundleCreateInput,
  ProductBundlePage,
  ProductBundleRecord,
  ProductImageUploadResult,
  OrderDetail,
  OrderShipment,
  OrderAssistantConfig,
  OrderAssistantSession,
  OrderEvent,
  OrderLineSkuSubstitutionResult,
  OrderPage,
  OrderVersion,
  Operator,
  OperatorPage,
  OperationalAlert,
  OperationalAlertPage,
  ProcurementTicket,
  ProcurementTicketPage,
  ProcurementPriceCompareCommand,
  ProcurementPriceRunResult,
  ProviderSkuReferencePreview,
  ProductMetric,
  PlatformOrderRefreshResult,
  RawImportRowPage,
  RawRowStatus,
  ReviewCasePage,
  ReviewCase,
  ResolveCustomerReviewCommand,
  ResolveSkuReviewCommand,
  VersionedNoteCommand,
  Shipment,
  ShipmentJdOutboundPreview,
  ShipmentJdOutboundSubmitResult,
  ShipmentJdSkuMappingGateResult,
  ShipmentJdStockCheckResult,
  ShipmentPage,
  SkuPage,
  SkuRecord,
  SourceChannel,
  SourceOrderIntakeJob,
  SourceReturnExport,
  TrackingImportBatch,
  WecomBot,
  ZhonghuiPmsBatchUploadResult,
  ZhonghuiPmsCaptcha,
  ZhonghuiPmsLoginResult,
  ZhonghuiPmsOptions,
  ZhonghuiPmsStatus,
} from './types';
import { trustedWriteHeaders, type TrustedWriteHeaderOptions } from './writeHeaders';
import { continuationExportRequest } from './continuationExport';
import {
  zhonghuiPmsBatchIdempotencyKey,
  type ZhonghuiPmsBatchUploadBody,
} from './zhonghuiPmsIdempotency';
import {
  shipmentJdOutboundIdempotencyKey,
  shipmentJdOutboundSubmitRequest,
} from './shipmentJdOutbound';
import {
  sourceSyncExecuteRequest,
  sourceSyncIdempotencyKey,
  type SourceSyncCheck,
  type SourceSyncOutcome,
} from './sourceSync';
import {
  manualTrackingIdempotencyKey,
  manualTrackingRequest,
  type CarrierOption,
  type ManualTrackingOutcome,
} from './manualTracking';

/** 写操作只由浏览器生成幂等键；操作人由受信网关认证后注入。 */
export function writeHeaders(options?: TrustedWriteHeaderOptions): Record<string, string> {
  return trustedWriteHeaders(options);
}

/** 通用分页查询参数（契约 §3.3）。 */
export interface PageQuery {
  page?: number;
  size?: number;
  sort?: string[];
  date_from?: string;
  date_to?: string;
}

export interface MasterDataListQuery {
  page?: number;
  size?: number;
  provider_id?: string;
  source_channel?: string;
  /** SKU 档案列表：按 SKU 编码 / 商品名称模糊搜索。 */
  query?: string;
}

export interface CustomerListQuery extends PageQuery {
  query?: string;
}

export interface ProductArchiveSheetListQuery extends PageQuery {
  query?: string;
}

export interface AnalyticsQuery {
  date_from?: string;
  date_to?: string;
  source_channel?: string;
  provider_id?: string;
  product_id?: string;
  sku_id?: string;
  category_id?: string;
}

export interface InventoryOverviewQuery {
  page?: number;
  size?: number;
  provider_id?: string;
  sku_id?: string;
  warehouse_code?: string;
}

export interface InventoryDetailsQuery {
  provider_id: string;
  sku_id: string;
  warehouse_code?: string;
}

/** GET /api/v1/orders 的查询参数（契约 §4.1 / openapi listOrders）。 */
export interface OrderListQuery {
  page?: number;
  size?: number;
  sort?: string[];
  date_from?: string;
  date_to?: string;
  source_channel?: string;
  order_status?: string;
  processing_stage?: string;
  processing_health?: string;
  provider_id?: string;
  query?: string;
}

export const dashboardApi = {
  /** GET /api/v1/dashboard/summary —— 今日 KPI、近 7 日趋势、待人工介入摘要。 */
  summary: (businessDate?: string) =>
    apiRequest<DashboardSummary>('/api/v1/dashboard/summary', { params: { business_date: businessDate } }),
};

export const ordersApi = {
  /** GET /api/v1/orders —— 业务订单分页列表（BUSINESS only）。 */
  list: (query: OrderListQuery) =>
    apiRequest<OrderPage>('/api/v1/orders', { params: query as Record<string, QueryValue> }),

  /** GET /api/v1/orders/{order_id} —— 聚合详情。 */
  detail: (orderId: string) => apiRequest<OrderDetail>(`/api/v1/orders/${orderId}`),

  /** GET /api/v1/orders/{order_id}/timeline —— 按 order-scoped sequence_no 排列的 OrderEvent。 */
  timeline: (orderId: string) => apiRequest<OrderEvent[]>(`/api/v1/orders/${orderId}/timeline`),

  /** GET /api/v1/orders/{order_id}/versions —— 不可变版本列表。 */
  versions: (orderId: string) => apiRequest<OrderVersion[]>(`/api/v1/orders/${orderId}/versions`),

  /** GET /api/v1/orders/{order_id}/shipments —— 分批、实发量、运单（不含收件人快照）。 */
  shipments: (orderId: string) => apiRequest<OrderShipment[]>(`/api/v1/orders/${orderId}/shipments`),
};

export const demoApi = {
  /** GET /demo/v1/scenarios —— 可演示的固定场景。 */
  scenarios: () => apiRequest<DemoScenario[]>('/demo/v1/scenarios'),

  /** POST /demo/v1/scenarios —— 按 scenario_code 创建 DemoRun（Mock 同步跑完 Timeline）。
   *  只写 DEMO 数据域；不进入业务队列/分析/Metabase。 */
  run: (scenarioCode: string) =>
    apiRequest<DemoRun>('/demo/v1/scenarios', {
      method: 'POST',
      body: { scenario_code: scenarioCode },
      headers: { 'Idempotency-Key': newRequestId() },
    }),

  /** GET /demo/v1/runs/{run_id} —— 查询演示运行与关联 Demo 订单摘要。 */
  runDetail: (runId: string) => apiRequest<DemoRun>(`/demo/v1/runs/${runId}`),
};

/** AI 订单提取会话；确认后由服务端调用 DEMO 专用订单入口。 */
export const orderAssistantApi = {
  config: () => apiRequest<OrderAssistantConfig>('/customer/v1/order-assistant/config'),
  createSession: () =>
    apiRequest<OrderAssistantSession>('/customer/v1/order-assistant/sessions', {
      method: 'POST',
      body: {},
    }),
  sendMessage: (sessionId: string, message: string) =>
    apiRequest<OrderAssistantSession>(`/customer/v1/order-assistant/sessions/${sessionId}/messages`, {
      method: 'POST',
      body: { message },
    }),
  confirm: (sessionId: string) =>
    apiRequest<OrderAssistantSession>(`/customer/v1/order-assistant/sessions/${sessionId}/confirm`, {
      method: 'POST',
      body: {},
    }),
};

// ---------- 主数据（openapi MasterData 组） ----------

/** GET /api/v1/customers —— 只返回 BUSINESS 客户，供人工复核选择既有主数据。 */
export const customersApi = {
  list: (query: CustomerListQuery = {}) =>
    apiRequest<MasterDataPage>('/api/v1/customers', { params: query as Record<string, QueryValue> }),
};

/** GET/POST /api/v1/categories，GET/PATCH /api/v1/categories/{id}。 */
export const categoriesApi = {
  list: (query: PageQuery = {}) => apiRequest<MasterDataPage>('/api/v1/categories', { params: query as Record<string, QueryValue> }),
  create: (body: { code: string; name: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>('/api/v1/categories', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; name?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/categories/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** GET/POST /api/v1/products，GET/PATCH /api/v1/products/{id}，GET /api/v1/products/tags。 */
export const productsApi = {
  list: (query: PageQuery = {}) => apiRequest<MasterDataPage>('/api/v1/products', { params: query as Record<string, QueryValue> }),
  create: (body: {
    product_code: string;
    product_name: string;
    category_id: string;
    ingredients?: string;
    tags?: string[];
    listed_from?: string;
    listed_until?: string;
    lead_time_hours?: number;
    purchase_price?: string;
    retail_price?: string;
    other_cost?: string;
    main_image_ref?: string;
    active?: boolean;
  }) =>
    apiRequest<MasterDataRecord>('/api/v1/products', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: {
    expected_version: number;
    product_name?: string;
    category_id?: string;
    ingredients?: string | null;
    tags?: string[] | null;
    listed_from?: string | null;
    listed_until?: string | null;
    lead_time_hours?: number | null;
    purchase_price?: string | null;
    retail_price?: string | null;
    other_cost?: string | null;
    main_image_ref?: string | null;
    active?: boolean;
  }) =>
    apiRequest<MasterDataRecord>(`/api/v1/products/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
  tags: () => apiRequest<string[]>('/api/v1/products/tags'),
  /** 商品档案·成本表全列留存（只读）。fields 按原表列序返回，渲染时不得排序。 */
  archiveSheet: (id: string) =>
    apiRequest<ProductArchiveSheet[]>(`/api/v1/products/${id}/archive-sheet`),
};

/** GET /api/v1/product-archive-sheets —— 成本表全部行（含未挂接行），只读。 */
export const productArchiveSheetsApi = {
  list: (query: ProductArchiveSheetListQuery = {}) =>
    apiRequest<ProductArchiveSheetPage>('/api/v1/product-archive-sheets', {
      params: query as Record<string, QueryValue>,
    }),
};

/** POST /api/v1/product-images（multipart），GET /api/v1/product-images?ref=...。 */
export const productImagesApi = {
  upload: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    return apiRequest<ProductImageUploadResult>('/api/v1/product-images', { method: 'POST', body });
  },
};

/** 主图读取 URL（内容寻址引用，可长期缓存）。 */
export function productImageUrl(ref: string): string {
  return `/api/v1/product-images?ref=${encodeURIComponent(ref)}`;
}

/** GET/POST /api/v1/skus，GET /api/v1/skus/export，GET/PATCH /api/v1/skus/{id}。 */
export const skusApi = {
  list: (query: MasterDataListQuery = {}) => apiRequest<SkuPage>('/api/v1/skus', { params: query as Record<string, QueryValue> }),
  exportFile: () => downloadFile('/api/v1/skus/export', '子牧商品档案'),
  create: (body: {
    provider_id: string;
    product_id: string;
    specification: string;
    unit: string;
    barcode?: string;
    purchase_price?: string;
    retail_price?: string;
    active?: boolean;
  }) =>
    apiRequest<SkuRecord>('/api/v1/skus', { method: 'POST', body, headers: writeHeaders() }),
  createWithProduct: (body: {
    product: {
      product_code: string;
      product_name: string;
      category_id: string;
      active?: boolean;
    };
    sku: {
      provider_id: string;
      specification: string;
      unit: string;
      barcode?: string;
      purchase_price?: string;
      retail_price?: string;
      active?: boolean;
    };
  }) =>
    apiRequest<SkuRecord>('/api/v1/products/with-sku', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: {
    expected_version: number;
    specification?: string;
    barcode?: string | null;
    purchase_price?: string | null;
    retail_price?: string | null;
    active?: boolean;
  }) =>
    apiRequest<SkuRecord>(`/api/v1/skus/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** POST /api/v1/order-lines/{id}/substitute-sku —— 京东库存/映射阻断补救：换货。 */
export const orderLinesApi = {
  substituteSku: (orderLineId: string, body: { new_sku_id: string; expected_order_version: number }) =>
    apiRequest<OrderLineSkuSubstitutionResult>(`/api/v1/order-lines/${orderLineId}/substitute-sku`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
};

/** GET/POST /api/v1/product-bundles —— 静态礼包及其当前 BOM。 */
export const productBundlesApi = {
  list: (query: PageQuery = {}) =>
    apiRequest<ProductBundlePage>('/api/v1/product-bundles', { params: query as Record<string, QueryValue> }),
  create: (body: ProductBundleCreateInput) =>
    apiRequest<ProductBundleRecord>('/api/v1/product-bundles', { method: 'POST', body, headers: writeHeaders() }),
};

/** GET/POST /api/v1/source-sku-mappings，GET/PATCH /api/v1/source-sku-mappings/{id}。 */
export const sourceSkuMappingsApi = {
  list: (query: MasterDataListQuery = {}) =>
    apiRequest<MasterDataPage>('/api/v1/source-sku-mappings', { params: query as Record<string, QueryValue> }),
  create: (body: {
    source_channel: string;
    source_sku_ref: string;
    source_sku_name?: string;
    sku_id: string;
    quantity_multiplier: string;
    active?: boolean;
  }) => apiRequest<MasterDataRecord>('/api/v1/source-sku-mappings', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; sku_id?: string; quantity_multiplier?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/source-sku-mappings/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/**
 * GET/POST /api/v1/source-bundle-mappings，GET/PATCH /api/v1/source-bundle-mappings/{id}。
 * 与 sourceSkuMappingsApi 对称；包装乘数一期恒为 1，创建/更新都不由前端传。
 */
export const sourceBundleMappingsApi = {
  list: (query: MasterDataListQuery = {}) =>
    apiRequest<MasterDataPage>('/api/v1/source-bundle-mappings', { params: query as Record<string, QueryValue> }),
  create: (body: {
    source_channel: string;
    source_bundle_ref: string;
    source_bundle_name?: string;
    source_barcode?: string;
    bundle_id: string;
    active?: boolean;
  }) => apiRequest<MasterDataRecord>('/api/v1/source-bundle-mappings', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; bundle_id?: string; source_bundle_name?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/source-bundle-mappings/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** GET/POST /api/v1/provider-sku-mappings，GET/PATCH /api/v1/provider-sku-mappings/{id}。 */
export const providerSkuMappingsApi = {
  list: (query: PageQuery = {}) =>
    apiRequest<MasterDataPage>('/api/v1/provider-sku-mappings', { params: query as Record<string, QueryValue> }),
  create: (body: {
    provider_id: string;
    sku_id: string;
    provider_sku_code: string;
    provider_sku_name?: string;
    merchant_sku_code?: string;
    jd_pieces_per_unit?: string;
    active?: boolean;
  }) =>
    apiRequest<MasterDataRecord>('/api/v1/provider-sku-mappings', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: {
    expected_version: number;
    provider_sku_code?: string;
    provider_sku_name?: string;
    merchant_sku_code?: string;
    jd_pieces_per_unit?: string;
    active?: boolean;
  }) =>
    apiRequest<MasterDataRecord>(`/api/v1/provider-sku-mappings/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
  jdPiecesCandidates: () =>
    apiRequest<JdPiecesCandidate[]>('/api/v1/provider-sku-mappings/jd-pieces-candidates'),
  importJdPiecesPerUnit: (body: { rows: Array<{ provider_sku_code: string; jd_pieces_per_unit: string }> }) =>
    apiRequest<JdPiecesImportResult>('/api/v1/provider-sku-mappings/jd-pieces-per-unit-imports', {
      method: 'POST',
      body,
      headers: writeHeaders(),
    }),
};

export const providerSkuMappingReferencesApi = {
  preview(referenceFile: File, sourceFile: File): Promise<ProviderSkuReferencePreview> {
    const form = new FormData();
    form.append('reference_file', referenceFile);
    form.append('source_file', sourceFile);
    return multipartRequest<ProviderSkuReferencePreview>('/api/v1/provider-sku-mapping-references/preview', form);
  },
};

/** 内部运营人员登记（Issue #89）：GET/POST /api/v1/operators，GET/PATCH /api/v1/operators/{id}。 */
export interface OperatorListQuery extends PageQuery {
  /** 责任团队精确筛选（服务端 trim + 大写归一）。 */
  responsible_team?: string;
  /** 姓名/企微 userid 模糊检索。 */
  query?: string;
}

export const operatorsApi = {
  list: (query: OperatorListQuery = {}) =>
    apiRequest<OperatorPage>('/api/v1/operators', { params: query as Record<string, QueryValue> }),
  create: (body: {
    display_name: string;
    responsible_team: string;
    /** 可空 = 未绑定；空串/纯空白视为未绑定。 */
    wecom_userid?: string | null;
    active?: boolean;
  }) => apiRequest<Operator>('/api/v1/operators', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: {
    expected_version: number;
    display_name?: string;
    responsible_team?: string;
    /** null = 不改动绑定；空串 = 显式清除绑定。 */
    wecom_userid?: string | null;
    active?: boolean;
  }) => apiRequest<Operator>(`/api/v1/operators/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** GET /api/v1/fulfillment-providers —— 履约方目录（京东仓 + 第三方）。 */
export const providersApi = {
  list: () => apiRequest<FulfillmentProvider[]>('/api/v1/fulfillment-providers'),
  update: (id: string, body: {
    expected_version: number;
    provider_name?: string;
    tracking_sla_minutes?: number;
    active?: boolean;
    /** config 合并写入：京东键字符串必须非空，townRequired 只接受布尔，null 清除该键；wecomGroupChatId 为企微群 chatid（空串/留空提交 null 清除）；wecomReminderIntervalMinutes 为提醒间隔分钟（1..10080，null 恢复默认 = 运单回传时限）。 */
    config?: Record<string, string | boolean | number | null>;
  }) =>
    apiRequest<FulfillmentProvider>(`/api/v1/fulfillment-providers/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** GET /api/v1/wecom/chats —— 机器人可达会话目录（配置企微推送目标时的候选）。 */
export const wecomChatsApi = {
  list: () => apiRequest<{ chats: KnownWecomChat[] }>('/api/v1/wecom/chats'),
  /**
   * 会话档案部分更新（全契约 snake_case）：字段省略 = 不动，空串 = 清除。
   * 客户群配 reply_mode=RECEIPTS_ONLY 仅业务消息；个人助手保持 FULL。
   */
  setProfile: (
    chatId: string,
    profile: {
      reply_mode?: 'FULL' | 'RECEIPTS_ONLY';
      display_name?: string;
      agent_slug?: string;
      note?: string;
    },
  ) =>
    apiRequest<KnownWecomChat>(`/api/v1/wecom/chats/${encodeURIComponent(chatId)}/profile`, {
      method: 'PUT',
      body: profile,
    }),
};

/**
 * GET/PUT /api/v1/wecom/bots —— 企微机器人管理台账（管理界面先行，运行时多机器人接线
 * 未启用）。secret 只回 secret_configured 存在性标记，绝不回显明文。
 */
export const wecomBotsApi = {
  list: () => apiRequest<{ bots: WecomBot[] }>('/api/v1/wecom/bots'),
  /**
   * 登记或更新一个机器人；secret 留空（undefined/空串）保持现值，与京东 pin 的编辑
   * 交互先例一致；不存在的 bot_id 直接新建。
   */
  upsert: (
    botId: string,
    body: {
      name: string;
      secret?: string;
      enabled?: boolean;
      note?: string;
    },
  ) =>
    apiRequest<WecomBot>(`/api/v1/wecom/bots/${encodeURIComponent(botId)}`, {
      method: 'PUT',
      body,
    }),
};

// ---------- 履约中心 ----------

export interface FulfillmentListQuery extends PageQuery {
  provider_id?: string;
  shipping_progress?: string;
  outcome?: string;
}

export interface ShipmentListQuery extends PageQuery {
  provider_id?: string;
  shipment_status?: string;
}

export interface FulfillmentExportListQuery extends PageQuery {
  provider_id?: string;
  usage_status?: string;
}

/** GET /api/v1/fulfillments + 详情；POST 第三方履约续发批次。 */
export const fulfillmentsApi = {
  list: (query: FulfillmentListQuery) =>
    apiRequest<FulfillmentPage>('/api/v1/fulfillments', { params: query as Record<string, QueryValue> }),
  detail: (id: string) => apiRequest<FulfillmentDetail>(`/api/v1/fulfillments/${id}`),
  createContinuationExport: (id: string, body: ContinuationExportCommand) => {
    const request = continuationExportRequest(id, body, writeHeaders());
    return apiRequest<ContinuationExportResult>(request.path, request.options);
  },
};

/** GET /api/v1/shipments + 详情（明细行 + 运单）。 */
export const shipmentsApi = {
  list: (query: ShipmentListQuery) =>
    apiRequest<ShipmentPage>('/api/v1/shipments', { params: query as Record<string, QueryValue> }),
  detail: (id: string) => apiRequest<Shipment>(`/api/v1/shipments/${id}`),
  previewJdOutbound: (id: string) =>
    apiRequest<ShipmentJdOutboundPreview>(`/api/v1/shipments/${id}/jd-so-order-preview`),
  submitJdOutbound: (id: string) => {
    const request = shipmentJdOutboundSubmitRequest(id, writeHeaders({
      idempotencyKey: shipmentJdOutboundIdempotencyKey(id),
    }));
    return apiRequest<ShipmentJdOutboundSubmitResult>(request.path, request.options);
  },
  /** 人工录入运单：手上只有单号、没有回填文件时用。走与文件链路同一个写入内核。 */
  enterManualTracking: (id: string, carrier: string | undefined, trackingNumber: string) => {
    const request = manualTrackingRequest(id, carrier, trackingNumber, writeHeaders({
      idempotencyKey: manualTrackingIdempotencyKey(id),
    }));
    return apiRequest<ManualTrackingOutcome>(request.path, request.options);
  },
  /** 录入界面的快递公司下拉；只返回启用的承运商。 */
  carriers: () => apiRequest<CarrierOption[]>('/api/v1/carriers'),
  /** 只读：去来源平台读一次当前事实，返回稳定 check_hash 供 execute 绑定。 */
  checkSourceSync: (id: string) =>
    apiRequest<SourceSyncCheck>(`/api/v1/shipments/${id}/source-sync/check`),
  /** 不可逆：把运单号写回来源平台。哈希与 check 对不上服务端会拒。 */
  executeSourceSync: (id: string, expectedCheckHash: string) => {
    const request = sourceSyncExecuteRequest(id, expectedCheckHash, writeHeaders({
      idempotencyKey: sourceSyncIdempotencyKey(id),
    }));
    return apiRequest<SourceSyncOutcome>(request.path, request.options);
  },
  checkJdSkuMapping: (id: string) =>
    apiRequest<ShipmentJdSkuMappingGateResult>(`/api/v1/shipments/${id}/jd-sku-mapping-check`, {
      method: 'POST',
      headers: writeHeaders(),
    }),
  checkJdStock: (id: string) =>
    apiRequest<ShipmentJdStockCheckResult>(`/api/v1/shipments/${id}/jd-stock-check`, {
      method: 'POST',
      headers: writeHeaders(),
    }),
  jdReceiverAddressCandidates: (params: { import_batch_id?: string; only_missing?: boolean } = {}) =>
    apiRequest<JdReceiverAddressCandidate[]>('/api/v1/shipments/jd-receiver-address-candidates', {
      params: params as Record<string, QueryValue>,
    }),
  confirmJdReceiverAddresses: (body: {
    items: Array<{
      shipment_id: string;
      expected_version: number;
      province: string;
      city: string;
      county: string;
      town?: string | null;
      detail_address: string;
    }>;
  }, options?: { idempotencyKey?: string }) =>
    apiRequest<{ confirmed_count: number; items: Array<Record<string, unknown>> }>(
      '/api/v1/shipments/jd-receiver-address-batch',
      { method: 'POST', body, headers: writeHeaders({ idempotencyKey: options?.idempotencyKey }) },
    ),
};

/** 已落库的跨履约方库存事实；无观测与显式零严格区分。 */
export const inventoryApi = {
  overview: (query: InventoryOverviewQuery = {}) =>
    apiRequest<InventoryOverviewResponse>('/api/v1/inventory/overview', {
      params: query as Record<string, QueryValue>,
    }),
  details: (query: InventoryDetailsQuery) =>
    apiRequest<InventoryDetailsResponse>('/api/v1/inventory/details', {
      params: {
        provider_id: query.provider_id,
        sku_id: query.sku_id,
        warehouse_code: query.warehouse_code,
      },
    }),
};

/** GET /api/v1/fulfillment-exports + 详情 + 文件下载。 */
export const fulfillmentExportsApi = {
  list: (query: FulfillmentExportListQuery) =>
    apiRequest<FulfillmentExportPage>('/api/v1/fulfillment-exports', { params: query as Record<string, QueryValue> }),
  detail: (id: string) => apiRequest<FulfillmentExportDetail>(`/api/v1/fulfillment-exports/${id}`),
  /** 下载履约导出文件（application/octet-stream）。client.ts 为 JSON 客户端，故此处直接 fetch。 */
  async downloadFile(id: string, exportBatchNo: string): Promise<void> {
    const res = await fetch(`/api/v1/fulfillment-exports/${id}/file`, {
      headers: { Accept: 'application/octet-stream', 'X-Request-Id': newRequestId() },
    });
    if (!res.ok) {
      throw new ApiError(res.status, { message: '文件下载未完成，请稍后重试', http_status: res.status });
    }
    const blob = await res.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${exportBatchNo}.xlsx`;
    a.click();
    URL.revokeObjectURL(url);
  },
  /** 人工停止企微自动发送与周期提醒（#84）：版本 CAS + 理由；已收齐/已停止幂等 no-op。 */
  wecomStop: (id: string, body: { expected_version: number; reason: string }) =>
    apiRequest<FulfillmentExportWecomState>(
      `/api/v1/fulfillment-exports/${id}/wecom-stop`,
      { method: 'POST', body, headers: { ...trustedWriteHeaders(), 'Content-Type': 'application/json' } },
    ),
  /** 人工重发企微文件消息（#84）：只登记新 delivery + 任务，发送异步执行。 */
  wecomResend: (id: string, body: { expected_version: number; reason?: string }) =>
    apiRequest<FulfillmentExportWecomState & { resend_delivery_id?: string; resend_sequence?: number }>(
      `/api/v1/fulfillment-exports/${id}/wecom-resend`,
      { method: 'POST', body, headers: { ...trustedWriteHeaders(), 'Content-Type': 'application/json' } },
    ),
};

async function multipartRequest<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'X-Request-Id': newRequestId(), ...writeHeaders() },
    body: form,
  });
  if (!res.ok) {
    let body = { message: '文件处理未完成，请检查文件后重试', http_status: res.status };
    try {
      body = { ...body, ...(await res.json()) };
    } catch {
      // 非 JSON 错误体使用稳定的用户提示。
    }
    throw new ApiError(res.status, body);
  }
  return (await res.json()) as T;
}

async function downloadFile(path: string, fallbackName: string): Promise<void> {
  const res = await fetch(path, {
    headers: { Accept: 'application/octet-stream', 'X-Request-Id': newRequestId() },
  });
  if (!res.ok) {
    throw new ApiError(res.status, { message: '文件下载未完成，请稍后重试', http_status: res.status });
  }
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  let filename = fallbackName;
  try {
    filename = encodedName ? decodeURIComponent(encodedName) : plainName ?? fallbackName;
  } catch {
    filename = plainName ?? fallbackName;
  }
  if (!/\.(csv|xls|xlsx)$/i.test(filename)) {
    filename += blob.type.includes('csv') ? '.csv' : '.xlsx';
  }
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 三平台（彩食鲜/聚福宝/飞象）订单数据一键刷新（人工触发）。 */
export const platformOrdersApi = {
  /** POST /api/v1/platform-orders/refresh —— 拉取并自动上传为导入批次。 */
  refresh: (body?: { channels?: SourceChannel[]; date_begin?: string; date_end?: string }) =>
    apiRequest<PlatformOrderRefreshResult>('/api/v1/platform-orders/refresh', {
      method: 'POST',
      body: body ?? {},
      headers: writeHeaders(),
    }),
};

/** 来源订单、履约回传与来源回填组成同一条文件作业闭环。 */
export const fileOperationsApi = {
  uploadSourceJob(
    file: File,
    sourceChannel: SourceChannel,
    mode: 'NEW' | 'REVISION' = 'NEW',
    parentBatchId?: string,
  ) {
    const form = new FormData();
    form.append('file', file);
    form.append('source_channel', sourceChannel);
    form.append('import_mode', mode);
    if (parentBatchId) form.append('parent_import_batch_id', parentBatchId);
    return multipartRequest<SourceOrderIntakeJob>('/api/v1/source-order-intake-jobs', form);
  },
  getSourceJob: (id: string) =>
    apiRequest<SourceOrderIntakeJob>(`/api/v1/source-order-intake-jobs/${id}`),
  downloadSourceOriginal: (id: string) =>
    downloadFile(`/api/v1/source-order-intake-jobs/${id}/file`, `来源订单原件-${id}`),
  uploadSource(file: File, mode: 'NEW' | 'REVISION' = 'NEW', parentBatchId?: string) {
    const form = new FormData();
    form.append('file', file);
    form.append('import_mode', mode);
    if (parentBatchId) form.append('parent_import_batch_id', parentBatchId);
    return multipartRequest<ImportBatch>('/api/v1/import-batches/source-orders', form);
  },
  getSourceBatch: (id: string) => apiRequest<ImportBatch>(`/api/v1/import-batches/${id}`),
  /** 待确认的来源批次清单——「确认发货」入口的数据来源，只读、无副作用。 */
  pendingConfirmationBatches: () =>
    apiRequest<{ items: PendingConfirmationBatch[] }>('/api/v1/import-batches/pending-confirmation'),
  confirmSourceBatch: (id: string) => apiRequest<ImportBatch>(`/api/v1/import-batches/${id}/confirm`, {
    method: 'POST',
    body: {},
    headers: writeHeaders({ idempotencyKey: `import-batch-confirm-${id}` }),
  }),
  /**
   * 补做确认：阻断行修好后，把新就绪的行接着发出去。
   *
   * 不能沿用 confirmSourceBatch 的稳定幂等键——那个键会被判为重放，补做会静默变成空操作。
   * 这里走随机键（writeHeaders 内部用 newRequestId，明文 HTTP 下有非 crypto 回退），
   * 重复点击是安全的：后端对批次行加锁，且 candidateRows 本身排除已导出行。
   */
  reconfirmSourceBatch: (id: string) => apiRequest<ImportBatch>(`/api/v1/import-batches/${id}/confirm`, {
    method: 'POST',
    body: {},
    headers: writeHeaders(),
  }),
  /** 05：对批次内京东发货批次批量触发 SDK 建单；已提交跳过，失败项可安全重试。 */
  submitJdOutboundsForBatch: (id: string) =>
    apiRequest<{
      submitted_count: number;
      skipped_count: number;
      items: Array<Record<string, unknown>>;
    }>(`/api/v1/import-batches/${id}/jd-outbound-submit`, {
      method: 'POST',
      body: {},
      headers: writeHeaders(),
    }),
  getSourceRows: (
    id: string,
    query: { page?: number; size?: number; status?: RawRowStatus } = {},
  ) => apiRequest<RawImportRowPage>(`/api/v1/import-batches/${id}/rows`, {
    params: query as Record<string, QueryValue>,
  }),
  uploadTracking(exportId: string, file: File, mode: 'NEW' | 'REVISION' = 'NEW', parentBatchId?: string) {
    const form = new FormData();
    form.append('file', file);
    form.append('import_mode', mode);
    if (parentBatchId) form.append('parent_import_batch_id', parentBatchId);
    return multipartRequest<TrackingImportBatch>(`/api/v1/fulfillment-exports/${exportId}/tracking-imports`, form);
  },
  getTrackingBatch: (id: string) => apiRequest<TrackingImportBatch>(`/api/v1/tracking-imports/${id}`),
  sourceReturns: (sourceBatchId: string) =>
    apiRequest<SourceReturnExport[]>(`/api/v1/import-batches/${sourceBatchId}/source-return-exports`),
  downloadSourceReturn: (id: string) => downloadFile(`/api/v1/source-return-exports/${id}/file`, `来源回填-${id}`),
};

// ---------- 采购工单 ----------

export interface ProcurementTicketListQuery extends PageQuery {
  status?: string;
}

export const procurementApi = {
  /** GET /api/v1/procurement-tickets —— 采购工单列表。 */
  list: (query: ProcurementTicketListQuery) =>
    apiRequest<ProcurementTicketPage>('/api/v1/procurement-tickets', { params: query as Record<string, QueryValue> }),
  /** GET /api/v1/procurement-tickets/{id} —— 明细（条目 + 追加式回执）。 */
  detail: (id: string) => apiRequest<ProcurementTicket>(`/api/v1/procurement-tickets/${id}`),
  retry: (id: string, expectedVersion: number, note: string) =>
    apiRequest<ProcurementTicket>(`/api/v1/procurement-tickets/${id}/retry`, {
      method: 'POST',
      body: { expected_version: expectedVersion, note },
      headers: writeHeaders(),
    }),
  cancelRemaining: (id: string, expectedVersion: number, note: string) =>
    apiRequest<ProcurementTicket>(`/api/v1/procurement-tickets/${id}/cancel-remaining`, {
      method: 'POST',
      body: { expected_version: expectedVersion, reason: note },
      headers: writeHeaders(),
    }),
};

// ---------- 采购比价 Agent（01 票：不可比候选降级展示） ----------

/** POST /api/v1/procurement-price-agent/compare —— 运行一次采购比价（只读）。 */
export const procurementPriceAgentApi = {
  compare: (command: ProcurementPriceCompareCommand) =>
    apiRequest<ProcurementPriceRunResult>('/api/v1/procurement-price-agent/compare', {
      method: 'POST',
      body: command,
    }),
};

// ---------- 复核队列 ----------

/** GET /api/v1/review-cases —— 业务人工复核队列（数据中台「需人工介入」）。 */
export const reviewCasesApi = {
  list: (query: { page?: number; size?: number; status?: string; reason_code?: string; responsible_team?: string; source_channel?: string; import_batch_id?: string }) =>
    apiRequest<ReviewCasePage>('/api/v1/review-cases', { params: query as Record<string, QueryValue> }),
  detail: (id: string) => apiRequest<ReviewCase>(`/api/v1/review-cases/${id}`),
  resolveCustomer: (id: string, body: ResolveCustomerReviewCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/resolve-customer`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
  resolveSku: (id: string, body: ResolveSkuReviewCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/resolve-sku`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
  completeSourceFollowup: (id: string, body: VersionedNoteCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/complete-source-followup`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
  /** POST /api/v1/review-cases/{id}/resolve —— 无专用动作的事项人工显式闭环。 */
  resolve: (id: string, body: VersionedNoteCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/resolve`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
  /** POST /api/v1/review-cases/{id}/dismiss —— 关闭误建或不再需要的事项。 */
  dismiss: (id: string, body: VersionedNoteCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/dismiss`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
  /** POST /api/v1/review-cases/{id}/resolve-jd-tracking-conflict —— 确认京东运单冲突已人工处理。 */
  resolveJdTrackingConflict: (id: string, body: VersionedNoteCommand) =>
    apiRequest<ReviewCase>(`/api/v1/review-cases/${id}/resolve-jd-tracking-conflict`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
};

export const operationalAlertsApi = {
  list: (query: { page?: number; size?: number; status?: string; severity?: string }) =>
    apiRequest<OperationalAlertPage>('/api/v1/operational-alerts', { params: query as Record<string, QueryValue> }),
  acknowledge: (id: string, body: VersionedNoteCommand) =>
    apiRequest<OperationalAlert>(`/api/v1/operational-alerts/${id}/acknowledge`, {
      method: 'POST', body, headers: writeHeaders(),
    }),
};

// ---------- 京东仓只读 SDK 作业面 ----------

export const jdWarehouseApi = {
  status: () => apiRequest<JdClientStatus>('/api/v1/jd-warehouse/status'),
  owners: () => apiRequest<JdQueryResult>('/api/v1/jd-warehouse/owners'),
  warehouses: (warehouseNo?: string) =>
    apiRequest<JdQueryResult>('/api/v1/jd-warehouse/warehouses', {
      params: { warehouse_no: warehouseNo },
    }),
  outboundOrder: (erpDeliveryNo: string) =>
    apiRequest<JdQueryResult>(`/api/v1/jd-warehouse/outbound-orders/${encodeURIComponent(erpDeliveryNo)}`),
  tracking: (query: { waybill_no?: string; warehouse_order_no?: string }) =>
    apiRequest<JdQueryResult>('/api/v1/jd-warehouse/tracking', { params: query }),
};

// ---------- 出库信息内外事实并排（Ticket 01） ----------

export const outboundReconApi = {
  /** GET /api/v1/outbound-recon —— 系统出库单号 / 京东单号 / 订单号收敛到同一笔出库并排对照。 */
  query: (query: { query_type: OutboundReconQueryType; query_value: string }) =>
    apiRequest<OutboundReconView>('/api/v1/outbound-recon', { params: query }),
};

// ---------- 系统（Connector / Audit Log） ----------

export const connectorsApi = {
  /** GET /api/v1/connectors —— 四渠道 Connector 配置。 */
  list: () => apiRequest<ConnectorConfig[]>('/api/v1/connectors'),
  /**
   * PATCH /api/v1/connectors/{source_channel} —— 连接配置更新。
   * username 非敏感，直接回显；password 比照履约方京东 pin 先例：留空不提交 = 保持现值，
   * 保存后永不回显明文，只投影 password_configured 存在性标记。
   */
  update: (channel: string, body: {
    expected_version: number;
    client_mode?: string;
    transport_mode?: string;
    enabled?: boolean;
    endpoint?: string;
    credential_secret_ref?: string;
    username?: string;
    password?: string;
    /**
     * 拉取时间表：给就必须五个字段齐全（整体替换），省略表示不改。
     * 后端刻意不接受部分 patch——缺省会回落成「启用」，一次漏发的字段等于把用户刚关掉的档位
     * 重新打开。
     */
    pull_schedule?: {
      morning: ConnectorPullScheduleSlot;
      evening: ConnectorPullScheduleSlot;
      notify_wecom: boolean;
    };
  }) =>
    apiRequest<ConnectorConfig>(`/api/v1/connectors/${channel}`, { method: 'PATCH', body, headers: writeHeaders() }),
  /** POST /api/v1/connectors/{source_channel}/test-connection —— 连通性测试。 */
  test: (channel: string) =>
    apiRequest<ConnectionTestResult>(`/api/v1/connectors/${channel}/test-connection`, {
      method: 'POST',
      headers: writeHeaders(),
    }),
};

export interface AuditLogListQuery extends PageQuery {
  request_id?: string;
  trace_id?: string;
  operator?: string;
  service?: string;
  operation?: string;
  business_code?: string;
}

export const auditLogsApi = {
  /** GET /api/v1/audit-logs —— 列表（省略大请求/响应体）。 */
  list: (query: AuditLogListQuery) =>
    apiRequest<AuditLogPage>('/api/v1/audit-logs', { params: query as Record<string, QueryValue> }),
  /** GET /api/v1/audit-logs/{id} —— 请求/响应快照。 */
  detail: (id: string) => apiRequest<AuditLog>(`/api/v1/audit-logs/${id}`),
};

export const channelMessagesApi = {
  /** GET /api/v1/channel-messages —— 企业微信原始文字证据列表。 */
  list: (query: Pick<PageQuery, 'page' | 'size'> = {}) =>
    apiRequest<ChannelMessagePage>('/api/v1/channel-messages', {
      params: query as Record<string, QueryValue>,
    }),
  /** GET /api/v1/channel-messages/{id} —— 只返回审核过的白名单字段。 */
  detail: (id: string) => apiRequest<ChannelMessageDetail>(`/api/v1/channel-messages/${id}`),
};

/**
 * 复核页原图受权 URL（GET /api/v1/message-media/{id}/content）。
 * 只返回受权字节（Basic Auth 校验通过即可，浏览器 <img> 无法携带 X-Operator 头），
 * 不暴露磁盘路径、下载凭据或 aeskey。
 */
export function messageMediaContentUrl(mediaId: string): string {
  return `/api/v1/message-media/${mediaId}/content`;
}

export const messageSubmissionsApi = {
  /** GET /api/v1/message-submissions/{id} —— 提交详情、解释历史与最近任务状态。 */
  detail: (id: string) => apiRequest<MessageSubmissionDetail>(`/api/v1/message-submissions/${id}`),
  /** POST /api/v1/message-submissions/{id}/reinterpret —— 追加一次解释版本（新任务）。 */
  reinterpret: (id: string) =>
    apiRequest<MessageSubmissionDetail>(`/api/v1/message-submissions/${id}/reinterpret`, {
      method: 'POST',
      body: {},
      headers: writeHeaders(),
    }),
};

export const businessFollowUpsApi = {
  list: (query: Pick<PageQuery, 'page' | 'size'> & { stage?: string } = {}) =>
    apiRequest<BusinessFollowUpPage>('/api/v1/business-followups', {
      params: query as Record<string, QueryValue>,
    }),
  detail: (id: string) =>
    apiRequest<BusinessFollowUp>(`/api/v1/business-followups/${id}`),
  create: (body: BusinessFollowUpCreateInput) =>
    apiRequest<BusinessFollowUpSummary>('/api/v1/business-followups', {
      method: 'POST',
      body,
      headers: writeHeaders(),
    }),
  organize: (id: string, body: BusinessFollowUpOrganizeInput) =>
    apiRequest<BusinessFollowUpSummary>(`/api/v1/business-followups/${id}/organize`, {
      method: 'POST',
      body,
      headers: writeHeaders(),
    }),
  decide: (id: string, body: BusinessFollowUpDecisionInput) =>
    apiRequest<BusinessFollowUp>(`/api/v1/business-followups/${id}/decisions`, {
      method: 'POST',
      body,
      headers: writeHeaders(),
    }),
};

// ---------- 数据中台（Analytics） ----------

/**
 * GET /api/v1/analytics/channels|products|fulfillments。
 * source_channel 单值参数；多选渠道由页面按渠道并发请求后合并（契约未定义多值参数）。
 */
export const analyticsApi = {
  channels: (query: AnalyticsQuery) =>
    apiRequest<ChannelMetric[]>('/api/v1/analytics/channels', { params: query as Record<string, QueryValue> }),
  products: (query: AnalyticsQuery) =>
    apiRequest<ProductMetric[]>('/api/v1/analytics/products', { params: query as Record<string, QueryValue> }),
  fulfillments: (query: AnalyticsQuery) =>
    apiRequest<FulfillmentMetric[]>('/api/v1/analytics/fulfillments', { params: query as Record<string, QueryValue> }),
};

// ---------- Agent 中心（T12 读契约；只读，写动作等 T11） ----------

/** GET /api/v1/agent-runs 的查询参数（AgentRunFilter，snake_case）。 */
export interface AgentRunsQuery {
  run_id?: string;
  slug?: string;
  outcome?: string;
  /** 不传 = LIVE（后端默认即 LIVE——PREVIEW 草稿试跑不污染线上判断） */
  run_mode?: string;
  business_entity_type?: string;
  business_entity_id?: string;
  started_from?: string;
  started_to?: string;
  limit?: number;
  offset?: number;
}

/** GET /api/v1/agent-runs/token-usage 的查询参数（AgentTokenUsageFilter，snake_case）。 */
export interface AgentTokenUsageQuery {
  slug?: string;
  outcome?: string;
  /** 不传 = LIVE；PREVIEW 是草稿试跑，混进成本视图会让「线上花了多少」失去意义 */
  run_mode?: string;
  business_entity_type?: string;
  business_entity_id?: string;
  started_from?: string;
  started_to?: string;
  /** AGENT（默认）/ DAY / BUSINESS_ENTITY_TYPE；后端按枚举白名单校验 */
  group_by?: string;
  limit?: number;
}

/** GET /api/v1/agents 列表 —— 一次拿全聚合，无分页无查询参数。 */
export const agentsApi = {
  list: () => apiRequest<AgentListResponse>('/api/v1/agents'),
  detail: (slug: string) => apiRequest<AgentDetail>(`/api/v1/agents/${slug}`),
  versions: (slug: string) => apiRequest<AgentVersionItem[]>(`/api/v1/agents/${slug}/versions`),
  /** 某定义版本的冻结用例集（可选 metric_kind 过滤，不传返回全部）。 */
  evalCases: (slug: string, version: number, metricKind?: string) =>
    apiRequest<AgentEvalCaseItem[]>(`/api/v1/agents/${slug}/versions/${version}/eval-cases`, {
      params: metricKind ? { metric_kind: metricKind } : undefined,
    }),
};

/**
 * meta-agent 对话式创建（agent-console 06）。
 * **接口上不存在任何启用路径**——启用必须由人到 Agent 详情页单独做。
 */
export const metaAgentApi = {
  /**
   * 字段名必须是 snake_case 的 `thread_id`。
   *
   * <p>后端 record 写的是 `MetaAgentMessage(String message, String threadId)`，但全局
   * Jackson 用 SNAKE_CASE（common/web/JacksonConfig.java），所以线上字段名是 `thread_id`；
   * 而 apiRequest 只做 JSON.stringify，不做 camel→snake 转换。之前这里发 `threadId`，
   * 后端一律收到 null——**多轮对话每轮都在新开线程，上下文全丢**。
   */
  converse: (message: string, threadId?: string) =>
    apiRequest<MetaAgentOutcome>('/api/v1/agents/meta/conversations', {
      method: 'POST',
      body: { message, thread_id: threadId },
    }),
};

/** 履约单据 Agent：进度只读不花钱，解读才跑模型。 */
export const importBatchApi = {
  progress: (batchId: number) =>
    apiRequest<ImportBatchProgress>(`/api/v1/import-batches/${batchId}/progress`),
  assess: (batchId: number) =>
    apiRequest<FulfillmentFileRunResult>(`/api/v1/import-batches/${batchId}/assessment`, {
      method: 'POST',
    }),
};

export const agentRunsApi = {
  /** GET /api/v1/agent-runs —— 列表；limit 1..500，offset ≥ 0。 */
  list: (query: AgentRunsQuery = {}) =>
    apiRequest<RunListResponse>('/api/v1/agent-runs', { params: query as Record<string, QueryValue> }),
  /** GET /api/v1/agent-runs/{run_id} —— 元信息 + 工具调用序列 + 评测结果摘要。 */
  detail: (runId: string) => apiRequest<RunDetail>(`/api/v1/agent-runs/${runId}`),
  /**
   * GET /api/v1/agent-runs/token-usage —— 消耗汇总（129 票）。
   * 默认 group_by=AGENT、run_mode=LIVE；PREVIEW 是草稿试跑，不进成本视图。
   */
  tokenUsage: (query: AgentTokenUsageQuery = {}) =>
    apiRequest<TokenUsageSummaryResponse>('/api/v1/agent-runs/token-usage', {
      params: query as Record<string, QueryValue>,
    }),
};


/**
 * 中汇 PMS 上传通道（`/api/v1/zhonghui-pms`）。
 *
 * 后端早已完备（7 个端点 + 5 个迁移），此前前端零调用、界面不可达；本对象是补回的调用入口。
 * 写端点的幂等取舍：login 每次都是新会话意图，用随机键；batchUpload 用
 * {@link zhonghuiPmsBatchIdempotencyKey} 生成的稳定键，同一批 SKU + 相同覆盖字段重放首次结果，
 * 不会在 PMS 侧重复建商品。
 */
export const zhonghuiPmsApi = {
  status: () => apiRequest<ZhonghuiPmsStatus>('/api/v1/zhonghui-pms/status'),
  captcha: () => apiRequest<ZhonghuiPmsCaptcha>('/api/v1/zhonghui-pms/captcha'),
  options: () => apiRequest<ZhonghuiPmsOptions>('/api/v1/zhonghui-pms/options'),
  login: (authCode: string, captchaNo: string) =>
    apiRequest<ZhonghuiPmsLoginResult>('/api/v1/zhonghui-pms/login', {
      method: 'POST',
      body: { auth_code: authCode, captcha_no: captchaNo },
      headers: writeHeaders(),
    }),
  logout: () =>
    apiRequest<{ success: boolean }>('/api/v1/zhonghui-pms/logout', {
      method: 'POST',
      headers: writeHeaders(),
    }),
  batchUpload: (body: ZhonghuiPmsBatchUploadBody) =>
    apiRequest<ZhonghuiPmsBatchUploadResult>('/api/v1/zhonghui-pms/batch-uploads', {
      method: 'POST',
      body,
      headers: writeHeaders({ idempotencyKey: zhonghuiPmsBatchIdempotencyKey(body) }),
    }),
  batch: (batchId: string) =>
    apiRequest<ZhonghuiPmsBatchUploadResult>(`/api/v1/zhonghui-pms/upload-batches/${batchId}`),
};
