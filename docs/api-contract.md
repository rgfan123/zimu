# 订单履约中台 API 契约

状态：Need Review  
日期：2026-08-11  
机器可读契约：`docs/openapi.yaml`  
权威边界：`docs/prd-v0.1.md`、`docs/state-machine.md`、`docs/schema.md`、`docs/excel-closed-loop-spec.md`及已关闭 Wayfinder 决策票。

## 0. 两份契约的关系（工单 07）

- **生成物是事实**：后端接入 springdoc-openapi（2.9.0，按 Spring Boot 3.5.16 构建），运行中的应用在
  `/v3/api-docs` / `/v3/api-docs.yaml` 暴露由控制器与 DTO 实时生成的 OpenAPI 契约。CI
  （`.github/workflows/ci-jry.yml`）里的 `OpenApiContractConsistencyTest` 把生成物与手写 yaml
  做结构化比对，漂移即失败并打印差异；每次测试也会把生成物快照导出到
  `backend/target/generated-openapi.yaml` 供人工检查。
- **手写 `docs/openapi.yaml` 是评审用契约草案**：承载业务语义、评审意图与说明性描述（即本文件
  §1–§9 的约定与逐端点说明，以及 yaml 里的描述/示例/错误响应）。它的机器可读结构——路径、方法、
  query/header 参数、2xx 响应码、请求体与成功响应的 schema——不再靠人眼与控制器保持一致：
  这些结构以生成物为事实，由门禁保证两份契约不漂移；散文层由人工维护，不在比对范围。
- **门禁粒度与首次处置**：比对路径模板集合（`{param}` 归一）、方法集合、参数名、2xx 码、
  schema 引用名（归一化 + 别名注册表），排除描述/示例/排序/路径参数命名/认证头等噪音；
  首次比对暴露的差异逐条修正或登记豁免，清单见
  `.scratch/repo-design-hardening/issues/07-spec-generation-gate.md` 的 Resolution。

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
| `/demo/v1` | 已认证模拟下单页；订单助手仅调用 `/extracted-orders` | 只读写 `DEMO` | 创建并查看 DemoScenario / DemoRun；浏览器使用业务操作人身份，订单助手使用独立内部服务身份 |

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
- `X-Operator`：公共浏览器客户端不得自行提供，受信 Nginx 用服务端主体覆盖该请求头。后端对全部 `/api/` 与浏览器 `/demo/` 请求复验同一 Basic Auth 凭据，并要求已验证主体与 `X-Operator` 一致；仅伪造该请求头不能授权。全部 `/internal/` 请求使用独立 Bearer 服务身份；订单助手调用 `/demo/v1/extracted-orders` 时也只使用这套内部服务身份。
- 真实逐人归因的 +1 决定必须启用网关逐人模式：Nginx 从 bcrypt htpasswd/SSO 得到
  `$remote_user`，覆盖 `X-Operator` / `X-Authenticated-Operator`，并携带独立
  `X-Gateway-Assertion`；后端恒定时复验 assertion token。用户名必须与 active
  `InternalOperator.wecom_userid` 一致。共享 `APP_ADMIN_USER` 只是兼容管理入口，不能通过 +1 身份校验。
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
| POST | `/api/v1/source-return-exports/{export_id}/push` | 人工触发把回填文件推回来源平台（彩食鲜/聚福宝）；真实外呼不可重放，幂等由 `push_status` 状态机承担 |

