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
| B | `frontend/src/pages/workbench`（基线 ManualReviewPage 1020 行，#95/#96 合入后实测） | ✅ 本批完成（Issue #97 第 2 批） |
| C | `frontend/src/pages/product` + `frontend/src/pages/inventory`（7 个生产路由页面） | ✅ 本批完成（Issue #97 第 3 批） |
| D | 其余页面（orders / procurement / system / dashboard / demo / analytics / agents 存量）+ 全仓最终审计：逐页核对采纳情况、更新本文件累计度量 | 待后续批次 |

批次 C/D 开工时把实际 before/after 行数追加到下方「累计度量」并逐页登记。

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

## 批次 B：workbench（本批）

### 逐页 before/after

行数为本批改动前后 `wc -l` 实测（含 import 与注释）。workbench 目录实际路由页面只有
两个（`routes.tsx` 挂载 ManualReviewPage / ChannelMessagesPage）；`index.ts` 仅 1 行
再导出，OrderDraftReviewPanel / TrackingDraftReviewPanel 是复核抽屉内的业务表单面板，
helper/presentation 文件（channelMessageView / manualReviewActions / jdSkuMappingReview /
orderDraftMasterData / orderDraftReview / trackingDraftReview / 各 `*Api`）不是页面，
均不入采纳率计数、本批零改动。

| 页面 | before | after | Δ | 采用组件 | 说明 |
|---|---|---|---|---|---|
| ManualReviewPage | 1020 | 1013 | −7 | PageShell + FilterBar + DataTable | 页头卡 → PageShell（title/说明/图标原样，Segmented 视图切换进 actions）；复核/提醒两个筛选卡 → FilterBar（刷新按钮进 actions 右对齐）；两张列表 Table → DataTable（`scroll`/分页/空态文案原样透传）；两个列表错误 Alert → DataTable `error/errorTitle`（原无重试按钮，不新增 onRetry） |
| ChannelMessagesPage | 240 | 234 | −6 | PageShell + DataTable | 表格 Card 的 title「企业微信消息」+ extra 刷新 → PageShell（文案不变，刷新进 actions）；列表 Table → DataTable（错误 Alert 原带重试 → `onRetry` 保留；`scroll={{ x: 980 }}`/分页原样）；FilterBar 未采用（见下） |
| **合计** | **1260** | **1247** | **−13** | — | 2 个页面文件，净 −13 行；删掉的都是页头卡 / 错误 Alert / 筛选卡 / 表格 locale 样板，页面主体（列定义、抽屉、确认动作、批次上下文）原样保留 |

> 行数说明：ManualReviewPage 是双视图作业页，业务逻辑（批次上下文卡、fail-closed
> 校验、六个解决动作、两张抽屉、运营提醒 ACK、主数据分页加载）占绝对主体，删掉的
> 承载结构样板与 batch A 同量级；ChannelMessagesPage 本身已较精简，净 −6 行。
> 两页行数净减幅度与 batch A 各页（−3 ~ −9）一致。

### 本批无法套用共享组件的文件（逐页原因）

FilterBar / DataTable 采用口径沿用 batch A：存在独立于工具面板的常驻筛选/查询行时用
FilterBar；存在真正的列表数据源时用 DataTable；抽屉/弹窗内明细子表与视图级三态不强套。

| 页面/文件 | 组件 | 原因 |
|---|---|---|
| ChannelMessagesPage | FilterBar | 页面无常驻筛选/查询行：消息列表仅分页（page/size 进 query），无筛选控件；套 FilterBar 会造出空壳筛选卡。 |
| ManualReviewPage 批次上下文 | — | 无例外：`?import_batch=` 批次上下文卡与非法标识 fail-closed Alert（#95）是业务状态承载（加载/不存在/已确认/复核中四态 + 返回链接），不是列表三态，保持原 Card/Alert；队列隐藏逻辑（非法批次整块隐藏）零改动。 |
| ManualReviewPage 抽屉内明细子表 | DataTable | SKU 映射证据表 / 京东阻断发货明细表：抽屉内嵌套小表（pagination=false、尺寸/空文案各异），batch A 口径即「页面特写，不强套」。 |
| OrderDraftReviewPanel / TrackingDraftReviewPanel | — | 非路由页面：复核抽屉内的业务表单面板（原始证据 + 候选映射 + 确认命令），不入采纳率计数。 |

### 本批保留的自定义三态（有意为之）

- ManualReviewPage 批次上下文卡（加载中 / 批次不存在 / 本批次已确认 / 正在复核导入批次）
  与非法批次 fail-closed Alert：业务语义状态，DataTable 错误态只承载系统错误。
