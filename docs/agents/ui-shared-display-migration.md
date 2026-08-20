# UI 共享展示组件迁移（Issue #97）

跟踪「既有页面批次迁移到共享展示组件」的批次划分、逐页度量与例外记录。
共享组件为 `frontend/src/components/PageShell.tsx`（页面外壳）、`FilterBar.tsx`（筛选区）、
`DataTable.tsx`（数据表格默认行为收敛），见组件文件头注释。

迁移原则（Issue #97 验收）：

- 迁移不改变任何页面的可见行为：视觉与交互不得倒退，只换承载结构。
- 页面保留 URL / API / 按钮 / 表格列 / 分页 / 确认弹窗 / 业务文案原样。
- 加载 / 空 / 错误态在**确实是表格数据页**时走 DataTable 默认行为，不再各页重复
  Alert/Empty/Table 样板。
- 非表格工具页**不强行套用** DataTable；无法套用共享组件的页面逐个记录原因。
- 每批独立可合，typecheck / test / build 全绿。

## 批次划分

| 批次 | 范围 | 状态 |
|---|---|---|
| A | `frontend/src/pages/fulfillment`（10 个生产页面，页面最多、最长） | ✅ 本批完成（Issue #97 第 1 批） |
| B | `frontend/src/pages/workbench`（含 880 行的 ManualReviewPage） | 待后续批次 |
| C | `frontend/src/pages/product` 与其余页面（orders / inventory / procurement / system / dashboard / demo / analytics / agents 存量） | 待后续批次 |
| D | 全仓最终审计：逐页核对采纳情况、更新本文件累计度量 | 待后续批次 |

批次 B/C/D 开工时把实际 before/after 行数追加到下方「累计度量」并逐页登记。

## 批次 A：fulfillment（本批）

### 逐页 before/after

行数为 `git diff` 前后 `wc -l` 实测（含 import 与注释）。ShipmentsPage 在共享组件
试点（commit 16cda17）已迁移，本批不动，仅登记。

| 页面 | before | after | Δ | 采用组件 | 说明 |
|---|---|---|---|---|---|
| FulfillmentTasksPage | 339 | 330 | −9 | PageShell + FilterBar + DataTable | 表格数据页；错误 Alert → DataTable `error/onRetry/errorTitle`；筛选卡 → FilterBar；刷新按钮移至 PageShell actions（与试点 ShipmentsPage 一致） |
| SalesOutboundPage | 868 | 859 | −9 | PageShell + FilterBar + DataTable | 表格数据页；错误 Alert → DataTable error；筛选卡 → FilterBar；`?import_batch=` 恢复/复核返回闭环（#95）所在 SourceImportPanel 零改动 |
| JdWarehousePage | 394 | 394 | 0 | PageShell + FilterBar + DataTable | 混合页：SDK 工具段保持独立 Card；出库单列表筛选行 → FilterBar（查询按钮进 actions）；列表 Table → DataTable（`emptyText` 保留「暂无出库单数据」）；表格原本漏配 scroll，现走 DataTable 默认 x=960 |
| JdReturnQueryPage | 368 | 363 | −5 | PageShell + FilterBar + DataTable | 结果列表 Table → DataTable（分页 `pageSize:10 + showTotal` 保留）；接口选择 + 参数表单进 FilterBar（查询按钮进 actions，表单 Enter 提交不变）；权限/失败业务 Alert 保留（业务码语义，非系统错误） |
| OutboundReconPage | 346 | 343 | −3 | PageShell + FilterBar + DataTable | 查询条进 FilterBar（保留 Space.Compact 连体样式）；结果区 3 张对照子表 → DataTable（`emptyText`/`scroll`/`pagination=false`/`rowClassName` 原样透传）；视图级加载/错误/空态保留自定义（Skeleton/Result/Empty，属对照视图三态而非列表三态） |
| JdBasicInfoQueryPage | 392 | 386 | −6 | PageShell | 非表格工具页：头部卡 → PageShell（含连接状态 Tag 进 actions）；结果用 Descriptions 白名单展示，不套 DataTable（例外见下） |
| JdStockQueryPage | 525 | 521 | −4 | PageShell | 非表格工具页：头部卡 → PageShell；纵向参数表单 + 结果 Alert 保持原 Card；不套 FilterBar/DataTable（例外见下） |
| JdOrderQueryPage | 485 | 480 | −5 | PageShell | 非表格工具页：头部卡 → PageShell（「系统渠道工具/只读」Tags 进 actions）；不套 FilterBar/DataTable（例外见下） |
| JdSerialQueryPage | 343 | 338 | −5 | PageShell | 非表格工具页：头部卡 → PageShell（只读 Tag 进 actions）；不套 FilterBar/DataTable（例外见下） |
| ShipmentsPage | 607 | 607 | 0 | PageShell + FilterBar + DataTable | 试点页（16cda17），本批零改动 |
| **合计** | **4667** | **4621** | **−46** | — | 9 个文件改动，净 −46 行；删掉的都是各页重复的头部卡 / 错误 Alert / 筛选卡样板 |

