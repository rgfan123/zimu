# 新开发对接文档：子牧订单履约与仓储物流中台

> 用途：给新加入的开发者快速建立系统全貌。读完本文 + 按「常用命令」跑起环境，即可开始改代码。
> 权威文档链：`docs/prd-v0.1.md`（PRD）→ `docs/state-machine.md`（状态机）→ `docs/schema.md` + `docs/schema.sql`（库表）→ `docs/api-contract.md` + `docs/openapi.yaml`（API）→ `docs/excel-closed-loop-spec.md`（Excel 闭环规范）→ `CONTEXT.md`（领域词汇表，先读它再读代码）。

---

## 1. 项目是什么

**业务定位**：公司 B 端订单从「进入公司」到「完成发货」的统一履约中台（非电商商城、非采购系统）。

- 订单来源：**彩食鲜 / 聚福宝 / 飞象** 三个平台的表格/文件 + **企业微信**群消息（AI 识别）+ **手工建单**（`MANUAL` 渠道，柜台/运营直录，V100）；
- 履约方：**京东云仓（ISC 供应链，SDK 直连）** + 第三方供应商（Excel 指令文件）；
- 产出：京东出库单/发货指令 → 实际发货数量+运单回收 → 按**平台原始格式**生成来源回填表。

**当前阶段（2026-08-18）**：
- P0「Excel 闭环」已通：三平台来源表导入 → SKU 映射确认 → 履约导出 → 运单回传 → 原格式回填，端到端可跑（有真实样本验证）；
- 京东 **SDK 只读链路已实测可用**（querySellers/Owners/Warehouses/Shops 返回 1000），**真实建单（addSoOrder）已进入最后联调阶段**（见 §9 在途工作，`JD_LOP_WRITE_MODE` 默认 `OFF`）；
- 企业微信消息接入（长连接 + AI 意图识别 + 订单草稿/运单草稿 + 人工复核）已完成主体，等待真实群消息验收；
- Agent 决策层（LangChain4j + MCP 工具）已落地：外部协议面（HTTP/SSE）与非特权 stdio 仍恒为只读；`MCP_STDIO_PRIVILEGED` 特权 stdio 面与 Agent 面（`AgentToolInvoker`，须 `allow_write=true` 白名单放行）现已开放写工具（含手工建单、路由履约、拉取平台订单等），业务写操作不再一律等待人工在界面点击确认，但仍强制幂等键、Agent 身份与审计留痕（详见 `CONTEXT.md` 企业微信一期实现边界一节）。
- 新增 V100 手工建单渠道（`MANUAL`）与 V101 组包师 Agent（按售价/毛利/费用条件出组包方案）；原料仓出入库 MCP（`McpRawMaterialTools`，7 个工具）已上线。

---

## 2. 技术栈与部署架构

| 层 | 选型 |
|---|---|
| 后端 | Java 21 + Spring Boot 3.x + Spring Data JPA，**模块化单体**（单 Maven 工程 `backend/`） |
| 前端 | React 18 + TypeScript + Vite + Ant Design 5 + ECharts（`frontend/`） |
| 数据库 | PostgreSQL 16（`app` 业务 schema + `analytics` 分析 schema），Flyway 迁移，`ddl-auto=validate` |
| 缓存 | Redis 7（幂等/缓存，非权威） |
| BI | Metabase（连 PG 只读分析视图） |
| AI | LangChain4j + OpenAI 兼容协议模型（意图识别、Agent）；MCP 工具注册 |
| 京东 | 官方 SDK jar（`backend/libs/`）：`IntegratedSupplyChain_ISC_JAVA_6.1_*.jar` + `lop-opensdk-support-1.0.30.jar` |
| 部署 | Docker Compose 一键起全套 + Nginx 网关（边缘 Basic Auth 默认开启，只绑 127.0.0.1） |

### Compose 服务（`docker-compose.yml`）

