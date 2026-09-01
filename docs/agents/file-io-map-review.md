# 文件进出全链路调研（只读核查，2026-08-25）

> **为什么放在 `docs/agents/`**：本文是一份**内部评审 note**——对既有实现做只读核查、纠正若干口头前提、
> 给出最小补法建议，体裁与 `docs/agents/wecom-group-file-spike.md`、`docs/agents/platform-pull-single-flight.md`
> 一致（结论先行 + 证据表 + 精确边界）。不放 `docs/research/`：那里只收外部 API 一手资料，本文没有外部
> 抓包或厂商文档原文。不放 `.scratch/<topic>/`：那里是票稿，本文不提出票、不认领实现。
>
> **数据来源纪律**：本文所有「生产事实」一律来自 `ssh zimupc` → `docker exec zimu-fulfillment-postgres-1 psql`，
> 时间点 2026-08-25。**未使用任何 MCP 工具取数**（`mcp__fulfillment-hub-mcp__*` 指向本机库，与生产不是同一份数据，
> 本文不引用）。代码事实来自 worktree `~/zimu-work/main`，HEAD = `de9048e`。
>
> **精确行号为 2026-08-25 快照**：本文引用的文件路径与行号按当时 HEAD 固定，此后代码持续演进，行号会漂移——
> 不追行号，仅在引用的类/方法本身被删除或替代（会造成死链接而非单纯行号偏差）时才更新，见 §4.2/§5.1/§5.3
> 对 `TrackingResultWorkbookService`/`TrackingResultReplyService`（已随 c9829238 删除并被
> `SourceReturnWecomDeliveryService`/`RouteResolver`/`Scanner` 取代）的更新记录。

---

## 0. 一句话结论

**「发货后回填给订单平台的 Excel」早就存在、且京东履约的单也照样产出（生产库里已有一条实证）；
真正缺的是「发货前的待发清单」——系统只会为第三方履约方生成发货清单，京东 SDK 路由下一个文件都不产出，
而恰恰是京东路由承担了生产上全部的单。**

---

## 1. 只做一件事

> **给 `import_batches` 加一个「下载原始来源文件」的只读端点。**

理由：所谓「发货前的待发清单（收货人/商品/数量）」，其内容与格式，**就是该平台自己发过来/被拉下来的那份原文件**——
它已经完整落在 `import_batches.file_ref`（内容寻址存储，`file/ContentAddressedFileStore.java:22-42`），
生产上 16 个批次**每一个都有真实 file_ref**。但全仓**没有任何端点把它读出来**
（`file/SourceImportController.java` 只有 upload / get / confirm / rows，无 file 下载）。

- 落点：`file/SourceImportController.java` 加 `GET /api/v1/import-batches/{batch_id}/source-file`，
  复用 `ProviderFileService.FileDownload` 投影与 `TrackingFileController.sourceReturn`（`file/TrackingFileController.java:55-65`）
  同一套 `ContentDisposition` 处理。
- 前端：`SalesOutboundPage` 的导入批次抽屉加一个下载按钮（现有下载入口全部挂在 `fulfillment_exports` 行上，见 §4.3）。
- 成本：一个只读端点 + 一个按钮，不动任何写路径、不动模板、不触发任何履约副作用。

**不建议**一上来就造一套新的「待发清单模板」：那等于凭空定义第五种 Excel 格式，而飞象/大者要的恰恰是自己那张表。

---

## 2. 六条前提逐条核验

| # | 你的前提 | 判定 | 证据与修正 |
|---|---|---|---|
| 1 | 五个 Connector 的 `ConnectorCapabilities` 取值 | ✅ 数值全对，**但推论有误** | 见 §2.1 |
| 2 | `scheduleInitial` 只在 THIRD_PARTY 分支调用（526/592），JD 分支 0 次 | ✅ 完全正确 | 见 §2.2 |
| 3 | `WecomTrackingFileProcessor` 拒绝群聊文件 | ✅ 正确，**但拒绝点比你以为的靠后** | 见 §2.3 |
| 4 | 导出文件在批次确认那一刻生成 | 🟡 半对 | 见 §2.4 |
| 5 | 生产库现状（FEIXIANG 5 批次全 `confirmed=false` 等） | ❌ 多处不符 | 见 §2.5 |
| 6 | 表头指纹机制与 `WANGQI` 永不命中 | ✅ 正确，**有两处重要补充** | 见 §2.6 |

### 2.1 前提 1：数值全对，但「中汇/大者声明了 fileImport/fileExport」是错的

数值逐条核对无误：

| 声明点 | fileImport | fileExport | onlinePull | onlinePush | callback |
|---|---|---|---|---|---|
| `connector/caishixian/CaishixianConnector.java:61` | true | true | true | **true** | false |
| `connector/jufubao/JufubaoConnector.java:92` | true | true | true | **true** | false |
| `connector/feixiang/FeixiangConnector.java:50` | true | true | true | **false** | false |
| `connector/ExcelPlatformConnector.java:12` | true | true | false | **false** | false |
| `connector/wecom/WecomConnector.java:32` | false | false | false | false | true |

record 定义在 `connector/ConnectorCapabilities.java:4-9`。

**⚠️ 修正 A：`ExcelPlatformConnector` 是抽象类且没有任何子类。**
`connector/ExcelPlatformConnector.java:8` 是 `public abstract class`；全仓 `grep "extends ExcelPlatformConnector"` = **0 命中**。
三个在线渠道各自继承的是 `AbstractHttpPullConnector`（`FeixiangConnector.java:29`、`JufubaoConnector.java:38`、
`CaishixianConnector.java:36`），并各自覆写 `capabilities()`。

因此 **中汇（ZHONGHUI）与大者（DAZHE）根本没有 `PlatformConnector` bean**——
`connector/` 下与 ZHONGHUI 相关的只有 `connector/zhonghui/ZhonghuiPmsProperties.java`（PMS 上架，非履约），
DAZHE 一个文件都没有。它们的能力不是「声明为 (true,true,false,false,false)」，而是**根本没有声明**。

**⚠️ 修正 B：`fileImport` / `fileExport` 两个字段在全仓从未被读取。**
`capabilities()` 的消费点只有两处：
- `connector/sync/SourceShipmentSyncService.java:233,236,238` —— 只读 `onlinePush()`
- `connector/PlatformOrderRefreshService.java:312` —— 拉取编排

`fileImport()` / `fileExport()` / `callback()` 的 getter **零调用**。所以 "fileExport=true" 不构成任何能力保证，
它是文档性声明，不是门禁。真正决定「有没有文件可导」的是履约方类型与 `outboundMode`（§3.2），与来源渠道无关。

结论方向仍然成立：**飞象/中汇/大者确实没有在线回传**（前两者 `onlinePush=false`，后两者连 connector 都没有），
回执只能走文件。但这个结论的依据是「没有 push 实现」，不是「声明了 fileExport」。

### 2.2 前提 2：完全正确

| 事实 | 位置 |
|---|---|
| `wecomExportService.scheduleInitial(exportId, providerId, slaMinutes)` | `file/ProviderFileService.java:526`（`generateThirdParty`） |
| 同上，续发路径 | `file/ProviderFileService.java:592`（`generateContinuation`） |
| 注释「#84：…（JD 路径不调用 = 不入队）」 | `file/ProviderFileService.java:525` |
| `generateJd` 全方法体（617–682）无该调用 | `file/ProviderFileService.java:617-682` |

