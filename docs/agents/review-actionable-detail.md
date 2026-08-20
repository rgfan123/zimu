# 复核事项「做决定所需事实」字段清单（Issue #72）

> 本地 Resolution：按仓库约定（docs/agents/issue-tracker.md），不改动 GitHub Issue 正文，
> 交付说明与字段清单记录在本文件。Issue #72 = 01 号（#71）模式的推广：每个复核家族在
> 抽屉里展示「做这个决定所需要的事实」，沿用 #71 的白名单机制与 PII 防护，展示逻辑
> 抽成可复用结构（字段定义 / 事实组 / 占位），不为每个家族各写一套 JSX。

## 可复用呈现结构

`frontend/src/presentation/publicReady.ts`：

- `FactFieldDef`：单个事实字段 = `{ key, label, value? }`。`key` 是 detail 白名单键；
  `value` 是可选的「结构化标量投影」（数组/对象证据投影为标量文本），只读自己的固定键。
- `FactGroup`：一个事实组 = `{ title, fields[] }`。
- `REVIEW_FACT_GROUPS`：复核家族 → 事实组列表；字段定义本身即白名单，任意 detail 键
  遍历 / 未知键被 fail-closed。
- `reviewFactGroups(reasonCode)`：取家族事实组；未定义家族返回空数组（走通用白名单兜底）。
- `factGroupRows(detail, group)`：逐字段渲染；白名单字段缺失/空白显示「来源未提供」
  （`SOURCE_NOT_PROVIDED`），不整行消失。
- 结构化投影（同文件内）：
  - `candidateListText`：候选清单（编号 · 名称）；空数组 = 确定性零命中 →「未命中候选」。
  - `revisionChangesText`：导出后改单的改动明细（字段白名单 `REVISION_FIELD_LABELS` +
    行号 + 改前/改后，值截断 200 字符）；未知字段键的条目被丢弃。
  - `truncate`：cell 值 / 改前改后值固定截断上限（200 字符）。

`ReviewCaseDrawer.tsx` 的 `FactGroupSection`：一个循环渲染任意家族的任意事实组
（标题 + Descriptions），SKU 映射家族也走同一结构（`SKU_MAPPING_FACT_GROUP`），
仅「待映射商品明细」证据表保留 #71 的结构化呈现。

## 逐家族字段清单与来源

### 1. CUSTOMER_MATCH_REQUIRED（客户映射待确认）

| 事实组 | 字段（detail 键） | 展示标签 | 事实来源 |
|---|---|---|---|
| 来源客户 | `source_channel` | 来源渠道 | 创建缝 `OrderCreateService.orderReviewCase`：`input.source().name()`（#71 通用白名单既有展示，切事实组后保留不回归） |
| 来源客户 | `customer_name` | 来源客户名称原文 | 创建缝 `OrderCreateService.orderReviewCase`：`CanonicalOrderInput.customer().name()` 原文 |
| 来源客户 | `source_customer_ref` | 来源客户编号 | 同缝：`customer().sourceCustomerRef()` |
| 收货信息（可展示部分） | `receiver_name` | 收货人 | 同缝：`receiver().name()`（与销售出库/发货页既有安全投影一致，不含电话） |
| 收货信息（可展示部分） | `receiver_address` | 收货地址 | 同缝：`receiver().address()`（既有安全投影） |
| 候选客户档案 | `customer_candidates` | 候选客户 | 同缝：确定性精确匹配（来源客户编号命中 `customer_source_refs`、或输入 `customer_code` 精确命中 BUSINESS/ACTIVE 客户），候选只含 `customer_code` + `customer_name`；零命中 = 空数组 →「未命中候选」 |

PII 边界：**不写 `receiver_phone`**；候选档案不读取 profile 等档案内其他字段。

### 2. CARRIER_MAPPING（承运商映射待确认）

