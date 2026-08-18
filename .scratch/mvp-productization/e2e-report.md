# 06 — MVP 公共端到端评价报告（可重复执行）

- 票：`.scratch/mvp-productization/issues/06-end-to-end-evaluation.md`
- 执行日期：2026-08-14（Asia/Shanghai）
- 评价入口：公共 Nginx `http://127.0.0.1:8088`（`zimu-fulfillment` compose 栈）
- 评价 run：`E2E-20260814-232558`（API 74 项：**70 PASS / 0 FAIL / 4 GATE**）+ 浏览器证据 5/5
- 评价脚本：`/tmp/zimu-e2e-eval.py`（API 动线）、`/tmp/zimu-e2e-browser.py`（浏览器证据）；凭据只从运行环境读取，脚本与报告均不含任何真实凭据

## 1. 栈健康

| 检查 | 结果 | 证据 |
|---|---|---|
| backend 镜像重建（`docker compose up -d --build backend`） | PASS | 重建前旧镜像缺 `/api/v1/wecom/readiness`（404）；重建后端点存在且 readiness 正常，证明新镜像包含企微长连接等近期交付 |
| nginx `GET /healthz` | PASS | `200 ok` |
| `GET /actuator/health` | PASS | `{"status":"UP"}` |
| 公共 API 可达 `GET /api/v1/orders` | PASS | `200`，评价结束时栈内 BUSINESS 订单 26 条（4 条历史 + 本轮公共 API 构造） |
| 容器健康 | PASS | nginx/backend/frontend/postgres/redis/order-assistant 均 `(healthy)`；metabase `Up` |

## 2. 评价矩阵与结果（8 覆盖域）

### 2.1 人工复核（订单/运单草稿确认流程）

| 动线 | 结果 | 证据（响应摘要） |
|---|---|---|
| 来源导入（未知 SKU 映射 → 复核） | PASS | `201`，`row_counts={total:1, accepted:0, need_review:1, rejected:0}` |
| OPEN 复核事项可查 | PASS | `GET /api/v1/review-cases?status=OPEN` 命中本轮订单，产生 `SKU_MAPPING_REQUIRED` |
| 导入客户自动建档 | PASS（行为观察） | 当前构建按收货人姓名+手机号自动建档并回写 `customer_id`，公共导入路径**不再**产生 `CUSTOMER_MATCH_REQUIRED`（与 acceptance.sh 旧断言不同，见 §6-B1） |
| 第三方 provider-SKU 映射构造（公共主数据 API） | PASS | `POST /api/v1/provider-sku-mappings` `201`；重复创建返回 `409 PROVIDER_SKU_MAPPING_EXISTS`（可复用） |
| `resolve-sku` 写命令 | PASS | `200`，`status=RESOLVED`，`resolved_by=APP_ADMIN_USER`（服务端身份覆盖伪造 `X-Operator: forged-browser-operator`） |
| 过期版本 409 | PASS | `409 VERSION_CONFLICT`（重放旧版本） |
| 复核关闭后订单可导出 | PASS | `order_status=SKU_MAPPED`，行 `READY_TO_EXPORT` |
| 批次级确认生成履约文件 | PASS | `POST /api/v1/import-batches/{id}/confirm` `200`，`generated_fulfillment_export_ids=["4"]`；幂等重放返回同一响应 |
| 确认后进入履约 | PASS | `order_status=FULFILLING`，行 `WAITING_PROVIDER` |
| 来源跟进入工完成（`complete-source-followup`） | PASS | `200 RESOLVED` → 订单 `CLOSED`，Timeline 含 `MANUAL_SOURCE_FOLLOWUP_COMPLETED` |
| `resolve-customer` 命令 | GATE | 当前构建公共导入路径不产生客户复核事项（客户按收货人姓名+手机号自动建档）；该命令由票 01 `ReviewCaseResolutionApiTest` 覆盖（内部/WECOM 通道），真实企微消息接入见 §4-G2 |

### 2.2 订单查询