**补充**：分流发生在 `routeForSourceBatch`，判据是**履约方类型**而非来源渠道：
`file/ProviderFileService.java:108` `if ("JD_WAREHOUSE".equals(entry.getValue().getFirst().providerType()))`。
并且 JD 分支下还有二级分流（`:118-119`）：`outboundMode=SDK` → `createJdShipments`，**连文件都不生成**；
否则才 `generateJd`。生产上 provider 1 的 `config->>'outboundMode'` = `SDK`。

### 2.3 前提 3：正确，但群聊文件其实**已经进了队列**

拒绝点确实在 `file/WecomTrackingFileProcessor.java:51-54`：
```java
if (!"single".equals(source.chatType())) {
    throw new WecomTrackingFileException(
            WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_CHAT_UNSUPPORTED);
}
```

**⚠️ 补充**：上游的任务分派**不看 `chat_type`**——
`message/MessageSubmissionService.java:171`：
```java
return "file".equals(messageType) ? WECOM_TRACKING_FILE_TASK_TYPE : INTERPRET_TASK_TYPE;
```
只按 `message_type` 判。所以**群聊 file 消息会照样被登记成 `WECOM_TRACKING_FILE` 异步任务**，
一路走到 processor 才被拒。这对「放开群聊要动什么」有直接影响（§5.2）。

### 2.4 前提 4：批次确认只是三个产出时机之一

「批次确认 → 发货指令文件」这条成立：
`file/SourceImportController.java:46-65` → `SourceImportService.confirm` → `ProviderFileService.routeForSourceBatch:101`。
不确认，`candidateRows` 拿不到行（`file/ProviderFileService.java:1015` 要求 `ol.processing_stage='READY_TO_EXPORT'`），
确实没有导出。

**⚠️ 但另有两类文件在确认之外产出**：

| 产物 | 触发时机 | 位置 |
|---|---|---|
| 第三方续发文件 | 人工按需，`POST /api/v1/fulfillments/{id}/continuation-exports` | `fulfillment/FulfillmentController.java:57` → `file/ProviderFileService.java:563` |
| 来源回填文件 `source_return_exports` | **运单落库之后**，与确认无关 | `file/TrackingFileService.java:511`（`generateSourceReturn`）、`:751`（`finalizeReadySourceReturnsForShipment`） |

第二条尤其关键：给订单平台的那份 Excel **不是确认时产出的，是回运单之后产出的**（§4.1）。

### 2.5 前提 5：生产库现状多处不符

全部数据来自 `ssh zimupc`，2026-08-25：

| 你的说法 | 实际 | 证据 |
|---|---|---|
| `import_batches` 有 `confirmed` 字段 | ❌ 无此列，只有 `confirmed_at` / `confirmed_by` | psql 报 `column "confirmed" does not exist` |
| FEIXIANG 5 个批次 | ❌ 共 **16** 个批次：FEIXIANG **11**、CAISHIXIAN **5** | `SELECT … FROM app.import_batches` |
| 全部未确认 | 🟡 FEIXIANG 确实全未确认；但 **批次 8（CAISHIXIAN）已确认**，`confirmed_at=2026-08-25 11:40:36.992036+08`，`confirmed_by=zimu-admin` | 同上 |
| 批次 20 有 2 行 ACCEPTED、已建订单 | ✅ 正确 | `raw_import_rows`：批次 20 → 2 行 ACCEPTED；订单 3、4 |
| 这 2 单履约方 JD_WAREHOUSE、状态 SKU_MAPPED | ✅ 正确 | `fulfillments` 3、4 → provider 1（JD/SDK），`processing_stage=READY_TO_EXPORT` |
| `fulfillment_exports` 只有 1 条且是 JD_WAREHOUSE | ✅ 正确 | id=1，`EXP-C4D3E236…`，`template_version=jd-golden-694a6e7c614a`，`generated_by=zimu-admin`，`generated_at=2026-08-25 11:40:36.992036+08` |

**➕ 你没提到、但更重要的四条生产事实**：

| # | 事实 | 说明 |
|---|---|---|
| P1 | **`source_return_exports` 有 1 条**：id=1，`import_batch_id=8`，`is_final=t`，`version_no=1`，`generated_by=jd-tracking-poller` | 这就是「京东履约的单产出了给订单平台的 Excel」的直接实证 |
| P2 | **16 个批次的 `uploaded_by` 全是 `system:platform-pull`** | 生产上**零次人工上传**；全部来自在线拉取 |
| P3 | **16 个批次全部有真实 `file_ref` + `content_sha256`**（如批次 20 → `…/source-orders/8ccf60ea…xlsx`） | 在线拉取产出的批次与人工上传**形态完全一致**，不是「没有文件的结构化批次」 |
| P4 | `fulfillment_export_wecom_states` = 0 行，`fulfillment_export_wecom_deliveries` = 0 行 | 企微出站文件投递**从未在生产触发过**（与 §2.2 一致：没有第三方导出，就没有入队） |

另：`raw_import_rows` 全库仅 4 行（批次 1 → 1 行，批次 8 → 1 行，批次 20 → 2 行）；
其余 13 个批次 **0 行**。订单 2（批次 8）已 `SHIPPED`，shipment 1 出库单号 `202608250001`，
运单 `JDVA46707982590`（京东物流）。

### 2.6 前提 6：正确，两处补充

| 事实 | 判定 | 位置 |
|---|---|---|
| 靠表头指纹识别、不看文件名 | ✅ | `file/SourceFileParser.java:38`（类注释）、`:82-89`（遍历 `SourceChannel.values()` 逐个 `matches`） |
| DAZHE 指纹 15 列 | ✅ | `file/SourceFileParser.java:582-585`（逐列数得 15） |
| 匹配语义 `containsAll` | ✅ | `file/SourceFileParser.java:439-444`：`new HashSet<>(headers).containsAll(required)` |
| `WANGQI` 在 FINGERPRINTS 无条目 → 永不命中 | ✅ | `fingerprints()` 只 put 了 5 个渠道（`:575-587`）；`matches(headers, null)` 在 `:440-442` 直接返回 false |

**➕ 补充 A：`WANQI` 同样不在 FINGERPRINTS，但它另有一条精确有序通道，是会命中的。**
`file/SourceFileParser.java:423-427`：
```java
return channel == SourceChannel.WANQI
        ? WANQI_52_HEADERS.equals(headers)     // 有序全量相等
        : matches(headers, FINGERPRINTS.get(channel));
```
`WANQI_52_HEADERS` 定义在 `:49-57`。所以「不在 FINGERPRINTS = 永不命中」只对 WANGQI 成立，对 WANQI 不成立。

**⚠️ 补充 B：飞象 v2 的 12 列指纹只在 CSV 分支生效，xlsx 分支根本不参与匹配。**
`FEIXIANG_V2_FINGERPRINT`（`:45-47`，12 列）只出现在 `parseCsv`（`:127-132`）；
`parseWorkbook`（`:82-89`）遍历的是 `FINGERPRINTS`，其中飞象只有 v1 的 6 列。
后果：一份**飞象 v2 格式的 .xlsx** 会零命中并被拒。

---

## 3. 文件进出全地图

### 3.1 入口（文件进来）