> 行数说明：迁移删除的是「承载结构」样板（每页 10–30 行），页面主体仍是业务逻辑
> （列定义、表单、抽屉、业务文案），行数净减幅度与试点 ShipmentsPage（617→607）
> 同量级；后续批次可复用同样口径。

### 本批无法套用共享组件的页面（逐页原因）

FilterBar 采用口径（本批定，后续批次沿用）：页面存在**独立于工具面板的常驻筛选/查询行**
（列表页筛选卡、单条查询条，如 FulfillmentTasks / SalesOutbound / JdWarehouse 出库单
列表 / OutboundRecon 查询条 / JdReturn 接口表单行）时采用；**工具表单面板**
（接口选择 + 参数表单 + 上下文说明 + 结果提示的组合面板，如 JdBasicInfo / JdStock /
JdOrder / JdSerial）不拆——拆出 FilterBar 会把表单上下文与查询按钮拆散到多张卡，
信息分组倒退。DataTable 采用口径：页面存在真正的列表/对照表格数据源时采用；纯
Descriptions 白名单结果与视图级三态不套。

| 页面 | 组件 | 原因 |
|---|---|---|
| JdBasicInfoQueryPage | DataTable | 非表格数据页：查询结果为「白名单字段 Descriptions」内嵌在成功 Alert 中，无列表数据源；强套 DataTable 会把结果改成表格、破坏只读白名单展示口径。FilterBar 也未采用：查询区是「接口选择 + 按接口动态切换的内联表单 + 上下文说明 + 结果提示」组合工具面板，不是常驻筛选行；拆出 FilterBar 会把表单上下文与查询按钮拆散到两张卡。 |
| JdStockQueryPage | FilterBar / DataTable | 非表格数据页：查询区为「接口选择 + 纵向参数表单 + 结果 Alert（Descriptions）」；结果无列表结构。纵向表单不是 FilterBar 覆盖的横向筛选控件行（FilterBar 组件注释覆盖 FulfillmentTasks / Shipments / JdWarehouse 等筛选卡页面）。 |
| JdOrderQueryPage | FilterBar / DataTable | 同 JdStockQueryPage：纵向参数表单工具页，结果白名单 Descriptions，非表格数据页。 |
| JdSerialQueryPage | FilterBar / DataTable | 同 JdStockQueryPage：接口选择 + 内联表单 + Descriptions 结果，非表格数据页。 |
| JdWarehousePage | — | 无例外：SDK 工具段与列表段分别采用 PageShell + FilterBar + DataTable；SDK 段自身保持工具 Card（按钮组 + 结果 Alert），不属于表格。 |
| OutboundReconPage | — | 无例外：结果区三态（Skeleton / Result / Empty）为对照视图级状态，保留自定义并记录于此——它们是「查询结果视图」的状态，不是表格列表三态，DataTable 默认行为不覆盖此类视图。 |
| SalesOutboundPage | — | 无例外：SourceImportPanel 是业务工具面板（上传/确认/回传闭环），保持自有 Card；主列表已用 PageShell + FilterBar + DataTable。 |

