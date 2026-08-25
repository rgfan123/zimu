# UX 膨胀研究：现象、根因与收敛建议（基于一手证据）

- 审计时间：2026-08-19
- 方法：只读源码证据（navigation/routes/页面/spec/票），每条结论附 `文件:行号`
- 前置事实：上一轮 UX 审计已闭环——`.scratch/ui-ux-audit-fixes/issues/01–05` 全部 resolved（导入确认明细、启用开关说明、出库 Popconfirm 明细、主数据命名、分析页迁工作台），其依据 `.scratch/saas-visual-system/ux-audit.md`（7 项）与 `layout-audit.md`（2 处可疑）均已消化。因此本文聚焦**结构性存量膨胀**，而非逐点修复。

---

## 1. 现象盘点

### 1.1 规模：9 个一级板块、31 个可路由叶子、27 个可见菜单项、6 个隐藏页

- 导航树定义：`frontend/src/navigation.ts:17-93`。一级板块 9 个（工作台/作业中心/订单中心/库存中心/主数据/上传平台(隐藏)/经营分析/系统管理/模拟下单/BI 外链，实际 10 项含 1 个隐藏分组）。
- 可路由叶子 31 个（`routes.tsx:58-90` routeElements 全部绑定）；`hideInMenu` 隐藏页 6 处（`navigation.ts:39,47,54,55,63,75`）。
- 上一轮布局审计已核对全部 30 页，26 页归属合理、2 处可疑且均已修复（`.scratch/saas-visual-system/layout-audit.md:12-15`）。**"入口错放"类问题已解决，膨胀主要在页面内部与重复实现。**

### 1.2 同一功能两处入口：「刷新三平台订单」

- `frontend/src/pages/system/ConnectorsPage.tsx:82-101`（`handleRefreshPlatforms`，按钮在 190-193）+ `frontend/src/pages/fulfillment/SalesOutboundPage.tsx:173-192`（`refreshPlatforms`，按钮在 204-206）——两处调用同一个 `platformOrdersApi.refresh()`（`frontend/src/api/endpoints.ts:539`），成功文案逐字相同（"三平台刷新完成：…批次"）。
- 佐证：`grep platformOrdersApi` 全前端仅这两页消费。这是历史功能在业务对象导航重组（`.scratch/business-object-navigation/issues/01`）后**未被去重**的典型案例。
- 附加问题：ConnectorsPage 工具栏同时并列两个"刷新"按钮——「刷新三平台订单」(190) 与「刷新」(193)，语义不同却同排，密度与混淆并存。

### 1.3 弹窗/提示密度（按 用户口径 = Modal+Drawer+Popconfirm+Alert 统计，已逐一 grep 核实；括注另有 message 调用数）

| 页面 | 合计 | Modal | Drawer | Popconfirm | Alert | message.* |
|---|---|---|---|---|---|---|
| SalesOutboundPage.tsx | **11（+27）** | 1 (491) | 1 (846) | 2 (284,509) | 7 | 27 |
| ManualReviewPage.tsx | **16（+1）** | 0 | 2 (576,847) | 1 (808) | 13 | 1 |
| TrackingDraftReviewPanel.tsx | **12** | 1 (531) | 0 | 0 | 11 | 0 |
| ShipmentsPage.tsx | **12（+6）** | 1 (211) | 1 (443) | 1 (530) | 9 | 6 |
| FulfillmentTasksPage.tsx | **7（+2）** | 1 (287) | 1 (193) | 0 | 5 | 2 |
| OrderDraftReviewPanel.tsx | **6** | 0 | 0 | 1 (541) | 5 | 6 |
| ChannelMessagesPage.tsx | 4 | 0 | 1 (116) | 0 | 3 | 0 |
| SkuMappingsPage.tsx | 4 | 2 (219,563) | 0 | 0 | 2 | 0 |
| ProcurementTicketsPage.tsx | 3 | 1 (250) | 1 (148) | 0 | 1 | 0 |
| ConnectorsPage.tsx | 3 | 1 (233) | 0 | 0 | 2 | 0 |
| AnalyticsPage.tsx | 2 | 0 | 1 (295) | 0 | 1 | 0 |

用户反馈的 ManualReviewPage 16 / TrackingDraftReviewPanel 12 / ShipmentsPage 12 / SalesOutboundPage 11 / FulfillmentTasksPage 7 / OrderDraftReviewPanel 6 **全部与源码一一对上**（口径 = 前四类）。

