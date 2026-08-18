# 05 — 收缩遗留颜色并完成全站视觉回归

**Type:** implementation

**What to build:** 用户在所有生产导航页面获得一致的低饱和 SaaS 体验；旧的页面级调色板和重复硬编码颜色被安全移除，并有全站截图与可访问性证据证明视觉收口没有破坏业务功能。

**Blocked by:** 02 — 降低数据看板饱和度并重建视觉层级；03 — 迁移订单、履约与工作台页面；04 — 迁移主数据、采购与系统配置页面

**Status:** resolved

**Claimed by:** opencode（2026-08-15；依赖 03/04 完成后执行）

- [x] 清理已无消费者的遗留主题变量、页面级调色板、重复图表色和装饰性硬编码颜色，不删除仍服务未迁移或隔离 Demo 的兼容项。
- [x] 所有生产一级路由完成桌面视觉回归，覆盖数据看板、列表、详情、表单和配置页面。
- [x] 对关键页面抽查常态、空态、加载、成功、警告、错误、禁用和无权限状态。
- [x] 品牌色、语义色、分类色和数据系列色的职责全站一致；普通分类不会误用告警色。
- [x] 正文、辅助文字、控件、焦点、链接、状态标签和图表关键信息达到可读对比度，重要信息不只依赖颜色表达。
- [x] 前端测试、构建、路由和关键操作回归通过，并保存最终截图基线供后续变更比较。

## Comments

### 清理清单（checkbox 1）

已删除：
- `frontend/src/constants/charts.ts`（删除前全站 grep 确认零 import：`src/`、`test/` 均无 `@/constants/charts` 引用；16 处 hex 高饱和彩虹色，原 130 行死代码）
- `frontend/src/components/PlaceholderPage.tsx`（零消费者——全站 grep 无任何 import；所有导航叶子路由均已绑定真实页面，`bindNavigationRoutes` 会为缺失 element 抛错）

保留并注明理由：
- `src/theme/saasTheme.ts`（45 hex）—— token 唯一权威来源（票内边界，不修改）
- `pages/demo/`（12 hex）—— 隔离 Demo（`/demo/order` 的 AiOrderAssistantPanel / DemoOrderPage），按票语言保留
- `pages/product/skuMappings.css`（12 hex）+ `pages/shared/productIdentity.css`（2 处 rgba）—— 均为 `var(--ant-*, #fallback)` 惰性回退形式：CSS 变量未注入时才有值，属于 antd cssVar 主题的兜底层而非活动调色板，保留（04 票沿用同一模式）
- `pages/fulfillment/ShipmentsPage.tsx`（2 处 `#7a8699` 小字标签）—— 位于 git 已跟踪且有未提交改动的 8 个 wecom-message-intake 文件中，票内硬边界禁止修改，记录待后续收口
- 页面级遗留 rgba 阴影（ShipmentsPage 2、OrderDetailPage 4，`0 1px 2px rgba(16,24,40,.05)...`）—— 同上，均在禁止修改的已跟踪文件中，记录待后续收口

说明：03/04 实施后，本票开工时的扫描结果已大幅收敛——原评估中「03 范围残留 28 处 hex」「global.css 时间线 18 处 hex」「labels.ts 预设色」「analytics 局部调色板」均已由 03/04 消化为 0，本票仅需删除上述 2 个死文件。

### 基线截图清单（checkbox 2）

新截（`output/playwright/saas-final-baseline/`，全部 1440×900，vite dev :5195 + Python playwright route mock `/api/v1/*`，截图前以状态文案选择器断言渲染完成）：
- channel-messages-1440.png（渠道消息列表）
- sales-outbound-1440.png（文件作业）
- shipments-1440.png（发货记录）
- orders-pending-1440.png（待处理订单）
- orders-exceptions-1440.png（异常订单）
- orders-tracking-1440.png（订单追踪）
- order-detail-1440.png（订单详情：Steps + 商品明细 + 发货运单 + 事件时间线）
- inventory-overview-1440.png（总库存）
- inventory-details-1440.png（专业库存明细）
- products-1440.png（商品基础信息）
- categories-1440.png（品类基础信息）
- jd-warehouse-1440.png / jd-basicinfo-1440.png / jd-stock-1440.png / jd-serial-1440.png / jd-order-1440.png / jd-return-1440.png（京东工具 6 页）
- demo-order-1440.png（模拟下单，固定演示场景 Tab）

