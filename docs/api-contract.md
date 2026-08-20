# 订单履约中台 API 契约

状态：Need Review  
日期：2026-08-11  
机器可读契约：`docs/openapi.yaml`  
权威边界：`docs/prd-v0.1.md`、`docs/state-machine.md`、`docs/schema.md`、`docs/excel-closed-loop-spec.md`及已关闭 Wayfinder 决策票。

## 1. 目标与非目标

本契约覆盖：

1. 管理后台全部页面所需查询与人工命令；
2. 三平台来源文件导入、履约方文件下载、运单文件回传与来源回填文件下载；
3. 未来 LangBot / Agent 创建内部订单与显式修订；
4. 采购回执、复核、取消剩余量、重试与多 Shipment 人工闭环；
5. 工作台、渠道、商品和履约分析；
6. 独立 DemoScenario 入口。

本契约不把京东 SDK、三平台真实 API 或 MCP 实现伪装成公共 REST 接口。它们是 Adapter，必须调用与 REST/UI 共用的应用层用例，禁止直连业务表。

## 2. 命名空间与边界

| 前缀 | 调用者 | 数据范围 | 用途 |
|---|---|---|---|
| `/api/v1` | React 管理后台 | 默认只查 `BUSINESS` | 业务查询、文件操作、人工命令、主数据维护 |
| `/internal/v1` | 受信任的 LangBot / Agent / 部门系统 | 只写 `BUSINESS` | 创建订单、显式修订、采购回执 |
| `/demo/v1` | 模拟下单页 | 只读写 `DEMO` | 创建并查看 DemoScenario / DemoRun |

`BUSINESS` 与 `DEMO` 不使用查询参数混查；管理后台业务 API 不提供 `include_demo=true`。

## 3. 通用约定

### 3.1 JSON、数量和时间

- JSON 字段使用 `snake_case`。
- 所有标识符在 JSON 中用字符串，避免 JavaScript 丢失 `BIGINT` 精度。
- `NUMERIC(18,3)` / BigDecimal 数量使用十进制字符串，例如 `"6.000"`，不使用 JSON 浮点数。
- 时间使用带偏移的 ISO-8601 `date-time`；业务日按 `Asia/Shanghai` 自然日。
- 所有请求和响应使用 UTF-8；文件容器/编码依 Excel 规范单独处理。

### 3.2 请求标识、操作人和幂等

- `X-Request-Id`：可选；未传时由服务端生成并在响应回显。
- `X-Operator`：公共浏览器客户端不得自行提供，受信 Nginx 用服务端主体覆盖该请求头。后端对全部 `/api/` 请求（含读取、预览和下载）复验同一 Basic Auth 凭据，并要求已验证主体与 `X-Operator` 一致；仅伪造该请求头不能授权。全部 `/internal/` 请求使用独立 Bearer 服务身份。仅绕过公共入口的内部 Demo 调用可默认 `demo-ops`，并且只能写 `DEMO` 数据域。
- `Idempotency-Key`：创建、文件导入、回执、重试、取消和复核命令必填。相同 key + 相同请求返回首次结果；相同 key + 不同请求返回 `409 IDEMPOTENCY_CONFLICT`。
- 服务端把每个写用例映射为稳定的 `snake_case` 幂等 scope；scope 不是客户端参数，也不是需要随每个新端点修改 DDL 的封闭枚举。
- `expected_version`：修改已存在业务事实的命令必填；版本不符返回 `409 VERSION_CONFLICT`。
- 公共入口使用部署凭据认证；后端仍把数据域隔离作为第二道边界。未来替换为企业统一身份时，继续由受信认证上下文生成操作人，不接收工具或页面传入的任意身份。

### 3.3 分页与排序