| 动线 | 结果 | 证据 |
|---|---|---|
| 按来源单号检索 | PASS | `GET /api/v1/orders?query=<ref>` 命中 1 条 |
| 订单详情 | PASS | `200`，`id` 一致 |
| 订单时间线 | PASS（观察见 §6-B4） | `200`，含 `ORDER_RECEIVED`；复核恢复订单不追加 `SKU_MAPPED` 事件 |
| 版本历史 / 运单列表 | PASS | `GET /orders/{id}/versions` ≥1；`/orders/{id}/shipments` 返回已生成 Shipment |
| 状态过滤 | PASS | `order_status=FULFILLING` 命中 |
| 订单草稿列表（空态） | PASS | `total_elements=0`（草稿来自企微消息接入，外部 gate 空态） |
| 运单草稿列表（空态） | PASS | `200`，空（运单草稿确认流程依赖企微消息接入，见 §4-G2） |

### 2.3 文件作业（Excel 导入导出闭环）

| 动线 | 结果 | 证据 |
|---|---|---|
| 已知映射直接导入（待批次确认） | PASS | `201`，`accepted=1` |
| 批次确认生成履约导出 | PASS | `200`，`generated_fulfillment_export_ids=["5"]` |
| 履约导出 XLSX 下载 | PASS | `200`，字节头 `PK`，真实 OOXML 工作簿 |
| 运单回传（首批 PARTIAL） | PASS | `201`，`business_results={shipped:0, partial:1, failed:0}` |
| 同文件重放幂等 | PASS | 重放返回与首次完全一致 |
| 部分发货来源跟进门禁 | PASS | `MULTI_SHIPMENT_SOURCE_FOLLOWUP` OPEN |
| 履约进度 | PASS | `shipping_progress=PARTIALLY_SHIPPED` |
| 续发批次 | PASS | `201`，`shipment_sequence=2`，独立导出可下载 |
| 第二批运单回传 SHIPPED | PASS | `201`，`business_results={shipped:1, partial:0, failed:0}` |
| 多 Shipment 来源回填延后 | PASS（测试锁定语义） | 当前构建 PARTIAL 即开 followup，回填文件在 followup 未关闭时不产出（`ExcelClosedLoopApiTest#twoShipmentPublicHttpFlowKeepsFirstReturnAndRequiresManualFollowup` 已锁定；与 acceptance.sh 旧断言不同，见 §6-B3） |
| 双批次终局 → NEED_REVIEW → 人工完成 → CLOSED | PASS | 全链路：`NEED_REVIEW` → `complete-source-followup` → `CLOSED` |
| 单 Shipment 全额回传 → 原格式回填文件 | PASS | 回传 `SHIPPED 1.000` 后产生来源回填文件，下载为原格式并含运单号 |

### 2.4 采购操作

| 动线 | 结果 | 证据 |
|---|---|---|
| 采购票列表 | PASS | `GET /api/v1/procurement-tickets` `200`，本栈 `total=0`（无 seed，MOCK 库存决策不产生缺货票） |
| `cancel-remaining` 端点接线 | PASS | 不存在票返回 `404 NOT_FOUND`（端点已接线） |
| 库存决策探针（shipment 级只读） | GATE | `POST /api/v1/shipments/{id}/jd-stock-check` 在第三方履约 Shipment 上返回 `409 JD_STOCK_PREVIEW_BLOCKED`（JD 出库预览前置门禁）；MOCK 库存 `usableNum=100` 无缺货 → 本栈无 PENDING 票，cancel 全流程写命令需 seed 数据或真实库存决策环境，见 §4-G3 |

### 2.5 京东只读（Mock 模式探针）

| 动线 | 结果 | 证据 |
|---|---|---|
| JD 状态门禁 | PASS | `{client_mode: MOCK, credentials_configured: true, tenant_configured: true, live_ready: false}` |
| 事业部查询 `queryOwners` | PASS | `MOCK_SUCCESS`，`MOCK-OWNER-001` |
| 仓库查询 `queryWarehouses` | PASS | `MOCK_SUCCESS`，`MOCK-WH-001` |
| 出库单查询 `queryOutboundOrder` | PASS | `MOCK_SUCCESS` |
| 轨迹查询 `queryTracking` | PASS | `MOCK_SUCCESS` |
| 京东写模式 OFF 失败关闭 | PASS | `POST /api/v1/jd-write/order/so-create` → `403 WRITE_MODE_DISABLED`，未提交任何真实写 |