来源回填推送是人工触发的一次性外呼：`Idempotency-Key` 只做格式校验挡重复点击，真正的幂等闸门是
`push_status` 状态机——`NOT_PUSHED`/`FAILED` 与超时的 `PUSHING` 可抢占，新鲜 `PUSHING` 与 `SUCCESS` 一律 409。
脚本在事务外执行，不持有数据库连接。脚本结果不可解析或超时按「结果未知」记为 `FAILED`，
并明确提示先到平台核实再决定是否重推，绝不报成功。推送成功或进行中的批次不允许再做来源归因订正
（`SOURCE_ATTRIBUTION_RETURN_PUSH_UNSAFE`）。

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
| GET | `/api/v1/shipments/{shipment_id}/source-sync/check` | 聚福宝/彩食鲜来源回传前的完整即时事实核对；仅认证人工可读，响应 `Cache-Control: no-store`，不产生平台写 |
| POST | `/api/v1/shipments/{shipment_id}/source-sync/execute` | 以刚查看的 `check_hash` 确认并执行一次 Shipment 级回传；每次外部写前复验租约，只有平台终态已验证才成功 |
| POST | `/api/v1/shipments/source-sync/batch-execute` | 最多 100 张 Shipment 的逐单回传；每项独立携带 `shipment_id`、`expected_check_hash` 与 `idempotency_key`，逐项返回成功/失败且互不回滚 |
| POST | `/api/v1/shipments/{shipment_id}/source-sync/reconcile` | 对外部效果未知的执行作三态人工对账；命令回显原 intent 全部稳定字段和 `lock_version`，不自动重提 |

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
| POST | `/api/v1/procurement-price-agent/compare` | 运行一次采购比价（只读 Agent）：入参 `{procurement_ticket_id?, sku_id?, quantity?}`；返回可比候选 `candidates` 与被剔除候选 `excluded_candidates`（含 `exclusion_reason` 理由标签与 `exclusion_reason_detail` 可读说明），推荐只在可比候选中产生，可比候选为空或信息不全时 `requires_human=true`；未配置模型等失败以结果内稳定 `error` 码返回（fail-closed），输入非法抛 `INVALID_PARAMETERS` |

采购回执使用同一受信任接入面 `POST /internal/v1/procurement/tickets/{ticket_id}/receipts`；Demo 采购操作台也调用该真实应用用例，不设置内部快进按钮。

### 4.6 客户、商品、SKU 和配置