| # | 入口 | 触发 | 类 / 方法 | 落表 / 存储 | 面向 | 前置条件 |
|---|---|---|---|---|---|---|
| I1 | 人工上传来源订单表 | `POST /api/v1/import-batches/source-orders` | `file/SourceImportController.java:28-39` → `SourceImportService.upload` → `SourceFileParser.parse:62` | `import_batches`(SOURCE_ORDER) + `raw_import_rows` + `orders`；原件 → `source-orders` 命名空间 | 内部 | 表头指纹**恰好**命中一个（`:415-417`）；关键表头不得重复（`:446-452`） |
| I2 | 在线拉取 · 彩食鲜 | `PlatformOrderRefreshService` 人工触发 | `connector/caishixian/CaishixianConnector.java:71-93` | **与 I1 完全同管线**：下载平台自身的导出 xlsx → `sourceImportService.upload(...)`（`:82-88`） | 内部 | `connector_configs` enabled + mode=REAL + transport=API |
| I3 | 在线拉取 · 飞象 | 同上 | `connector/feixiang/FeixiangConnector.java:60-93`（`:71-77` 调 upload） | 同 I1 | 内部 | 同上 |
| I4 | 在线拉取 · 聚福宝 | 同上 | `connector/jufubao/JufubaoConnector.java:116-143` → `SourceImportService.importStructured:283` | `import_batches` 但 `template_family='STRUCTURED'`、`file_ref='structured://'+batchNo`（`:323-332`）**⚠️ 占位，非真实文件** | 内部 | — |
| I5 | 履约方运单回传（后台上传） | `POST /api/v1/fulfillment-exports/{id}/tracking-imports` | `file/TrackingFileController.java:31-43` → `TrackingFileService.upload:103` | `import_batches`(PROVIDER_TRACKING) + `raw_import_rows` + `trackings`/`shipment_items`；原件 → `tracking-imports` | 内部 | `export_kind` 必须 THIRD_PARTY（`:118-120`）；精确 24 列且顺序一致（`:405-413`）；A2 单元格的导出批次号须命中既有 `fulfillment_exports`（`:421-441`） |
| I6 | 企微单聊 file 消息 | 企微回调 | `message/MessageSubmissionService.java:171` 入队 → `file/WecomTrackingFileWorker` → `WecomTrackingFileProcessor.process:45` → `TrackingFileService.parseForDraft:79` → `WecomTrackingFileDraftService.apply` | `message_media` + ProviderTrackingDraft + `review_cases`；**不建 import batch、不建 Shipment/Tracking**（`file/TrackingFileService.java:75-78` 注释） | 内部 | `chat_type='single'`（`WecomTrackingFileProcessor.java:51`）；同 I5 的 24 列 + 批次号门禁 |
| I7 | 履约方 SKU 映射参考预览 | `POST /api/v1/provider-sku-mapping-references/preview` | `file/ProviderSkuMappingReferenceController.java:21-28` | **不落库**（类注释 `:11`「只读映射预览」） | 内部 | — |
| I8 | 商品主图 | `POST` 上传 | `product/ProductImageController.java:26` | 内容寻址存储 | 内部 | — |

**I2/I3 vs I1 的异同（回答「在线拉取是否也建 raw rows」）**：
**完全相同**。彩食鲜/飞象的 `pullOrders` 做的是「登录 → 调平台的导出接口拿一份 xlsx 字节 → 丢进 `sourceImportService.upload`」，
operator 固定 `system:platform-pull`（`connector/AbstractHttpPullConnector.java:78-81`）。
产出物没有任何差别：一样建 `import_batches`、一样建 `raw_import_rows`、一样把原件存进内容寻址存储。
生产 P2/P3 两条事实是直接佐证。

**I4 是唯一的例外，也是唯一真正「没有源文件」的入口**（见 §6 风险 R1）。

### 3.2 出口（文件出去）

| # | 产物 | 触发时机 | 类 / 方法 | 落表 / 存储 | 面向 | 前置条件 |
|---|---|---|---|---|---|---|
| O1 | **第三方发货清单**（24 列，sheet 名「发货清单」） | 批次确认 | `file/ProviderFileService.java:484`（`generateThirdParty`）→ `thirdPartyWorkbook:1077`；表头 `THIRD_PARTY_HEADERS:50-53` | `fulfillment_exports`(THIRD_PARTY, `v1-24-columns`) + `fulfillment_export_items`；文件 → `fulfillment-exports` | **履约方** | 履约方 `provider_type != JD_WAREHOUSE`（`:108`） |
| O2 | **京东 77 列导单文件** | 批次确认 | `file/ProviderFileService.java:617`（`generateJd`）→ `jdWorkbook:684`；内置脱敏 golden 模板 `templates/jd-cold-chain-order-template.xlsx`（`:47-48`），`validateJdGolden:721` 校验 | `fulfillment_exports`(JD_WAREHOUSE) | **履约方（京东）** | provider_type=JD_WAREHOUSE **且** `outboundMode != 'SDK'`（`:118-121`）；数量须为正整数（`:110-114`，否则建 QUANTITY_SCALE 复核） |
| O3 | **（京东 SDK 路由）零文件** | 批次确认 | `file/ProviderFileService.java:118-119` → `createJdShipments:292` | 只建 `shipments` + `shipment_items` | — | `outboundMode='SDK'`。**生产 provider 1 就是这个配置** |
| O4 | 第三方续发文件 | 人工，`POST /api/v1/fulfillments/{id}/continuation-exports` | `fulfillment/FulfillmentController.java:57` → `ContinuationExportService`（`:73-75` 硬门禁 THIRD_PARTY）→ `file/ProviderFileService.java:563` | 同 O1 格式，新 `fulfillment_exports` 行 | **履约方** | 该 fulfillment 已部分发货（`ContinuationExportService.java:71`）**且**为第三方（`:73-75`） |
| O5 | **来源回填文件 SourceReturnExport** | **运单落库之后** | `file/TrackingFileService.java:511`（`generateSourceReturn`）：重新解析原始来源文件（`:584`），只改结果列（`:593-627`） | `source_return_exports` + `source_return_export_items`；文件 → `source-return-exports`；飞象输出 CSV，其余 xlsx（`:630-634`） | **订单平台** | 见下方触发点与门禁 |
| O6 | 回填结果工作簿（24 列同表头，后六列填实） | — | `file/TrackingResultWorkbookService.workbook:111`，行取自 `rows(shipmentId):62` | 无（返回 byte[]） | 来件人 | **⚠️ 全仓无生产调用方**，只有单测 |
| O7 | 回填结果回发企微 | — | `file/TrackingResultReplyService.replyForShipment:51` | 无 | 来件人原会话 | **⚠️ 全仓零调用方、零测试**（见 §4.2） |
| O8 | 彩食鲜在线回传上传件 | Shipment 级在线回传 | `connector/caishixian/CaishixianShipmentArtifactFactory.prepare:71`：从 `import_batches.file_ref` 原始工作簿裁出该 Shipment 的行 | 内存产物，≤1 MiB（`:48`），确定性 zip（固定时间戳 `:49-50`） | **订单平台（在线）** | 渠道必须是 CAISHIXIAN（`:72`）；须找到该 Shipment 的原始工作簿行（`:107-110`） |
| O9 | 来源回填文件在线推送 | 人工，`POST /api/v1/source-return-exports/{id}/push` | `file/TrackingFileController.java:74-82` → `SourceReturnPushService`（彩食鲜 importDeliverExcl / 聚福宝 multi-send，类注释 `:38-42`） | 更新 `source_return_exports.push_status` + `shipment_syncs`（`:107-117`） | **订单平台（在线）** | 仅彩食鲜/聚福宝；**⚠️ 前端无调用方**（§4.3） |
| O10 | 企微文件投递（把 O1/O4 发给履约方群） | O1/O4 同事务入队 | `file/FulfillmentExportWecomService.scheduleInitial:73` → `FulfillmentExportWecomDeliveryRunner` | `fulfillment_export_wecom_states` / `_deliveries` | **履约方群** | 履约方 `config.wecomGroupChatId` 已登记（`connector/wecom/WecomGroupChatResolver.java:26-30`）。**生产 provider 2 (TP) 的 config 是 `{}`，未登记** |
| O11 | 到期未收齐运单提醒 | 定时扫描 | `file/WecomTrackingReminderScanner`（类注释 `:10-17`） | `_deliveries`(REMINDER) | 履约方群 | export 状态 ACTIVE 且 `next_reminder_at<=now` |
| O12 | 京东出库单号导出 xlsx | 人工查询页 | `connector/jd/order/JdOrderController.java:74`，`GET /api/v1/jd-order/outbound-order-nos/export` | 无落库，仅审计（`:87,94`） | **内部** | — |
| O13 | 下载端点（把已生成文件取出） | 人工 | `GET /api/v1/fulfillment-exports/{id}/file`（`file/ProviderFileController.java:46-57`）<br>`GET /api/v1/source-return-exports/{id}/file`（`file/TrackingFileController.java:55-65`） | 记录下载审计 | 内部 | — |