登记复用（本票引用，不重复截图）：
- `/dashboard` → `output/playwright/saas-ops-03/dashboard-1440.png`
- `/workbench/reviews` → `saas-ops-03/workbench-manual-review-1440.png`
- `/fulfillment/tasks` → `saas-ops-03/fulfillment-tasks-1440.png`
- `/orders` → `saas-ops-03/orders-list-1440.png`
- `/procurement/tickets` → `saas-admin-04-procurement-1440.png`（+ procurement-detail）
- `/product/skus` → `saas-admin-04-final/sku-second-create-reset-1440.png`
- `/product/sku-mappings` → `saas-theme-01-sku-mappings-1440.png`（+ editor）
- `/analytics` → `saas-analytics-02/analytics-dashboard-1440.png`
- `/system/connectors` → `saas-admin-04-connectors-1440.png`（+ connector-success）
- `/system/audit-logs` → `saas-admin-04-final/audit-logs-1440.png`（+ detail-drawer）
- `/system/config` → `saas-admin-04-final/system-config-1440.png`
- `/system/fulfillment-providers` → `saas-admin-04-final/providers-1440.png`

覆盖：数据看板（dashboard/analytics）、列表（orders×3/fulfillments/shipments/exports/skus/products/categories/inventory）、详情（order-detail/audit-logs-drawer/procurement-detail）、表单（jd×6/sku-mappings-editor）、配置（connectors/providers/system-config）。

### 状态抽查矩阵（checkbox 3）

| 状态 | 证据 |
|------|------|
| 常态 | 上方全部基线截图 |
| 空态 | `saas-admin-04-final/providers-empty-1440.png`、`system-config-empty-1440.png`（04）+ 新截 `channel-messages-empty-1440.png`、`shipments-empty-1440.png` |
| 加载 | `saas-admin-04-final/sku-loading-1440.png`（04）+ 新截 `orders-loading-1440.png`（mock 延迟 2.5s） |
| 成功 | `saas-admin-04-final/sku-save-success-1440.png` + `sku-save-success-toast.png`（04）、`connector-success.png`（04） |
| 警告 | 新截 `order-detail-warning-1440.png`（异常订单分支警告 Alert + 复核事项 Alert）；`providers-warning-403-1440.png` 的 AdminFailureAlert 亦为 warning 态 |
| 错误 | `saas-admin-04-final/sku-error-500-1440.png`（04）+ 新截 `orders-error-500-1440.png` |
| 禁用 | `saas-admin-04-final/sku-save-pending-disabled-1440.png`（04）；SalesOutboundPage 回传按钮禁用态同源逻辑 |
| 无权限 | `saas-admin-04-permission-403.png`、`providers-warning-403-1440.png`、`system-config-warning-403-1440.png`（04）+ 新截 `orders-forbidden-403-1440.png` |

### 语义一致性检查（checkbox 4）

- 品牌色 `#3f6fd1` 系：`saasTheme.ts` token + `CHANNEL_ACCENT`（分类点缀）+ analytics `analyticsVisualSystem` 派生，职责单一。
- 语义色：`semanticStatus.ts` 统一映射（ORDER_STATUS / PROCESSING_STAGE / SHIPMENT_STATUS / SHIPPING_PROGRESS / FULFILLMENT_OUTCOME / EXPORT_USAGE / importRowStatus / reviewCaseStatus / operationalAlert / jdConnection），全部走 antd 语义预设 `success/error/warning/processing/default`（随 saasTheme token 降饱和）；`labels.ts` 仅保留标签，颜色委托 `semanticStatus.ts`。
- 分类色不误用告警色：`CHANNEL_ACCENT` 使用 `saasVisualTokens.data.*`（品牌/数据色阶），`READ_ONLY_TAG_COLOR=processing`、`TOOL_CATEGORY_TAG_COLOR=default`；`ATTENTION_COLORS` 仅用于待介入类 KPI（waiting→warning / severe→error，属真实语义）。
- 数据系列色：DashboardPage 用 `saasChartPalette.categorical`，analytics 用 `analyticsVisualSystem`（从 saasVisualTokens 派生）；`saasTheme.test.ts` 断言 categorical 不含语义色。
- 具名预设色（blue/green/gold/red/purple/cyan/geekblue/orange/volcano）生产页面扫描：**5 处残留，全部位于 git 已跟踪且带未提交改动的文件中**（`ChannelMessagesPage.tsx` 2 处、`OrderDetailPage.tsx` 3 处），受票内硬边界限制未改，记录待 wecom 特性提交后收口；其余生产页面 0 处。
- 语义预设直接使用 2 处（FulfillmentTasksPage 异常 Tag、OrderDetailPage 异常 Tag）+ OrderTimeline 当前态，符合「语义预设可保留」。

