/**
 * 类型化端点 —— 与 docs/openapi.yaml paths 一一对应（本票用到的子集）。
 * 命名空间边界（contract §2）：
 *   /api/v1  管理后台（BUSINESS）
 *   /demo/v1 模拟下单页（DEMO）
 *   /internal/v1 受信任内部接入 —— 前端不调用。
 */

import { apiRequest, type QueryValue } from './client';
import { ApiError } from './client';
import type {
  AuditLog,
  AuditLogPage,
  ChannelMessageDetail,
  ChannelMessagePage,
  ChannelMetric,
  ConnectionTestResult,
  ConnectorConfig,
  DashboardSummary,
  DemoRun,
  DemoScenario,
  ContinuationExportCommand,
  ContinuationExportResult,
  FulfillmentDetail,
  FulfillmentExportDetail,
  FulfillmentExportPage,
  FulfillmentMetric,
  FulfillmentPage,
  FulfillmentProvider,
  ImportBatch,
  JdQueryResult,
  JdClientStatus,
  MasterDataPage,
  MasterDataRecord,
  OrderDetail,
  OrderAssistantConfig,
  OrderAssistantSession,
  OrderEvent,
  OrderPage,
  OrderVersion,
  OperationalAlert,
  OperationalAlertPage,
  ProcurementTicket,
  ProcurementTicketPage,
  ProviderSkuReferencePreview,
  ProductMetric,
  RawImportRowPage,
  RawRowStatus,
  ReviewCasePage,
  ReviewCase,
  ResolveCustomerReviewCommand,
  ResolveSkuReviewCommand,
  VersionedNoteCommand,
  Shipment,
  ShipmentPage,
  SourceReturnExport,
  TrackingImportBatch,
} from './types';
import { trustedWriteHeaders } from './writeHeaders';
import { continuationExportRequest } from './continuationExport';

/** 写操作只由浏览器生成幂等键；操作人由受信网关认证后注入。 */
export function writeHeaders(extra?: Record<string, string>): Record<string, string> {
  return trustedWriteHeaders(extra);
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
}

export interface CustomerListQuery extends PageQuery {
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

  /** GET /api/v1/orders/{order_id}/shipments —— 分批、实发量、运单。 */
  shipments: (orderId: string) => apiRequest<Shipment[]>(`/api/v1/orders/${orderId}/shipments`),
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
      headers: { 'Idempotency-Key': crypto.randomUUID() },
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

// ---------- 商品中心（主数据，openapi MasterData 组） ----------

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

/** GET/POST /api/v1/products，GET/PATCH /api/v1/products/{id}。 */
export const productsApi = {
  list: (query: PageQuery = {}) => apiRequest<MasterDataPage>('/api/v1/products', { params: query as Record<string, QueryValue> }),
  create: (body: { product_code: string; product_name: string; category_id: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>('/api/v1/products', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; product_name?: string; category_id?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/products/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

/** GET/POST /api/v1/skus，GET/PATCH /api/v1/skus/{id}。 */
export const skusApi = {
  list: (query: MasterDataListQuery = {}) => apiRequest<MasterDataPage>('/api/v1/skus', { params: query as Record<string, QueryValue> }),
  create: (body: { provider_id: string; product_id: string; specification: string; unit: string; barcode?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>('/api/v1/skus', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; specification?: string; barcode?: string | null; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/skus/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
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

/** GET/POST /api/v1/provider-sku-mappings，GET/PATCH /api/v1/provider-sku-mappings/{id}。 */
export const providerSkuMappingsApi = {
  list: (query: PageQuery = {}) =>
    apiRequest<MasterDataPage>('/api/v1/provider-sku-mappings', { params: query as Record<string, QueryValue> }),
  create: (body: { provider_id: string; sku_id: string; provider_sku_code: string; provider_sku_name?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>('/api/v1/provider-sku-mappings', { method: 'POST', body, headers: writeHeaders() }),
  update: (id: string, body: { expected_version: number; provider_sku_code?: string; provider_sku_name?: string; active?: boolean }) =>
    apiRequest<MasterDataRecord>(`/api/v1/provider-sku-mappings/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
};

export const providerSkuMappingReferencesApi = {
  preview(referenceFile: File, sourceFile: File): Promise<ProviderSkuReferencePreview> {
    const form = new FormData();
    form.append('reference_file', referenceFile);
    form.append('source_file', sourceFile);
    return multipartRequest<ProviderSkuReferencePreview>('/api/v1/provider-sku-mapping-references/preview', form);
  },
};

/** GET /api/v1/fulfillment-providers —— 履约方目录（京东仓 + 第三方）。 */
export const providersApi = {
  list: () => apiRequest<FulfillmentProvider[]>('/api/v1/fulfillment-providers'),
  update: (id: string, body: { expected_version: number; provider_name?: string; tracking_sla_minutes?: number; active?: boolean }) =>
    apiRequest<FulfillmentProvider>(`/api/v1/fulfillment-providers/${id}`, { method: 'PATCH', body, headers: writeHeaders() }),
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
};

/** GET /api/v1/fulfillment-exports + 详情 + 文件下载。 */
export const fulfillmentExportsApi = {
  list: (query: FulfillmentExportListQuery) =>
    apiRequest<FulfillmentExportPage>('/api/v1/fulfillment-exports', { params: query as Record<string, QueryValue> }),
  detail: (id: string) => apiRequest<FulfillmentExportDetail>(`/api/v1/fulfillment-exports/${id}`),
  /** 下载履约导出文件（application/octet-stream）。client.ts 为 JSON 客户端，故此处直接 fetch。 */
  async downloadFile(id: string, exportBatchNo: string): Promise<void> {
    const res = await fetch(`/api/v1/fulfillment-exports/${id}/file`, {
      headers: { Accept: 'application/octet-stream', 'X-Request-Id': crypto.randomUUID() },
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
};

async function multipartRequest<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'X-Request-Id': crypto.randomUUID(), ...writeHeaders() },
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
    headers: { Accept: 'application/octet-stream', 'X-Request-Id': crypto.randomUUID() },
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
  if (!/\.(csv|xlsx)$/i.test(filename)) {
    filename += blob.type.includes('csv') ? '.csv' : '.xlsx';
  }
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

/** 来源订单、履约回传与来源回填组成同一条文件作业闭环。 */
export const fileOperationsApi = {
  uploadSource(file: File, mode: 'NEW' | 'REVISION' = 'NEW', parentBatchId?: string) {
    const form = new FormData();
    form.append('file', file);
    form.append('import_mode', mode);
    if (parentBatchId) form.append('parent_import_batch_id', parentBatchId);
    return multipartRequest<ImportBatch>('/api/v1/import-batches/source-orders', form);
  },
  getSourceBatch: (id: string) => apiRequest<ImportBatch>(`/api/v1/import-batches/${id}`),
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

// ---------- 复核队列 ----------

/** GET /api/v1/review-cases —— 业务人工复核队列（数据中台「需人工介入」）。 */
export const reviewCasesApi = {
  list: (query: { page?: number; size?: number; status?: string; reason_code?: string; responsible_team?: string; source_channel?: string }) =>
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

// ---------- 系统（Connector / Audit Log） ----------

export const connectorsApi = {
  /** GET /api/v1/connectors —— 四渠道 Connector 配置。 */
  list: () => apiRequest<ConnectorConfig[]>('/api/v1/connectors'),
  /** PATCH /api/v1/connectors/{source_channel} —— 非密文配置更新。 */
  update: (channel: string, body: Record<string, unknown> & { expected_version: number }) =>
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