**O5 的三个触发点**（这是全篇最容易被漏掉的部分）：

| 触发点 | 位置 | 适用路径 |
|---|---|---|
| 第三方运单回传上传事务内 | `file/TrackingFileService.java:192` | 文件路由（O1 → I5） |
| **京东运单回填之后** | `file/TrackingFileService.java:751`（`finalizeReadySourceReturnsForShipment`），被 `fulfillment/ShipmentJdTrackingBackfillService.java:306` 与 `:391` 调用 | **京东 SDK 路由与文件路由都覆盖** |
| 来源归因纠正后重生成 | `file/TrackingFileService.java:983`，被 `file/SourceAttributionService.java:158` 调用 | 归因纠正 |

`finalizeReadySourceReturnsForShipment` 的注释写得很直白（`file/TrackingFileService.java:753-754`）：
> `jd-real-sdk-switch 06：SDK 直连路由（05）的 shipment 没有 fulfillment_export_items，通过 shipment_items → fulfillments → raw_import_rows 反查来源批次`

**O5 的门禁**（`generateSourceReturn` 逐条返回 null / 抛错的条件）：

| 门禁 | 位置 | 行为 |
|---|---|---|
| 已有未失效的 final 版本 | `:516-529` | 幂等返回既有 id |
| 来源行有多个履约分片 | `:531-533` + `holdMultiPartitionSourceReturns:701` | 建 `MULTI_SHIPMENT_SOURCE_FOLLOWUP` 复核，返回 null |
| 渠道 = WANQI | `:534-536` | 直接返回 null（V42 迁移注明「来源回填契约尚未确认」） |
| 批次内还有未完成分片 / 未关闭复核 / 行数不齐 | `:581-583` | 返回 null |
| 渠道不在 6 个 case 内 | `:626` | `throw new IllegalStateException` |

支持回填的渠道共 6 个（`:593-627`）：CAISHIXIAN、JUFUBAO、FEIXIANG、ZHONGHUI、WANGQI、DAZHE。

---

## 4. 核心缺口：两份 Excel 到底有没有

### 4.1 现状对照表

| 时点 | 你要的内容 | 现在由什么产出 | 京东履约的单能不能拿到 |
|---|---|---|---|
| **发货前** | 待发清单（收货人/商品/数量） | **只有 O1（第三方 24 列发货清单）**，且它是给**履约方**的、不是给订单平台的 | **❌ 不能**。O3 路径零文件；即使退回 FILE 模式也只有 O2 那份 77 列京东模板，不能给飞象/大者 |
| **发货后** | 回填结果（+运单号/快递公司） | **O5 来源回填文件**，按平台**自己的原始列格式**回填 | **✅ 能，而且生产已经发生过一次** |

**「发货后」这一格的实证**：生产 `source_return_exports` id=1 →
`import_batch_id=8`（CAISHIXIAN，在线拉取）→ 订单 2 → fulfillment 2 → provider 1（**JD_WAREHOUSE / SDK**）→
`outcome=FULLY_FULFILLED`，`processing_stage=RETURN_FILE_READY`，`generated_by=jd-tracking-poller`。
**一条京东仓履约的单，产出了一份给订单平台的回填 Excel。**

所以「京东履约的单拿不到 Excel」这个痛点**只对发货前成立**，对发货后不成立。

**「线上拉取的单无从导出」这句也需要拆开看**：
- 对彩食鲜/飞象（I2/I3）：**不成立**。拉取产出的批次与人工上传形态完全一致，有真实源文件、有 raw rows，
  O5 照常工作（生产 id=1 就是拉取来的批次 8）。
- 对聚福宝（I4）：**成立**，但原因不是「在线拉取」，而是 `importStructured` 走了 `structured://` 占位 file_ref（见 §6 R1）。

### 4.2 【已解决，2026-08-25 同日】曾经漏掉的能力：两个写好但没接线的类，已删除并被真正接线的实现取代

> 本节记录的 `TrackingResultWorkbookService` / `TrackingResultReplyService` 在本报告完成
> 审核的**同一天**（commit `c9829238`）就被作者本人判定为「方向错误」并删除——它们绕开了
> 已有的 `SourceReturnPushService` 回填事务，且只覆盖「回原会话」这一种去处。下方两段按
> 「历史状态 → 最终解法」分别记录，`TrackingResultWorkbookService`/`TrackingResultReplyService`
> 引用的类名/文件已不存在于当前代码库，不要按原文路径去找。

**历史状态（本报告原始发现，已不成立）**：

| 类（已删除） | 能力 | 状态 |
|---|---|---|
| `file/TrackingResultWorkbookService`（commit `642727f`，2026-08-25 16:47） | 按 `shipmentId` 取 `trackings` + `shipment_items` 的落库事实，生成与 24 列发货清单**同表头**的回填结果 xlsx | 有单测（5 例），无生产调用方 |
| `file/TrackingResultReplyService`（commit `de9048e`，2026-08-25 16:50） | 上传该 xlsx 为企微临时素材并发回**原会话**（`originatingChatId` 由 `message_submissions` → `channel_messages` 反查） | 零调用方、零测试 |

**最终解法（当前代码，commit `c9829238`）**：飞象/大者/中汇这三个 `ConnectorCapabilities.onlinePush=false`
渠道缺在线回传去处的缺口，改由以下三个类补齐，且**已真正接线**（非孤立类）：