### 2.6 Demo 隔离

| 动线 | 结果 | 证据 |
|---|---|---|
| 固定演示场景列表 | PASS | `GET /demo/v1/scenarios`，`HAPPY_PATH` |
| 固定场景创建 DEMO 订单 | PASS | `201`，`data_scope=DEMO`，`order_status=SYNCED`，Timeline 9 步到 `SOURCE_SYNCED` |
| DEMO 订单不进 BUSINESS 查询 | PASS | 以 DEMO 订单 `source_ref` 查询 `/api/v1/orders` → `total_elements=0` |
| DemoRun 详情 | PASS | `/demo/v1/runs/{id}` `data_scope=DEMO` |
| 未确认 AI 草稿拒绝创建 | PASS | `400 VALIDATION_ERROR`（`confirmed=false`） |
| 确认后创建隔离 DemoRun | PASS | `201`，`data_scope=DEMO`，`SYNCED`，Timeline 操作人全部 `local-operator` |
| 默认审计不混入 demo | PASS | `audit-logs?service=demo&operation=demo.run` → `total=0` |

### 2.7 分析

| 动线 | 结果 | 证据 |
|---|---|---|
| 看板汇总 | PASS | `GET /api/v1/dashboard/summary?business_date=2026-08-14`，`order_count=20`，`trend` 7 日 |
| 渠道统计 | PASS | `GET /api/v1/analytics/channels?date_from=30d`，3 行（CAISHIXIAN/FEIXIANG；无 seed 故无 JUFUBAO/WECOM 数据） |
| 商品分析 | PASS | 非空 |
| 履约分析 | PASS | 非空（含本轮 E2E 履约） |

### 2.8 审计

| 动线 | 结果 | 证据 |
|---|---|---|
| 审计日志列表 | PASS | `total=84`（含本轮全部写操作） |
| 按操作类型过滤 | PASS | `operation=review_case.resolve_sku` 命中 |
| 伪造操作人不产生审计 | PASS | `operator=forged-browser-operator` → `total=0` |
| 关键写操作均有审计 | PASS | `file.upload`、`source-orders.upload`、`review_case.resolve_sku`、`review_case.complete_source_followup`、`continuation_export.create` 全部在案 |
| 审计详情不含 PII | PASS | 对已知收货人姓名/手机号/地址做全量审计详情扫描，0 泄漏 |
| 本栈无 seed 标记审计 | PASS | `operation=seed.demo-dataset` → `total=0`（`DEMO_SEED_ENABLED=false` 且已有 BUSINESS 数据，seed 未运行） |

## 3. 浏览器证据（Playwright 1.58 + chromium，1440×900）

截图存于 `.scratch/mvp-productization/e2e-evidence/`：

| 覆盖点 | 结果 | 证据 |
|---|---|---|
| 主导航层级 | PASS | `nav-dashboard.png`：顶级菜单 工作台/作业中心/订单中心/库存中心/主数据/经营分析/系统管理/模拟下单/管理驾驶舱；作业中心展开 人工复核/渠道消息/履约任务/采购协同/文件作业/发货记录 |
| 空态 | PASS | `empty-channel-messages.png`：渠道消息页无数据行（表头 接收时间/发送人/业务群/类型/消息内容/操作 空态）——真实企微未接入时的诚实空态 |
| 失败态（未知路由） | PASS（观察） | `failure-404-route.png`：`/no-such-route-xyz` 被 `App.tsx` catch-all 重定向到 `/dashboard`（无独立 404 页，见 §6-B5） |
| 关键写操作（浏览器内） | PASS | `write-op-demo-run.png`：模拟下单页固定场景「京东仓完整履约」→ 开始模拟下单 → 运行编号 `RUN-6522C78D1B4F45178EE8130B2AF312B7`、状态「已完成」、数据域 `DEMO`、演示订单号 `DEMO-ORD-6522C78D1B4F45178EE8130B2AF312B7` |
| 复核工作台 | PASS | `workbench-reviews.png`：人工作业中心渲染阻断复核/运营提醒，状态「待处理」 |