| 服务 | 端口 | 说明 |
|---|---|---|
| nginx | `${APP_PORT:-8088}` | 统一入口，注入服务端操作人身份 |
| backend | 8080（容器内） | Spring Boot 单体 |
| frontend | 80（容器内） | Vite 构建产物 |
| order-assistant | 8765 | Python 订单助手（仅 Demo 模拟下单页用） |
| postgres | 5432（容器内） | 命名卷 `postgres-data` |
| redis | 6379（容器内） | 命名卷 `redis-data` |
| metabase + metabase-init | 3000（容器内） | 预置仪表板；`metabase-init` 一次性初始化 |

持久化卷：`postgres-data` / `redis-data` / `fulfillment-files`（来源原文件、履约导出、回填文件）/ `app-media-data`（企微图片证据）。**不要随意 `docker compose down -v`**。

### 目录结构

```
backend/src/main/java/cn/zimu/fulfillment/
├── order/         订单域：CanonicalOrder、OrderDraft、ReviewCase、OperationalAlert、内部订单接口
├── customer/      客户档案 + 来源身份映射
├── product/       商品、品类
├── sku/           SKU 主数据、来源渠道映射、履约方映射、京东 SKU 门禁
├── fulfillment/   Fulfillment 状态机、Shipment、运单回传、京东出库/库存/回填、物流公司前缀映射
├── procurement/   采购工单 + 回执
├── file/          来源表导入、履约导出、运单回传文件、内容寻址文件存储
├── connector/     京东 SDK 防腐层（jd/）、企微长连接（wecom/）、三平台 Connector 接口（caishixian|jufubao|feixiang）
├── message/       渠道消息、意图识别、消息媒体、解释 Worker、MessageSubmission
├── agent/         LangChain4j Agent 运行时、工具绑定、数据查询 Agent、采购比价 Agent
├── mcp/           MCP Server（只读 + 受限写）
├── analytics/     数据中台
├── audit/         审计日志
├── seed/          演示数据种子
├── demo/          DemoScenario（隔离演示）
├── inventory/     库存观测
└── common/        幂等、版本、审计切面、错误模型、web 认证

frontend/src/
├── navigation.ts / routes.tsx   生产导航树 + 路由绑定
├── api/           端点封装（endpoints.ts、client.ts、types.ts）
└── pages/         工作台、作业中心、订单中心、库存、主数据、经营分析、系统、模拟下单、履约文件作业

scripts/           Python 拉表脚本（彩食鲜/聚福宝/飞象 抓包复刻的真实接口）+ 验收脚本
docs/research/     平台 API 抓包结论、京东 ISC 接口研究
.scratch/          Local Markdown 工单系统（见 §9）
wayfinder/         早期决策地图（历史票，已关闭的决策仍权威）
```

---

## 3. 核心业务主线（必读）

### 3.1 P0 Excel 闭环（当前主力业务路径）

```
三平台来源表上传（彩食鲜/聚福宝/飞象）
  → 表头指纹识别渠道（魔数 PK→XLSX；唯一表头指纹，零/多命中进 NEED_REVIEW）
  → 整文件留痕（import_batches + raw_import_rows），逐行容错解析
  → 客户：规范化「姓名+手机号」二元组确定性复用/自动建档（缺任一字段 → 复核）
  → SKU：必须命中 source_channel_skus 显式映射（映射乘数 quantity_multiplier 缺失 → 复核）
  → 操作员对整批一次确认（POST /import-batches/{id}/confirm；仍有阻断问题则拒绝）
  → 自动创建 Fulfillment + 按【同一订单+同一履约方+同一收货地址+同一批次】聚合生成 Shipment(CREATED)
  → 生成履约导出文件（京东导单模板 或 第三方发货清单模板），履约字段冻结（FulfillmentExport）
  → 履约方按文件发货，返回实发数量 + 快递公司 + 运单号（运单回传文件，整批校验后单事务接收）
  → 京东：SDK querySoOrder 取运单（ShipmentJdTrackingBackfill，可自动轮询）；第三方：文件回传
  → 全部运单回收后生成「来源回填表」（按平台原格式：来源份数 + 快递公司 + 运单号），可下载
  → 行级 ProcessingStage 到达 COMPLETED；订单显示聚合进度
```