| 类 | 能力 |
|---|---|
| `file/SourceReturnWecomDeliveryService` | 把已生成的来源回填文件发到企微（人转交平台），与 `ShipmentJdTrackingBackfillService` 的回填事务**解耦**（回填是业务事实，送达回执失败不得反噬它）——不复用 `push_status`（那表达「平台已受理」，onlinePush=false 渠道永远走不到），V57 新增独立状态列区分「已发企微」与「平台已受理」两件不同的事 |
| `file/SourceReturnWecomRouteResolver` | 解析该发到哪个会话（不再局限于「回原会话」这一种去处） |
| `file/SourceReturnWecomScanner` | 异步扫描器领取待投递文件，与回填事务解耦的落点 |

**已遵守 retryable 纪律**：企微 ack 超时与提交后断线都可能已送达（`WecomSendResult` 契约），
因此只有 `retryable=true`（帧未提交）才回到可重试态，其余停在 `FAILED` 等人工判断——不像
已删除的 `TrackingResultReplyService` 那样只判 `sent.status() == SUCCESS`（见原 §5.3 记录，
现已随该类一并作废，本文档不再维护那段状态机描述）。

### 4.3 前端所有触发文件下载/上传的入口（穷尽）

全仓 `frontend/src` 里只有三个文件含 blob 下载逻辑：

| # | 入口 | 位置 | 后端路径 | 可见文案 | 条件渲染 |
|---|---|---|---|---|---|
| F1 | 下载履约导出文件 | `api/endpoints.ts:554-568` ← `pages/fulfillment/SalesOutboundPage.tsx:698-708`，按钮 `:800-802` | `GET /api/v1/fulfillment-exports/{id}/file` | 「下载」 | 无条件（但只在 `fulfillment_exports` 列表行上） |
| F2 | 下载来源回填文件 | `api/endpoints.ts:682` + 通用 `downloadFile:601-628` ← `SalesOutboundPage.tsx:710-744`，按钮 `:812-822` | `GET /api/v1/source-return-exports/{id}/file` | 「来源回填」 | **`r.tracking_import_batch_id \|\| r.import_batch_id` 才渲染** |
| F3 | 抽屉内下载来源回填 | `SalesOutboundPage.tsx:499`，按钮文案 `:580` | 同上 | 「下载来源回填文件」 | 有 items 时 |
| F4 | 导出京东出库单号 | `pages/fulfillment/JdWarehousePage.tsx:80-107` | `GET /api/v1/jd-order/outbound-order-nos/export` | （京东仓页面导出） | 无 |
| F5 | 上传来源订单表 | `api/endpoints.ts:642-647` ← `SalesOutboundPage.tsx:154` | `POST /api/v1/import-batches/source-orders` | — | 无 |
| F6 | 上传履约回传文件 | `api/endpoints.ts:672-677` ← `SalesOutboundPage.tsx:479`，按钮 `:810`、`:520` | `POST /api/v1/fulfillment-exports/{id}/tracking-imports` | 「回传」/「选择回传文件」 | **`canReceiveTracking(export_kind, usage_status)`**：`pages/fulfillment/fileOperations.ts:7-9` 要求 `export_kind === 'THIRD_PARTY'` 且状态为 `DOWNLOADED_WAITING_RETURN` / `RETURN_OVERDUE` |

**⚠️ 三个结构性发现**：

1. **所有文件入口都挂在 `fulfillment_exports` 这张表的行上**（F4 除外）。
   京东 SDK 路由不产 `fulfillment_exports` 行 → 该批次在销售出库页**没有任何一行**可点，
   连 F2「来源回填」按钮都无处可挂。生产上批次 8 之所以能点，只是因为它在 `outboundMode` 切到 SDK **之前**
   确认过一次，留下了 export id=1（`generated_at` 与 `confirmed_at` 同为 `11:40:36.992036`）。
   **下一个 SDK 路由确认的批次不会再有这一行。**
2. **`POST /api/v1/source-return-exports/{id}/push`（O9）在前端零调用方**——
   `grep "/push"` 在 `frontend/src` 无命中。在线推送目前只能靠直接打 API。
3. **`import_batches.file_ref` 没有任何下载端点**（后端也没有）。原始来源文件进了库就取不出来。

### 4.4 如果确实要补，最小补法

| 目标 | 最小改法 | 落点 | 风险 |
|---|---|---|---|
| **发货前待发清单** | 加只读端点导出 `import_batches.file_ref` 原件 | `file/SourceImportController.java` 新增 `GET /api/v1/import-batches/{batch_id}/source-file`；复用 `ContentAddressedFileStore.read` + `TrackingFileController.sourceReturn` 的响应构造 | 低。⚠️ 唯一注意：聚福宝 STRUCTURED 批次的 file_ref 是 `structured://…`，须 fail-closed 返回明确业务码而不是 500 |
| **发货后回填结果（非平台格式）** | 把已写好的 O6/O7 接上线 | 在 `fulfillment/ShipmentJdTrackingBackfillService` 现有的 `finalizeReadySourceReturnsForShipment` 调用点旁（`:306` / `:391`）追加 `TrackingResultReplyService.replyForShipment(shipmentId, chatId)`；或先加一个 `GET /api/v1/shipments/{id}/tracking-result-file` 只读下载端点 | 中。⚠️ `replyForShipment` 会**真实发企微消息**，接线前必须先确认会话来源与去重（§5.3） |
| **让 SDK 路由的批次在 UI 上可见** | 销售出库页增加以 `import_batches` 为主表的视图，而不是只列 `fulfillment_exports` | `pages/fulfillment/SalesOutboundPage.tsx` | 中，属界面重构，不是最小改动 |

> 按「只做一件事」，先做第一行。第二行的两个类已经写完，接线是独立的一步，不该和第一步捆在一起。

---

## 5. 企微文件收发的约束边界

### 5.1 出站：`WecomMediaType.FILE` 的硬限制

| 项 | 值 | 位置 |
|---|---|---|
| 协议值 | `file` | `connector/wecom/WecomMediaType.java:14` |
| 大小上限 | **20 MiB**（`20 * 1024 * 1024`） | 同上 |
| 允许扩展名 | **仅 `xlsx` / `xls`** | 同上 |
| 依据 | 官方文档 path/101463，注释注明「2026-08-21 核对」 | `WecomMediaType.java:6,8-10` |
| 其他类型 | image ≤10 MiB(png/jpg/jpeg/gif)；voice ≤2 MiB、video ≤10 MiB **但允许扩展名为空 = 上传必被拒** | `:15-17` |
| 上传前置校验 | 类型/文件名非空、文件名 UTF-8 ≤ `MAX_FILENAME_BYTES`、文件存在且为普通可读文件、大小在 `[MIN_TOTAL_SIZE, maxSizeBytes]` 内；任一失败抛 `WecomUploadValidationException`，**不创建 upload_id、不写审计** | `connector/wecom/WecomMediaUploader.java:503-541` |
| 分片协议 | init 拿 upload_id（**30 分钟会话**）→ 0 起逐片，单片 ≤ 512 KiB、≤ 100 片 → finish | `WecomMediaUploader.java:24`、`MAX_CHUNKS=100` 在 `:57` |

**`media_id` 时效**：**3 天临时素材**，三处一致写明——
`connector/wecom/WecomOutboundMessage.java:11`（「media_id 是 3 天有效的临时引用，调用方应立即使用、不得持久化明文」）、
`connector/wecom/WecomMediaUploader.java:25`、
`docs/agents/wecom-outbound-upload.md:24` 与 `:134`（「业务侧**不得**把它当永久引用缓存」）。

