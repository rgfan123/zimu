# saas-visual-system 03/04/05 WIP 完成度评估（只读）

- 评估时间：2026-08-15
- 评估方式：只读。逐条对照三张票 checkbox，检查 `frontend/src` 工作树现状、截图证据（`output/playwright/`、`.playwright-cli/`）、测试与类型检查；未改任何代码、未提交。
- 基线说明：`frontend/` 仅有 11 个文件被 git 跟踪（`git ls-files frontend`），其余（含 `src/theme/`、`src/pages/` 大部分、所有测试）均为未跟踪文件；已跟踪的 8 个前端文件的工作树修改属于 wecom-message-intake 特性（与 03/04 无关）。因此 03/04 的「WIP」只能以工作树现状评估，git 无法提供迁移前基线。

---

## 基础设施（01 交付物，已就绪）

- `frontend/src/theme/saasTheme.ts`：token 齐全——brand（primary `#3f6fd1`）、data（蓝/青/紫 6 阶）、neutral（50–900）、surface（canvas/raised/sunken）、text（primary/secondary/tertiary/inverse）、semantic（info/success/warning/error）+ `saasChartPalette` + antd `ThemeConfig`（cssVar `zimu-saas`）。
- 已接线：`frontend/src/main.tsx` 通过 `ConfigProvider theme={saasTheme}` 全局生效。
- 测试：`frontend/test/saasTheme.test.ts` 5/5 通过（对比度、语义色隔离、焦点、动效预算）。
- 04 在其上派生了一套共享视觉系统：`src/pages/shared/adminVisualCore.ts`（状态→语义 tone 映射，token 注入）+ `adminVisual.ts` + `AdminVisualComponents.tsx`（AdminStatusTag / AdminCategoryTag / AdminFailureAlert / AdminEmpty / AdminLoading）+ `adminSurface.css`（`--ant-*` CSS 变量）+ `MasterDataCrud.tsx`（主数据 CRUD 骨架）。`adminVisualSystem.test.ts` 9/9 通过。

---

## 03 — 迁移订单、履约与工作台页面（claimed，未收口）

**完成度：约 5–10%。工作树中未发现任何 03 实现痕迹。**

范围页面：`/dashboard`（DashboardPage）、`/workbench/*`（ManualReviewPage、ChannelMessagesPage）、`/orders/*`（OrdersPage/OrderListView、PendingOrdersPage、ExceptionOrdersPage、OrderTrackingPage、OrderDetailPage）、`/fulfillment/*`（FulfillmentTasksPage、SalesOutboundPage、ShipmentsPage、Jd*QueryPage×5）。

硬数据：工作台/订单/履约/仪表盘页面及其共用组件（KpiCard、Chart、PlaceholderPage）对 `saasVisualTokens/saasTheme/useToken` 的引用数 = **0**；硬编码 6 位 hex = **28 处**；复用同一条遗留内联阴影 `rgba(16,24,40,.05)` 的 Card = 10+ 处。