要点：
- **Order ≠ Fulfillment ≠ Shipment**。一行可多次发货（部分发货 → 采购 → 续发新 Shipment）；
- 数量口径：`实际发货数量 = 来源份数 × 来源包装乘数`；礼包按「完整份数」计数、展开组件；
- 缺货流程：我方库存不足 → 先发可用量 + 自动创建采购工单（ProcurementTicket）+ 黄色提醒 → 采购回执（SUCCESS/PARTIAL/FAILED 只追加）→ 补齐后再发第二批（新 Shipment/新出库号/新运单）；
- 多 Shipment 时**只自动回填首批运单**，后续由人工关闭 `MULTI_SHIPMENT_SOURCE_FOLLOWUP` 复核事项；
- 来源回填只写「来源份数」（整数），不暴露履约方侧的包装乘数换算。

### 3.2 京东 SDK 直连（联调中）

- 防腐层：`connector/jd/`（JdWarehouseClient、只读查询、写操作 seam `JdWriteOpsService`）；
- **写门闩**：`JD_LOP_WRITE_MODE=OFF`（默认）→ 所有写端点 403 `WRITE_MODE_DISABLED`；`ON` 才允许 Shipment 受控建单 `POST /api/v1/shipments/{id}/jd-so-order`，且操作人必须在 `JD_OUTBOUND_AUTHORIZED_OPERATORS` 名单；
- 通用 `/api/v1/jd-write/*`（20 个写接口）默认永久关闭（另需 `JD_GENERIC_HTTP_WRITE_MODE=ON`，Compose 不暴露），`order/so-create` 永久拒绝——**建单只能走 Shipment 业务入口**；
- 出库单号：`isv出库单号 = 上海业务日 yyyyMMdd + 四位当日原子流水`，同 Shipment 共号，禁止 MAX+1；
- 已确认租户标识（真实联调配置，存 `fulfillment_providers.config`）：ownerNo=`EBU4418056064528`、shopNo=`ESP0020008943717`、erpShopNo=`4418056064528`、salesPlatformSource=`6`、warehouseNo=`118085840`（石家庄冷链 C 仓）、townRequired=false（待真实建单裁决）。`sourceNo`/`carrierNo` 需向 JDL 对接人索取书面确认，**不得猜测**（尤其 sourceNo 与 appKey 逐字节相同存疑）。

### 3.3 企业微信消息接入

```
企微 API 长连接（WebSocket，非 HTTP 回调）→ 验签/解密/幂等落库（ChannelMessage 不可变证据）
  → 固定回执「已接收」（不暴露任何业务信息）
  → 异步：媒体下载（内容寻址存储 + 解密）+ 模型意图识别（MessageInterpreter 接缝，可换模型）
  → MessageIntent：CUSTOMER_ORDER / SUPPLIER_TRACKING / ORDER_CHANGE / ORDER_CANCEL / NON_BUSINESS / NEED_REVIEW
  → 生成 OrderDraft 或 ProviderTrackingDraft + 每个草稿一个 ORDER_OPS ReviewCase
  → 人工复核工作台：查看原始消息+图片、模型原值、映射候选 → 修订 → 确认
  → 确认订单 → 复用标准订单应用用例；确认运单 → 复用 Shipment/Tracking 应用用例
```

要点：一条 `@机器人` 消息 = 一个 MessageSubmission；不同消息 ID 即使内容相同也不自动合并；图片原件不可变、模型结果可重算；`NON_BUSINESS` 只留档不建待办；临时错误自动重试 3 次后仍失败才建 NEED_REVIEW；一期**任何路径禁止自动确认订单**。