代码里**没有 media_id 缓存**：〔已随 c9829238 改由 `file/SourceReturnWecomDeliveryService` 接线，见 §4.2〕
同一模式仍然成立——写临时文件 → `gateway.upload` → 立即 `gateway.send` → `finally` 删临时文件
（`file/SourceReturnWecomDeliveryService.java` 约 `:153-178`；原引用的 `TrackingResultReplyService` 已删除，不存在于当前代码库）。
审计层面 media_id 也被刻意排除：请求侧只落 `media_id_sha256`（`connector/wecom/WecomOutboundGateway.java:71-75`），
响应侧「media_id 一律不落审计」（`:105-106`）。

出站文件消息体只带 media_id，不允许带 content（`WecomOutboundMessage.java:41-46`）；
`chatId` 对单聊是 userid、对群聊是群 chatid（`:7`）。群 chatid 由 `WecomGroupChatResolver.resolve:26-30`
从 `fulfillment_providers.config->>'wecomGroupChatId'` 实时读库解析，未登记直接抛业务错误、不静默返回空（`:12-14`）。

### 5.2 入站：为什么只收单聊，放开群聊要动什么

**为什么只收单聊**：不是技术偏好，是 **#85 实测结论**。
`docs/agents/wecom-group-file-spike.md` §0「一句话（操作结论）」：真实环境下用户在业务群发 XLSX 后，
`app.channel_messages` **无成功持久化的有效 group/file 回调**，而同群同机器人的**文字**回调正常落库；
UI 层面也**不允许在文件消息上 @ 机器人**（该文档 §1 证据表第 3 条）。
该文档 §2「结论的精确边界（不得过度声明）」明确只声明「当前操作流不可用」，
**不声明**企微协议永远不支持、也不能证明帧未到达进程。

**放开群聊要动什么**（按代码实际结构，从少到多）：

| 层 | 现状 | 放开需要动 |
|---|---|---|
| 任务分派 | `message/MessageSubmissionService.java:171` **只看 message_type**，群聊 file 已经会入队 | **不用动** |
| 处理器 | `file/WecomTrackingFileProcessor.java:51-54` 硬拒 `chat_type != 'single'` | 去掉/放宽这一处判断 |
| 失败码 | `message/WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_CHAT_UNSUPPORTED`，公开文案「当前仅支持把运单文件单聊直发给机器人」（`:16`） | 相应调整 |
| 上游可达性 | 见上，群聊 file 回调**根本没到过库** | **这才是真正的阻塞项**，改代码不解决 |

**风险（⚠️ 群里任何人扔文件都会触发解析）**：
放开后唯一的内容门禁是 `identifyThirdPartyExport`（`file/TrackingFileService.java:393-443`）——
它要求 ①真 XLSX 魔数 ②恰好一个 sheet ③精确 24 列且顺序一致 ④A2 单元格的导出批次号命中既有
`fulfillment_exports` 且 `export_kind='THIRD_PARTY'`。这道门相当严，随手扔的文件会被拒。
但这道门在**下载与解密之后**才生效——`process()` 的顺序是
chatType 判断（`:51-54`）→ `mediaEvidence.storeMedia` 下载解密并落 `message_media`（`:61-67`）
→ 24 列解析（`:85`）。当前 chatType 检查恰好挡在下载**之前**，一旦移除它，
群内任意成员扔的任意文件都会先被下载、解密、留存为 `message_media` 证据，然后才被 24 列门禁拒绝。
放开群聊等于把「谁能让系统下载并留存一个文件」的范围从「单聊对话人」扩大到「群内任意成员」，
这是移除该判断的**主要**代价，不是次要副作用。
入站大小上限体现在失败码文案「运单文件超过 20MB 上限」（`WecomTrackingFileFailureCode.java:19`），
判定靠 `media.failureReason().contains("超过大小上限")`（`WecomTrackingFileProcessor.java:69-72`）——
⚠️ **字符串匹配判失败原因**，属脆弱耦合。

### 5.3 失败与重试语义

**`WecomSendResult.retryable` 的确切语义**，类注释写得很清楚（`connector/wecom/WecomSendResult.java:9-10`）：
> `retryable=true` **只表示帧尚未提交**（例如连接未就绪或本地背压），可安全重试。
> ack 超时、提交后断线和传输失败都可能**已经送达**，必须先对账，**禁止盲目重发**。

这条纪律被 record 的 compact constructor 强制（`:20-42`）：

| 状态 | 约束 | 位置 |
|---|---|---|
| `SUCCESS` | 必须有 requestId + acknowledgedAt；**不允许 retryable=true** | `:23-29` |
| `TIMEOUT` | 必须有 requestId、无 acknowledgedAt、errorMessage 恒为 `ACK_TIMEOUT`、**retryable 必须为 false** | `:30-35` |
| `FAILED` | 必须带错误、无 acknowledgedAt；retryable 可 true 可 false | `:36-40` |

即：**TIMEOUT 在类型层面就不可能是 retryable**。三态定义在 `WecomSendStatus.java:4-8`。

**消费方与调度**：`file/FulfillmentExportWecomDeliveryRunner`（类注释 `:30-43`）是唯一的状态机消费者：

| 规则 | 说明 | 位置 |
|---|---|---|
| 外部调用不持长事务 | 短事务 CAS `PENDING→SENDING` → 事务外执行 resolve/upload/send → 短事务 finalize | `:33-35` |
| 崩溃落在 SENDING | 转 **UNKNOWN + 告警**，**绝不盲重发** | `:35-36`，常量 `STUCK_ERROR="DELIVERY_STUCK_IN_SENDING"` 在 `:49` |
| 安全失败（帧未提交） | 自动重试 1 次，delivery `max_attempts=2`（外部总尝试 2 次），之后终态告警 | `:36-37` |
| 已提交后的 TIMEOUT / LOST / 非 retryable | **一律 UNKNOWN**，遵守 #81 未知态纪律 | `:37` |
| 告警收口 | async task `max_attempts=3`，第 3 次只允许幂等 ensure terminal alert，**绝不再外部发送** | `:39-41` |
| INITIAL 成功 ack | 两阶段收口，SENT 重进走同一收口，**绝不重新 upload/send** | `:41-43` |

**人工重发**：`POST /api/v1/fulfillment-exports/{id}/wecom-resend`（`file/ProviderFileController.java:74-85`），
注释明确「只生成新 initial delivery + 任务，**HTTP 线程不直接发送**」（`:73`）。
**人工停止**：`POST …/wecom-stop`（`:60-71`），要求 `expected_version` CAS + 必填理由
（`FulfillmentExportWecomService.stop:91-96`）；已 COMPLETED/MANUALLY_STOPPED 幂等 no-op（`:100-102`）。

**⚠️ 禁止盲目重发的具体场景**：
① 状态停在 SENDING（进程崩在外部调用中间）；② `WecomSendStatus.TIMEOUT`；③ FAILED 但 `retryable=false`。
这三种情况外部**可能已经送达**——重发会让履约方群里出现两份同样的发货清单，
而第三方看到两份清单可能按两批发货。这就是「禁止盲目重发」在业务上的实际代价。

