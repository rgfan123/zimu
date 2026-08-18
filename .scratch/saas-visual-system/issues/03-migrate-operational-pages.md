# 03 — 迁移订单、履约与工作台页面

**Type:** implementation

**What to build:** 订单运营人员在工作台、订单和履约流程中获得一致、克制的页面层级；主要操作、等待事项和真实异常清晰可见，普通状态不再以多种高饱和颜色装饰。

**Blocked by:** 01 — 扩展克制的 SaaS 主题 Token

**Status:** resolved

**Claimed by:** opencode（2026-08-15 接管重做；原认领方 `/root/ticket_frontier_inventory` 经 wip-assessment 确认无实现痕迹）

- [x] 工作台、订单与履约导航下的生产页面统一采用主题背景、表面、文字、边框、间距和品牌色规则。
- [x] 每个操作区域只有一个明确的主要动作；次要动作、筛选器和辅助链接采用更低视觉权重。
- [x] 状态标签按稳定语义映射颜色，普通类别与来源渠道使用中性或品牌色阶，不使用任意彩虹色。
- [x] 表格、详情、步骤、弹窗、抽屉、分页、空态、加载、禁用和错误反馈在不同页面保持一致。
- [x] 已存在的危险操作确认、写门闩提示和错误信息不能因降饱和而变得不明显。
- [x] 每个一级业务区域至少保留一个代表页面截图，并验证交互、路由与现有前端测试不回退。

## Comments

**2026-08-15 opencode 收口记录**

### 做了什么

1. **新建共享语义状态模块 `frontend/src/pages/shared/semanticStatus.ts`**（不复用、不修改 04 的 adminVisual* 文件）：
   - `ORDER_STATUS_SEMANTIC` / `PROCESSING_STAGE_SEMANTIC` / `SHIPMENT_STATUS_SEMANTIC` / `SHIPPING_PROGRESS_SEMANTIC` / `FULFILLMENT_OUTCOME_SEMANTIC` / `EXPORT_USAGE_SEMANTIC` 只输出 antd 语义预设（default/processing/success/warning/error，随 saasTheme token 降饱和）；
   - `CHANNEL_ACCENT` 来源渠道走品牌/数据色阶点缀色；
   - `severitySemantic` / `reviewCaseStatusSemantic` / `importRowStatusSemantic` / `jdConnectionSemantic` 等单值映射。
2. **`constants/labels.ts`**：删除 `CHANNEL_COLORS`（geekblue/purple/cyan/green），`ORDER_STATUS_COLORS` 改由 `ORDER_STATUS_SEMANTIC` 提供；新增 `PROCESSING_STAGE_COLORS`、`SHIPMENT_STATUS_COLORS`（原为空映射）。
3. **`components/StatusTag.tsx`**：状态 kind 走语义预设；渠道 kind 改为中性底（`token.colorFillTertiary`）+ 品牌色阶圆点，色点+文字双通道。
4. **hex 消除（28 处 → 0，范围文件内）**：DashboardPage（BLUE/GREEN/GOLD/RED 局部调色板 → `saasChartPalette.categorical` / `saasVisualTokens` / `ATTENTION_COLORS`）、ManualReviewPage（#1d4ed8/#b45309 → brand/semantic token）、KpiCard（#2563eb 默认色 → brand.primary；#1c2230/rgba 文字 → `theme.useToken()`；删除内联阴影与彩色顶边）、Chart（加载色 → brand.primary）、PlaceholderPage（#c3cad6 → token）、Jd* 六页（#1d4ed8 → brand.primary）。
5. **重复内联 rgba 阴影 `0 1px 2px rgba(16,24,40,.05)...` 全部移除**（DashboardPage 2、OrderListView 2、FulfillmentTasksPage 2、SalesOutboundPage 3、JdOrder/JdStock 各 1、KpiCard 1、PlaceholderPage 1），Card 统一交给主题 `boxShadow` token。
6. **antd 具名预设 Tag 色全部消除**：labels.ts（13 项）、FulfillmentTasksPage（PROGRESS/OUTCOME 映射 + red×2→error）、SalesOutboundPage（USAGE_COLORS + 导入明细 green/red/gold）、Jd 页（green/orange/blue/purple → `jdConnectionSemantic`/`READ_ONLY_TAG_COLOR`/`default`）、ManualReviewPage（gold/green/red → 语义函数）。保留的均为 antd 语义预设（success/error/warning/processing/default），随主题降饱和；danger/错误语义原样保留（checkbox 5）。
7. **`styles/global.css` `.tl-*` 时间线 18 处 hex → `var(--zimu-saas-*)` CSS 变量**（colorPrimary/colorSuccess/colorWarning/colorError/colorText*/colorBorder*/colorFill*/boxShadow/colorPrimaryBg）。
8. **单一主要动作**：ManualReviewPage 工具栏「刷新」保持默认按钮权重，仅抽屉内处理动作保留 primary；各列表页筛选栏无 primary 按钮；SalesOutboundPage「开始导入」为该区域唯一 primary。
9. 行为零变化：未改数据提交、权限、路由、错误处理；`ShipmentsPage/OrderDetailPage/ChannelMessagesPage/channelMessageView`（wecom-message-intake 未提交改动）与 04 范围文件均未触碰。