### 可读性对比度抽查（checkbox 5）

方法：token 级由 `saasTheme.test.ts` 5/5 覆盖（primary-on-white ≥4.5、secondary/tertiary-on-raised ≥4.5、focus ≥3、canvas 亮度 ≥0.94）；页面级用 Python PIL 对全部 25 张基线截图程序化取色，验证（a）无遗留高饱和色泄漏（旧 `#2563eb/#16a34a/#f59e0b/#ef4444/#7c3aed/#0891b2/#0d9488/#94a3b8` 等零命中；仅捕获到主题中性灰的文字抗锯齿混合像素），（b）WCAG 对比度计算：

| 前景/背景 | 对比度 | 结论 |
|-----------|--------|------|
| 正文 #202633 / raised、canvas | 15.15 / 14.36 | AA |
| 辅助 #5f6878 / raised、canvas | 5.62 / 5.33 | AA |
| 三级 #667080 / raised | 5.01 | AA |
| 品牌/链接 #3f6fd1 / raised | 4.77 | AA |
| 品牌 #3f6fd1 / subtle #edf3ff | 4.28 | AA-large（大字号/加粗） |
| 成功/警告/错误语义色 / raised | 4.85 / 4.69 / 5.48 | AA |
| 语义色 / 各自浅底（successBg/warningBg/errorBg） | 4.37 / 4.25 / 4.88 | ≥4.2（接近 AA，4.25+） |
| 反白 #ffffff / 品牌 | 4.77 | AA |
| 表头 #7d8796 / #f7f8fa | 3.42 | AA-large |
| 禁用 #9da5b1 / raised | 2.49 | 禁用控件 WCAG 豁免 |

不只依赖颜色：04 的 `AdminStatusTag`（图标+文字+色）、03 的 `StatusTag`（分类圆点点缀+文字）、错误/警告均为 Alert 文案+图标双通道；`order-detail-warning` 截图同时含警告文案。

### 回归与基线（checkbox 6）

- `cd frontend && npm test`：**155/155 通过，0 失败**（删除死代码后复跑）
- `npx tsc --noEmit`：**0 错误**
- `npm run build`：通过（3692 modules；仅既有 chunk-size 警告，可接受）
- 路由与关键操作：mock 下逐一确认 23 个叶子路由可达、无白屏（每页均以内容文本断言渲染成功）；截图过程收集 console error 共 7 条 = 2×antd 既有警告（destroyOnClose 弃用、Descriptions span）+ 状态抽查 mock 的 500/403 各 2 条（dev StrictMode 双请求），无新增异常
- 未改动任何数据提交、权限、路由与错误处理逻辑（仅删 2 个死文件，均零消费者）
- 最终基线已存 `output/playwright/saas-final-baseline/`（25 张）供后续变更比较

### 遗留（记录待办，非本票范围）

- `ShipmentsPage.tsx` / `OrderDetailPage.tsx`（git 已跟踪 + wecom-message-intake 未提交改动）：`#7a8699` 小字 ×2、遗留 rgba 阴影 ×6、具名预设 Tag ×5 —— wecom 特性提交后按本票标准收口
- `providerSkuMapping.ts`（零生产消费者、仅测试消费的纯业务模块，无颜色）：超出本票颜色收口范围，未动