注：python-playwright 的 a11y snapshot 接口在该版本不可用，DOM 层级证据以截图 + 页面文本（已内联本表）为准；如需 yml 格式快照可用仓库既有 `.playwright-cli/` 工具链补拍。

## 4. 外部系统 gate 清单（非失败，未接入/未授权）

| Gate | 状态 | 证据 |
|---|---|---|
| G1 京东真实只读 | GATE | `client_mode=MOCK`，`live_ready=false`；本票只做 Mock 探针。真实只读需 `JD_LOP_CLIENT_MODE=LIVE` 后跑 `scripts/jd-readonly-uat.sh`（票 04 已证明账号到达京东业务校验层，事业部权限已开通，仍缺已知 ERP 出库单号做发货事实验证） |
| G2 企业微信真实接入 | GATE | `GET /api/v1/wecom/readiness`：`enabled=false`、`connection_state=DISCONNECTED`、`missing_requirements=[CONNECTION_ENABLED, BOT_ID, SECRET]`；长连接凭据未注入 compose（`WECOM_ENABLED=false`），真实业务消息验收需企微侧发消息并在 `/workbench/channel-messages` 核对 |
| G3 采购 cancel-remaining 全流程 | GATE | 本栈无 seed、MOCK 库存充足 → 无 PENDING 采购票可取消；`JD_TRACKING_TEMPLATE_GATE`（京东官方 tracking golden 缺失）同样未解除 |
| G4 Metabase | PASS（本地可达） | `/metabase/api/health` ok；管理员会话 200；仪表板已配置：履约总览/渠道分析/商品分析/E-commerce Insights；未登录访问 dashboard API → 401 |

## 5. 未授权的生产写操作（明确未执行）

- 真实京东创建/取消出库单：**未执行**（`JD_LOP_WRITE_MODE=OFF`，写探针仅验证 `403 WRITE_MODE_DISABLED` 失败关闭）。
- 真实企业微信外呼/消息发送：**未执行**（长连接未启用，无任何外呼）。
- 生产环境访问：**未执行**（全部请求仅到本机 `127.0.0.1:8088`）。
- 内部服务端点：nginx 对 `/internal/` 直接返回 `404`（不暴露），未访问。
- 数据构造仅通过公共 API（导入/主数据/演示接口），未直写 DB。

## 6. 发现的 bug / 行为偏差（未修复，供 07 回归票处置）

> 以下均为**当前工作树行为与 `scripts/acceptance.sh` 断言/旧构建的不一致**，不视为本票修复对象；其中 B1–B3 若 acceptance 脚本原样重跑将失败。

- **B1（高）**：来源导入不再产生 `CUSTOMER_MATCH_REQUIRED` 复核。
  复现：`POST /api/v1/import-batches/source-orders` 导入含收货人姓名+手机号的 CSV（当前构建 `ImportedCustomerService` 按姓名+手机号自动建档并回写 `customer_id`）。
  影响：`acceptance.sh` L447 断言 `{CUSTOMER_MATCH_REQUIRED, SKU_MAPPING_REQUIRED}` 将失败；客户复核仅出现在内部/WECOM 通道。
- **B2（高）**：履约文件改为批次级确认生成。
  复现：导入已映射订单后 `GET /api/v1/import-batches/{id}` 的 `generated_fulfillment_export_ids` 为空，必须 `POST /api/v1/import-batches/{id}/confirm` 后才生成。
  影响：`acceptance.sh` L502/L486 直接断言导入/复核后即存在导出，将失败。
- **B3（高）**：首批 PARTIAL 立即创建 `MULTI_SHIPMENT_SOURCE_FOLLOWUP`，来源回填文件延后（followup 未关闭不产出）。
  复现：履约导出回传 PARTIAL → followup OPEN → `/import-batches/{id}/source-return-exports` 为空；`complete-source-followup` 后订单直接 CLOSED，当前流程不自动补生成回填文件（JD 回填路径除外）。
  影响：`acceptance.sh` L536/L598-L604 断言首批回填存在，将失败。当前 `ExcelClosedLoopApiTest`（15 项，0 失败）已锁定新语义，需确认产品意图后更新脚本或补回填生成逻辑。