| 资源 | 端点 |
|---|---|
| Customer | `GET/POST /api/v1/customers`，`GET/PATCH /api/v1/customers/{customer_id}` |
| Category | `GET/POST /api/v1/categories`，`GET/PATCH /api/v1/categories/{category_id}` |
| Product | `GET/POST /api/v1/products`，`GET/PATCH /api/v1/products/{product_id}` |
| 商品成本档案 | `GET /api/v1/product-archive-sheets`（含未挂接行，支持 query/page/size），`GET /api/v1/products/{product_id}/archive-sheet`（已挂接到指定商品的行） |
| SKU | `GET/POST /api/v1/skus`，`GET/PATCH /api/v1/skus/{sku_id}`；`GET /api/v1/skus/export` 导出全部 active SKU（固定 8 列 + 成本档案 A..AU 47 列，SKU 级挂接优先、product 级兜底、未挂接档案列为空，文件名业务日为 Asia/Shanghai） |
| 来源 SKU 映射 | `GET/POST /api/v1/source-sku-mappings`，`GET/PATCH /api/v1/source-sku-mappings/{mapping_id}` |
| 履约方 SKU 映射 | `GET/POST /api/v1/provider-sku-mappings`，`GET/PATCH /api/v1/provider-sku-mappings/{mapping_id}`；读模型以 `provider_sku_code_scope` 区分 `PROVIDER_EXTERNAL` 与仅供子牧内部路由的 `INTERNAL_ROUTING`，后者不得解释为已核验外部编码 |
| FulfillmentProvider | `GET /api/v1/fulfillment-providers`，`GET/PATCH /api/v1/fulfillment-providers/{provider_id}` |
| 内部运营人员（Issue #89） | `GET/POST /api/v1/operators`，`GET/PATCH /api/v1/operators/{operator_id}`；只读诊断 `GET /api/v1/operator-team-resolutions?responsible_team=...`（返回 active 人员、可推送 userid 与未绑定人员名单，不静默过滤）；`&require_pushable=true` 时不可全员推送直接 422 `OPERATOR_TEAM_NOT_PUSHABLE` |
| 企微主动通知投递记录（Issue #90） | `GET /api/v1/admin/wecom-notifications/deliveries`；按 `source_type/source_id/status` 过滤，逐源事实 × 收件人返回 SENT/BLOCKED/UNKNOWN/FAILED、尝试次数、req_id、稳定原因及 durable alert id/key/severity；要求 `X-Operator`，不暴露源 payload 或客户 PII |
| ConnectorConfig | `GET /api/v1/connectors`，`GET/PATCH /api/v1/connectors/{source_channel}` |
| Connector 连通性 | `POST /api/v1/connectors/{source_channel}/test-connection` |
| 业务模块开放清单（票 03） | `GET /api/v1/business-modules`；只读部署事实，返回 `{ "modules": [...] }` 即当前**已开放**的业务模块标识（现有标识：`customer-center` = 客户中心 kehuzx，判据取其只读网关是否就绪，与抛 `KEHUZX_NOT_CONFIGURED` 的是同一个开关）。前端外壳启动时读取并据此过滤导航树，使入口可见性与接通开关联动；清单只列已开放的模块，不暴露未开放模块及其未接通原因。**与 MCP 的 `MCP_MODULES`（§8 工具暴露面）是两件不同的事，不互相推导** |
| MCP 开放面核对（票 05） | `GET /api/v1/mcp-exposure`；只读部署事实，返回 `{ "open_modules": [{ "module", "tools": [{ "name", "description", "read_only" }] }], "unopened_modules": [...] }`。**已开放** = 该模块的工具真的进了注册表（`McpToolRegistry` 是唯一真源，不重新解析 `MCP_MODULES`）；**已知但未开放** = 有工具声明该模块但当前未列出，只给模块名不给工具明细（未注册的工具凭空列出就得另建一份必然漂移的清单）。两个清单都可能为空且空是合法状态（未配置 → `open_modules` 空，全开 → `unopened_modules` 空）。纯只读，不提供修改开放面的能力——开放面在部署期由 `MCP_MODULES` 决定、启动期一次性生效（§8 / ADR 0015）。前端 `/system/mcp-exposure` 消费 |

SKU 列表与候选搜索的 `query` 同时匹配商品名称、active SKU 别名、规格和内部 SKU 编码；规范名称变更后，历史 NAME 别名仍可定位 canonical SKU，但不会恢复或改写已停用的重复 SKU。

主数据不提供硬删除端点。已被订单快照引用的 Product/SKU/provider 不能改写历史。来源 SKU 映射的 `quantity_multiplier` 必须为正数；空值只能作为待复核主数据，不能进入自动履约。

商品价格的唯一系统真源是 `app.skus.purchase_price / retail_price`，分别由成本核算表 AI「线下供货成本/份」与 AJ「售价」供数并允许人工覆盖；`app.product_archive_sheets` 只保留不可变导入快照。Product 的写入与读取投影不再包含 `purchase_price / retail_price / other_cost / margin`，因此 MCP `list_products` 同步少这四项。SKU 投影保留两价并返回 `margin = retail_price - purchase_price`，缺任一价格时 `margin=null`；SKU PATCH 可更新 `unit`，`expected_version` 乐观锁语义不变。

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

### 4.8 Agent 运行消耗汇总

| Method | Path | 用途 |
|---|---|---|
| GET | `/api/v1/agent-runs/token-usage` | 按当前运行记录筛选范围汇总 token、模型调用次数、运行数与未计量运行数 |