| # | Checkbox | 状态 | 证据 |
|---|----------|------|------|
| 1 | 生产页面统一采用主题背景/表面/文字/边框/间距/品牌色 | ❌ 未做 | 0 处 token 引用；DashboardPage L21-24 仍定义 `BLUE #2563eb / GREEN #16a34a / GOLD #f59e0b / RED #ef4444`；KpiCard 仍带彩色顶边与彩色图标；各页重复内联 rgba 阴影；ManualReviewPage 硬编码 `#1d4ed8`、`#b45309`；Jd* 页各硬编码 `#1d4ed8` |
| 2 | 每操作区域单一主要动作，次要动作低视觉权重 | ⚠️ 部分（未做刻意设计） | 页面主要用 antd 默认 Button（ConfigProvider 下 primary 唯一性尚可），但无刻意收敛；ManualReviewPage 工具栏多个同权重按钮（批量/驳回/重跑映射等） |
| 3 | 状态标签稳定语义色，不用任意彩虹色 | ❌ 未做 | `constants/labels.ts`：`CHANNEL_COLORS`（geekblue/purple/cyan/green）、`ORDER_STATUS_COLORS`（blue/cyan/geekblue/green/gold/orange/red）经 StatusTag 全站使用——antd 具名预设色不随主题 token 变化，仍是高饱和彩虹；`channelMessageView.ts` intentColor（blue/cyan/orange/volcano/red/default）；OrderDetailPage 时间线 `.tl-dot--*`（global.css：`#2563eb/#16a34a/#d97706/#ef4444/#94a3b8`）；ManualReviewPage gold/green/red 三元 |
| 4 | 表格/详情/步骤/弹窗/抽屉/分页/空态/加载/禁用/错误跨页一致 | ⚠️ 部分 | antd 组件经 ConfigProvider 自动获得基础一致（表格/弹窗/空态/加载），但页面级覆写仍在（内联 rgba 阴影、`#7a8699` 小字、OrderTimeline 自定义 CSS）；无系统性一致性检查 |
| 5 | 危险操作确认、写门闩提示、错误信息不因降饱和变不明显 | ⚠️ 未验证（也无回归） | 本批未做任何降饱和，危险按钮仍走 antd danger（token `colorError #a44f57`），无回归但无验证；`OrderDraftReviewPanel` 有 danger 按钮、`ShipmentsPage` 有确认弹窗 |
| 6 | 每个一级业务区域至少一张代表截图 + 交互/路由/测试不回退 | ❌ 未做 | 03 范围（dashboard/workbench/orders/fulfillment）**零截图**；`.playwright-cli` 08-13 的捕获几乎全为 `/metabase`；`output/playwright/` 无 03 迁移截图（`manual-review.png`、`jd-warehouse.png` 为早期特性截图） |

**结论：03 无实质实现，checkbox 全空并非「未更新」而是「确实没做」。**

---

## 04 — 迁移主数据、采购与系统配置页面（claimed by codex → `/root/saas_admin_04`，未收口）

**完成度：约 60–70%。共享视觉系统已完成并被 7 个页面消费，有截图证据；存在明确缺口。**

范围页面：`/product/*`（ProductsPage、CategoriesPage、SkusPage、SkuMappingsPage）、`/procurement/tickets`（ProcurementTicketsPage）、`/system/*`（ConnectorsPage、FulfillmentProvidersPage、SystemConfigPage、AuditLogsPage）。

| # | Checkbox | 状态 | 证据 |
|---|----------|------|------|
| 1 | 商品/SKU、采购、连接器、系统配置统一主题，不新增局部调色板 | 🟡 已做（有 1 例外） | Products/Categories/Skus 走 `MasterDataCrud`；Procurement 用 `AdminStatusTag`；Connectors/Providers/SystemConfig 用 `AdminCategoryTag`+`AdminStatusTag`；SkuMappingsPage 用 `token.*`（01 交付）。**例外：`AuditLogsPage` 未迁移**（`Tag color="purple"/"blue"/"green"/"red"` 预设 + 内联 rgba 阴影） |
| 2 | 表单分组/说明/验证错误/保存反馈/只读层级，保存动作唯一焦点 | 🟡 部分 | `MasterDataCrud` 统一了弹窗表单 + 保存反馈（message）+ 乐观锁；但主要依赖 antd 默认视觉，无刻意层级设计；SKU 页有 validation/save/success 截图（`saas-admin-04-final/*`） |
| 3 | 连接状态/权限门禁/外部验证用稳定语义色 + 文字/图标双通道 | ✅ 已做 | `adminVisualCore.ts` 状态表（PENDING/SUCCESS/PARTIAL/FAILED/CONFIGURED/UNCONFIGURED…）映射 semantic token，`AdminStatusTag` 带图标+文字；`permissionFailurePresentation` 403 有独立 Alert（`saas-admin-04-permission-403.png`）；connector 凭据/模式状态有截图 |
| 4 | Alert/Tag/Badge/Button/Input/Table/Card 在配置页与业务页一致 | 🟡 部分 | 共享组件 + antd 主题已覆盖大部分；AuditLogsPage 与早期页面（SalesOutboundPage 等）仍用预设色 |
| 5 | 空态/加载/成功/警告/错误/禁用/无权限完成代表视觉检查 | 🟡 部分 | 有证据：SKU loading/error-500/save-pending-disabled/save-success/validation（`output/playwright/saas-admin-04-final/`）、connectors 1440 + connector-success、procurement 1440 + detail、permission-403。**缺**：明确的 warning、empty 状态截图；且这些截图只覆盖 SKU/connector/procurement，未覆盖 providers/system-config |
| 6 | 不改变数据提交/权限/路由/错误处理，相关测试通过 | ✅ 通过 | `npm test` 155/155；`npx tsc --noEmit` 0 错误；`adminMasterDataRoute.test.ts`、`adminVisualSystem.test.ts`、`skuMappingMatrix.test.ts`、`providerSkuMapping.test.ts` 等通过 |