- **B4（低，观察）**：复核恢复的订单 Timeline 不追加 `SKU_MAPPED` 事件（仅 `ORDER_RECEIVED`，直至 `SHIPMENT_CREATED/TRACKING_RECEIVED`）；与演示场景 9 步 Timeline 不一致。属展示口径问题，建议确认是否需要补事件。
- **B5（低，观察）**：前端未知路由直接 `<Navigate to="/dashboard">`，无独立 404 页（`frontend/src/App.tsx` L19）；对内部用户可用，但地址栏失效时无任何提示。
- **B6（低，观察）**：`JD_LOP_CLIENT_MODE=MOCK` 下库存决策恒充足（`usableNum=100`），无法通过公共 API 构造 PENDING 采购票；采购写命令的端到端验收依赖 seed 栈（`DEMO_SEED_ENABLED=true` 全新卷）或真实库存。

## 7. 可重复执行说明

### 7.1 重建与启动（保持数据）

```bash
cd /Users/jerry/Documents/子牧
# compose 需要 POSTGRES_USER/POSTGRES_PASSWORD（容器已运行时可从 postgres 容器 env 提取，
# 或使用含完整变量的 0600 env 文件）：
docker compose --env-file /tmp/zimu-e2e.env up -d --build backend
curl -s http://127.0.0.1:8088/actuator/health   # {"status":"UP"}
```

### 7.2 数据准备（全部经公共 API，不直写 DB）

1. 主数据：使用既有 4 客户/63 SKU/9 分类；为文件闭环构造第三方 provider-SKU 映射：
   `POST /api/v1/provider-sku-mappings {provider_id:<THIRD_PARTY>, sku_id:<TP sku>, provider_sku_code, active:true}`
   （已存在时返回 `409 PROVIDER_SKU_MAPPING_EXISTS`，可复用）。
2. 复核动线：导入未知 SKU 来源 CSV → `resolve-sku`（引用第三方 SKU）→ `POST /import-batches/{id}/confirm`。
3. 文件闭环：同客户/SKU 再导入 → confirm → 下载履约导出 → 填 S:W 单元格回传 PARTIAL → 续发 → 第二批 SHIPPED → `complete-source-followup`；另做单 Shipment 全额回传验证回填文件。
4. Demo：`GET/POST /demo/v1/scenarios` 与 `POST /demo/v1/extracted-orders`（`confirmed` 门禁）。

### 7.3 重跑评价

```bash
python3 /tmp/zimu-e2e-eval.py        # API 动线，输出 PASS/FAIL/GATE 与 /tmp/zimu-e2e-results.json
python3 /tmp/zimu-e2e-browser.py     # 浏览器证据（截图 /tmp/e2e-browser-evidence/）
```

脚本内 run_id 每次变化，所有构造数据带 `E2E-<run_id>` 前缀，可与既有数据并存；`/tmp/zimu-e2e.env` 为 0600 权限、不落库不打印。若需完全干净基线：全新卷 + `DEMO_SEED_ENABLED=true`（会播种 120 条 BUSINESS 订单与采购票，覆盖 §2.4 的 GATE 与 §6-B6），此时勿用 `-v` 之外的破坏性操作。

## 8. 结论

- 公共 Nginx 入口（8088）8 域动线全部按当前构建语义通过：**70 PASS / 0 FAIL / 4 GATE**；浏览器证据 **5/5**。
- 4 个 GATE 均为外部依赖（京东真实只读、企微真实接入、采购写全流程数据、Metabase 属 PASS），无本地功能失败。
- 当前工作树相对验收脚本存在 3 处行为偏差（B1–B3），`scripts/acceptance.sh` 需在 07 票对齐更新；另有 3 条低优先级观察（B4–B6）。
- 未授权生产写操作（真实京东写、真实企微外呼、生产环境访问、`/internal/`）均未执行，且有失败关闭证据。
