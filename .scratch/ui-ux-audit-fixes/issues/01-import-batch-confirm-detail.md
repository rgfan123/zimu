# 01 — 订单导入确认区改造（确认前明细 + 二次确认 + 逐行结果）

**Type:** implementation

**What to build:** 用户在「来源订单导入」页确认批次前能看清"将确认哪些行、确认后影响是什么"，确认后能看清逐行结果；不再只有一个孤零零的按钮。

**Source:** .scratch/saas-visual-system/ux-audit.md §1 + §2（🔴 P0 #1、🟠 P1 #2/#3/#4、🟡 P2 #6）

**Status:** resolved

**Claimed by:** opencode 分派 subagent

- [x] 「确认本批次」按钮带数量与行范围（如「确认本批次（已接收 3 行）」），Alert 描述补充确认影响（"确认后已接收行将写入系统订单，并生成履约文件，形成履约承诺"），影响说明在确认前可见（不再只出现在确认后）。
- [x] 明细表语义改为确认相关：标题与确认动作关联（如"确认明细"），已接收行加"将确认"标记；确认后逐行展示结果（成功/失败 + 原因）。
- [x] 「确认本批次」包一层 Popconfirm 二次确认（说明确认范围与影响）。
- [x] 禁用态「确认本批次」提供原因（Tooltip 或 Alert 细分："待复核 2 行、拒绝 1 行，请先处理后再确认"）。
- [x] 明细表空态区分加载中/加载失败/确实无数据，不再与汇总 Alert 自相矛盾；200 行截断时明确展示"共 X 行，当前展示 Y 行"。
- [x] 回传履约结果 Modal「校验并接收」加二次确认（Popconfirm），确认后展示逐行失败原因（不止 shipped/partial/failed 三个数字）。
- [x] 验证：前端 tsc 0 错误、npm test 全过、npm run build 通过；浏览器回归截图存 output/playwright/ui-fixes/import-batch-*（混合批次确认前/确认后、禁用态、全通过批次）。

**Scope:** frontend/src/pages/fulfillment/SalesOutboundPage.tsx（及该页专属测试/类型）。不得修改其他页面。

**Do not:** commit；修改 saasTheme.ts；触碰 8 个 wecom 已跟踪文件之外的其他已跟踪文件结构。

## Comments

### 改动摘要（本轮从零完成，此前中断的残片已并入）

改动文件（均在本票 Scope 内）：

- `frontend/src/pages/fulfillment/SalesOutboundPage.tsx`（唯一生产页面改动）
  - 确认区：按钮「确认本批次（已接收 N 行）」；Alert 描述在确认前即含影响说明（"确认后已接收行将写入系统订单，并生成履约文件，形成履约承诺"）。
  - 确认按钮包 Popconfirm（范围 + 影响）；禁用态 Tooltip 细分原因（"待复核 N 行、拒绝 M 行，请先处理后再确认"）。注意：Tooltip 与 Popconfirm 嵌套时按钮需包一层 `<span>`（antd 对禁用按钮克隆事件处理器，禁用 button 不触发 mouse 事件，tooltip 永不弹出；已按 antd 官方模式修复）。
  - 明细表：标题改为「确认明细」，副行说明确认影响；新增「本次确认」列——未确认时 ACCEPTED 行显示「将确认」Tag，确认后显示「已确认」Tag，待复核/拒绝行显示「不参与」；空态三态区分（加载中/加载失败带重试/确实无数据）；右上角保留"共 X 行，当前展示 Y 行"截断提示。
  - 回传履约结果 Modal：「校验并接收」包 Popconfirm（说明原子写入 + 失败行标记异常原因）；确认后除 shipped/partial/failed 三个数字外，新增逐行结果表（行号/出库单号/结果/实际发货数量/快递公司/物流单号/异常原因，FAILED 行异常原因以 danger 文本展示）。
- `frontend/src/pages/fulfillment/fileOperations.ts`：新增 `presentTrackingBatchRow` + `TrackingBatchRowView`（回传逐行安全视图，只透出业务列，不透收件人 PII）。
- `frontend/src/api/types.ts`：`TrackingImportBatch` 增加可选 `rows?: TrackingBatchRow[]` + 新类型 `TrackingBatchRow`（对齐后端 upload/get 已返回的 `rows` 字段）。

### 逐行结果粒度决策（已查后端确认）

- 来源批次确认（`SourceImportService.confirm`）是整批原子事务：非 ACCEPTED 行先阻断（IMPORT_BATCH_BLOCKED）、导出覆盖不完整则整批拒绝（IMPORT_BATCH_EXPORT_INCOMPLETE）。后端不返回逐行确认结果，且逐行失败不可能发生 → 前端按"全部已接收行 = 确认成功（已确认 Tag）+ 批次级失败以 Alert 呈现"展示，与后端语义一致。
- 回传接收（`TrackingFileService.import`）返回体已含 `rows`（每行 raw_cells 含 结果/实际发货数量/快递公司/物流单号/异常原因），前端经 `presentTrackingBatchRow` 白名单直接消费 raw_cells 的「异常原因」做逐行失败展示（FAILED 行的 `shipment.failure_reason` 为另一持久化事实，未用于展示），无需额外请求。

### 测试