### 1.4 「确认动作」有三种形态且各自内嵌 Alert

同一"写操作前确认"意图，系统里并存三种实现：
- **Popconfirm**（轻量）：SalesOutboundPage 批次确认/回传接收 (284,509)、ManualReviewPage 关闭事项 (808)、OrderDraftReviewPanel 拒绝草稿 (541)。
- **Modal + 内嵌说明 Alert + 必填理由**（重型）：FulfillmentTasksPage「创建续发批次」(287-336，Alert 300-305)、ShipmentsPage「编辑收货地址」(211-247，Alert 241-245)、SalesOutboundPage「回传履约结果」(491-572)、ProcurementTicketsPage「取消剩余缺口/重试采购」(250-279)。
- **Drawer 内做写操作**：ManualReviewPage 运营提醒 Drawer (847-871) 内含「确认提醒」提交（860-861 又一条 Alert 说明"此动作只记录…不推进业务状态"）；复核 Drawer (576-845) 内 13 处条件 Alert。

无任何文档或组件约定"哪种确认用哪种形态"——同一页（SalesOutboundPage）两种形态并存。

### 1.5 状态呈现两套体系并行

- **共享体系**（系统/主数据域）：`pages/shared/AdminVisualComponents.tsx` 的 `AdminFailureAlert/AdminLoading/AdminEmpty`，被 ConnectorsPage:161-167、AuditLogsPage:106、FulfillmentProvidersPage:211-217、SystemConfigPage:100-106 使用。
- **手写体系**（订单/履约/工作台域）：每页各自 `{error ? <Alert type="error" showIcon>…</Alert> : null}` + 重试按钮，见 OrderListView:179-201、ManualReviewPage:522,550、FulfillmentTasksPage:148、ShipmentsPage:193,399、SalesOutboundPage:804、JdStockQueryPage:408、DashboardPage:280 等 ≥10 页。
- **「刷新/重试」按钮重复实现 ≥12 处**：FulfillmentProvidersPage:228、SystemConfigPage:125,150、ChannelMessagesPage:87,93、InventoryOverviewPage:285、ManualReviewPage:533,559、AuditLogsPage:117、ProcurementTicketsPage:123、ShipmentsPage:181,414、SalesOutboundPage:819、SkuMappingsPage:341,548、MasterDataCrud:262。

### 1.6 京东工具：6 个子菜单 = 5 份重复的「只读查询页」骨架

- 菜单：`navigation.ts:77-88` 京东工具 6 子项（连接与出库/基础资料/库存原始/序列号/专业单据/退货退供），URL 均落在 `/fulfillment/jd-*`（URL 命名空间与菜单归属解耦是 issue 01 的刻意设计，layout-audit.md:108）。
- 其中 JdBasicInfoQueryPage（7 个查询）、JdStockQueryPage（7 个）、JdSerialQueryPage（4 个）、JdOrderQueryPage、JdReturnQueryPage 各自实现同一结构：`QUERIES/QUERY_DEFS` 配置数组 + Select 选查询 + Form 参数 + Descriptions 结果 + 「业务码 2001 权限未开通」分支 + `message.success/warning/error` 反馈（JdBasicInfoQueryPage:258-263、JdStockQueryPage:357-365、JdSerialQueryPage:220-227）。grep 计数：BasicInfo 5 / Stock 4 / Order 4 / Serial 2 处同名配置结构。仅 JdWarehousePage 特化（直接分页）。
- 结论：6 页可收敛为 1 个 `JdQueryPage` 骨架组件 + 6 份纯配置。

### 1.7 菜单按"过滤视图"建入口（代码复用良好、IA 冗余）

- 订单中心 4 个可见菜单项全是 `OrderListView` 薄壳：OrdersPage.tsx:6-8（无筛选）、PendingOrdersPage.tsx:6-12（`NEED_REVIEW`）、ExceptionOrdersPage.tsx:6-12（`FULFILLMENT_EXCEPTION`）、OrderTrackingPage.tsx:6-12（`SHIPPED`）。一个列表组件 + 4 个菜单入口。
- 主数据板块 4 页中 2 页隐藏（`/product/products`、`/product/categories`，navigation.ts:54-55），仅靠 SkusPage:314-315 两个链接可达——隐藏页可达性 OK，但菜单只露 2/4。