- 抽屉内提交错误 Alert（`submitError` / `alertSubmitError`）：动作失败反馈，位置在表单区，
  非列表错误态。

### 可见行为核对（本批）

- 既有文案零改动：页头「人工作业中心」与说明、「企业微信消息」、筛选标签（状态/事项类型/
  责任团队）、空态（「当前没有复核事项」/「当前没有运营提醒」）、错误条标题（「复核队列
  加载失败」/「运营提醒加载失败」/「消息记录加载失败」）、分页统计（「共 N 项」/「共 N 条」）
  与迁移前逐字一致。
- URL / API 零改动：#95 `?import_batch=` 批次筛选、fail-closed、批次上下文/返回路径；
  #96 status/reason_code/responsible_team/view 的 URL 唯一事实源与 Dashboard 落地预筛；
  ManualReview 全部业务表单/抽屉/确认动作（含运营提醒 ACK「确认已知晓」语义——
  不推进业务状态）；ChannelMessages 消息证据/媒体读取（抽屉白名单字段与原文）/重新解释。
  均由既有 route 测试（importBatchReviewRoute / manualReviewQueueRoute /
  manualReviewDraftRoute / dashboardDispatchRoute）与本批新增 6 个测试固定。
- 表格 scroll 保留原值（x: 900 / x: 980）而非 DataTable 默认 x=960：原页面即显式配置，
  避免横向滚动行为改变。
- 重试按钮有无与迁移前一致：ManualReviewPage 两个列表错误条原无重试 → 不传 `onRetry`
  （不新增可见按钮）；ChannelMessagesPage 错误 Alert 原带重试 → `onRetry` 保留。
- 有意的承载变化（与 batch A 同口径）：
  - ManualReviewPage 页头卡换为 PageShell 渲染（图标/标题/说明/视图切换外观一致）；
    刷新按钮从筛选行内移至 FilterBar actions 右对齐；错误条从筛选行上方移入表格上方
    （DataTable 错误条位置）。
  - ChannelMessagesPage 表格 Card 的 title/extra 换为 PageShell 页头（文案与按钮不变，
    刷新按钮仍在右上角）；错误条位置同 batch A（列表上方）。

## 批次 C：product + inventory（本批）

### 逐页 before/after

行数为本批改动前后 `wc -l` 实测（含 import 与注释）。product 目录实际路由页面为
CategoriesPage / ProductsPage / SkusPage / SkuMappingsPage / BundlesPage（`routes.tsx`
挂载 `/product/*`）；inventory 目录为 InventoryOverviewPage / InventoryDetailsPage
（`/inventory/overview`、`/inventory/details`）。`index.ts`、masterOptions / productArchive /
productArchiveFields / providerSkuMapping / skuCommercialPrice / skuMappingMatrix /
inventoryOverviewView / inventoryDetailsView 等 helper/presentation 文件不是页面，
不入采纳率计数、本批零改动（与 batch A/B 口径一致）。

| 页面 | before | after | Δ | 采用组件 | 说明 |
|---|---|---|---|---|---|
| SkuMappingsPage | 699 | 696 | −3 | PageShell + DataTable | 页头 Flex（标题 + 说明 + 「主数据」Tag）→ PageShell（Tag 进 actions，文案原样）；矩阵表 → DataTable（`locale` → `emptyText`，`scroll`/`sticky`/分页原样）；矩阵加载/错误态与两个辅助面板保持自定义（例外见下） |
| InventoryOverviewPage | 310 | 310 | 0 | PageShell + FilterBar + DataTable | 新增 PageShell 页头（title「总库存」取自导航标签，原 intro 说明文案移入 description）；筛选行 admin-toolbar → FilterBar（刷新按钮进 actions 右对齐，控件/aria-label/查询重置逻辑原样）；表格 → DataTable（`scroll` x=1360 / `emptyText` / 分页原样）；加载/错误态保留（例外见下） |
| InventoryDetailsPage | 150 | 153 | +3 | PageShell | 标题行（Space wrap + Typography.Title + 返回链接 + 两个状态 Tag）→ PageShell（返回链接与 Tags 进 actions，文案原样）；库存对象/能力卡/口径说明零改动；加载/错误态保留（例外见下） |
| CategoriesPage | 50 | 50 | 0 | — | 不可迁：页面是共享骨架 MasterDataCrud 的薄配置层，无逐页页头/筛选/表格样板（原因见下） |
| ProductsPage | 195 | 195 | 0 | — | 同上 |
| SkusPage | 170 | 170 | 0 | — | 同上（筛选控件经 `filters` prop 由骨架 toolbar 承载） |
| BundlesPage | 367 | 367 | 0 | PageShell + DataTable | 试点已采用（53c8aa7 随静态礼包功能合入），本批零改动，仅登记 |
| **合计（7 页）** | **1941** | **1941** | **0** | — | 3 页采用/1 页已采用；另删 skuMappings.css 死规则 −13 行 |