汇总端点的可选筛选参数为 `slug`、`outcome`、`run_mode`、`business_entity_type`、
`business_entity_id`、`started_from`、`started_to`；其中 `outcome` 与
`business_entity_id` 的名称和语义与 `GET /api/v1/agent-runs` 逐字一致。两者缺省时不追加对应
WHERE 条件，行为与扩展前一致。`outcome` 的派生口径仍由运行行的 `status + error_type` 决定：
`SUCCESS/NEEDS_INPUT` 对应成功行，`REJECTED` 对应 `PII_GUARDED` 失败行，其余失败行为
`FAILED`。`runs_without_token_usage > 0` 表示 token 求和只是已计量部分的下界，调用方必须显式标注。

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

出库信息内外事实并排（Ticket 01）暴露 `GET /api/v1/outbound-recon`：输入系统出库单号 / 京东单号 / 订单号，收敛到同一笔出库后把内部事实与 `querySoOrder` 返回按语义对齐，逐字段给差异状态；京东侧失败/超时与无记录分别用 `jd.status=UNAVAILABLE` / `NOT_FOUND` 表达，内部事实照常返回。每次查询写审计（`outbound.recon.query`），京东收件人 PII 只在响应中保留脱敏姓名。

### 6.2 三平台 Connector

```java
public interface PlatformConnector {
    SourceChannel channel();
    ConnectorCapabilities capabilities();
    PullResult pullOrders(PullCursor cursor);
    PullResult pullOrderChanges(PullCursor cursor);
    PullResult pullCancellations(PullCursor cursor);
    CanonicalOrderDraft transform(SourceOrderEnvelope sourceOrder);
    SourcePlatformCheckResult checkShipmentResult(SourceShipmentResult result);
    SourceSyncResult pushShipmentResult(SourceShipmentResult result);
    SourceSyncResult pushShipmentResult(SourceShipmentResult result, ExternalWritePermit permit);
}
```

- 彩食鲜、聚福宝、飞象各自实现一个 Connector；`channel()` 必须固定，禁止运行时混用渠道。
- 三平台真实接口契约（登录/认证/订单获取/发货回传）已通过抓包确认，见 `docs/research/platform-apis-overview.md` 及三份平台契约文档；在线 API 接入评估见 `docs/research/platform-api-integration-plan.md`。
- `checkShipmentResult` 只读查询来源平台的最新状态、收货信息、可发来源份数和承运商字典；不能接单、上传或发货。
- 生产在线写只能从 Shipment source-sync `execute` 进入；无 `ExternalWritePermit` 的旧调用在聚福宝和彩食鲜均失败关闭。
- `pushShipmentResult` 输入只使用内部标准字段：Shipment/来源血缘、Canonical 实发件数、来源份数、履约结果、承运商输出值、正式运单与 receiver 快照；彩食鲜工作簿封装在 artifact seam 内，不把表格列名泄露到领域层。
- 聚福宝的接单与发货是两次不可逆写，每次都要分别复验外层执行租约和平台内层 owner/lease；彩食鲜在登录和 multipart 构造完成后、真正 `http.send` 前复验一次。
- 平台 HTTP 受理不是成功。聚福宝必须写后确认目标离开 `NO_DELIVERY`；彩食鲜必须在详情中同时确认状态 `4`、承运商代码和运单号。
- WECOM 使用独立 `InternalShipmentCallback` 接口；Demo 的 mock 可以记录“模拟回传成功”，但 BUSINESS 模式在真实回调未接入前必须返回 `CONNECTOR_CAPABILITY_UNAVAILABLE`，不能伪造已同步。

#### Shipment source-sync 状态与对账