列表默认 `page=0&size=20`，`size` 最大 200。`sort` 格式为 `field,asc|desc`，可重复。响应统一为：

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total_elements": 0,
  "total_pages": 0
}
```

只允许 OpenAPI 中明确列出的排序字段，禁止把任意输入直接拼到 SQL。

### 3.4 错误模型

```json
{
  "business_code": "SKU_MAPPING_REQUIRED",
  "message": "来源商品未建立显式 SKU 映射",
  "http_status": 422,
  "request_id": "req_...",
  "trace_id": "trace_...",
  "field_errors": [
    {"field": "items[0].source_sku_ref", "code": "NOT_MAPPED", "message": "..."}
  ],
  "details": {}
}
```

| HTTP | 含义 |
|---:|---|
| 400 | JSON / multipart / 参数形状错误 |
| 404 | 资源不存在或不在当前数据域 |
| 409 | 幂等、版本、唯一性或当前状态冲突 |
| 422 | 请求形状正确，但业务规则不允许 |
| 500 | 未预期内部错误，不向客户端泄露堆栈 |
| 502 / 504 | 京东/平台等外部依赖失败或超时 |

### 3.5 写操作原子性

每个命令的业务事实、ProcessingStage、OrderEvent、OrderVersion、AuditLog 在同一数据库事务提交。外部调用不持有该事务；需要外部交互的操作先持久化意图/批次，再由 Adapter 执行并回写结果。

## 4. 管理后台 API

### 4.1 工作台与订单

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/dashboard/summary` | 今日 KPI、近7日趋势、待人工介入摘要 |
| GET | `/api/v1/orders` | 按日期、来源渠道、履约方、OrderStatus、ProcessingStage、ProcessingHealth、客户检索业务订单；多履约方订单仍只返回一个 CanonicalOrder |
| GET | `/api/v1/orders/{order_id}` | 订单、Receiver、OrderLine、Fulfillment、Shipment/Tracking、ReviewCase 聚合详情 |
| GET | `/api/v1/orders/{order_id}/timeline` | 按订单内权威 `sequence_no` 排列并返回该序号的 OrderEvent |
| GET | `/api/v1/orders/{order_id}/versions` | 不可变 OrderVersion 列表 |
| GET | `/api/v1/orders/{order_id}/shipments` | 订单页展示履约方、商户/京东出库号、同步状态、失败阶段、运单与更新时间；不返回 Shipment 收件人快照、凭据或供应商原始响应 |
| POST | `/api/v1/orders/{order_id}/corrections` | 已形成履约承诺后创建显式纠正单，不覆盖原单 |
| POST | `/api/v1/orders/{order_id}/fulfillment-routing` | 将无来源批次、已确认且全部为京东普通单品的企业微信订单幂等接入 Shipment pipeline；只创建本地 Shipment，不调用京东 |

订单列表返回行级聚合的 `processing_stage`、`processing_health`、`completed_count/total_count`和 `attention_reason`，不允许客户端自行重算最差进度。`source_channel` 表示订单来源，`provider_id` 表示订单行已分配的履约方，两者不得混用。

### 4.2 文件导入与回填

| Method | Path | 用途 |
|---|---|---|
| POST | `/api/v1/import-batches/source-orders` | `multipart/form-data` 上传来源表；必须显式选 `NEW` 或 `REVISION` |
| POST | `/api/v1/import-batches/{batch_id}/confirm` | 对一个已识别且无阻断问题的来源批次作一次整体确认，并生成履约指令 |
| GET | `/api/v1/import-batches/{batch_id}` | 导入批次结果、渠道指纹、行统计、复核数与自动生成的履约导出 |
| POST | `/api/v1/import-batches/{batch_id}/source-attribution-corrections` | 追加来源渠道归因纠正；不改写原批次、订单、原始行、文件、审计或幂等快照 |
| GET | `/api/v1/import-batches/{batch_id}/rows` | 逐行查看原值、解析结果、订单/行血缘和错误 |
| GET | `/api/v1/import-batches/{batch_id}/source-return-exports` | 阶段性/最终来源回填版本 |
| GET | `/api/v1/source-return-exports/{export_id}/file` | 下载指定来源回填版本并写 AuditLog |

来源文件先留存整文件和 RawImportRow，再按行容错。同文件哈希重放返回既有批次。`REVISION` 必须传 `parent_import_batch_id`；系统不根据相似内容猜测修订。

来源订单客户按规范化姓名与手机号二元组确定性复用或自动创建；缺少任一字段或缺 SKU 的来源行保持 `NEED_REVIEW`。SKU 问题解决后原始行转为 `ACCEPTED` 并创建缺失 Fulfillment，但不逐订单生成文件。操作员只对整个 ImportBatch 执行一次确认；确认时若仍有问题则拒绝，否则生成履约指令。幂等重放和已经进入导出的原始行不得重复生成文件。

一个来源 ImportBatch 最终只对应一份按原来源模板生成的完整回填文件。批次内可以包含多张内部订单和多个 Shipment；只有这些 Shipment 的运单结果均已回收后，才生成并下载该批次的最终来源回填文件，不按客户或订单拆分确认与来源回填。