> 行数说明：本批三页可删的承载样板本就各只有一处（页头卡 / 筛选行容器 / 表格 locale），
> PageShell / FilterBar 的 props 化 API 与删掉的 JSX 等量（details 页头原来只有 8 行，
> 换 PageShell 反而 +3）；与 batch A/B 各页 −3 ~ −9 的幅度一致，页面主体（列定义、表单、
> 抽屉、面板、业务文案）原样保留。真实的样板减少体现在结构统一与 CSS 死规则清理。

### 本批无法套用共享组件的页面（逐页原因）

FilterBar / DataTable 采用口径沿用 batch A/B：存在独立于工具面板的常驻筛选/查询行时用
FilterBar；存在真正的列表数据源时用 DataTable；视图级三态、权限语义 Alert 与面板内
特写小表不强套。以下例外均按 Issue #97 验收「发现某页确实无法套用共享组件时，记录原因
而不是强行套」登记；DataTable 的加载/空/错默认行为只覆盖「表格数据页的列表三态」，
视图级三态（整页/整块替换）与权限语义错误不在其覆盖范围（组件文件头注释明确错误态
只承载系统错误 `errorMessage`）。

| 页面/文件 | 组件 | 原因 |
|---|---|---|
| CategoriesPage / ProductsPage / SkusPage | PageShell / FilterBar / DataTable | 三个页面是共享骨架 MasterDataCrud（`pages/shared/`）的薄配置层：页头、筛选 toolbar、表格、加载/空/错三态已集中在该骨架一处，不存在逐页重复样板。骨架不在本批范围（非 product/inventory 页面文件），其内部 admin-toolbar / Table 的 FilterBar / DataTable 化会同时改变五个主数据页的加载/空/错行为（如全页 loading → 表格内 loading），列入 batch D 候选；强套 PageShell 只会新增可见页头与行数、无样板可删，不为采纳率乱改。 |
| SkuMappingsPage 矩阵工具行 | FilterBar | 矩阵工具行是弹性筛选行：显示平台多选宽度由 `sku-matrix__filter` 网格（`minmax(260px, 460px)` + 移动端 1fr 全宽）驱动，行内还含「N 个内部 SKU · 显示 M 个平台」计数文本；FilterBar 的固定控件行（Space wrap）会把多选宽度钉死并改变移动端布局，属可见行为倒退。 |
| SkuMappingsPage 矩阵加载/错误 | DataTable | 视图级三态：整块工作区被 AdminLoading / AdminFailureAlert 替换（含权限语义），不是列表三态，沿用 batch A OutboundRecon 口径。 |
| SkuMappingsPage 参考预览表 / 京东件数换算表 | DataTable | Collapse 工具面板内的特写小表（rowSelection / 自定义列 / 无三态样板），沿用 batch A「抽屉/弹窗内明细子表不强套」口径。 |
| InventoryOverviewPage / InventoryDetailsPage 加载/错误 | DataTable | 视图级三态 + 权限语义：403 业务码 FORBIDDEN →「暂无查看权限」warning Alert（`adminFailurePresentation`），DataTable 错误态只承载系统错误（`errorMessage`），沿用 batch A「业务码语义 Alert 保留」口径；由 inventoryOverviewRoute / inventoryDetailsRoute 既有测试固定。 |
| InventoryOverviewPage 表格容器 | — | `admin-surface` 边框/圆角容器保留（DataTable 不提供表格容器，且页面在 `.admin-page` 内阴影口径不变）。 |

### 本批保留的自定义三态（有意为之）

- InventoryOverviewPage / InventoryDetailsPage 的视图级 AdminLoading（「正在加载库存观测…」
  /「正在加载专业库存明细…」）与 AdminFailureAlert（403 权限 →「暂无查看权限」warning，
  系统错误 → 错误标题 + 安全文案）。
- SkuMappingsPage 矩阵工作区与京东件数换算面板的 AdminLoading / AdminFailureAlert
  （「正在加载 SKU 映射矩阵…」/「京东件数换算加载失败」等）。
- 参考预览面板的预览结果表（无列表三态，加载在「开始核对」按钮上）。

### 可见行为核对（本批）