### 1.8 演示页在主菜单、视觉组件双实现

- `/demo/order`「模拟下单」是运营主菜单可见项（navigation.ts:91；routes.tsx:101 图标）。MVP 演示需求（`.scratch/mvp-productization/spec.md` User Story 4/5）使其存在合理，但作为演示/ Mock 页常驻生产菜单是膨胀来源。
- ECharts 封装两份：`components/Chart.tsx` 与 `pages/analytics/AnalyticsChart.tsx`（同一 init/ResizeObserver/dispose 生命周期，后者注释自述"共享 Chart 仍服务其他页面；本组件仅承担 analytics 的视觉契约"）；`components/KpiCard.tsx` 与 `pages/analytics/AnalyticsKpiCard.tsx` 同理。

### 1.9 术语口径已收敛（正面）

- CONTEXT.md:9「订单 ≠ 履约 ≠ 发货」、:14 导入批次确认、:34 复核事项等边界清晰；04 号票已把 9 处「商品中心」残留统一为「主数据」（见 `issues/04-product-center-naming.md`）。仅剩 layout-audit.md:109 备注的「文件作业」菜单名比页面标题宽泛这一级文案问题。

---

## 2. 根因分析

1. **逐票独立打补丁，缺跨页交互规范**。5 张 UX 票 Scope 全部限定单页（issues/01 Scope: SalesOutboundPage；02: 3 个 system 页；03: ShipmentsPage），每次都"照抄邻页先例"（ux-audit.md:34-41 明确"对标 TrackingDraftReviewPanel 批量确认区"），但从未把「确认动作」「查询页」「加载态」抽象为共享组件——于是形成 §1.5/§1.6 的重复实现。launch 秩序是"正确性优先"，代价是结构性债务。
2. **Toast/弹窗代替页面内联状态**。SalesOutboundPage 单页 27 处 `message.*`（§1.3）——操作结果一律走 toast，页面主区反而空白；错误态每页手写 Alert（§1.5）。没有「页面内结果区」这一模式。
3. **菜单按查询场景而非业务对象建入口**。业务对象导航已重组（`.scratch/business-object-navigation/issues/01-04` 全部 resolved），但叶子仍多：订单中心 4 个过滤视图、京东工具 6 个查询页（§1.6/§1.7）——"为每种查法开菜单"的惯性仍在。
4. **历史功能重构后未去重**。刷新三平台（§1.2）在导航重组后留在两个板块，无人收敛。
5. **演示/生产混层**（§1.8）。demo 页占生产菜单，视觉组件因"域主题差异"复制而非参数化。

---

## 3. 高优先级收敛建议（按 ROI 排序）

### 建议 A（最高 ROI）：抽取「只读查询页」骨架，收敛 6 个京东页 + 去重「刷新三平台订单」
- 现状证据：§1.6（5 份同构查询页）、§1.2（双入口同 API）。
- 建议动作：新建 `pages/shared/JdQueryPage.tsx`（配置数组驱动：查询名/参数/白名单/接口），6 个京东页改为 6 份配置；「刷新三平台订单」收敛为单一入口（建议留在文件作业页，渠道接入页改为指向它的链接或移除按钮）。
- 影响范围：frontend 6 页 + 2 页按钮，预计净删 500+ 行；菜单、URL、旧书签不变（issue 01 的 hideInMenu/旧 URL 兼容设计已保证）。

### 建议 B：统一「加载/错误/空态」呈现，替换手写 Alert
- 现状证据：§1.5 两套体系并行、≥12 处手写刷新/重试。
- 建议动作：把 `AdminFailureAlert/AdminLoading/AdminEmpty` 提升为全站 `PageState/QueryState`（含统一重试、统一文案），订单/履约/工作台页的 `{error ? <Alert/>}` 全部替换；顺带把散落的「刷新」按钮收敛为工具栏统一组件。
- 影响范围：~10 个页面，每页删 3-6 处手写 Alert；视觉与语义双统一。