1. `check` 从已确认 BUSINESS 导入批次、单一完整 Shipment、唯一来源行和正式运单派生 `check_hash`；receiver 或来源份数任何不一致都会阻断。
2. `execute` 在数据库中固化 outer/platform intent key、check/artifact hash、来源行、承运商、运单和版本，然后才允许平台写。相同幂等键改变请求返回冲突。
3. 平台明确拒绝或确认无远端效果进入 `SYNC_FAILED`；可能已有远端效果但响应/终态/本地归档不明进入 `RECONCILIATION_REQUIRED`。
4. `reconcile` 必须回显原 intent 的 check hash、来源行、承运商、运单和版本，并再次核对当前事实。`ACCEPTED` 不再写平台并转 `SYNCED`；`NOT_ACCEPTED` 释放原平台 intent 后回到 `PENDING`；`UNCERTAIN` 保持隔离。
5. 在线 begin 与来源回填文件进入 `PUSHING` 竞争同一 Shipment 行锁。任一侧已占用或在线已成功/待对账时，另一侧失败关闭；失效的文件 artifact 也不能重新 claim。
6. `execute` 审计记录 allowlist 校验后的平台请求引用与端到端外部调用耗时；请求/响应摘要只含哈希、状态和“字段存在”标记，不复制 receiver、凭据或平台原始报文。
7. `batch-execute` 只编排既有单 Shipment `execute`：每项使用自己的检查哈希和幂等键，任一项被阻断、冲突或异常都只形成该项结果，已成功项不得被整批事务回滚；`RECONCILIATION_REQUIRED` 仍禁止普通重试。

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

> 工单 07 注：上表涉及的 54 条 `/api/v1/jd-write` 等 ISC 透传端点由 springdoc 生成契约（`/v3/api-docs`）
> 覆盖；手写 `docs/openapi.yaml` 未逐条补录（登记为已知豁免，门禁仍盯住这些路径），原因与清单见
> `.scratch/repo-design-hardening/issues/07-spec-generation-gate.md` 的 Resolution。

#### 启用条件与权限核对

受控 Shipment 建单启用条件：`app.jd.write-mode: ON`、操作人通过服务端身份复验并进入授权名单，且京东开放平台已开通对应接口权限，缺一不可。旧通用 HTTP 写面不作为真实环境验收入口；真实环境权限核对按以下步骤进行（外部 gate 约束见 `.scratch/jd-sdk-bridge/spec.md`，不把 Mock 冒充真实权限）：

1. 保持 `write-mode` 默认 `OFF`，先用只读探针（`JD_LOP_*` 凭据）确认当前 appKey/PIN 组合的授权基线：未开通的接口返回 `2001 / 没有事业部操作权限`。
2. 对照上表逐行在京东开放平台后台核对开通情况，开通一个、登记一个，形成接口权限清单；未登记开通的接口一律不得临时放行。
3. 对已登记开通的接口，可临时置 `write-mode: ON` 做最小验证（Mock 先行、REAL 后行），验证完成后立即恢复 `OFF`；生产环境默认保持 `OFF`。

## 7. Demo API

所有 Demo 浏览器入口均要求后端复验通过的业务操作人身份。`POST /demo/v1/extracted-orders`
额外接受配置的内部服务名与 Bearer token，供订单助手直连；调用方自报 `X-Operator` 不构成授权。

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

企微订单草稿卡片属于另一条人工入口，不是 Agent 自动确认：新草稿通过持久化卡片 outbox 异步发送到原会话，`task_id=order-draft_{draft_id}_v{draft_revision}_{128-bit授权引用}`。`template_card_event` 回调只接受该协议安全、持久化且不可猜的 task id 与 `confirm_order`/`supplement_order` 键；实体和版本只从 SENT 投递记录取得，不信任回调字符串自述。actor 只能取回调 `from.userid`，缺失时 fail closed，不能从卡片内容或请求参数冒充。确认按钮重新读取当前草稿并调用既有 `OrderDraftService.confirm`，所有缺失字段、唯一 Customer/SKU 候选、草稿版本与开放复核事项仍按原门禁校验。