### 本批保留的自定义三态（有意为之）

- OutboundReconPage 结果视图：Skeleton 加载卡 / Result 错误卡（含 404 与多批次歧义提示）/ Empty 空查询卡。
- Jd 查询工具页的权限语义 Alert（业务码 2001「权限未开通」等）——业务文案区分于系统错误，DataTable 错误态只承载系统错误（`errorMessage`）。
- 各页抽屉 / 弹窗内的明细子表（履约任务发货批次表、销售出库明细表等）：嵌套小表，尺寸/分页/空文案各不相同，属页面特写，不强套。

### 可见行为核对（本批）

- 既有文案零改动：所有**既有**标题、说明、按钮、占位符、Alert 文案与迁移前逐字一致
  （Spec review 按字符串逐条比对确认）。
- 新增文案仅两处页头：FulfillmentTasksPage（「履约任务」）与 SalesOutboundPage
  （「销售出库」）此前没有页头卡，标题只存在于文件头注释；本批按试点 ShipmentsPage
  口径新增 PageShell 页头（title 取自导航标签，description 依据文件头注释改写，
  例如履约任务页头说明改写自「每行 = 一条履约单元（订单行 → 履约方）」），
  属新增可见文案 + 有意承载变化。
- 页头图标改由 PageShell 统一渲染（`saasVisualTokens.brand.primary`、字号 20），
  与迁移前各页手写图标样式一致。
- URL / API 参数零改动：OutboundRecon 查询条件进 query string 不变；SalesOutbound
  `?import_batch=` 恢复闭环（#95）与 ReviewQueue `?import_batch=`（#96）由既有
  route tests 固定，本批全绿。
- 表格列 / 分页 / 确认弹窗 / 抽屉零改动。
- 有意的承载变化（与试点 ShipmentsPage 同口径）：
  - FulfillmentTasksPage / SalesOutboundPage 新增 PageShell 页头；刷新按钮从筛选行
    移至页头 actions。
  - 筛选区 Card 换为 FilterBar（圆角 + 阴影外观统一）。
  - JdWarehousePage 出库单列表表格由「无 scroll」变为 DataTable 默认 x=960 横向
    滚动（修复窄屏撑破容器，DataTable 组件设计目的之一）。
  - JdWarehousePage SDK 工具段从原头部大卡拆为独立 Card（信息分组不变）。

## 累计度量（供后续批次追加）

以固定基线 e3e6b87（#96 合入后）为准：`frontend/src/pages` 共 54 个页面文件。
Issue #97 立项时采纳数为 PageShell 5/53、DataTable 4/53、FilterBar 3/53；
基线实测（含试点后新增页面）为 PageShell 6/54、DataTable 5/54、FilterBar 3/54。

| 批次 | PageShell | FilterBar | DataTable | 备注 |
|---|---|---|---|---|
| 基线（e3e6b87） | 6/54 | 3/54 | 5/54 | 试点页 + Agent 中心 |
| A fulfillment（本批） | **15/54** | **8/54** | **10/54** | +9 页 PageShell，+5 页 FilterBar/DataTable |
| B workbench | 待追加 | 待追加 | 待追加 | ManualReviewPage 880 行为最大目标 |
| C product + 其余 | 待追加 | 待追加 | 待追加 | |
| D 最终审计 | 待追加 | 待追加 | 待追加 | 全仓逐页核对并收敛口径 |

口径说明：按「页面文件 import 并实际使用该组件」计数（`grep "components/<Name>'"`
命中即计 1 页）；子组件（如 EvalsTab）单独计数会导致页面数虚高，后续批次沿用
「每页面文件计一次」口径，与 54 页总数对齐。
