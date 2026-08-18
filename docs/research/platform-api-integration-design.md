# 三平台订单数据在线接入详细设计方案（彩食鲜 / 聚福宝 / 飞象）

状态：设计稿（2026-08-18，待评审）
依据：`docs/research/platform-api-integration-plan.md`（评估稿，本设计细化其 Phase 0/1/2 框架）
契约来源：`docs/research/platform-apis-overview.md` + 三份平台契约文档 + `scripts/*_fetch_orders.py` 实测
系统侧依据（已只读核实）：`docs/api-contract.md` §4.2/§4.3/§6.2、`docs/excel-closed-loop-spec.md`、`CONTEXT.md`、
`backend/src/main/java/cn/zimu/fulfillment/{connector,file,order}/`、`backend/src/main/resources/db/migration/V1__baseline.sql`

> ⚠️ 本设计只读分析现有代码与契约，不含任何业务代码改动；所有「新增/修改」均为**待实施建议**，须经评审通过后另行开工。

---

## 0. 结论先行

- 三平台在线接入的**系统侧条件已具备**：`connector_configs` 表已预留 `transport_mode='API'`、`mode='REAL'`、`last_pull_at`、`last_error` 列；`orders` 表已具备 `UNIQUE (source_channel, source_ref)` 订单号去重；`import_batches` 已具备内容哈希幂等唯一索引；文件解析与订单创建应用层用例均已就绪。
- 关键架构事实（本次代码核实）：**backend 没有目录监听**，文件导入唯一入口是 `POST /api/v1/import-batches/source-orders`（multipart 上传）；且 **`orders` 表 CHECK 约束要求非 WECOM 业务订单必须挂 `source_import_batch_id`**——因此无论 Phase 0 文件模式还是 Phase 1 在线 Pull，**数据都必须先进入 ImportBatch + 人工确认闭环**，不能绕过。这恰好与「EXCEL 闭环不可变、CanonicalOrder 为事实源」的铁律自洽：在线拉取只是来源文件的另一种生产方式。
- 推荐路径不变：**Phase 0 本周启用自动拉表（零 Java 改动）→ Phase 1 聚福宝 Java Connector 为试点 → 彩食鲜/飞象跟进 → Phase 2 在线回传**。

---

## 1. 总体架构

### 1.1 三层职责划分

```
┌───────────────────────────────────────────────────────────────────────────────┐
│  Layer 1 · Python 拉取层（Phase 0 资产；Phase 1 上线后降级为「兜底通道」）           │
│  scripts/caishixian_fetch_orders.py / jufubao_fetch_orders.py /                │
│  feixiang_fetch_orders.py（+ jufubao JSON→Excel 转换器）                        │
│  职责：登录续期 · 拉取原始数据 · 魔数/结构校验 · 落盘 ingest/ · （可选）上传导入批次    │
│  失败自愈：重试 → 状态文件 → 告警；最终兜底 = 人工平台导表                          │
└───────────────────────────────┬───────────────────────────────────────────────┘
                                │ Seam A：ingest/ 目录（字节流 + manifest）
                                │ Seam B：上传端点（multipart）
┌───────────────────────────────▼───────────────────────────────────────────────┐
│  Layer 2 · Java Connector 层（Phase 1 主路径，api-contract §6.2 目标形态）        │
│  PlatformConnector 实现：CaishixianConnector / JufubaoConnector /               │
│  FeixiangConnector                                                            │
│  共性组件：PlatformHttpClient / PlatformSessionManager（登录续期+并发锁）          │
│           PlatformErrorMapper（平台错误码→内部稳定码）/ 限流重试器                  │
│  职责：pullOrders / pullOrderChanges / pullCancellations → transform →           │
│        CanonicalOrderInput；pushShipmentResult（Phase 2）                       │
│  铁律：禁止直写业务表 —— 只产出 DTO / 批次素材，一律交 Layer 3 应用层用例            │
└───────────────────────────────┬───────────────────────────────────────────────┘
                                │ Seam C：CanonicalOrderInput + SourceOrderEnvelope
┌───────────────────────────────▼───────────────────────────────────────────────┐
│  Layer 3 · 应用层用例（唯一写入口，现有代码，零领域改动）                            │
│  SourceImportService.upload(bytes, mode, parentBatch, idemKey, ctx)             │
│    → SourceFileParser.parse(byte[])（指纹识别，零命中→NEED_REVIEW）               │
│    → OrderCreateService.createImported(CanonicalOrderInput, batchId, idemKey,    │
│        ctx)  ← Connector 只能调到这里                                            │
│    → 客户二元组复用/创建 · SKU 显式映射 · 数量换算 · ReviewCase · Fulfillment ·     │
│       OrderEvent / OrderVersion / AuditLog —— 同一事务提交                        │
│  人工门禁：POST /api/v1/import-batches/{batch_id}/confirm（整体确认）→ 履约导出      │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 接缝（Seam）定义

| 接缝 | 两端 | 形态 | 契约 |
|---|---|---|---|
| A | Python 拉取层 ↔ 上传脚本 | `ingest/{channel}/{yyyyMMdd}/` 目录 + `manifest.json` | 见 §3.1 命名规范 |
| B | 上传脚本 ↔ backend | multipart 上传端点 | `POST /api/v1/import-batches/source-orders`（现状，见 §3.2） |
| C | Java Connector ↔ 三平台 HTTP | 平台私有接口 | 三份平台契约文档 |
| D | Java Connector ↔ 应用层 | `CanonicalOrderInput` / `SourceOrderEnvelope` | `OrderCreateService.createImported`（现签名见 §4.6） |
| E | 监控 ↔ Connector | `testConnection` 真实探测 | `POST /api/v1/connectors/{channel}/test-connection`（现状） |

### 1.3 失败降级路径（自动 → 半自动 → 人工）

```
自动拉取失败（脚本 exit≠0 / Connector PullResult 非成功）
   ├─ 重试：同通道重试 2 次，指数退避（5min / 15min）
   ├─ 留痕：ingest/status/last-run-{channel}.json + connector_configs.last_error
   │        + AuditLog（operation=pullOrders, business_code=PLATFORM_PULL_FAILED）
   ├─ 告警：日志 + 状态文件 +（可选）企微/邮件通道，见 §6.3
   │
   ├─ [Phase 0] 兜底 = 人工登录平台后台导表 → 走现有前端上传界面（NEW/REVISION）
   ├─ [Phase 1] 兜底 1 = 平台 Connector 失效时回退 Phase 0 Python 脚本
   └─ [Phase 1] 兜底 2 = Python 也失效时 → 人工导表上传（与 Phase 0 同一入口）
        ↓
   系统侧无差别：无论自动拉取 / 脚本落盘 / 人工上传，全部汇入同一 ImportBatch + 确认闭环；
   自动通道恢复后，人工批次与自动批次并存互不干扰（内容哈希幂等防重复建单）。