### 4.3 履约、导出和运单

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/fulfillments` | 履约任务列表 |
| GET | `/api/v1/fulfillments/{fulfillment_id}` | 数量守恒、批次、采购和异常详情 |
| POST | `/api/v1/fulfillments/{fulfillment_id}/continuation-exports` | 部分发货的第三方 Fulfillment 显式创建续发 Shipment 与独立履约文件 |
| GET | `/api/v1/fulfillment-exports` | 按 provider、生成日期、使用状态查导出批次 |
| GET | `/api/v1/fulfillment-exports/{export_id}` | 导出批次、行、Shipment、下载审计和回传关联 |
| GET | `/api/v1/fulfillment-exports/{export_id}/file` | 下载京东/第三方履约文件，记录首次/最近时间、操作人与次数 |
| POST | `/api/v1/fulfillment-exports/{export_id}/tracking-imports` | 上传单一 provider 的运单回传文件；整批校验后单事务接收 |
| GET | `/api/v1/tracking-imports/{batch_id}` | 回传批次、行结果、业务结果与关联导出 |
| GET | `/api/v1/shipments` | Shipment 列表 |
| GET | `/api/v1/shipments/{shipment_id}` | 出库单号、分批序号、收货快照、行、Tracking 与 Shipment 级京东出库集成状态（只暴露诊断字段，不暴露凭据/PII） |
| POST | `/api/v1/shipments/{shipment_id}/jd-so-order` | 将整个 Shipment 批次聚合为一张京东出库单（addSoOrder），Idempotency-Key 幂等重放；`app.jd.write-mode=OFF` 时拒绝，失败阶段与诊断码落 Shipment 级集成记录 |

`FulfillmentExport` 在所有前置复核通过后自动生成，不提供「生成发货表」人工命令。一份文件只属于一个 FulfillmentProvider。

#### 导出文件使用状态

| `usage_status` | 可证明的事实 |
|---|---|
| `GENERATED_NOT_DOWNLOADED` | 已生成，无下载 AuditLog |
| `DOWNLOADED_WAITING_RETURN` | 至少下载过一次，但无已接收的履约方回传 |
| `RETURNED` | 已有校验成功且显式关联本导出的 ProviderTrackingBatch |
| `RETURN_OVERDUE` | 未回传且当前时间超过 `tracking_due_at` |

下载不等于「已被履约方使用」。系统不提供人工「标记已使用」按钮；只有成功回传能确认。

续发命令仅对 `BUSINESS + THIRD_PARTY + PARTIALLY_SHIPPED` 履约开放，必须携带 `expected_version`、正数 `instructed_quantity`、必填 `remark`、`Idempotency-Key` 和网关覆盖的操作人。可分配剩余量为请求量减已发、已取消和已有 `CREATED` 续发指令；命令在锁定 Fulfillment 后做版本 CAS，成功时一次性创建新 Shipment、新出库单号和新 FulfillmentExport，并写事件、版本与审计。

Provider tracking 的 `business_results` 只统计本次回传文件中的 Shipment 行，不拿整个 Fulfillment 请求量误判续发批次。相同导出与相同文件内容重放返回首次完整响应，包括原 `business_results` 和 `generated_source_return_export_ids`，也不重复生成来源回填版本。

#### 京东出库单号与回传定位

- `isv出库单号` 由本系统创建 Shipment/FulfillmentExport 时生成，即 `outbound_order_no`。
- 格式是上海业务日 `yyyyMMdd` + 四位当日原子流水，例如 `202608030052`。禁止 `MAX + 1`。
- 同一 Shipment 全部商品行共用一号；幂等重放/重新下载不换号；后续缺口批次、补发或纠正单生成新号。
- 京东可自定义回传列；正式回传模板优先使用 `isv出库单号 + 京东物流单号`，姓名只作校对。
- 如果实际文件只有「收货人姓名 + 运单号」，仅当该姓名在当前待回传京东导出中唯一时自动匹配；否则进入 `JD_TRACKING_MATCH / NEED_REVIEW`，不猜测。

### 4.4 复核与运营提醒

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/review-cases` | 待人工介入队列 |
| GET | `/api/v1/review-cases/{case_id}` | 主体、稳定原因码、证据、候选和可执行动作 |
| POST | `/api/v1/review-cases/{case_id}/resolve-customer` | 人工关联/创建 Customer |
| POST | `/api/v1/review-cases/{case_id}/resolve-sku` | 人工关联/创建 SKU 并保存来源映射与乘数 |
| POST | `/api/v1/review-cases/{case_id}/complete-source-followup` | 确认多 Shipment 的来源平台后续回传已人工完成；备注可选 |
| POST | `/api/v1/review-cases/{case_id}/resolve-jd-tracking-conflict` | 确认京东运单冲突/终态异常已人工处理并关闭事项 |
| POST | `/api/v1/review-cases/{case_id}/resolve` | 通用人工闭环：仅白名单原因（主数据冲突/导出后改单/数量精度/履约异常/回传失败/库存阻断等无专用表单的事项）在主数据或线下处理完毕后标记已解决；备注可选 |
| POST | `/api/v1/review-cases/{case_id}/dismiss` | 关闭误建或不再需要的事项；消息链路事项（草稿/重新识别）由各自生命周期管理，禁止直接关闭 |
| GET | `/api/v1/operational-alerts` | 黄/红提醒列表 |
| POST | `/api/v1/operational-alerts/{alert_id}/acknowledge` | 记录已知晓，不改变业务状态 |