### 截图（1440×900，真实后端数据，vite dev :5193 + 管理网关）

- `output/playwright/saas-ops-03/dashboard-1440.png`（真实 summary 数据）
- `output/playwright/saas-ops-03/workbench-manual-review-1440.png`（真实复核队列）
- `output/playwright/saas-ops-03/orders-list-1440.png`（真实订单数据，含语义状态标签）
- `output/playwright/saas-ops-03/fulfillment-tasks-1440.png`（真实履约数据）

注：本次截图经程序化校验（尺寸、色彩分布、后端 API 返回真实数据）确认非空态渲染；订单/履约/复核接口均返回真实数据。dev server 截图后已关闭。

### 验证结果

- `cd frontend && npm test`：**155/155 通过，0 失败**
- `cd frontend && npx tsc --noEmit`：**0 错误**
- 残留扫描：03 范围可编辑文件内 6 位 hex = 0、antd 具名预设 Tag 色 = 0、`rgba(16,24,40` 内联阴影 = 0（OrderDetailPage/ShipmentsPage/ChannelMessagesPage 属 wecom-message-intake 未提交改动文件，按票边界未动，遗留色留给 05）。

**2026-08-15 opencode 独立复核记录（第二轮会话，只读验证 + 本记录）**

- 残留扫描复测通过：03 范围可编辑文件 6 位 hex = 0（仅 `constants/charts.ts` 25 处，已确认为零引用死代码，属 05 清理项）、具名预设 Tag 色 = 0、`rgba(16,24,40` 阴影 = 0、`global.css` hex = 0（`.tl-*` 全部走 `var(--zimu-saas-*)`）。
- `labels.ts` 的 `EventTone`（blue/green/gold/red/gray）经 `OrderTimeline` 证实只作 `.tl-dot--*` CSS 类后缀、颜色来自 CSS 变量；组件内 antd Tag 仅 `error`/`processing` 语义预设，不构成彩虹残留。
- 截图复验：4 张均 1440×900、色彩分布 2300–2700  distinct colors，逐张目检确认 SaaS 主题生效（语义状态标签、渠道中性底+色点、筛选栏低权重、工具栏无 primary）。
- 单一主要动作复验：ManualReviewPage 工具栏「刷新」为默认权重，primary 仅存在于抽屉/弹窗处理动作；列表页筛选栏无 primary。
- 边界复验：wecom-message-intake 8 个已跟踪文件 mtime 均早于 03 实施会话（未被触碰）；04 共享文件行为由 `adminVisualSystem.test.ts` 9/9 担保；`saasTheme.ts` 未动；无 git 提交。
- 验证复跑：`npm test` 155/155 通过；`npx tsc --noEmit` 0 错误；5193 端口无残留 dev server。
- 结论：票内收口记录与工作树现状一致，6 个 checkbox 全部成立，维持 resolved。