```

---

## 2. 架构铁律与本方案的落点（代码核实结论）

| 铁律 | 代码事实 | 本方案落点 |
|---|---|---|
| Connector 禁止直接写业务表 | `SourceImportService` / `OrderCreateService.createImported` 是文件 Adapter 共用创建缝（javadoc 原文）；`api-contract` §6 明确 Adapter 只能调用应用层用例 | Layer 2 全部写操作收敛到 `SourceImportService.upload` / `OrderCreateService.createImported`；Connector 只产 DTO |
| CanonicalOrder 是长期事实源 | `orders.source_ref_kind ∈ (PROVIDED, SYNTHETIC)`、OrderVersion 只追加 | 拉取只创建/不覆盖；状态变化（如待发货→已发货）**不静默改单**，只产生 OrderEvent/ReviewCase 信号（见 §4.6.3） |
| 非 WECOM 业务订单必须挂导入批次 | `orders` CHECK：`source_channel<>'WECOM' → source_import_batch_id IS NOT NULL` | **在线 Pull 也走 ImportBatch**：每次 pull 落一个 `SOURCE_ORDER` 批次（`batch_no=PULL-{channel}-{ts}`），仍由操作员确认后进入履约 |
| EXCEL 闭环不可变 / ImportRevision 语义 | `import_batches.import_mode ∈ (NEW, REVISION)`，REVISION 必须带 `parent_import_batch_id`；`uq_import_content_scope` 内容哈希幂等 | 每次自动拉取 = `NEW` 批次；人工修正 = `REVISION` 显式关联父批次；相同内容重放返回既有批次 |
| 幂等 | `UNIQUE (source_channel, source_ref)`；`IdempotencyService`（PG 权威注册表，IN_PROGRESS 租约） | 订单号去重 + 内容哈希 + 幂等键三层，见 §4.6 |
| 密钥不入库 | `connector_configs.config.credential_secret_ref` 只存引用；`app.jd.*` 全部 `JD_LOP_*` 环境变量注入；`.env.*.local` 0600 先例 | 见 §5 |

---

## 3. Phase 0 细化：人工触发 / 定时拉表 → 现有文件闭环

目标：**零 Java 改动**，本周内把「人工导 Excel → 上传」替换为「脚本自动拉取 → 落盘 → 自动上传 → 现有导入闭环」。

### 3.1 落盘目录与文件命名规范

**建议使用独立 `ingest/` 目录（gitignored），与 `data-local/` 分离**：

- `data-local/` 保留给抓包样本、凭据文件、SKU 映射等**研究与本地资产**（已在 `.gitignore`）；
- `ingest/` 是**生产拉取工作目录**：脚本输出、待上传、已上传文件、状态文件、manifest 均在此，便于运维一眼看清管道健康度，也避免与研究工作目录互相污染。

目录结构：

```
ingest/
├── {channel}/                      # channel ∈ caishixian | jufubao | feixiang
│   └── {yyyyMMdd}/                 # 拉取业务日（Asia/Shanghai）
│       ├── {文件名}.{ext}          # 数据文件（命名见下）
│       └── manifest.json           # 边车元数据（拉取/上传状态）
├── status/
│   └── last-run-{channel}.json     # 最近一次运行结果（供告警/排错）
└── .gitkeep
```

文件命名规范（统一 `yyyyMMdd`，`-NN` 为当日批次号，同日多次拉取递增）：

| 平台 | 文件名模板 | 示例 | 内容 |
|---|---|---|---|
| 彩食鲜 | `待发货订单-{yyyyMMdd}-NN.xlsx` | `待发货订单-20260818-01.xlsx` | 平台导出 Excel（保留平台原名前缀，日期规范化） |
| 聚福宝 | `聚福宝订单-{tab}-{yyyyMMdd}-NN.xlsx` | `聚福宝订单-no_delivery-20260818-01.xlsx` | JSON→Excel 转换产物（见 §3.4.2） |
| 飞象 | `飞象待发货订单-{yyyyMMdd}-NN.xlsx` | `飞象待发货订单-20260818-01.xlsx` | 平台直下文件 |

`manifest.json`（幂等与审计素材，上传时一并携带信息）：

```json
{
  "platform": "jufubao",
  "business_day": "20260818",
  "batch": "01",
  "fetched_at": "2026-08-18T08:40:00+08:00",
  "range": {"begin": "2026-07-19", "end": "2026-08-18"},
  "file": "聚福宝订单-no_delivery-20260818-01.xlsx",
  "content_sha256": "…64位hex…",
  "source": "orders/query tab=no_delivery",
  "fetch_status": "ok | failed",
  "upload_status": "pending | uploaded | failed",
  "upload_batch_id": null
}
```

> 注：现有脚本默认落 `data-local/` 且文件名带 `yyyy-MM-dd`（聚福宝 JSON）。接入 ingest 时统一改为上述规范；**旧文件不迁移**（历史产物只读）。

### 3.2 与现有文件导入入口对接（代码核实结论）

**结论：backend 当前没有目录监听 / ingest 扫描**。文件进入系统的唯一入口是：

- `POST /api/v1/import-batches/source-orders`（`SourceImportController.upload`，multipart）
  - 必填：`file`、`import_mode`（`NEW`/`REVISION`）、`Idempotency-Key`（≥8 字符）、`X-Operator`
  - 流程：`SourceImportService.upload(byte[], filename, mode, parentBatchId, key, ctx)` → `SourceFileParser.parse(byte[])` → 内容哈希幂等检查 → 原文件留痕（`app.file-store.root`，默认 `${java.io.tmpdir}/zimu-fulfillment-files`）→ 逐行分组 → `OrderCreateService.createImported` → AuditLog（service=`source-file-import`）

因此 Phase 0 对接方式有两条路（**需决策，见 §8 决策点 D1**）：

**方案 A（推荐先做，零 Java 改动）**：脚本/封装脚本用 `curl` 上传到现有端点。

- 幂等键派生：`imp-{channel}-{sha256[:24]}`（确定性 → 重传重放返回原批次，符合内容哈希幂等语义）；
- `X-Operator`：见 §8 决策点 D2——网关 Basic Auth 主体为权威操作人（`api-contract` §3.2），生产需为拉取服务建立固定网关主体（如 `svc-platform-pull`），本地开发可直接传 `system:platform-pull`。
- 产出：`scripts/upload_ingest.sh`（或三脚本加 `--upload-endpoint` 参数）。

**方案 B（建议后续收口，最小 Java 改动）**：新增受信任内部端点 `POST /internal/v1/import-batches/source-orders`（Bearer 服务身份，operator 固定为服务名），内部直接转发同一 `SourceImportService.upload`。改动面：一个薄 Controller（参照 `InternalOrderController`），**不动领域层与导入逻辑**。收益：脚本不再持有网关 Basic Auth 凭据，符合 `api-contract` §2 `/internal/v1` 命名空间设计。

> 明确不建议：新增「按服务器路径导入」端点（路径穿越风险，且与既有 byte-upload 形态不一致）；不建议 Phase 0 就做目录监听（工作量大，Phase 1 Java Connector 会取代它的价值）。

### 3.3 调度方案

| 项 | 方案 |
|---|---|
| 工具 | macOS `launchd`（当前开发机）或 Linux `cron`（部署机）；封装脚本 `scripts/run_platform_pull.sh`（顺序执行三平台，含重试与状态汇总） |
| 触发时间 | 工作日/每日 08:30 起，三平台**错峰** 10 分钟：彩食鲜 08:30 → 聚福宝 08:40 → 飞象 08:50（避免三平台同时登录/拉取被限流，也错开平台高峰期） |
| cron 表达式 | 彩食鲜 `30 8 * * *`、聚福宝 `40 8 * * *`、飞象 `50 8 * * *`（`launchd` StartCalendarInterval 等价）；午间补拉（可选）`0 13 * * *` |
| 拉取窗口 | 默认「近 30 天 → 当日」（与脚本现状一致），增量口径后续由 Phase 1 游标承接 |
| 失败重试 | 通道内重试 2 次（5min/15min 退避）→ 仍失败写 `ingest/status/last-run-{channel}.json`（status=failed + 错误摘要）→ 退出码非 0 → launchd/cron 触发告警 |
| 告警 | Phase 0 先落日志 + 状态文件 +（可选）cron 邮件；接入企微发消息需确认通道能力（见 §8 决策点 D3）；Phase 1 复用 `OperationalAlert` 实体（§6.3） |
| 防重入 | 状态文件锁（`flock` 或 manifest 时间戳检查）：同通道并行运行只允许一个，避免同日重复批次 |
| 观察点 | `ingest/status/*.json` 聚合三通道健康度；上传后回填 `manifest.upload_batch_id` 形成血缘闭环 |

### 3.4 三平台各自动作清单

#### 3.4.1 彩食鲜 —— export 模式（现状脚本，仅目录/命名对齐）

1. `POST /ucenter/login/scc` 登录 → 响应头取新 `login-token`（脚本已实现，每次运行自动续期）；
2. 显式带 `supplier-code: 20075684`（主供应商，登录后默认可能落在「基地」供应商 `20070589`，脚本已显式指定）；
3. `POST /scc/bbc/order/exportDeliverExcl`（`orderStatus=3` 待发货）→ `data` = 任务 ID；
4. 轮询 `GET /task/task/my`（完成判定 `taskStatus==2 && progress==100/100 && resultCode==200000`，5s 间隔，最多 20 轮）；
5. `GET /task/file/download?name&url`（`taskAttach[0].url`，JSON 字符串）→ 校验 `PK` 魔数 → 落盘 ingest；
6. **落盘文件即为规范 v1 指纹彩食鲜格式（21 列）→ 现有 `SourceFileParser` 直接识别，Connector 零改动**；
7. 上传 → ImportBatch → 人工确认。

#### 3.4.2 聚福宝 —— JSON→Excel 转换器设计（Phase 0 新增脚本）

聚福宝**没有导出接口**，JSON 直连产物无法直接喂给现有解析器，需要一次转换。设计要点：

- 新增 `scripts/jufubao_fetch_orders.py --convert`（或独立 `scripts/jufubao_json_to_excel.py`），输入 `聚福宝订单-{tab}-{yyyyMMdd}.json`，输出 `.xlsx`；
- **输出表头必须命中 excel-closed-loop-spec §3.2 聚福宝指纹**（`主单号`/`拆单号`/`供货商`/`渠道订单号`/`结算方式`/`需结算总额` 六列**必须存在**，值可空），同时按 §4.2 输出完整可回填列集合，这样**零 Java 改动**即可进现有闭环：
  - 指纹只要求表头存在，不要求值非空 → `渠道订单号`/`结算方式`/`需结算总额` 在 JSON list 对象中缺失时输出空列，不阻塞识别；
  - **收货人缺口（未确认项，见 §8 决策点 D4）**：`orders/query` list 对象不含收货人 → 转换器输出 `收货人姓名/收货人电话/收货地址` 空列 → 按规范缺姓名或电话 → 每行 `NEED_REVIEW`（`CUSTOMER_MATCH` 前先被必填校验卡住）。**这是 Phase 0 聚福宝的主要代价**：全部订单进入待复核，人工补收货人后才能确认批次。
- 列映射表：

| JSON 字段 | 输出列（Excel 规范 §4.2） | 说明 |
|---|---|---|
| `main_order_id` | `主单号` | 去除尾随制表符（转换器生成，天然无） |
| `sub_order_id` | `拆单号` | 同上 |
| `supplier_name` | `供货商` | 指纹必需列，值为 `京诚乾元` |
| `created_time` | `下单时间` | epoch→Asia/Shanghai `yyyy-MM-dd HH:mm:ss` |
| （缺口） | `收货人姓名/收货人电话/收货地址` | 空 → NEED_REVIEW，见上 |
| `product_list[].product_id` | `商品ID` | |
| `product_list[].product_name` | `商品名称` | |
| （缺口） | `商品编码/商品条码` | 空 |
| `product_list[].product_num` | `数量` | 必须 > 0 |
| （缺口） | `订单备注` | 空 |
| （缺口） | `渠道订单号/结算方式/需结算总额` | 指纹必需列，空值 |
| 回填区 | `是否发完/发货数量/快递公司/快递单号` | 转换时全空，仅由 SourceReturn Adapter 写 |

- 幂等：转换器输出 sha256 与 JSON 输入绑定（内容哈希幂等在上传层生效）。

#### 3.4.3 飞象 —— 直下（现状脚本，仅目录/命名对齐）

1. `GET /welcome/index/` 引导会话（种 `fxqf_sess`）→ `POST /welcome/index/` 表单登录 → 302 落点非登录页即成功；
2. `GET /order/deliveryExport?start_time&end_time` → 校验 `PK` 魔数（响应 `application/vnd.ms-excel`，误命名 `.csv` 实为 XLSX）→ 落盘；
3. 落盘文件命中**飞象 v1 指纹**（`订单号`/`订单商品ID`/`可发货数量`/`物流状态`/`物流公司`/`物流单号`）→ 现有 v1 解析复用，Connector 零改动；
4. （可选）拉前自检 `POST /order/ajaxOrderNum`（区间订单数 > 0 才导出）。

### 3.5 Phase 0 验收标准

1. 三脚本落盘 `ingest/`，命名与 manifest 符合 §3.1 规范；
2. 端到端：拉取 → 上传 → ImportBatch 生成 → 操作员确认 → 履约导出生成（与人工导表路径同一条链路）；
3. 同一文件重传返回原批次（内容哈希幂等）；
4. 彩食鲜/飞象文件指纹零命中变更；聚福宝转换文件命中现有聚福宝指纹；
5. 连续 3 个工作日定时运行无人工干预；任一次失败有状态文件 + 日志留痕；
6. 凭据仅存 `.env`（gitignored），脚本日志无明文密码/token。

---

## 4. Phase 1 细化：Java Connector 在线 Pull

### 4.1 接口演进（api-contract §6.2 落地）

现状：`PlatformConnector` 只有 `channel()/capabilities()/testConnection(runtime)`；§6.2 的 `pullOrders/pullOrderChanges/pullCancellations/transform/pushShipmentResult` **在代码中尚不存在**（已核实，`PullCursor` 等类型均未定义）。

**建议（兼容演进，不动现有三个子类）**：在 `PlatformConnector` 增加 default 方法，默认返回 `CONNECTOR_CAPABILITY_UNAVAILABLE`（与 `ExcelPlatformConnector` 现状语义一致）；`ExcelPlatformConnector.capabilities()` 维持 `(true,true,false,false,false)`；各平台子类覆盖 `capabilities()` 置 `onlinePull=true` 并实现方法。

新类型（**签名级建议，非完整代码**；新增于 `connector` 包，均为 record/接口）：

```java
// —— 拉取契约（api-contract §6.2 形态）——
public record PullCursor(
        String watermark,          // 上次成功拉取位点（各平台自有语义，见 §4.6.2）
        String pageToken,          // 分页游标（聚福宝 next_page_token；首页 "1"/null）
        OffsetDateTime since,      // 区间起点（Asia/Shanghai）
        OffsetDateTime until,      // 区间终点（默认 now）
        Map<String, String> extra) {}   // 平台专属筛选（彩食鲜 orderStatus 等）

public record PullResult(
        SourceChannel channel,
        List<SourceOrderEnvelope> orders,
        String nextCursor,         // 有剩余页时为分页游标，页拉完为新的 watermark
        int pulledCount,
        OffsetDateTime pulledAt,
        PullStatus status,         // enum PullStatus { OK, PARTIAL, EMPTY, FAILED, CAPABILITY_UNAVAILABLE }
        String businessCode,       // 失败时的稳定内部码（PlatformErrorMapper 输出）
        String message) {}

public record SourceOrderEnvelope(
        SourceChannel channel,
        String sourceRef,          // 主单号（聚福宝 main_order_id / 彩食鲜主订单编号 / 飞象订单号）
        String sourceLineRef,      // 拆单号/子单号（聚福宝 sub_order_id / 彩食鲜子订单编号）
        String sourceVersion,      // 平台侧版本/状态快照（如 order_status 枚举）
        OffsetDateTime orderedAt,
        Map<String, Object> raw,   // 平台原始字段快照（审计与复核证据，脱敏后入库）
        CanonicalOrderInput draft) {}   // transform 的产物，直接喂 Layer 3

// —— 回传契约（Phase 2）——
public record SourceShipmentResult(
        SourceChannel channel,
        String sourceRef, String sourceLineRef,   // 来源引用（只用内部标准字段，不泄露平台列名）
        BigDecimal actualShippedQuantity,
        String outcome,                            // FulfillmentOutcome
        String carrierOutputValue,                 // 来源渠道承运商输出值（carrier_mappings 映射后）
        String firstTrackingNo,
        String exceptionReason) {}

public record SourceSyncResult(
        boolean success, String businessCode, String message,
        String platformRef, OffsetDateTime syncedAt) {}

// —— 平台 HTTP 会话（认证管理，见 §4.2）——
public interface PlatformHttpClient { /* 单方法语义：execute(request) 自动带会话头 */ }
public final class PlatformSessionManager {   // 每渠道单例
    // getSession(channel) —— 内存缓存 token/cookie；过期/401 时续期；
    //   续期用 ReentrantLock 保证并发安全（单实例）；多实例部署需 Redis 锁（一期不引入）
}
public interface PlatformErrorMapper {
    // map(channel, httpStatus, platformCode, body) -> PlatformError(内部稳定码, 是否可重试, 是否需重登)
}