- 既有文案零改动：标题「SKU 映射矩阵」「专业库存明细」「总库存」、说明、页脚、两个
  Collapse 面板标题与说明、「主数据」Tag、「返回总库存」、筛选占位符与 aria-label、
  空态（「当前筛选范围内暂无匹配 SKU」「暂无内部 SKU」）、错误标题与权限文案与迁移前
  逐字一致（Spec review 按字符串逐条比对确认）。
- 新增文案仅一处页头标题：InventoryOverviewPage 此前没有页头，「总库存」取自导航标签，
  原 intro 说明文字移入 PageShell description（文案不变）——与 batch A 为无页头页面
  （FulfillmentTasks / SalesOutbound）新增 PageShell 页头同口径。
- URL / API / 跳转零改动：InventoryOverview 筛选（provider_id / sku_id / warehouse_code /
  page / size）与「查看明细」跳转（含 return_to 闭环）、InventoryDetails 的 return_to
  安全回落与能力工具链接、SkuMappings 矩阵两个 list 请求与 jd-pieces-candidates 请求，
  均由既有 route tests（inventoryOverviewRoute / inventoryDetailsRoute / adminMasterDataRoute）
  与本批新增 3 个测试固定。
- 表格列 / 分页 / 行选择 / sticky / 确认弹窗 / 抽屉零改动。
- 有意的承载变化（与 batch A/B 同口径）：
  - SkuMappingsPage 页头 Flex → PageShell：标题字号 level 4 → PageShell level 5 统一；
    工作区可访问名称由 `aria-labelledby`（指向手写标题 id，名称 =「SKU 映射矩阵」）改为
    `aria-label="SKU 映射矩阵工作区"`（标题 id 随手写页头移除；名称变更为更明确的
    「SKU 映射矩阵工作区」，语义等价）；删除 `box-shadow: none` 页级规则后页头卡恢复
    antd 默认阴影（与 fulfillment/workbench 页头一致）。
  - InventoryOverviewPage 筛选区 admin-toolbar → FilterBar（圆角容器外观统一，刷新按钮
    从行内移至 actions 右对齐；查询/重置按钮保留在筛选控件行内——与输入框同属筛选操作
    分组，维持原布局，不因 FilterBar 的 children/actions 拆分而移动）；表格 locale 样板
    → DataTable `emptyText`（渲染节点相同）。
  - InventoryDetailsPage 返回链接与两个状态 Tag 从标题行左/中位移入 PageShell actions
    右侧（与 batch A「刷新按钮进 actions」同口径）。

## 累计度量（供后续批次追加）

以固定基线 e3e6b87（#96 合入后）为准：`frontend/src/pages` 共 54 个页面文件。
Issue #97 立项时采纳数为 PageShell 5/53、DataTable 4/53、FilterBar 3/53；
基线实测（含试点后新增页面）为 PageShell 6/54、DataTable 5/54、FilterBar 3/54。

| 批次 | PageShell | FilterBar | DataTable | 备注 |
|---|---|---|---|---|
| 基线（e3e6b87） | 6/54 | 3/54 | 5/54 | 试点页 + Agent 中心 |
| A fulfillment（第 1 批） | **15/54** | **8/54** | **10/54** | +9 页 PageShell，+5 页 FilterBar/DataTable |
| B workbench（第 2 批） | **17/54** | **9/54** | **12/54** | +2 页 PageShell / +2 页 DataTable（ManualReviewPage + ChannelMessagesPage），+1 页 FilterBar（ManualReviewPage 双视图筛选；ChannelMessagesPage 无常驻筛选行不套） |
| C product + inventory（第 3 批） | **20/54** | **10/54** | **14/54** | +3 页 PageShell（SkuMappingsPage + InventoryOverviewPage + InventoryDetailsPage）；+1 页 FilterBar（InventoryOverviewPage）；+2 页 DataTable（SkuMappingsPage 矩阵表 + InventoryOverviewPage）。BundlesPage 为试点已计基线（本批零改动）；Categories/Products/Skus 由共享骨架 MasterDataCrud 承载不计数 |
| D 最终审计 | 待追加 | 待追加 | 待追加 | 其余页面（orders / procurement / system / dashboard / demo / analytics / agents 存量）+ 全仓逐页核对并收敛口径；MasterDataCrud 骨架的 FilterBar/DataTable 化列入候选 |

口径说明：按「页面文件 import 并实际使用该组件」计数（`grep "components/<Name>'"`
命中即计 1 页）；子组件（如 EvalsTab）单独计数会导致页面数虚高，后续批次沿用
「每页面文件计一次」口径，与 54 页总数对齐。