### 3.4 Demo 隔离

`/demo/v1/*` 只读写 `data_scope=DEMO` 数据，Mock Adapter 同步跑完 Timeline 到 SYNCED；不进入业务查询/复核/分析/文件/Metabase，不能作为 Excel 闭环验收证据。

---

## 4. 状态机（五维分离，勿合并成单一 order.status）

- **OrderStatus**：RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED；异常分支 NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED
- **ProcessingStage（行级权威）**：NEED_REVIEW → READY_TO_EXPORT → WAITING_PROVIDER → TRACKING_RECEIVED → RETURN_FILE_READY → COMPLETED；分支 PROCUREMENT_IN_PROGRESS / EXCEPTION
- **ShippingProgress（Fulfillment）**：NOT_SHIPPED / PARTIALLY_SHIPPED / SHIPPED（按累计实发算）
- **FulfillmentOutcome**：IN_PROGRESS → FULLY_FULFILLED / PARTIALLY_FULFILLED / CANCELLED；守恒 `requested = shipped + cancelled`
- **ShipmentStatus**：CREATED → SHIPPED / FAILED（DELIVERED 预留，不参与 Excel 闭环完成判定）
- **SyncStatus**：PENDING → SYNCED（SYNC_FAILED → 重试 → SYNCED）
- **ProcurementStatus**：PENDING → SUCCESS / PARTIAL / FAILED / CANCELLED
- **处理健康度**（UI 投影，非业务状态）：BLUE 自动处理中 / YELLOW 等人/等履约方 / RED 异常 / GREEN 全部 COMPLETED

多行订单：行级独立推进，订单级只展示聚合摘要（`v_order_progress_summary` 最差进度）。

---

## 5. API 面（详见 `docs/openapi.yaml`，共 100+ 端点）

| 命名空间 | 调用者 | 数据域 | 用途 |
|---|---|---|---|
| `/api/v1` | React 管理后台 | 只查 BUSINESS | 业务查询、文件操作、人工命令、主数据维护 |
| `/internal/v1` | 受信任 LangBot/Agent/部门系统 | 只写 BUSINESS | 创建订单、显式修订、采购回执（Bearer 服务身份） |
| `/demo/v1` | 模拟下单页 | 只读写 DEMO | DemoScenario / AI 提取订单 |

关键约定：
- JSON `snake_case`；数量 `NUMERIC(18,3)` 用**十进制字符串**（"6.000"），禁止浮点；ID 一律字符串（防 BIGINT 精度丢失）；
- 写命令必带 `Idempotency-Key`（相同 key+相同请求幂等返回；相同 key+不同请求 → 409 IDEMPOTENCY_CONFLICT）；改已存在业务事实必带 `expected_version`（乐观锁，不符 → 409 VERSION_CONFLICT）；
- `X-Operator` 由受信 Nginx 覆盖，浏览器自报无效；后端对全部 `/api/` 复验 Basic Auth 服务端身份；
- 错误模型统一 `{business_code, message, http_status, request_id, trace_id, field_errors}`；409=冲突 422=业务规则拒绝 502/504=外部依赖；
- 写操作原子性：业务事实 + ProcessingStage + OrderEvent + OrderVersion + AuditLog 同一事务。

主要端点分组：dashboard / orders(+timeline+versions+corrections) / import-batches(+confirm+rows+source-return-exports) / fulfillment-exports(+file+tracking-imports) / shipments(+jd-so-order) / review-cases(resolve-*、dismiss、complete-source-followup) / operational-alerts / procurement-tickets(+retry+cancel-remaining) / customers / products / categories / skus / source-sku-mappings / provider-sku-mappings / fulfillment-providers / connectors / audit-logs / analytics(channels|products|fulfillments) / inventory / jd-warehouse / jd-basicinfo / jd-stock / jd-serial / jd-order / jd-return / jd-write（写门闩）。