人工命令都校验自己的原因码白名单、`expected_version` 与证据；通用 `resolve` 只接受无专用表单的白名单原因，`dismiss` 只接受非消息链路事项，两者都走幂等与审计，不能由模糊匹配自动关闭。

### 4.5 采购

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/procurement-tickets` | 采购工单列表 |
| GET | `/api/v1/procurement-tickets/{ticket_id}` | 工单、SKU/礼包组件明细、累计数量和只追加回执 |
| POST | `/api/v1/procurement-tickets/{ticket_id}/retry` | FAILED 后人工创建关联的新工单 |
| POST | `/api/v1/procurement-tickets/{ticket_id}/cancel-remaining` | 人工取消明确剩余未发量，不回滚已发事实 |

采购回执使用同一受信任接入面 `POST /internal/v1/procurement/tickets/{ticket_id}/receipts`；Demo 采购操作台也调用该真实应用用例，不设置内部快进按钮。

### 4.6 客户、商品、SKU 和配置

| 资源 | 端点 |
|---|---|
| Customer | `GET/POST /api/v1/customers`，`GET/PATCH /api/v1/customers/{customer_id}` |
| Category | `GET/POST /api/v1/categories`，`GET/PATCH /api/v1/categories/{category_id}` |
| Product | `GET/POST /api/v1/products`，`GET/PATCH /api/v1/products/{product_id}` |
| SKU | `GET/POST /api/v1/skus`，`GET/PATCH /api/v1/skus/{sku_id}` |
| 来源 SKU 映射 | `GET/POST /api/v1/source-sku-mappings`，`GET/PATCH /api/v1/source-sku-mappings/{mapping_id}` |
| 履约方 SKU 映射 | `GET/POST /api/v1/provider-sku-mappings`，`GET/PATCH /api/v1/provider-sku-mappings/{mapping_id}` |
| FulfillmentProvider | `GET /api/v1/fulfillment-providers`，`GET/PATCH /api/v1/fulfillment-providers/{provider_id}` |
| ConnectorConfig | `GET /api/v1/connectors`，`GET/PATCH /api/v1/connectors/{source_channel}` |
| Connector 连通性 | `POST /api/v1/connectors/{source_channel}/test-connection` |

主数据不提供硬删除端点。已被订单快照引用的 Product/SKU/provider 不能改写历史。来源 SKU 映射的 `quantity_multiplier` 必须为正数；空值只能作为待复核主数据，不能进入自动履约。

Connector 配置分成两条互不替代的轴：`client_mode=MOCK|REAL` 控制在线接口是否调用真实外部 Client，`transport_mode=EXCEL|API` 控制文件接入或在线接口接入。当前三平台使用 `transport_mode=EXCEL`，因此 `client_mode` 不参与业务文件处理并默认 `MOCK`；隔离 Demo 也只用 Mock Adapter。后续拿到平台文档/凭据后，才允许切换为 `REAL + API`。

### 4.7 审计与数据中台

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/audit-logs` | 按 request/trace/operator/service/operation/business_code/日期检索 |
| GET | `/api/v1/audit-logs/{audit_id}` | 请求/响应快照与耗时 |
| GET | `/api/v1/analytics/channels` | 渠道×日期指标 |
| GET | `/api/v1/analytics/products` | 渠道×商品/SKU/品类×日期 |
| GET | `/api/v1/analytics/fulfillments` | 履约状态、京东与采购指标 |

分析默认今日，可传 `date_from/date_to`。三个 Analytics 端点与 ReviewCase 队列均可传单值 `source_channel`；前端多选按渠道并发后去重合并，使订单/商品、积压/漏斗与人工介入保持同一渠道口径。「实际发货数量」按来源包装乘数换算后的 Canonical SKU 实发件数，礼包展开组件；不统计来源包装数、礼包份数或重量。商品分析行同时返回当前渠道的活动来源 SKU 映射/包装乘数与京东 SKU 编码，供热力图下钻展示。

## 5. 受信任内部 API

### 5.1 创建订单

`POST /internal/v1/orders`

- 只创建 `BUSINESS` CanonicalOrder，不为 Demo 复用。
- 当前受信任内部入口的 `source` 固定为 `WECOM`；三平台只能走各自 Connector/文件入口。
- 接受 SINGLE 和 CUSTOM_BUNDLE；礼包必须携带当单明确组件清单。
- 仍执行 Schema → Business → Customer/SKU → Duplicate/Version Validation。
- 缺客户、SKU、乘数或其他映射时可创建订单/行与 ReviewCase，但不得进入履约。