| 事实组 | 字段（detail 键） | 展示标签 | 事实来源 |
|---|---|---|---|
| 来源运单 | `tracking_number` | 运单号原文 | 来源渠道运单号（业务标识）；当前无生产创建缝（TrackingFileService 以 `CARRIER_MAPPING` 业务码拒绝文件而非建复核），呈现层就绪，供 seed/未来中汇回填路径使用（V31 注释） |
| 来源运单 | `tracking_prefix` | 识别前缀 | 运单号确定性前缀（如 `SF`） |
| 来源运单 | `source_logistics_company` | 来源物流公司 | 来源渠道给的物流公司名称 |
| 候选标准承运商 | `carrier_candidates` | 候选标准承运商 | 前缀规则命中的内部 Carrier 主数据（`carrier_code` + `carrier_name`）；零命中 →「未命中候选」 |

### 3. MAPPING_MULTIPLIER / QUANTITY_SCALE（数量换算与精度）

两家族共用同一事实组（`quantityFactGroups`）：

| 事实组 | 字段（detail 键） | 展示标签 | 事实来源 |
|---|---|---|---|
| 数量换算 | `source_quantity` | 来源数量原文 | QUANTITY_SCALE 创建缝 `ProviderFileService.markJdQuantityReview`：`order_lines.source_quantity_snapshot` |
| 数量换算 | `source_unit` | 来源单位 | 同缝：`ExportRow.unit()`（行快照） |
| 数量换算 | `quantity_multiplier` | 当前乘数 | 同缝：`order_lines.mapping_multiplier_snapshot` |
| 数量换算 | `converted_quantity` | 换算后结果 | 同缝：`requestedQuantity`（被拒绝的换算结果） |
| 数量换算 | `reject_reason` | 拒绝原因 | 同缝：固定文案「京东出库数量必须为正整数」（确定性原因，非自由文本） |
| 数量换算 | `provider_code` | 履约方 | 同缝：履约方代码（#71 通用白名单既有展示，切事实组后保留不回归） |

### 4. IMPORT_DATA（导入数据待修正）

| 事实组 | 字段（detail 键） | 展示标签 | 事实来源 |
|---|---|---|---|
| 问题单元格 | `source_sheet_name` | 来源工作表 | 来源文件结构元数据（#71 同源字段） |
| 问题单元格 | `source_row_index` | 来源行号 | 来源文件结构元数据（#71 同源字段） |
| 问题单元格 | `column_name` | 列名 | 出问题列的来源表头 |
| 问题单元格 | `cell_value` | 原始单元格值 | 被标记的问题单元格原文；只读这一个固定键（不遍历其他单元格），截断 200 字符 |
| 拒绝原因 | `reject_reason` | 拒绝原因 | 标记原因 |

当前无生产创建缝（导入解析错误落在 `raw_import_rows.status=NEED_REVIEW`）；呈现层就绪。

### 5. REVISION_AFTER_EXPORT（导出后改单待确认）

| 事实组 | 字段（detail 键） | 展示标签 | 事实来源 |
|---|---|---|---|
| 改动明细 | `changes` | 改动字段 | 创建缝 `OrderCreateService.doRevise`（committed 分支）对改前订单/行与修订输入做确定性 diff：`{field, line_no?, before, after}`；字段键白名单 `REVISION_FIELD_LABELS`（source_version / receiver_name / receiver_address / quantity / product_name / specification / unit / line_count / settlement_method / settlement_time / remark）；改前/改后值截断 200 字符 |
| 改动明细 | `changed_fields` | （机器可读汇总，不在 UI 单独展示） | 同缝：去重后的改动字段键列表 |
| 导出文件版本 | `export_batch_no` | 已导出文件批次 | 同缝：`fulfillment_exports.export_batch_no`（经 `fulfillment_export_items` 关联该订单行；无导出记录则缺省 →「来源未提供」） |
| 导出文件版本 | `template_version` | 导出模板版本 | 同缝：`fulfillment_exports.template_version` |
| 来源与原因 | `source_version` | 来源版本 | 同缝：修订输入 `sourceVersion` |
| 来源与原因 | `change_reason` | 变更原因 | 同缝：修订输入 `changeReason`（#71 已放行） |