**【已解决，2026-08-25 同日】原 `TrackingResultReplyService` 不在这套状态机里的风险已随该类删除而消失**：
它当时直接 `gateway.upload` + `gateway.send`，只判 `sent.status() == SUCCESS`，不区分
TIMEOUT 与 FAILED、不看 retryable、失败只记日志返回 false——一旦接线就没有本节这套
UNKNOWN 纪律的保护（本报告原判"中风险"的依据）。commit `c9829238` 删除该类，改由
`file/SourceReturnWecomDeliveryService` 补齐同一能力，且**遵守 retryable 纪律**：只有
`retryable=true`（帧未提交）才回到可重试态，`SUCCESS`/`TIMEOUT`/`FAILED 且 retryable=false`
一律停在终态等人工判断，不盲目重发（见 §4.2）。该风险点不再成立。

---

## 6. 表头指纹机制

### 6.1 全部指纹定义

| 渠道 | 列数 | 匹配语义 | 位置 | 有效 sheet |
|---|---|---|---|---|
| CAISHIXIAN | 6 | `containsAll`（超集） | `file/SourceFileParser.java:577` | index==0（`:456`） |
| JUFUBAO | 6 | `containsAll` | `:578` | sheet 名 == `sheet1`（`:458`） |
| FEIXIANG v1 | 6 | `containsAll` | `:579` | index==0（`:459`） |
| FEIXIANG v2 | **12** | `containsAll`，**仅 CSV 分支** | `:45-47`，使用点 `:127-132` | — |
| ZHONGHUI | 9 | `containsAll` | `:580-581` | index==0（`:460`） |
| DAZHE | **15** | `containsAll` | `:582-585` | index==0（`:457`） |
| WANGQI | **无条目** | 恒 false（`matches(headers, null)` → `:440-442`） | `fingerprints()` `:575-587` 未 put | index==0（`:461`，形同虚设） |
| WANQI | **52，严格有序** | `WANQI_52_HEADERS.equals(headers)`，**全等** | `:49-57`，判定 `:424-426` | index==0（`:462`） |
| WECOM | — | 直接 `continue`，永不参与 | `:83`、`:463` | — |

命中后还有两道校验：必须**恰好命中一个**（`:415-417`，零命中/多命中都抛 `fingerprintError`），
且关键表头**不得重复出现**（`assertNoDuplicateKeyHeaders:446-452`，抛 `DUPLICATE_KEY_HEADER`）。

### 6.2 `containsAll` 超集语义的后果

```java
return new HashSet<>(headers).containsAll(required);   // :443
```

| 情形 | 结果 | 说明 |
|---|---|---|
| **文件多一列** | ✅ 仍命中 | 指纹是必要条件不是充分条件。多出来的列被完整读进 `raw_cells`（`cells(...)` 按实际表头逐格取，`:94`），不丢证据 |
| **文件少一列（少的是指纹列）** | ❌ 零命中 → `fingerprintError(0)` | 契合 `docs/excel-closed-loop-spec.md:38-39`「关键列缺失时禁止降级猜测」 |
| **文件少一列（少的是非指纹列）** | ✅ 仍命中 | 但下游投影取该列时会拿到空值 |
| **多个渠道同时命中** | ❌ `fingerprintError(matches.size())` | 因为是超集语义，一份列很多的表可能同时是两个指纹的超集——列数越少的指纹越容易被误命中 |
| **关键表头重复** | ❌ `DUPLICATE_KEY_HEADER` | `:446-452` |

⚠️ **超集语义的隐藏成本**：`ZHONGHUI` 的 9 列指纹里有 `订单号`、`商品名称`、`收件人` 等相当通用的列名；
`FEIXIANG` v1 的 6 列里有 `订单号`、`物流单号`。任何一份列足够全的新模板都有可能同时命中两个指纹并被整体拒绝
（错误信息只说命中数，不说命中了谁）。加新渠道时列数越少的指纹越危险。

### 6.3 大者 12 列文件 vs 15 列指纹

**⚠️ 诚实边界：我没有找到那份 12 列的大者文件。**
仓库、`docs/`、`.scratch/` 里都不存在 12 列大者样表或对它的描述；
`docs/excel-closed-loop-spec.md:49` 记录的大者 v1 就是那 15 列，与代码 `:582-585` 逐字一致。
以下选项基于「确实存在一份少了 3 列的大者文件」这个**你给的前提**推演，不是我核实过的事实。

| 选项 | 改法 | 风险 |
|---|---|---|
| A. 收缩指纹到 12 列的交集 | 从 `DAZHE` 指纹里删掉那 3 列 | ⚠️ **高**。指纹越短越容易与 ZHONGHUI（9 列，共享 `商品名称` 等）互相误命中，触发 §6.2 的多命中整体拒绝。且 `快递单号`/`快递公司` 若被删，`generateSourceReturn` 的 DAZHE 分支（`:621-625`）仍会往这两列写值——写进一份没有该列的表 |
| B. 新增 `DAZHE_V2_FINGERPRINT`，按 WANQI 的方式并行判定 | 仿 `:423-427` 加分支，并在 `templateVersion`（`:435-437`）区分 `v2-12-columns` | ⚠️ **中**。要同时改回填分支（`:621-625`）按 templateVersion 决定写哪些列，否则回填会写出原表没有的列 |
| C. 仿飞象 v1/v2 的双指纹互斥判定 | 参照 `parseCsv:127-132` 的 `v1 == v2 → 报错` 模式 | ⚠️ **中**。飞象那套目前**只在 CSV 分支有**（§2.6 补充 B），照搬到 workbook 分支要连带把飞象 v2 也接进去，改动面变大 |
| D. 要求对方补齐 3 列 | 不改代码 | 低，但取决于对方是否可控 |

**⚠️ 无论选哪个，都要先回答一个前置问题**：那 3 列缺的是不是 `快递单号` / `快递公司` / `订单商品状态`？
如果是，那大者的**回填**能力也一并缺失（`generateSourceReturn` 的 DAZHE 分支正是往这三列写），
这就不只是识别问题了。

### 6.4 `WANGQI` / `WANQI` / `DAZHE` 三个枚举的真实关系

| 值 | 中文显示名 | 引入迁移 | 真实含义 |
|---|---|---|---|
| `WANGQI` | **大者**（`common/domain/SourceChannelDisplayNames.java:15`） | `V40__add_wangqi_source_bundle_mappings.sql` | ⚠️ **命名事故**：V40 的注释写「万齐来源渠道」，但它实际承载的是**大者 15 列**的事实。枚举注释已改正：「历史技术值：对应大者 15 列来源文件」（`common/domain/SourceChannel.java:11-12`） |
| `DAZHE` | 大者（`:11`） | `V41__add_source_attribution_corrections.sql:5-30` | 纠正后的正式技术值。V41 把 `connector_configs`（`:33-34`）、`source_channel_skus`（`:40-42`）、`source_channel_bundles`（`:48-50`）从 WANGQI **复制**到 DAZHE |
| `WANQI` | **万齐**（`:16`） | `V42__add_wanqi_52_source_channel.sql` | 真正的万齐，52 列订单管理导出。V42 开头注释：「既有 WANGQI 技术值承载的是大者 15 列历史事实，**禁止覆盖或改写**」 |

**代码里的处理**：
- 解析：DAZHE 与 WANGQI 共用同一段逻辑（`file/SourceFileParser.java:101-104` 的 `isWangqiPurchaseTotal` 过滤），
  但只有 DAZHE 有指纹，WANGQI 走不到（§2.6）。