### 5.2 显式修订

`POST /internal/v1/orders/{order_id}/revisions`

- 必须携带 `source_version`、`expected_version`和完整修订输入；不提供自动 upsert。
- 未形成履约承诺时，在同一事务保留 OrderVersion 并应用修订。
- 已进入任何 FulfillmentExport 的履约字段不得覆盖；创建 `REVISION_AFTER_EXPORT` ReviewCase，后续走取消、补发或纠正单。

### 5.3 采购回执

`POST /internal/v1/procurement/tickets/{ticket_id}/receipts`

- 头部 `result = SUCCESS | PARTIAL | FAILED`；明细按 ProcurementTicketItem 传本次 `available_quantity`。
- 回执只追加；累计量从明细派生，禁止直接修改 `fulfilled_quantity`。
- PARTIAL 取得多少就继续履约多少；FAILED 不自动重试，不回滚已发数量。

## 6. 外部 Adapter 契约

这些接口是 Java 应用边界，不是额外的 HTTP 端点。Adapter 只能调用应用层用例并写 AuditLog，禁止直接写业务表；领域层也不得依赖京东 SDK DTO 或三平台原始字段。

### 6.1 京东仓配

```java
public interface JDWarehouseService {
    JdResult<List<Warehouse>> queryWarehouses(WarehouseQuery query);
    JdResult<List<JdProduct>> queryProducts(ProductQuery query);
    JdResult<Page<JdStock>> queryStock(StockQuery query);
    JdResult<OutboundOrderRef> createOutboundOrder(CreateOutboundOrder command);
    JdResult<OutboundOrder> queryOutboundOrder(OutboundOrderQuery query);
    JdResult<CancelResult> cancelOutboundOrder(CancelOutboundOrder command);
    JdResult<List<TrackingTrace>> queryTracking(TrackingQuery query);
}
```

| 应用方法 | 京东 LOP API | SDK 领域请求 |
|---|---|---|
| `queryWarehouses` | `/integratedsupplychain/basicinfo/warehouse/query/v1` | `WarehouseQueryRequest` |
| `queryProducts` | `/integratedsupplychain/basicinfo/goods/query/v1` | `GoodsInfoQueryRequest` |
| `queryStock` | `/integratedsupplychain/stock/query/v1` | `StockQueryRequest` |
| `createOutboundOrder` | `/integratedsupplychain/order/delivery/create/v1` | `SoCreateOrderRequest` |
| `queryOutboundOrder` | `/integratedsupplychain/order/delivery/query/v1` | `SoQueryRequest` |
| `cancelOutboundOrder` | `/integratedsupplychain/order/cancel/v1` | `OrderCancelRequest` |
| `queryTracking` | `/integratedsupplychain/order/trace/query/v2` | `CommonOrderTraceRequest` |

`JdResult` 统一携带 `success`、稳定 `business_code`、`message`、京东 `request_id` 和 `data`。真实实现必须同时判断 LOP 外层与领域信封；`queryTracking` 的 SDK 响应读取 `getResult()`，其余六项读取 `getResponse()`。`pin`、`ownerNo` 等固定租户字段由 Connector 配置注入；业务用例不接触密钥。SDK 的未确认枚举值继续以 `docs/research/jd-isc-api.md` 标注为准，不在本票猜测。

管理端暴露四个只读作业接缝：`GET /api/v1/jd-warehouse/owners`、`GET /api/v1/jd-warehouse/warehouses`、`GET /api/v1/jd-warehouse/outbound-orders/{erp_delivery_no}`、`GET /api/v1/jd-warehouse/tracking`。`owners` 允许在只有 PIN 时发现已授权事业部；负责人、电话、邮箱和地址与出库单收发件信息一样在 HTTP 边界移除。Shipment 页面另有唯一受控建单入口 `POST /api/v1/shipments/{shipment_id}/jd-so-order`；它不调用通用 `jd-write/order/so-create`，且会重新执行授权、幂等、SKU/数量/库存门禁。取消出库仍不得由页面直接调用。真实客户端配置统一从 `JD_LOP_*` 环境变量映射到 `app.jd.*`，密钥不得进入数据库或 API 响应。

### 6.2 三平台 Connector

```java
public interface PlatformConnector {
    SourceChannel channel();
    ConnectorCapabilities capabilities();
    PullResult pullOrders(PullCursor cursor);
    PullResult pullOrderChanges(PullCursor cursor);
    PullResult pullCancellations(PullCursor cursor);
    CanonicalOrderDraft transform(SourceOrderEnvelope sourceOrder);
    SourceSyncResult pushShipmentResult(SourceShipmentResult result);
}
```