**结论：04 是「实现了主体、未收口」——共享视觉层质量不错（语义色 + 图标双通道、403 提示、token 注入），收口工作量集中在 AuditLogsPage 迁移、补齐 warning/empty 证据、逐条 checkbox 对账与测试留档。**

---

## 05 — 收缩遗留颜色并完成全站视觉回归（ready-for-agent，blocked by 02/03/04）

**状态：未开工（符合依赖关系）。** 下方扫描结果为 05 开工时的工作清单。

### 遗留颜色扫描（frontend/src，共 146 处 hex）

| 类别 | 文件 | hex 数 | 说明 |
|------|------|--------|------|
| 主题源（保留） | `src/theme/saasTheme.ts` | 45 | token 唯一权威来源 ✅ |
| 死代码（可删） | `src/constants/charts.ts` | 25 | **零 import 者**（全站 grep 无 `@/constants/charts` 引用；SalesOutboundPage 已本地化 USAGE_*，analytics 已用 `analyticsVisualSystem`）→ 05 首要清理项 |
| 03 范围残留 | `DashboardPage` 8、`ManualReviewPage` 3、`FulfillmentTasksPage` 3、`ShipmentsPage` 2、`SalesOutboundPage` 2、`KpiCard` 2、Jd* 页 6×1、`PlaceholderPage` 1、`Chart.tsx` 1 | 28 | 待 03 迁移消化 |
| 04 范围残留 | `AuditLogsPage`（hex 0，但预设色 + rgba 4） | 0 | 预设色见下 |
| 全局 CSS | `src/styles/global.css`（`.tl-*` 时间线） | 18 | OrderTimeline 用，随 03 迁移 |
| CSS 变量回退 | `skuMappings.css` 13（productIdentity.css 另含 2 处 rgba 回退） | 13 | `var(--ant-*, #fallback)` 形式，惰性可留可清 |
| 隔离 Demo（保留） | `demo/AiOrderAssistantPanel.tsx` 9、`DemoOrderPage.tsx` 8 | 17 | 按 05 票语言「不删除仍服务隔离 Demo 的兼容项」保留 |

- rgba()：共 53 处，绝大多数是同一串遗留内联阴影 `0 1px 2px rgba(16,24,40,.05), 0 2px 8px rgba(16,24,40,.06)` 在 03 范围 Card 上的重复（OrderDetailPage 4、OrderListView 2、ShipmentsPage 2、SalesOutboundPage 3、FulfillmentTasksPage 2、AuditLogsPage 2、DashboardPage 4、KpiCard 2…）——与主题 `boxShadow` token 冲突。
- antd 预设 Tag 色（彩虹色，不随主题 token 变化）：约 40 处，分布 `labels.ts`（CHANNEL 4 + ORDER_STATUS 13 个映射项）、`ChannelMessagesPage` intentColor 6 项、`OrderDetailPage` 4、`FulfillmentTasksPage` 本地 PROGRESS/OUTCOME 映射、`ShipmentsPage` STATUS_COLORS、`SalesOutboundPage` USAGE_COLORS、`AuditLogsPage` 4、Jd 页 6、`ManualReviewPage` 3。注意：antd 语义预设（`success/error/warning/processing`）走 theme token 会随主题降饱和；具名预设（`blue/green/gold/red/purple/cyan/geekblue/orange/volcano`）固定高饱和，不随主题——是「彩虹残留」的主体。
- 页面级局部调色板（05 票点名「页面级调色板」）：`ShipmentsPage.tsx` STATUS_COLORS、`FulfillmentTasksPage.tsx` PROGRESS/OUTCOME_COLORS、`SalesOutboundPage.tsx` USAGE_COLORS、`labels.ts` ORDER_STATUS_COLORS/CHANNEL_COLORS（经 StatusTag 消费）。