**MCP / Agent 边界**：非特权 stdio 与外部 HTTP/SSE 面恒只读，只暴露查询工具。特权 stdio 面（`MCP_STDIO_PRIVILEGED`+`MCP_AGENT_IDENTITY`，仅供本机内部工具）与 Agent 面（`AgentToolInvoker`，须 `allow_write=true` 白名单放行）开放写工具：创建内部订单、提交匹配建议/业务材料，以及 f79e6fee 新增的拉取平台订单（`refresh_platform_orders`）、手工建单（`create_manual_order`）、履约路由（`route_order_fulfillment`）。**仍禁止**注册确认客户/SKU/快递映射、取消剩余量、重试采购、关闭复核一类工具；这些终局动作一律人工。

---

## 6. 数据库要点（业务表数以 `docs/schema.sql` 权威快照为准，另见 4 分析视图 + 2 操作视图）

- Flyway 管理（V1 基线 + V2–V101 增量）；**禁 ddl-auto 改表**；枚举用 VARCHAR+CHECK，事件类型用目录表；
- 时间全 TIMESTAMPTZ / Java Instant；Excel 无时区时间按 Asia/Shanghai 解释；
- 只追加表（order_versions、order_events、raw_import_rows、文件版本等）由触发器禁止 UPDATE/DELETE；
- 核心关系链：`import_batches → orders → order_lines(+components) → fulfillments → shipment_items → shipments → trackings`；`shipment_jd_outbounds`（京东出库集成记录）；`fulfillment_exports(+items)`、`source_return_exports(+items)`；`procurement_tickets(+items) → procurement_receipts(+items)`；`order_versions / order_events / audit_logs`；
- `orders` 三平台 BUSINESS 订单必须关联 SOURCE_ORDER 导入批次（WECOM/内部接口订单不伪造文件血缘）；
- `fulfillment_providers.config`（JSONB）：京东 SDK 租户配置（ownerNo 等），真实建单的配置写入通道（见 §9）；
- analytics schema：视图按上海自然日分桶，供 ECharts 与 Metabase 同口径。

---

## 7. 配置、密钥与安全模型

- `.env`（git-ignored）：Postgres/管理凭据、`APP_BIND_ADDRESS`（默认 127.0.0.1）、`JD_LOP_*`（京东 SDK，`JD_LOP_CLIENT_MODE=MOCK|REAL`、`JD_LOP_WRITE_MODE=OFF|ON`）、`WECOM_*`（企微长连接）、`MESSAGE_INTERPRETER_*`（模型供应商）、`DEMO_SEED_*`；
- 本地密钥文件：`backend/.env.jd.uat.local`（京东凭据，0600）、`backend/.env.wecom.local`（企微，0600）、三平台凭据在 `data-local/*-credentials.txt`（0600）；
- **密钥不进数据库、不进 API 响应、不进日志**；HTTP 边界移除联系人/电话/邮箱/地址；
- 认证：网关边缘 Basic Auth 默认开启（`GATEWAY_BASIC_AUTH_ENABLED`，单一共享凭据），Nginx 注入服务端身份只保护审计归属，**不提供远程访问控制**；对外发布前必须先做真实认证 + HTTPS；显式设 `GATEWAY_BASIC_AUTH_ENABLED=false` 可临时关闭（nginx 启动日志可见警告）；
- Connector/Adapter/MCP/Agent **一律禁止直接写业务表**，只能调应用层用例。

---

## 8. 常用命令