- 彩食鲜、聚福宝、飞象各自实现一个 Connector；`channel()` 必须固定，禁止运行时混用渠道。
- 三平台真实接口契约（登录/认证/订单获取/发货回传）已通过抓包确认，见 `docs/research/platform-apis-overview.md` 及三份平台契约文档；在线 API 接入评估见 `docs/research/platform-api-integration-plan.md`。
- 当前 `EXCEL` 模式只启用文件指纹识别、`transform` 与来源回填文件生成；三种 `pull*` 和在线 `pushShipmentResult` 返回稳定的 `CONNECTOR_CAPABILITY_UNAVAILABLE`，不得假装成功。
- 后续切换 `API` 模式时才启用鉴权、游标拉单/变更/取消、限流、重试和平台错误码转换；仍复用相同 CanonicalOrder、ShipmentSync 和幂等用例。
- `pushShipmentResult` 输入只使用内部标准字段：来源引用、来源行引用、实际发货数量、履约结果、来源渠道承运商输出值、首批运单号与异常原因；不得把平台表格列名泄露到领域层。
- WECOM 使用独立 `InternalShipmentCallback` 接口；Demo 的 mock 可以记录“模拟回传成功”，但 BUSINESS 模式在真实回调未接入前必须返回 `CONNECTOR_CAPABILITY_UNAVAILABLE`，不能伪造已同步。

### 6.3 京东 ISC 写接口收口

京东 ISC SDK 的全部写类接口（建档、创建、修改、关闭、设置、绑定类，共 20 个）通过 `JdWriteOpsService` seam 接入，统一由 `/api/v1/jd-write` 控制器暴露为 HTTP 端点，但默认锁死：

- **默认拒绝**：`app.jd.write-mode` 未配置或为 `OFF` 时，所有写端点返回 HTTP 403 + 业务码 `WRITE_MODE_DISABLED` + 消息「写模式未启用」；请求不触达 seam、不产生任何外部调用，Mock 环境同样拒绝。被拦截的写尝试同样落审计（操作人、请求摘要、结果）。
- **显式放行**：Shipment 建单只消费 `app.jd.write-mode: ON`；通用 `/api/v1/jd-write` 的其他写端点还要求独立 `app.jd.generic-http-write-mode: ON`，Compose 不暴露该开关并始终保持 OFF，且只允许 Mock 契约验证。`client_mode=REAL` 时这组遗留 HTTP 入口永久返回 403；真实写操作必须逐项建设授权、幂等、恢复策略完备的业务纵切。通用 `order/so-create` 无论总闸如何都永久拒绝，只能走 Shipment 业务入口。
- **审计与脱敏**：放行与被拦截的写尝试都记 AuditLog（service `jd.isc`）；HTTP 边界剔除负责人、电话、邮箱、地址。`pin`/`ownerNo` 等固定租户字段由 Connector 配置注入，业务用例不接触密钥。
- **调用方**：前端仅提供 Shipment 受控建单入口；通用写控制器没有页面入口，不注册为 Agent 工具。