卡片事件业务结果先独立提交，再以原回调 `req_id` 调用 `update_template_card`。普通回调走有界保序业务队列；`template_card_event` 立即提交到独立的 4 并发快通道，并只允许最多 4 个仍受原始到达 deadline 约束的等待位。任一回调池溢出都只拒绝超出的事件，不重建共享连接或破坏已经受理的 update/无关外发 ACK。4.5 秒绝对 deadline 从 WebSocket listener 收到完整帧的单调时刻起覆盖线程切换、socket 提交与 ACK，过期发送帧不得事后发送。未确认、超时或异常时改发只含草稿号、操作人和处理时间的文字结果。卡片更新与文字兜底的失败都不得回滚已确认订单；`wecom_events.processing_status/processing_claim_token/processing_attempt`、`update_status/update_latency_ms/update_error_code` 与 `fallback_status/fallback_error_code` 分别保存业务尝试、卡片快路径和补偿结局。同一 `(event_type,msgid)` 的首次 bot/chat/actor/event/task/草稿/raw facts 不可变，变形重投不处理另一草稿；超过安全恢复窗且原业务幂等租约已失效时才轮换 claim token，业务完成及 update/fallback 结果均以 token CAS，旧 worker 无权覆盖。

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
| `check_shipment_source_sync` | `shipment_id` | PII 安全的 receiver/数量比较、状态、`blocker_codes`、`outcome_category` 与 `next_action`；只作建议且 `write_allowed=false` | 否 |
| `search_skus` | `query?`, `provider_id?`, `barcode?`, `sku_code?`, `category_id?`, `tag?`, `active?`, `page`, `size` | SKU 候选页；`query` 含条码模糊检索，`barcode`/`sku_code`/`tag` 为精确匹配，条件之间为“与”；`active` 不传时含停用 SKU | 否 |
| `list_bundles` | `status?`, `provider_id?`, `query?`, `page`, `size` | 静态礼包摘要页，含组件数、履约方及拆单事实 | 否 |
| `get_bundle` | `bundle_id` | 礼包状态与组件业务投影；跨履约方时明确发货单元数 | 否 |
| `find_bundle_candidates` | `query?`, `provider_id?`, `mapping_status?`, `page`, `size` | 启用 SKU 候选、进货价状态、履约映射及各仓最新库存观测 | 否 |
| `create_internal_order` | `idempotency_key` + OpenAPI `InternalOrderInput` | OrderDetail；可能同时产生 ReviewCase | 是，创建订单 |
| `suggest_customer_match` | `idempotency_key`, `review_case_id`, `expected_version`, `customer_id?`, `create_customer_suggestion?`, `reason` | 追加建议后的 ReviewCase | 只追加建议，不确认映射 |
| `suggest_sku_match` | `idempotency_key`, `review_case_id`, `expected_version`, `sku_id?`, `quantity_multiplier?`, `reason` | 追加建议后的 ReviewCase | 只追加建议，不确认映射 |
| `submit_business_material` | `idempotency_key`, `review_case_id`, `expected_version`, `evidence_refs`, `note` | 追加材料后的 ReviewCase | 只追加证据，不关闭复核 |

MCP 标识符和数量沿用 OpenAPI 的字符串规则。`operator`/Agent 身份来自 MCP 认证上下文，不能由工具参数冒充；写工具使用与 REST 相同的幂等注册表、乐观版本和审计切面。服务端不得注册 `resolve_*`、`cancel_remaining`、`retry_procurement` 或 `complete_source_followup` 一类 Agent 工具；未来若改变该权限边界，必须新开业务决策票。

静态礼包读取工具属于独立 `bundles-read` 模块：进程内 Agent 与公共 MCP 协议面分别配置，
公共面只有显式加入该模块才可发现。投影不返回履约方连接配置、库存原始载荷或任何写操作；
没有库存观测时返回 `NOT_OBSERVED`，不得伪造为零库存。

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

其中第 1 条的「能被 YAML 解析」以及路径/方法/参数/2xx 码/schema 名等机器可读结构，由 CI 门禁
（`OpenApiContractConsistencyTest`，见 §0）对照运行中应用生成的契约强制执行；本节的评审要求
（业务语义、白名单、副作用描述）落在人工评审层，由本文件的散文与 yaml 的 description 承载。