// —— Connector 能力开关（沿用现有字段）——
// ConnectorCapabilities(fileImport, fileExport, onlinePull, onlinePush, callback)
//   Phase 1 目标：彩食鲜 (true,true,true,false,false)、聚福宝 (true,false,true,false,false)、
//                 飞象 (true,true,true,false,false)
```

> `CanonicalOrderDraft` 建议**直接复用现有 `CanonicalOrderInput`**（`order/dto/CanonicalOrderInput.java`，含 `source/sourceRef/sourceVersion/customer/receiver/items/settlement/remark/evidenceRefs`），避免平行 DTO 造成映射漂移；`transform` 签名仍可写 `CanonicalOrderInput transform(SourceOrderEnvelope)`。

### 4.2 认证 / 会话管理（公共能力）

| 平台 | 凭据形态 | token 有效期 | 续期策略 | 并发安全 |
|---|---|---|---|---|
| 彩食鲜 | 自定义头 `login-token`（JWT）+ `supplier-code` | exp 未知（契约未确认） | **每次 pull 前登录续期**（成本极低，与脚本一致）；缓存 + 业务请求 401 时重登一次 | `PlatformSessionManager` 内 `ReentrantLock` per channel |
| 聚福宝 | Cookie `JFB_SESSION_CID` + `JFB-ADMIN-ACCESS-TOKEN` + CSRF 双提交 + `X-Jfb-Project-Id: supplier` | access ~12.8h / refresh 15d | 内存缓存 access；过期用 refresh 续期；refresh 失败回退完整登录；请求 401 → 续期重试一次 | 同上 |
| 飞象 | Cookie `fxqf_sess` | 1 天（Max-Age=86400） | 每次 pull 重新登录（脚本同策略，天然规避） | 同上 |

统一约束：
- token/cookie **只存内存**，重启即失效，绝不落盘；
- 登录请求体/响应头、日志一律脱敏（只打 username，不打 password/token/cookie）；
- 登录也写 AuditLog（`service=connector.{channel}, operation=login`）。

### 4.3 聚福宝（试点）—— 方法级设计

#### 4.3.1 认证（JufubaoAuthClient）

```
JufubaoAuthClient
  String bootstrapSession()            // GET g.jufubao.cn/ → JFB_SESSION_CID
  Session login(String username, String password)   // POST /idaas-auth/v1/login-by-username
                                             //   form {username,password,system:"supplier"}
                                             //   返回 (ACCESS, REFRESH, CSRF) 三 cookie + expireIn
  Session refresh(String refreshToken)  // 可选：走 idaas refresh 端点（契约待补抓，见 §8 决策点 D5）
  boolean isExpired(Session)            // 依据 access_token_expire_in / 401