### 建议 C：建立「确认动作」三形态规范，堵住"弹窗叠弹窗"
- 现状证据：§1.4 三种形态并存且 Modal 内嵌说明 Alert、Drawer 内做写操作。
- 建议动作：定规范——行内轻量确认用 Popconfirm（禁止再包 Alert）；需理由/表单的确认用 Modal（影响文案放标题与正文，不重复内嵌 Alert）；大批量复核用 Drawer 但**不在 Drawer 内叠 Modal**。SalesOutboundPage 的 27 处 toast 收敛为页面底部固定「操作结果区」。
- 影响范围：SalesOutboundPage、ShipmentsPage、FulfillmentTasksPage、ProcurementTicketsPage、ManualReviewPage；同时可作为新页面的开发约定写入 docs。

### 建议 D：菜单收敛（IA）
- 现状证据：§1.7 订单中心 4 入口 = 1 组件 4 预设。
- 建议动作：全部订单页内做「预设筛选」Segmented/标签（全部/待处理/异常/已发货），保留 URL query 直达（`/orders?stage=NEED_REVIEW`）以满足运营高频路径；「模拟下单」移出主菜单（保留 URL 与演示文档入口）。
- 影响范围：导航树 + 订单中心 4 页合并为 1 页 + 1 个演示页隐藏；保守方案可仅合并订单 4 入口。

### 建议 E：视觉组件参数化去重
- 现状证据：§1.8 Chart/KpiCard 双实现。
- 建议动作：`AnalyticsChart` 改为 `Chart` 增加 `theme/onClick/ariaLabel` props，删除 analytics 本地副本；KpiCard 同理。
- 影响范围：2 个组件，改动小收益直接。

### 建议 F（低优先）：文案口径收尾
- 「文件作业」菜单名对齐页面标题（layout-audit.md:109 备注），1 行文案级改动。

---

## 4. 风险与边界（哪些"冗余"不能一刀切）

1. **不可逆写操作的人工确认是业务硬需求，不是冗余**。CONTEXT.md:14（导入批次确认"不得拆成逐行或逐客户确认"）、:54（履约导出"文件一旦生成即形成履约承诺"）、:34（复核事项"均不能由模糊匹配自动关闭"）均要求确认；京东出库建单 REAL 模式、取消采购缺口同理。03 号票的方向是**给 Popconfirm 补对象明细**（issues/03），不是删确认。建议 C 只统一形态，不减少确认次数。
2. **ManualReviewPage 的 13 处 Alert 是"一处 Drawer 内按 action 分支的条件信息"，不是 13 个弹窗**（§1.4 证据行 622-744：JD_STOCK/JD_SKU_MAPPING/CUSTOMER/SKU 各分支各一条提示）。收敛方向是"提示分级/折叠"（默认收起、展开看细节），不是删减——每一条都在回答"这个事项为什么需要人工、下一步做什么"。
3. **message.* 多数是必要行为反馈**。问题在"位置不一致"（toast 满天飞）而非数量本身；建议改统一结果区，不清零。
4. **京东工具归属系统管理是正确的设计**（business-object-navigation/01 明确"低频专业查询收纳"），合并骨架 ≠ 合并菜单；且 6 个旧 URL 必须保持可访问（issue 01 的 hideInMenu + 已注册路由已保证，layout-audit.md:108）。
5. **订单中心 4 预设是运营高频直达路径**（layout-audit.md:50-52 判定合理）；合并需保留直达语义（query 参数），删除入口会破坏工作流。
6. **Agent 决策层在途**（`.scratch/agent-decision-layer/spec.md`：采购比价/数据查询 Agent、只读建议）——后续界面会新增"Agent 建议"形态，建议 A/B/C 的组件抽象正好为它提供落点，属前瞻收益而非风险。

---

### 附：证据索引（本文引用的一手文件）

- 导航/路由：`frontend/src/navigation.ts`、`frontend/src/routes.tsx`
- 页面：`frontend/src/pages/**/*.tsx`（行号见正文）
- 共享组件：`frontend/src/pages/shared/{AdminVisualComponents,MasterDataCrud}.tsx`、`frontend/src/components/{Chart,KpiCard}.tsx`
- 术语：`CONTEXT.md`
- 既有票：`.scratch/ui-ux-audit-fixes/issues/01-05`（均 resolved）
- 审计依据：`.scratch/saas-visual-system/{ux-audit,layout-audit,wip-assessment}.md`
- 导航重组：`.scratch/business-object-navigation/issues/01-04`（均 resolved）
- 产品化：`.scratch/mvp-productization/spec.md`；Agent 层：`.scratch/agent-decision-layer/spec.md`