```bash
# 一键起整套（首次慢；先 cp .env.example .env 填凭据）
docker compose up -d --build --wait
# 访问：http://localhost:8088 （工作台 /workbench/reviews 复核 /fulfillment/jd-warehouse 京东 /demo/order 演示 /analytics 分析 /metabase BI /actuator/health 健康）

# 前端热更（连已启动的 Compose 网关，端口 5173）
cd frontend && npm ci && npm run dev

# 后端测试（模块化单体，测试很全：Excel 闭环/幂等/复核/企微/Agent）
cd backend && mvn test

# 整栈自动验收（独立项目 zimu-fulfillment-acceptance，端口 18088，参考日 2026-08-12）
sh scripts/acceptance.sh

# 京东只读探针（真实 UAT；需要 backend/.env.jd.uat.local 凭据）
scripts/jd-readonly-uat.sh          # queryOwnerInfo；JD_PROBE_WAREHOUSES=true 加查仓库
```

后端本地跑：不直连 8080（会被认证拒绝），必须走 Nginx 网关或受信测试配置。

---

## 9. 当前在途工作（.scratch 工单系统，Local Markdown）

| 工单 | 状态 | 内容 |
|---|---|---|
| `jd-real-sdk-switch` | Ready for tickets（最热） | 真实京东 SDK 建单：已确认租户标识；待 JDL 书面确认 sourceNo/carrierNo；`fulfillment_providers.config` 正式写入通道（PATCH DTO 缺 config 字段）；首次真实 addSoOrder 裁决 townRequired；收货地址 4 字段补齐；customerCode 改为客户级字段 |
| `wecom-message-intake` | ready-for-agent | 企微消息→意图→草稿→复核闭环（主体已实现，待真实群消息验收） |
| `meta-agent-platform` | 进行中 | Agent 平台化：LangChain4j 动态 schema、统一运行时、eval 数据化、MCP 权限隔离、agentic inventory（10 个 issue） |
| `wecom-long-connection` | 进行中 | 企微长连接（已实现常驻 WS + 指数退避重连 + readiness 接口） |
| `message-interpreter-real` | 进行中 | 真实模型解释器（DeepSeek 等，含结构化输出边界） |
| `agent-decision-layer` | 已闭环 | LangChain4j 运行时 + MCP 工具绑定 + 数据查询 Agent + 采购比价 Agent（9 个 issue） |
| `platform-online-integration` | 待办 | 三平台真实 API 模式（transport_mode=API），当前为 EXCEL 模式 |
| `mvp-productization` / `mvp-stabilization` | 已闭环 | 整栈验收、公共文案对账 |

工单格式：`.scratch/<ticket>/spec.md`（目标/方案/用户故事/实现决策/测试决策）+ `issues/`（编号 issue）+ `map.md`。新工作先看 spec 再动手。

## 10. 开发注意事项（踩坑清单）

1. **不要绕过认证/门闩**：`JD_LOP_WRITE_MODE=OFF` 是默认安全态，真实写操作必须走受控 Shipment 入口 + 授权名单；Mock 成功 ≠ 真实权限证明。
2. **不要猜测映射**：客户（姓名+手机二元组）、SKU、物流公司（前缀映射命中也要人工确认）、企微父消息——全部禁止模型/代码猜测，进复核。
3. **数量用 BigDecimal 字符串**：来源份数必为整数、实发量按乘数换算、礼包按完整份数；超过 3 位小数进 NEED_REVIEW。
4. **写操作三件套**：Idempotency-Key + expected_version + 审计；业务事实/事件/版本/审计同事务。
5. **文件即证据**：原文件、原始行、图片原件不可变；导出/回填文件版本化只追加；文件格式识别靠魔数+表头指纹，不看扩展名。
6. **BUSINESS 与 DEMO 强隔离**，接口不提供混查参数。
7. 修改 schema 必须：Flyway 新迁移（V18+）+ 同步 `docs/schema.sql` 权威快照 + 更新 `docs/schema.md`；改 API 同步 `docs/openapi.yaml`。
8. 领域词汇变化先改 `CONTEXT.md`；状态机决策先看 `docs/state-machine.md` 再改代码。
9. 前端导航树在 `frontend/src/navigation.ts`（无 React 元数据），路由组件在 `routes.tsx` 按 path 绑定；新页面两处都要改。