### 05 其余 checkbox

- 全站桌面视觉回归：无基线（仅 01 的 SKU 映射、02 的 analytics、04 的 SKU/connector/procurement 有截图；03 范围零截图）。
- 抽查关键状态：仅 04 部分覆盖（loading/error/save/403），无常态/空态/警告/禁用全站矩阵。
- 对比度与不只靠颜色：01 有对比度测试（saasTheme.test.ts），03/04 范围未验证；04 的 AdminStatusTag 已是「图标+文字+色」三通道 ✅。
- 测试/构建/路由回归 + 最终截图基线：测试 155/155 与 tsc 通过，但未跑 build（本评估只读，未执行）；无最终基线。

---

## 前端测试与类型检查（本评估实测）

- `cd frontend && npm test`：**155/155 通过，0 失败**（node --test，38.3s）。相关聚焦测试：saasTheme 5/5、adminVisualSystem 9/9、channelMessageView 8/8、manualReviewActions 5/5、adminMasterDataRoute 1/1。
- `npx tsc --noEmit`：**0 错误**（exit 0）。
- 未执行 `npm run build`（会写 dist，超出只读约束）；01 票记录此前 build 通过（3661 modules，仅既有 chunk-size 警告）。

---

## 建议

### 03 — 重开（或明确回退认领）
没有可收口的实现。选项：
1. **优先做 04 收口 → 03 整票重做**：03 范围大（4 个一级区域、25+ 页面文件），建议复用 04 已验证的 `adminVisualCore`/`AdminStatusTag` 模式（提取为共享层，而非复制 04 的 `pages/shared` 私有实现），把 StatusTag 的预设色映射替换为 semantic token 映射，替换 global.css 时间线色阶，删除内联 rgba 阴影。
2. 若 03 原认领方已不可用，建议更新认领信息后按「04 的套路」重走：共享语义组件 → 逐页迁移 → 每区域 1 张 1440px 截图 → 交互/路由/测试回归。

### 04 — 直接收口（小工作量）
1. 迁移 `AuditLogsPage`（预设色 → AdminStatusTag/语义映射）。
2. 补 warning/empty 代表截图（providers、system-config 各 1 张 1440px）。
3. 逐条 checkbox 对账写回票内 + 跑全量测试（当前 155/155）与 `tsc --noEmit` 留档。
4. 注意 `pages/shared/adminVisual*` 与 `MasterDataCrud` 目前全未跟踪——收口提交时需一并纳入版本控制。

### 05 — 保持 blocked，开工清单已就绪
依赖 03/04 完成后解锁。已有可执行清单：删 `constants/charts.ts`（死代码，25 hex）→ 随 03/04 消化页面级调色板与 global.css 时间线 → 全站 1440px 基线截图（每一级路由至少 1 张，补常态/空/加载/成功/警告/错误/禁用/无权限抽查矩阵）→ 语义预设与具名预设隔离检查 → 对比度抽查。

### 风险提示
- 当前工作树混有 wecom-message-intake 特性未提交改动（tracked 的 8 个前端文件）与 03/04 全部未跟踪文件，提交时需注意区分，避免把 03/04 收口混入无关特性提交。
- antd 具名预设 Tag 色不随 ConfigProvider 主题变化——03/04/05 的「状态标签语义化」若不替换这些预设，主题降饱和将形同虚设（这是当前遗留色的主要结构性来源）。