- 新增 `frontend/test/fileOperations.test.ts` 2 个用例（回传逐行视图 + PII 不透出、退化占位）。
- 更新 `frontend/test/sourceImportRoute.test.ts`：标题断言改「确认明细」+「将确认」；新增"确认经 Popconfirm → 已确认逐行结果"用例与"回传 Popconfirm → 逐行失败原因"用例（含请求断言）。
- `npx tsc --noEmit`：0 错误；`npm test`：162/162 通过（基线 155 全过 + 新增 7 全过）；`npm run build`：通过（仅 chunk 体积提示）。
- 注：tsc 过程中观察到 `src/pages/dashboard/DashboardPage.tsx` 曾有报错，属另一任务未跟踪 WIP（dashboard/ 目录为 untracked），非本票改动引起；最终复跑 tsc 0 错误。

### 浏览器回归（dev server :5196 已关闭）

脚本：`/var/folders/7l/hfq22bfx5ll23zgl36k5qcs80000gn/T/opencode/ui-fixes-01-import-batch.py`（写端点全 route-mock，零后端状态污染），全部断言通过：

- `output/playwright/ui-fixes/import-batch-01-mixed-before-confirm.png` — 混合批次（已接收 3/待复核 2/拒绝 1）确认前：Alert 影响说明 + 禁用按钮（带数量）+ 确认明细表「将确认」标记
- `output/playwright/ui-fixes/import-batch-02-mixed-disabled-reason-tooltip.png` — 禁用态悬停 Tooltip："待复核 2 行、拒绝 1 行，请先处理后再确认"
- `output/playwright/ui-fixes/import-batch-03-clean-popconfirm.png` — 全通过批次确认按钮二次确认弹层（范围 + 影响）
- `output/playwright/ui-fixes/import-batch-04-clean-after-confirm.png` — 确认后：Alert"生成履约文件 2 份" + 逐行「已确认」
- `output/playwright/ui-fixes/import-batch-05-tracking-popconfirm.png` — 回传 Modal「校验并接收」二次确认弹层
- `output/playwright/ui-fixes/import-batch-06-tracking-row-results.png` — 回传逐行结果表（已发货 1 + 失败 1 行"客户拒收，已多次联系无果"）

（本模型不支持读图，截图内容正确性以脚本内文本断言为准。）

**Status:** claimed（主控统一收口）

## Comments（追加：review 修复）

- 双轴 code review（2026-08-15）后修复：① 确认成功后 `void loadConfirmRows(confirmed)` 重载明细（确认前 ACCEPTED 行若无 order_id，确认后仍显示"未建立订单关联"的缺陷）；② state/函数命名去 issue 语义：`issueRows/issueTotal/issueRowsLoading/issueRowsError/loadIssueRows` → `confirmRows/confirmTotal/confirmRowsLoading/confirmRowsError/loadConfirmRows`，类型 `ImportIssueRowView` → `ImportRowView`、`presentImportIssueRow` → `presentImportRow`（含测试同步）；③ 回传逐行失败数据源表述修正（消费 raw_cells「异常原因」，非 shipment.failure_reason）。
- 最终验证：tsc 0 错误、npm test 162/162、build 通过。

## Comments（追加：需求扩展 + 双轴 review 修复）

- 用户需求扩展（2026-08-15）：① 确认明细需展示**解析后的基本信息**（收货人姓名-手机号-地址-商品）用于核对解析是否正确；② 展示**当前 SKU 履约归属**（京东 JD_WAREHOUSE / 第三方 THIRD_PARTY）。
- 实现：后端 rows 接口每行新增 `parsed`（SourceFileParser.projection 按渠道模板提取的 7 键白名单，复用 map() 管线保证与落单解析同源）+ `sku_fulfillment`（source_channel_skus→skus→fulfillment_providers 批量查询，仅 active 映射，record SkuFulfillmentProjection）；前端明细表新增 收货人/手机号/收货地址/商品/规格/数量/履约归属 列（履约归属标签走统一 PROVIDER_TYPE_LABELS，京东/第三方）。
- 双轴 code review 修复：① openapi.yaml RawImportRow 补 parsed/sku_fulfillment 契约（硬违规）；② rows() 对非 SOURCE_ORDER 批次返回 400（原 500）；③ skuFulfillmentByRef 由 Map<String,Map> 改为 record，修 DB 返回顺序与 IN 顺序不一致的 key 映射 bug；④ containsExactly 脆断言 → containsExactlyInAnyOrder；⑤ PROVIDER_TYPE_LABELS 抽到 constants/labels.ts 三处统一（FulfillmentProvidersPage/SystemConfigPage/SalesOutboundPage）；⑥ projection 复用 map() 的理由以 javadoc 固化（同源一致，非复制逻辑）。
- 书面接受项：前端 firstText 回退列名（仅后端未返回 parsed 的旧/测试场景触发，注释已说明）；全量手机号/地址展示与运单 masked 口径并存（页面受众不同，用户明确要求）。
- 最终验证：前端 tsc 0、npm test 163/163、build ✓；后端 ExcelClosedLoopApiTest/CaishixianSourceFileParserTest 16/16；全量后端待跑。

## Comments（追加：需求微调）

- 用户需求微调（2026-08-15）：① 规格列在来源文件未提供时回退**内部 SKU 表默认规格**（`app.skus.specification`，经 `sku_fulfillment.sku_specification` 白名单带出，不再显示"来源未提供"）；② 确认明细删除「Sheet」「行号」列，新增「所属来源」列（批次 `source_channel` → CHANNEL_LABELS：彩食鲜/飞象/聚福宝）。
- 验证：前端 tsc 0、164/164、build ✓；后端 ExcelClosedLoopApiTest 15/15 ✓；8088 重新构建部署，真实批次确认 `sku_specification: "200g"`。