```

#### 4.3.2 pullOrders（拉新单）

- 请求：`POST /order-supplier/v1/orders/query`，body：

```json
{"tab":"no_delivery",
 "filter":{"created_time_range":{"start_time":<watermark起点epoch>,"end_time":<now epoch>}},
 "page_token":<cursor.pageToken 或 "1">,"page_size":20,"system":"supplier"}
```

- 实现要点：
  1. `cursor.since` 取 `max(上次 watermark, now - 30 天)`（首跑全量近 30 天，与脚本一致）；
  2. 循环翻页：`next_page_token` 为空即末页；每页之间 sleep（限流，见 §4.7）；
  3. 每单构造 `SourceOrderEnvelope`（raw 保留平台原始对象）→ `transform` 见 §4.3.5；
  4. 返回 `PullResult(status=OK, nextCursor=新watermark=本次end_time)`。
- 游标持久化：`connector_configs.last_pull_at`（表已存在）+ 专用 `platform_pull_cursors` 表（若需多游标：orders/delivered/all 各自水位，见 §8 决策点 D6）。

#### 4.3.3 pullOrderChanges（拉变更）

- 请求同 `orders/query`，但 `tab=delivered` + `tab=all` 差分：
  1. 拉 `tab=delivered` 区间（上次 watermark → now）；
  2. 与本系统已入库订单比对（`source_channel='JUFUBAO' AND source_ref=main_order_id`），识别「本系统为待发货、平台已发货」的订单；
  3. 产出变化信号：**不直接改单**——写入 OrderEvent（如 `SOURCE_STATUS_CHANGED`）并创建 ReviewCase（reason_code 建议 `SOURCE_STATUS_CHANGE`，需扩原因码白名单，见 §8 决策点 D7），由操作员决定后续动作（如补货、取消、标记）。
- 备选（更轻）：`tab=all` 全量比对状态枚举（`order_status_name`），仅对状态变化的单产生信号。

#### 4.3.4 pullCancellations（拉取消）

- 依赖平台状态枚举中的取消态（契约目前只确认 `NO_DELIVERY`/`delivered` 等，**取消态枚举值未确认**，见 §8 决策点 D8）；
- 实现：`tab=all`（或专用取消 tab，待确认）→ 过滤取消态订单 → 与本系统 `CANCELLED`/活动订单比对 → 差异产生取消信号（ReviewCase/OrderEvent）；
- **边界**：本系统一期不允许自动取消已确认订单（CONTEXT.md：任何路径都禁止自动修改或取消已确认订单），取消信号只进人工队列。

#### 4.3.5 transform 字段映射表（对照 excel-closed-loop-spec §4.2）

| `orders/query` JSON 字段 | CanonicalOrder 概念 | CanonicalOrderInput 字段 | 对应 Excel 闭环列 |
|---|---|---|---|
| `main_order_id` | source order reference | `sourceRef` | `主单号` |
| `sub_order_id` | source line reference | `items[].sourceLineRef` | `拆单号` |
| `product_list[].product_id` | source product reference | `items[].sourceSkuRef` | `商品ID` |
| `product_list[].product_name` | source product snapshot | `items[].productName` | `商品名称` |
| `product_list[].product_num` | requested quantity | `items[].quantity`（`"3.000"` 十进制字符串） | `数量` |
| `supplier_name` | 来源上下文 | `evidenceRefs` 或 remark 快照 | `供货商` |
| `order_status=NO_DELIVERY` | 待发货状态 | `sourceVersion`（快照，不驱动状态机） | `物流状态` |
| `created_time` | source ordered at | `settlement.settlementTime` 或 evidence | `下单时间` |
| `delivery_method` | 配送方式上下文 | evidenceRefs | — |
| `total_amount/purchase_amount` | 金额上下文 | evidenceRefs（`NUMERIC` 分→元换算需确认） | `需结算总额` |
| **收货人（缺口）** | Receiver | `receiver`（**缺失** → 进入 NEED_REVIEW） | `收货人姓名/电话/地址` |
| `product_sku_id` | 扩展 SKU 引用 | evidenceRefs | `商品编码/商品条码` |

映射规则遵循规范：
- 数量必须 > 0 且最多三位小数，否则 `NEED_REVIEW`（`IMPORT_VALIDATION`/`QUANTITY_SCALE`）；
- 来源产品只走显式 SKU 映射（`source_channel_skus`），未命中 → `SKU_MATCH` ReviewCase；
- 收货人缺失（当前事实）→ `CUSTOMER_MATCH` 前先缺字段 → 整单 NEED_REVIEW；**补抓 `sub-order-info` 后（决策点 D4）消除**。

### 4.4 彩食鲜 —— 实现要点

- 定位：**Excel 导出任务链路 Java 重放 + orderList JSON 仅做统计**（明细缺口不阻塞，见 §8 决策点 D9）。
- `pullOrders` 实现链：
  1. 登录（§4.2）→ `POST /scc/bbc/order/exportDeliverExcl`（`orderStatus=3`）→ taskId；
  2. 轮询 `GET /task/task/my`（完成判定同脚本；轮询间隔 5s、上限 20 轮，失败依据 `taskResult/taskMessage`）；
  3. `GET /task/file/download?name&url` → `byte[]` → **直接复用 `SourceFileParser.parse(byte[])`**（已核实：Parser 接收 byte[]，零改动）→ `ParsedSourceFile` → 逐行构 `CanonicalOrderInput`（复用 `SourceImportService` 中现有 canonical 映射逻辑，建议抽成共享方法）；
  4. `orderList` JSON：`POST /scc/bbc/order/orderList` 拉主订单级数据，**只用于**：状态计数（`data.number`）、增量比对信号、`testConnection` 真实探测；不产生业务订单（明细缺失）。
- `pullOrderChanges`：orderList `number` 差分（deliveryNum/canceledNum 变化）→ 触发人工关注信号；精确行级变更依赖导出差分。
- `pullCancellations`：orderList `canceledNum` 变化 → 信号；具体取消单需导出/详情接口（契约未确认）。

### 4.5 飞象 —— 实现要点

- 定位：**cookie 登录 + deliveryExport 直下 + 复用 v1 文件解析**（无 JSON 接口，在线 pull 的价值 = 自动化，形态仍是文件）。
- `pullOrders`：登录（§4.2）→ `GET /order/deliveryExport?start_time&end_time` → 校验 `PK` 魔数 → `byte[]` → `SourceFileParser.parse(byte[])`（v1 指纹）→ `CanonicalOrderInput`；
- 拉前自检：`POST /order/ajaxOrderNum`（区间订单数 > 0）；返回非 200/HTML → 判定未登录或参数问题；
- `pullOrderChanges/pullCancellations`：v1 文件含 `物流状态/物流单号` 回填列 → 差分本系统已入库订单的运单列变化，产生变更信号；取消依赖文件中的状态列（`订单状态` 21 列含「订单状态」列，需确认枚举）；
- 由于无 JSON，**建议飞象长期维持「文件模式 + 自动拉取」**（在线 pull 三件套仅实现 pullOrders 文件化版本），与评估稿 §4.3 结论一致。

### 4.6 幂等设计（订单号去重 / 游标 / ImportRevision）

#### 4.6.1 订单号去重（DB 层保底）

- `orders` 已有 `UNIQUE (source_channel, source_ref)`：在线 pull 落库时，同一 (渠道, 主单号) 第二次出现将触发唯一冲突；
- 策略：落库前 `SELECT 1 FROM orders WHERE source_channel=? AND source_ref=?`（或捕获 `DuplicateKeyException`）→ **已存在则跳过该单并记 AuditLog**（business_code=`ORDER_ALREADY_EXISTS`），不重复建单、不覆盖；
- 与内容哈希幂等的层级关系：文件路径幂等（同内容重放）→ 批次幂等（`uq_import_content_scope`）→ 订单幂等（sourceRef UNIQUE）→ 应用层幂等（IdempotencyService）。在线 pull 若跳过 `import_batches` 直接调 `createImported`，仍需批次 id——**因此在线 pull 也走 `SourceImportService`（或等价的批次服务），先建批次再建单**，四层幂等全部生效。

#### 4.6.2 游标语义

| 平台 | 游标 = | 推进时机 | 存储 |
|---|---|---|---|
| 聚福宝 | `last_pull_at`（成功拉完的 until 时间点） | 整轮 pull 成功且落库后 | `connector_configs.last_pull_at`（已存在） |
| 彩食鲜 | 上次导出任务完成时间 / payTime 水位 | 下载 + 解析成功 | 同上 |
| 飞象 | 上次导出 end_time | 下载 + 解析成功 | 同上 |

- 分页游标（page_token）**不持久化**，只存在单次 PullResult 内；失败重拉从水位重来（天然幂等）；
- 变更/取消各自维护水位（或统一单水位 + 全量差分），见决策点 D6。

#### 4.6.3 与 ImportRevision 的关系

- 每次自动拉取 = `import_mode=NEW` 的新批次（`batch_no=PULL-{channel}-{yyyyMMddHHmm}`）；
- 同内容重放（sha256 相同）→ 返回既有批次（`uq_import_content_scope`），不重复建单；
- 平台侧修正（人工改单后需重导）→ 操作员显式以 `REVISION` 上传并关联父批次，系统不猜测；
- **状态变化不通过重导覆盖**：拉取发现订单状态与库内不一致 → 只产生 OrderEvent / ReviewCase 信号（§4.3.3），由人工走现有修正路径（纠正单/取消/补发），符合不可变原则。

### 4.7 限流 / 重试 / 平台错误码转换

#### 4.7.1 限流

- 全局口径：**每平台每日拉取 ≤ 2 次**（合规与防封，契约文档风险表一致）；拉取最小间隔（如 12h）由配置控制；
- 页间隔：聚福宝翻页间 sleep 500ms；彩食鲜任务轮询 5s（平台页面即此频率）；飞象单次直下无需页间隔；
- 所有平台统一 `Retry-After`/429 感知：平台返回限流码时退避并终止本轮（下一轮再拉）。

#### 4.7.2 重试

| 错误类别 | 判定 | 动作 |
|---|---|---|
| 瞬态网络 | connect timeout / reset / 5xx | 重试 3 次，指数退避（1s/2s/4s + 抖动） |
| 鉴权失效 | 401 / 302 回登录页 / 平台 auth 错误码 | 续期/重登 1 次后重放当前请求 |
| 业务错误 | 平台业务码明确拒绝（如 `InvalidArgument`） | 不重试，`PullResult(status=FAILED)` + 留痕 |
| 任务失败（彩食鲜） | `taskStatus=2` 但 resultCode≠200000 | 重发导出任务 1 次，仍失败转人工 |

#### 4.7.3 平台错误码 → 内部稳定码

| 平台 | 平台错误形态 | 内部 business_code |
|---|---|---|
| 彩食鲜 | 响应 `code≠200000` | `PLATFORM_BUSINESS_ERROR`（可带 message） |
| 彩食鲜 | 登录无 `login-token` 响应头 | `PLATFORM_AUTH_MISSING_TOKEN` |
| 聚福宝 | 登录响应无 `access_token_cookie_key` / cookie 缺失 | `PLATFORM_AUTH_FAILED` |
| 聚福宝 | `{"code":"InvalidArgument",...}` | `PLATFORM_REJECTED`（不重试） |
| 飞象 | 302 停留登录页 | `PLATFORM_AUTH_EXPIRED` |
| 飞象 | 下载非 `PK` 魔数（拿到 HTML） | `PLATFORM_NOT_LOGGED_IN` / `PLATFORM_RESPONSE_INVALID` |
| 通用 | HTTP 429 | `PLATFORM_RATE_LIMITED` |
| 通用 | 5xx/超时 | `PLATFORM_UNAVAILABLE`（可重试） |

映射表由 `PlatformErrorMapper` 实现；`connector_configs.last_error` 记录最近一次失败的 business_code + message（表已存在）。

---

## 5. 配置与凭据

### 5.1 app.* 配置项清单（建议新增于 application.yml，沿用 `app.jd.*` 的 env 注入模式）

```yaml
app:
  platform:
    pull:
      enabled: ${PLATFORM_PULL_ENABLED:false}        # 总开关；默认关，与 JD write-mode 同理 fail-closed
      cron: ${PLATFORM_PULL_CRON:0 30 8 * * *}       # Java @Scheduled 入口（Phase 1 替换 Python cron）
      operator: ${PLATFORM_PULL_OPERATOR:system:platform-pull}
      retry-max: ${PLATFORM_PULL_RETRY_MAX:3}
      min-interval-hours: ${PLATFORM_PULL_MIN_INTERVAL_HOURS:12}
    caishixian:
      enabled: ${CSX_PULL_ENABLED:false}
      api-base-url: ${CSX_API_BASE_URL:https://wapi.freshfood.cn}
      portal-url: ${CSX_PORTAL_URL:https://scc.freshfood.cn}
      username: ${CSX_USERNAME:}                     # 密钥：仅环境变量
      password: ${CSX_PASSWORD:}                     # 密钥：仅环境变量
      supplier-code: ${CSX_SUPPLIER_CODE:20075684}
      business-code: ${CSX_BUSINESS_CODE:fe-web-scc}
      order-status: ${CSX_ORDER_STATUS:3}
      export-page-size: ${CSX_EXPORT_PAGE_SIZE:10}
      task-type: ${CSX_TASK_TYPE:csx-b2b-supplier-schedule}
      poll-interval-ms: ${CSX_POLL_INTERVAL_MS:5000}
      poll-max-rounds: ${CSX_POLL_MAX_ROUNDS:20}
    jufubao:
      enabled: ${JFUBAO_PULL_ENABLED:false}
      api-base-url: ${JFUBAO_API_BASE_URL:https://supplier-apis.jufubao.cn}
      portal-url: ${JFUBAO_PORTAL_URL:https://g.jufubao.cn}
      username: ${JFUBAO_USERNAME:}                  # 密钥：仅环境变量
      password: ${JFUBAO_PASSWORD:}                  # 密钥：仅环境变量
      page-size: ${JFUBAO_PAGE_SIZE:20}
      page-sleep-ms: ${JFUBAO_PAGE_SLEEP_MS:500}
      pull-window-days: ${JFUBAO_PULL_WINDOW_DAYS:30}
    feixiang:
      enabled: ${FEIXIANG_PULL_ENABLED:false}
      base-url: ${FEIXIANG_BASE_URL:https://ziyousupplier.wowcarp.com}
      username: ${FEIXIANG_USERNAME:}                # 密钥：仅环境变量
      password: ${FEIXIANG_PASSWORD:}                # 密钥：仅环境变量
      pull-window-days: ${FEIXIANG_PULL_WINDOW_DAYS:30}
      precheck-order-count: ${FEIXIANG_PRECHECK_ORDER_COUNT:true}
```

- 同时补齐 `.env.example`：新增 `CSX_*`/`JFUBAO_*`/`FEIXIANG_*`/`PLATFORM_PULL_*` 条目（值留空）；
- 三平台 `transport_mode` 切换由 `connector_configs` 表（`PATCH /api/v1/connectors/{channel}`）控制，与 `app.platform.*.enabled` 双开关：**两开关同时为真才执行在线拉取**（fail-closed，参照 JD write-mode 治理风格）。

### 5.2 密钥处理

- 平台密码只存环境变量（`.env` / docker-compose `env_file`，均为 gitignored）；`connector_configs.config.credential_secret_ref` 存引用名（如 `env:CSX_USERNAME`），**不存明文**；
- token/cookie 仅 `PlatformSessionManager` 内存缓存；日志、AuditLog request/response 一律脱敏（`***`）；
- 现有 `data-local/*-credentials.txt` 凭据文件保持 gitignored 并定期改密；HAR 副本不提交（已有 `.gitignore` 覆盖 `*.har`）。

---

## 6. 审计与监控

### 6.1 AuditLog 记录点（沿用 `AuditLogService`，`service=connector.{channel}`）

| 事件 | operation | 关键内容（脱敏） |
|---|---|---|
| 登录/续期 | `login` | username、成功/失败、耗时；**不含密码/token** |
| 拉单 | `pullOrders` | 游标/区间、平台、pulledCount、耗时、status |
| 拉变更 | `pullOrderChanges` | 同上 |
| 拉取消 | `pullCancellations` | 同上 |
| 落库批次 | （由 `source-file-import` / 批次服务记录） | 批次号、sha256、行数、ReviewCase 数 |
| 状态变化信号 | `sourceStatusSignal` | 订单号、旧/新状态、去向（ReviewCase/Event） |
| 回传（Phase 2） | `pushShipmentResult` | 来源引用、实际数量、运单号、平台响应、business_code |
| 失败 | （各 operation 内） | business_code=`PLATFORM_*`、message、last_error 同步 |

- 拉取任务运行身份：`operator=app.platform.pull.operator`（默认 `system:platform-pull`），`actorType` 用非 HUMAN 类型（现有枚举含哪些需确认，见决策点 D10）；
- 审计的 request/response 快照保留平台原始字段时先脱敏（收货人电话属 PII，按 `api-contract` §4.1 原则不在 HTTP 边界外暴露——拉取侧存 evidenceRefs 引用而非全文冗余，需评审确认）。

### 6.2 健康检查升级（testConnection → 真实探测）

现状：`ExcelPlatformConnector.testConnection` 只做配置存在性检查（EXCEL 模式返回 `EXCEL_ADAPTER_READY`）。Phase 1 建议：

| 平台 | 探测动作（只读、pageSize=1 或仅登录） | 成功判定 |
|---|---|---|
| 彩食鲜 | 登录 + `orderList`（page_size=1） | code=200000 且 data 可解析 |
| 聚福宝 | 登录 + `orders/query`（page_size=1） | 返回 `list` 字段 |
| 飞象 | 登录 + `ajaxOrderNum`（或 deliveryExport 头校验） | status=1 且返回 num |

- `transport_mode=EXCEL` 时维持现状返回（不误报）；`API + REAL` 且配置齐备时才发起真实探测；
- 探测结果写 AuditLog + `connector_configs.last_error`（失败时）；
- 探测频率受限流约束（人工点击触发为主，不自动高频探测）。

### 6.3 失败告警

| 级别 | 触发 | 通道（按可用性排序，见决策点 D3） |
|---|---|---|
| 黄 | 单次拉取失败但重试成功 / ReviewCase 增量异常 | 日志 + `ingest/status` / `OperationalAlert`（黄色提醒，不阻断） |
| 红 | 连续 ≥2 次拉取失败 / testConnection 连续失败 | 企微群消息（复用 wecom 通道需确认发送能力）/ 邮件 / 管理后台展示 |
| 灰 | 平台接口 404/结构变化（疑似改版） | 同上红级 + 人工介入契约重抓 |

Phase 1 起 `OperationalAlert` 实体（`OperationalAlertService` 已有）承载业务侧告警；基础设施级告警先走日志 + 状态文件。

---

## 7. 实施计划

### 7.1 分步任务清单

| 步骤 | 内容 | 产出 | 验收标准 | 依赖 |
|---|---|---|---|---|
| 0a | ingest 目录 + 命名规范落地；三脚本 `--out-dir ingest/`、文件名统一 | 规范文档（本文 §3.1）+ 脚本改造 | 三脚本落盘符合命名 + manifest 生成 | 无 |
| 0b | 上传对接（方案 A 脚本 curl；或方案 B `/internal` 端点） | `scripts/upload_ingest.sh` 或新 Controller | 端到端：拉取→上传→批次→确认→履约导出 | 决策点 D1 |
| 0c | 调度 + 重试 + 状态文件 + 告警 | `scripts/run_platform_pull.sh` + launchd/cron 配置 | 连续 3 天自动运行；失败有留痕与告警 | 0a/0b |
| 0d | 聚福宝 JSON→Excel 转换器（§3.4.2） | 转换脚本 + 单测（表头指纹用例） | 转换文件命中聚福宝指纹；上传进闭环；NEED_REVIEW 行为符合预期 | 0b |
| 1a | 接口演进：`PlatformConnector` 增 default 方法 + 新 DTO + `PlatformSessionManager`/`ErrorMapper`/限流重试 | 新类型（签名级）+ 单元测试 | 旧实现不受影响（default 返回 CAPABILITY_UNAVAILABLE）；单测覆盖 | 无 |
| 1b | 聚福宝 Connector（试点）：认证 + pullOrders + transform | `JufubaoConnector` + 单测 + REAL 冒烟 | REAL 模式端到端拉单→批次→确认；收货人缺口按决策点 D4 结果 | 1a、决策点 D4/D5/D8 |
| 1c | 彩食鲜 Connector：导出任务重放 + 文件解析复用 + orderList 统计 | `CaishixianConnector` + 单测 | 导出→轮询→下载→解析→批次全链路 | 1a |
| 1d | 飞象 Connector：cookie 登录 + 下载 + v1 解析复用 | `FeixiangConnector` + 单测 | 真实区间下载一次验证数据行结构 | 1a |
| 1e | Phase 1 调度（`@Scheduled` cron）+ 游标持久化 + last_pull_at/last_error 更新 | 调度器 + 单测 | 定时自动拉取；失败写 last_error | 1b/1c/1d |
| 1f | 健康检查升级（§6.2）+ AuditLog 补全 + 告警 | testConnection 真实探测 | 探测真实平台成功/失败均留痕 | 1b–1e |
| 2 | 回传 `pushShipmentResult`：聚福宝 multi-send 先行；彩食鲜/飞象补抓 | 回传实现 + 单测 + 冒烟 | 发货结果回传平台成功；幂等重放不重复回传 | 决策点 D11、1b |

### 7.2 里程碑

| 里程碑 | 内容 | 目标时间 |
|---|---|---|
| M0 | Phase 0 上线（自动拉表 + 上传 + 调度） | 本周（0a–0d，约 1–2 天） |
| M1 | 聚福宝 Java Connector 试点（拉单主链路） | 评估稿估 2–3 天（1a+1b） |
| M2 | 三平台在线拉单 + 调度 + 健康检查 | 1a–1f，约 +1 周 |
| M3 | Phase 2 在线回传（聚福宝先行） | 后续排期 |

### 7.3 风险登记

| 风险 | 说明 | 缓解 |
|---|---|---|
| 接口失效（无 SLA） | 私有接口，平台改版可能 404/改参 | 契约文档 + 平台实现隔离；`PlatformErrorMapper` 识别结构变化（灰级告警）；**回退链：Phase 1 → Phase 0 脚本 → 人工导表** |
| 凭据泄露 | 三平台明文密码 + 有效会话 | 仅环境变量/0600 文件；定期改密；HAR 不提交；日志脱敏 |
| 收货人缺失（聚福宝） | JSON 缺收货人 → 全单 NEED_REVIEW | 补抓 `sub-order-info`（决策点 D4）消除；期间人工复核 |
| 明细缺口（彩食鲜） | orderList 无商品明细 | 维持 Excel 导出模式为事实源；JSON 只做统计（铁律：Excel 不可变） |
| 飞象样本无数据行 | 样例仅表头 | 真实区间下载验证 v1 数据行结构（1d 验收项） |
| 时区/区间口径 | 聚福宝 epoch 起点时区、飞象 end_time 是否含当天未验证 | 与页面筛选/统计（ajaxOrderNum）交叉核对 |
| 状态变化误改单 | 拉取覆盖已确认订单 | 不可变原则：状态变化只进信号/ReviewCase，不静默改单 |
| 合规 | 供应商后台官方功能的接口化 | 每日 ≤2 次、不绕过限流、不抓权限外数据 |
| 双通道重复 | Phase 0 脚本与 Phase 1 Connector 并存时同日重复拉取 | 内容哈希幂等 + (channel, source_ref) UNIQUE + 拉取最小间隔 |

---

## 8. 依赖未确认信息的清单（需用户/业务确认）

| # | 决策点 | 现状 | 影响 | 建议 / 确认动作 |
|---|---|---|---|---|
| D1 | Phase 0 上传对接方式 | backend 无目录监听；仅 `/api/v1/import-batches/source-orders` | 决定 Phase 0 是否零 Java 改动 | **A（推荐，先做）**：脚本 curl 上传现有端点；**B**：新增 `/internal/v1/import-batches/source-orders` 薄端点（后续收口）。确认 A 是否可接受长期持有网关凭据 |
| D2 | 上传操作人身份 | `api-contract` §3.2：Nginx Basic Auth 主体为权威操作人 | 脚本上传需固定网关主体 | 确认：生产网关为拉取服务建立主体（如 `svc-platform-pull`），或复用管理账号 |
| D3 | 告警通道 | 现无对外发消息能力（wecom 目前只收不发） | 决定失败告警形态 | 确认是否建设企微发消息/邮件通道，或 Phase 0 仅日志 + 状态文件 |
| D4 | 聚福宝收货人字段 | `orders/query` list 无收货人；`sub-order-info`/`multi-send-form` 未抓包 | 无收货人 → 每单 NEED_REVIEW（Phase 0/1 均受影响） | **优先补抓** `sub-order-info`（一次抓包即可）；期间接受人工补收货人 |
| D5 | 聚福宝 refresh 端点 | refresh token 15 天，但 refresh 接口契约未抓 | 长期运行需 refresh，否则每次完整登录 | 补抓 idaas refresh 端点；或一期每次登录（成本低，可接受） |
| D6 | 游标粒度 | 单 `last_pull_at` vs 每 tab 独立水位 | 决定 pullOrderChanges 实现复杂度 | 一期单水位 + 全量差分；多水位后续再说 |
| D7 | 状态变化 ReviewCase 原因码 | 原因码白名单（excel-closed-loop-spec §11）无「来源状态变化」 | pullOrderChanges 的信号去向 | 确认新增 `SOURCE_STATUS_CHANGE` 原因码（属规范修订，需走评审） |
| D8 | 聚福宝取消态枚举 | 契约仅确认 `NO_DELIVERY` | pullCancellations 依赖取消态枚举值 | 补抓/文档确认订单状态枚举全集 |
| D9 | 彩食鲜明细缺口 | orderList 无明细；orderDetail 未抓包 | 是否支持纯 JSON 拉单 | 短期维持 Excel 导出模式；中期补抓 orderDetail 后再评估（评估稿决策点 2） |
| D10 | AuditLog actorType | 现有枚举（HUMAN 等）需确认 | 系统拉取任务的操作人类型 | 确认新增服务/系统 actor 类型或复用现有 |
| D11 | Phase 2 回传范围 | 聚福宝 multi-send 契约已确认；彩食鲜/飞象未抓包 | 决定回传实现范围 | 聚福宝先行；彩食鲜/飞象回传接口补抓后实施 |
| D12 | 飞象长期形态 | 无 JSON 接口 | 在线 pull 意义有限 | 确认飞象长期维持「自动拉取文件模式」（Connector 只做文件化 pullOrders） |
| D13 | 凭据管理 | 现状 env/.env（gitignored） | 决策点 5（评估稿） | 一期 env；密钥服务后续评估 |
| D14 | 调度归属 | Phase 1 Java `@Scheduled`（单实例）vs 独立 worker | 决定部署形态 | 一期 `@Scheduled` + 单实例（低频任务）；多实例需分布式锁 |

---

## 9. 与既有文档的关系

- **不推翻评估稿**：本文是其 Phase 0/1/2 的细化落地（目录/命名、导入入口、调度、类与方法级设计、配置/审计/监控、实施计划、风险、未确认清单）；
- **补充的新事实（本次代码核实）**：backend 无目录监听；`orders.source_import_batch_id` 非空 CHECK → 在线 Pull 也必须走批次；`connector_configs.last_pull_at/last_error` 已存在可直接用；`SourceFileParser.parse(byte[])` 支持 Connector 直接喂字节；`(source_channel, source_ref)` UNIQUE 为订单号去重兜底；`IdempotencyService` 已有 `ReadOnlyExternalWork + TransactionalCompletion` 模式天然适配「外部拉取 + 本地落库」；
- 实施前需要评审的规范面改动：聚福宝转换文件表头策略（§3.4.2，命中现有指纹则零改动）、`SOURCE_STATUS_CHANGE` 原因码（D7）、`/internal` 导入端点（D1 方案 B）。