| HTTP 端点（POST，前缀 `/api/v1/jd-write`） | Seam 方法 | 京东 LOP API | SDK 接口方法 | 用途 |
|---|---|---|---|---|
| `/basicinfo/customer-create` | `customerCreate` | `/integratedsupplychain/basicinfo/customer/create/v1` | `addOrUpdateCustomerInfo` | 客户新增/更新 |
| `/basicinfo/goods-create` | `goodsCreate` | `/integratedsupplychain/basicinfo/goods/create/v1` | `saveGoodsInfo` | 商品新增 |
| `/basicinfo/goods-update-by-seller-goods-sign` | `goodsUpdateBySellerGoodsSign` | `/integratedsupplychain/basicinfo/goods/updateBySellerGoodsSign/v1` | `updateGoodsInfoBySellerGoodsSign` | 按商家商品标识更新商品 |
| `/basicinfo/supplier-create` | `supplierCreate` | `/integratedsupplychain/basicinfo/supplier/create/v1` | `upsert` | 供应商新增/更新 |
| `/basicinfo/shop-create` | `shopCreate` | `/integratedsupplychain/basicinfo/shop/create/v1` | `saveShopInfo` | 店铺新增 |
| `/basicinfo/shop-goods-create` | `shopGoodsCreate` | `/integratedsupplychain/basicinfo/shopGoods/create/v1` | `saveShopGoodsInfo` | 店铺商品新增 |
| `/basicinfo/serialnumber-create` | `serialnumberCreate` | `/integratedsupplychain/basicinfo/serialnumber/create/v1` | `transportGoodsSerialNumberRule` | 串码规则新增 |
| `/basicinfo/processed-create` | `processedCreate` | `/integratedsupplychain/basicinfo/processed/create/v1` | `addGoodsFormula` | 加工配方新增 |
| `/basicinfo/logicalinventoryfactor-create` | `logicalinventoryfactorCreate` | `/integratedsupplychain/basicinfo/logicalinventoryfactor/create/v1` | `insertLogicalStockConfig` | 逻辑库存配置新增 |
| `/basicinfo/boxandserialnumber-transport` | `boxandserialnumberTransport` | `/integratedsupplychain/basicinfo/boxandserialnumber/transport/v1` | `transportBoxAndSerialInfo` | 箱码与串码流转 |
| `/order/adjustment-create` | `orderAdjustmentCreate` | `/integratedsupplychain/order/adjustment/create/v1` | `transportInsideOrder` | 调整单新增 |
| `/order/destroy-create` | `orderDestroyCreate` | `/integratedsupplychain/order/destroy/create/v1` | `addUlOrder` | 销毁单新增 |
| `/order/operate-command-modify` | `orderOperateCommandModify` | `/integratedsupplychain/order/operateCommand/modify/v1` | `updateDeliveryCommand` | 配送指令修改 |
| `/order/processed-create` | `orderProcessedCreate` | `/integratedsupplychain/order/processed/create/v1` | `addProcessOrder` | 加工单新增 |
| `/order/purchase-create` | `orderPurchaseCreate` | `/integratedsupplychain/order/purchase/create/v2` | `addPoOrder` | 采购单新增 |
| `/order/purchase-close` | `orderPurchaseClose` | `/integratedsupplychain/order/purchase/close/v1` | `closePoOrder` | 采购单关闭 |
| `/order/returntosupplier-create` | `orderReturntosupplierCreate` | `/integratedsupplychain/order/returntosupplier/create/v1` | `addRtsOrder` | 退货供应商单新增 |
| `/order/returntowarehouse-create` | `orderReturntowarehouseCreate` | `/integratedsupplychain/order/returntowarehouse/create/v1` | `addRtwOrder` | 退货入库单新增 |
| `/order/so-create` | `orderSoCreate` | `/integratedsupplychain/order/delivery/create/v1` | `addSoOrder` | 出库单新增 |
| `/stock/shopstockfixed-set` | `stockShopstockfixedSet` | `/integratedsupplychain/stock/shopstockfixed/set/v1` | `setShopStockFixed` | 店铺库存固定值设置 |

上表 LOP API 路径由对应 `Integratedsupplychain<域><动作>V<版本>LopRequest` 请求类名推导，与 §6.1 同源；登记开通时以京东开放平台后台展示为准。

#### 启用条件与权限核对

受控 Shipment 建单启用条件：`app.jd.write-mode: ON`、操作人通过服务端身份复验并进入授权名单，且京东开放平台已开通对应接口权限，缺一不可。旧通用 HTTP 写面不作为真实环境验收入口；真实环境权限核对按以下步骤进行（外部 gate 约束见 `.scratch/jd-sdk-bridge/spec.md`，不把 Mock 冒充真实权限）：

1. 保持 `write-mode` 默认 `OFF`，先用只读探针（`JD_LOP_*` 凭据）确认当前 appKey/PIN 组合的授权基线：未开通的接口返回 `2001 / 没有事业部操作权限`。
2. 对照上表逐行在京东开放平台后台核对开通情况，开通一个、登记一个，形成接口权限清单；未登记开通的接口一律不得临时放行。
3. 对已登记开通的接口，可临时置 `write-mode: ON` 做最小验证（Mock 先行、REAL 后行），验证完成后立即恢复 `OFF`；生产环境默认保持 `OFF`。

## 7. Demo API

| Method | Path | 用途 |
|---|---|---|
| GET | `/demo/v1/scenarios` | 可演示的固定场景 |
| POST | `/demo/v1/scenarios` | 按 `scenario_code` 创建 DemoRun，使用 Mock Adapter 同步跑完演示 Timeline |
| POST | `/demo/v1/extracted-orders` | 仅接收 `confirmed=true` 且字段完整的人工确认草稿，创建隔离的 AI DemoRun |
| GET | `/demo/v1/runs/{run_id}` | 查询该次演示运行和关联 Demo 订单摘要 |

订单助手只在显式确认命令中添加 `confirmed=true`；模型输出本身不能设置这一事实。DemoRun 查询返回已落库的客户、收货人与多行快照，Timeline 以 `SOURCE_SYNCED` 结束且订单状态为 `SYNCED`。Demo 不生成可交付 Excel，不调真实履约方，不进入业务队列/分析/复核/默认审计/Metabase，不能用作 P0 文件闭环验收证据。

## 8. MCP / Agent 权限边界