- 回填：`file/TrackingFileService.java:621` `case "WANGQI", "DAZHE"` —— 共用一个 case，写同样三列。
- 归因纠正：`V41:59` `CHECK (attributed_source_channel IN ('DAZHE', 'WANGQI'))`；
  `SourceChannelDisplayNames.fromDisplayName:33-38` 只接受「大者」→ DAZHE 与「万齐」→ WANQI，
  **无法纠正回 WANGQI**——即 WANGQI 是只出不进的历史值。

**生产数据**（`ssh zimupc`）：

| 表 | WANGQI | DAZHE | WANQI |
|---|---|---|---|
| `source_channel_skus` | 12 | 12 | 0 |
| `connector_configs` | 有（MOCK/EXCEL） | 有（MOCK/EXCEL） | 有（MOCK/EXCEL） |
| `orders` | 0 | 0 | 0 |
| `source_attribution_corrections` | 全表 0 行 | | |

即：V41 的复制发生过（两边各 12 条 SKU 映射），但**从未有过任何大者/万齐订单**，
归因纠正机制也**从未被使用过**。

---

## 7. 环境差异与数据来源

### 7.1 生产（zimupc）实测，逐条

| 项 | 生产实测值 | 查询 |
|---|---|---|
| `fulfillment_providers` 行数 | **2 行**：id=1 `JD`/JD_WAREHOUSE/`lock_version=3`；id=2 `TP`/**THIRD_PARTY**/`lock_version=0`，两者 `active=t` | `SELECT id, provider_code, provider_type, active, lock_version FROM app.fulfillment_providers` |
| `fulfillment_providers` 列 | id, provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes, active, **config**, lock_version, created_at, updated_at | `information_schema.columns` |
| 企微群 chatid | provider 1 的 `config->>'wecomGroupChatId'` = `wrn8VIbw…`；provider 2 的 config = `{}` | `SELECT config::text …` |
| 迁移版本 | `public.flyway_schema_history` 最高 **V56** | — |
| 仓库迁移版本 | `backend/src/main/resources/db/migration` 最高 **V56**（共 56 个） | `ls V*.sql` |

### 7.2 关于「生产与本机 schema 不同」的核查结论

协调方提出的四点差异，我逐条用 `ssh zimupc` 复核：

| 说法 | 核查结论 |
|---|---|
| 生产只有 1 个履约方（只有京东） | ❌ **不成立**。生产有 2 个，id=2 是 `TP`/THIRD_PARTY/active=t。因此「生产上第三方履约导出链路不可能被触发」这个推论**不成立**——它没被触发的原因不是缺履约方，而是**没有任何订单行被路由到 provider 2**（生产 4 条 fulfillment 全部指向 provider 1，见 §2.5） |
| 生产 JD provider `lock_version=v3` | ✅ 成立 |
| 生产 `wecom_group_chat_id` 已设、本机 null | 🟡 **字段名不对**。全仓**没有** `wecom_group_chat_id` 这一列：`grep -rl wecom_group_chat_id backend/src/main/resources/db/migration` = 0 命中。群 chatid 存在 `config` JSONB 的 `wecomGroupChatId` 键（`sku/FulfillmentProviderWecomConfig.java:11-20`），API 层的 `FulfillmentProviderDto.wecomGroupChatId`（`sku/FulfillmentProviderDto.java:15`）是从 config 投影出来的（`masterdata/MasterDataService.java:999,1119`）。生产 provider 1 的这个**键**确有值 |
| 生产表**无** `wecom_group_chat_id` 列、本机**有** → 本机迁移比生产新 | ❌ **不成立**。该列在两边都不存在（因为它压根不存在于任何迁移）。生产迁移版本 V56 = 仓库 V56，**没有落后**。查生产时报 `column does not exist`，是因为查了一个从不存在的列名，不是版本漂移 |

**⚠️ 这条差异因此不影响本文任何结论**——本文所有生产事实都来自 `ssh zimupc`，
且已复核生产 schema 与本 worktree 的迁移版本一致（均 V56）。
本文**没有使用 `mcp__fulfillment-hub-mcp__*` 取过任何数据**，不存在需要重查的段落。

---

## 8. 诚实边界：我没能核实的

| # | 未核实项 | 原因 |
|---|---|---|
| U1 | **大者 12 列文件是否真实存在、缺的是哪 3 列** | 仓库/docs/.scratch 里没有该样表或任何描述。§6.3 全部基于你给的前提推演，不是核实结论 |
| U2 | **中汇/大者的 Excel 实际怎么进出系统** | 两者既无 Connector（§2.1），生产又零订单（§6.4）。文件只能靠 I1 人工上传，但生产 16 个批次全是 `system:platform-pull`（P2）——即这条路在生产上**从未走过**。它到底是怎么在业务上运转的，代码和生产库都回答不了 |
| U3 | **聚福宝 STRUCTURED 批次走到京东履约 + 回运单时是否真的抛异常**（下方 R1） | 生产无任何聚福宝批次，无法复现。R1 是代码级推演 |
| U4 | **群聊 file 回调现在是否仍然收不到** | 我只读了 #85 在 2026-08-21 的实测记录（`docs/agents/wecom-group-file-spike.md`），未重新实测。企微侧行为可能已变 |
| U5 | **企微官方文档 path/101463 的当前内容** | 代码注释注明「2026-08-21 核对」，我未联网复核 20 MiB / 512 KiB / 100 片 / 3 天这些数值是否仍然准确 |
| U6 | **`fulfillment_exports` id=1 生成时 `outboundMode` 到底是什么** | `generated_at` 与批次 8 的 `confirmed_at` 精确相同（`11:40:36.992036`），且当前配置是 SDK 却存在 JD 文件导出——按 `ProviderFileService.java:118-121` 推断当时是 FILE 模式、之后才切 SDK。但我**没有找到配置变更的时间证据**（`fulfillment_providers` 无历史表，审计日志未查） |
| U7 | **`TrackingResultReplyService` 是否有意留作未接线** | 它是 HEAD 提交（`de9048e`），可能是分步交付的中间态而非遗漏。commit message 未说明接线计划 |

### 附：核查中发现的两个风险（不在你的问题清单里）

| # | 风险 | 证据 | 严重度 |
|---|---|---|---|
| **R1** | **聚福宝 STRUCTURED 批次会让来源回填生成抛异常并回滚运单回填事务** | `importStructured` 写入 `file_ref='structured://'+batchNo`（`file/SourceImportService.java:332`）；`generateSourceReturn` 无条件 `fileStore.read(source.fileRef())`（`file/TrackingFileService.java:584`）；`ContentAddressedFileStore.openRead` 对非普通文件抛 `IllegalStateException("无法读取已留存文件")`（`file/ContentAddressedFileStore.java:63-65`）；`generateSourceReturn` 只对 WANQI 做了 null 返回（`:534-536`），**没有 STRUCTURED 分支**；调用方 `ShipmentJdTrackingBackfillService.java:306,391` **无 try/catch**，而 `finalizeReadySourceReturnsForShipment` 标了 `@Transactional`（`:751`） | ⚠️ **高**（未在生产复现，见 U3） |
| **R2** | **入站文件大小超限靠中文字符串匹配判定** | `WecomTrackingFileProcessor.java:69-72`：`media.failureReason().contains("超过大小上限")` 决定返回 `TOO_LARGE` 还是 `DOWNLOAD_FAILED` | ⚠️ 中（只影响错误码准确性，不影响正确性） |