PII 边界：diff **不含收货电话**（不新增完整电话泄露面）；`before`/`after` 只承载白名单
字段的值；未知字段键的改动条目在前端被 fail-closed 丢弃。

## 测试覆盖

前端（`frontend/test/`）：

- `reviewFactGroups.test.ts`（10 条）：事实组定义完整性、逐字段占位、五家族关键事实、
  PII/未知键 fail-closed、候选零命中、cell 截断、数量家族共用、改动明细投影与过滤。
- `reviewActionableDetail.test.ts`（8 条，routeHarness + ReviewCaseDrawer）：逐家族抽屉
  渲染关键事实可见、PII/未知键不出现、缺字段显示「来源未提供」、已解决事项无白名单
  字段时显示提示而非空表格。

后端（真实集成测试）：

- `ReviewCaseResolutionApiTest#customerReviewCaseCarriesReceiverAndCandidateFactsForTheDecision`：
  CUSTOMER_MATCH_REQUIRED detail 含收货可展示部分 + 候选客户档案（空/按编码命中，
  双路径命中同一客户不重复），且不含 `receiver_phone`。
- `WecomOrderFulfillmentRoutingApiTest#readyWecomOrderCreatesOneJdShipmentAndCannotBeRoutedTwice`：
  REVISION_AFTER_EXPORT detail 含 `changed_fields` / `changes`（改前 null → 改后
  after-routing）/ 来源版本 / 变更原因，且不含 `receiver_phone`。
- `ExcelClosedLoopApiTest#jdNonIntegerQuantityCreatesAnActionableReviewCaseInsteadOfAnExport`：
  QUANTITY_SCALE detail 含来源数量/单位/乘数/换算结果/拒绝原因。

## 维护注意（review 结论）

- 事实组字段 = 展示白名单。新增后端写入的 detail 键时，若要在抽屉展示，必须同时加入
  对应家族的事实组字段定义（含 PII 理由注释）；未知键不会自动渲染。
- 改动字段清单有两份且必须同步：后端 `OrderCreateService.revisionAfterExportDetail` 的
  `appendChange` 调用（允许 diff 的字段）与前端 `REVISION_FIELD_LABELS`（展示白名单）。
  新增可 diff 字段需两处同时修改。
- 收货电话永不进入 review_case.detail；改单 diff 也不含电话字段。

## 未做之事与理由

- `CARRIER_MAPPING` / `IMPORT_DATA` / `MAPPING_MULTIPLIER` 当前无生产创建缝：
  - `CARRIER_MAPPING`：TrackingFileService 以 `CARRIER_MAPPING` 业务码拒绝文件而非建复核；
    未来中汇回填路径（V31 注释）计划建复核。
  - `IMPORT_DATA`：导入解析错误落在 `raw_import_rows.status=NEED_REVIEW`，不建复核事项。
  - `MAPPING_MULTIPLIER`：全仓库无落库路径（OrderMapper / ReviewCaseResolutionService
    只声明了该 reason 的 resolve 语义）；真实事项出现时 detail 为空，抽屉显示整组
    「来源未提供」占位，不会静默消失。
  三者本次不新增创建路径（超出 #72 范围），呈现层与测试已就绪；一旦出现真实事项，
  创建缝的事实写入按本文件字段来源补齐。
- 候选客户不做名称相似度匹配、候选承运商不做猜测——只呈现确定性事实。
- 不改动 #64 两路由、#95/#96 URL、既有 SKU 映射证据与全部 resolve 动作。
- 收货人/收货地址展示的是与销售出库/发货页一致的既有安全投影（不含电话）；
  REVISION_AFTER_EXPORT 改动明细中的 receiver_address / remark 值与既有页面/白名单
  投影同源，且统一截断 200 字符。