MCP Adapter 与 REST/UI 共用应用层 Interface，预留：

- 查询：客户、订单、订单时间线、Shipment/Tracking、ReviewCase、SKU 候选；
- 输入：创建内部订单、提交客户/SKU 匹配建议、提交业务材料；
- 禁止：确认客户/SKU/快递映射、取消剩余量、重试采购、关闭 ReviewCase、执行「已完成后续回传」。

Agent 可以提建议，但上述终局动作必须由管理后台人员确认。任何 Agent 写入都需幂等键、operator/agent 身份和 AuditLog。

### 8.1 预留工具契约

| Tool | 最小输入 | 输出 | 是否写业务事实 |
|---|---|---|---|
| `search_customers` | `query`, `page`, `size` | Customer 摘要页 | 否 |
| `get_customer` | `customer_id` | Customer 与来源身份 | 否 |
| `list_customer_review_cases` | `customer_id?`, `status`, `page`, `size` | Customer 相关 ReviewCase 页 | 否 |
| `search_orders` | 日期、渠道、状态、客户检索条件 | OrderSummary 页 | 否 |
| `get_order` | `order_id` | OrderDetail | 否 |
| `get_order_timeline` | `order_id` | OrderEvent 列表 | 否 |
| `get_shipment` | `shipment_id` | Shipment/Tracking 详情 | 否 |
| `search_skus` | `query`, `provider_id?`, `page`, `size` | SKU 候选页 | 否 |
| `create_internal_order` | `idempotency_key` + OpenAPI `InternalOrderInput` | OrderDetail；可能同时产生 ReviewCase | 是，创建订单 |
| `suggest_customer_match` | `idempotency_key`, `review_case_id`, `expected_version`, `customer_id?`, `create_customer_suggestion?`, `reason` | 追加建议后的 ReviewCase | 只追加建议，不确认映射 |
| `suggest_sku_match` | `idempotency_key`, `review_case_id`, `expected_version`, `sku_id?`, `quantity_multiplier?`, `reason` | 追加建议后的 ReviewCase | 只追加建议，不确认映射 |
| `submit_business_material` | `idempotency_key`, `review_case_id`, `expected_version`, `evidence_refs`, `note` | 追加材料后的 ReviewCase | 只追加证据，不关闭复核 |

MCP 标识符和数量沿用 OpenAPI 的字符串规则。`operator`/Agent 身份来自 MCP 认证上下文，不能由工具参数冒充；写工具使用与 REST 相同的幂等注册表、乐观版本和审计切面。服务端不得注册 `resolve_*`、`cancel_remaining`、`retry_procurement` 或 `complete_source_followup` 一类 Agent 工具；未来若改变该权限边界，必须新开业务决策票。

## 9. 自动生成与副作用

以下是命令成功后的领域副作用，客户端不得分步拼装：

1. 来源导入/内部订单通过客户和 SKU 校验后，自动创建 Fulfillment。
2. 来源批次确认后，`READY_TO_EXPORT` 行按 provider/收货地址/批次自动创建 Shipment(CREATED) 和 FulfillmentExport；无来源批次的已确认企业微信订单由受权操作员调用 `fulfillment-routing` 接回同一 Shipment pipeline。两条路径都必须冻结履约字段并追加事件、版本和审计。
3. 我方库存不足时，自动生成可发批次、采购工单和黄色提醒；第三方库存不由本系统判断。
4. 某 provider 的合法运单回传批次整批提交后，自动生成一版 SourceReturnExport。
5. 多 Shipment 只自动回填来源行首批；后续完成由人工命令关闭复核，不伪造首批-only final 文件。
   首批部分发货时行保持 `WAITING_PROVIDER`/采购进行中；累计实发达请求量且所有实际 Shipment 都有 Tracking 后才转 `NEED_REVIEW`。只要该来源行历史上出现过 `MULTI_SHIPMENT_SOURCE_FOLLOWUP`，所有来源回填版本都必须 `is_final=false`，并始终使用与该 Fulfillment 关联的最小 `shipment_sequence` 运单。

## 10. OpenAPI 覆盖与验收

`docs/openapi.yaml` 必须满足：

1. 能被 YAML 解析，`openapi: 3.0.3`；
2. 每个 operation 有唯一 `operationId`、成功响应和统一错误响应；
3. 写操作声明 `Idempotency-Key` / `X-Operator`，修改命令包含 `expected_version`；
4. 文件上传使用 `multipart/form-data`，下载返回二进制流；
5. 所有 `BUSINESS` 列表不暴露 Demo；
6. 覆盖前端导航、P0 Excel 闭环、人工复核、采购、审计、分析和 DemoScenario；
7. 不存在通用「推进订单」、「改状态」或「任意关闭 ReviewCase」端点。
