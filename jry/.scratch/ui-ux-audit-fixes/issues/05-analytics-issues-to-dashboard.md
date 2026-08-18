# 05 — 经营分析「需人工介入」块迁入工作台（方案 A）

**Type:** implementation

**What to build:** 经营分析页混入的「需人工介入」操作清单（ReviewCase 域）移入工作台，与现有「待人工介入明细」合并去重；经营分析回归纯"看数"。

**Source:** .scratch/saas-visual-system/layout-audit.md §3-1；用户拍板方案 A（移入工作台合并去重）

**Status:** resolved

**Claimed by:** opencode 分派 subagent

现状（已侦察）：
- `AnalyticsPage.tsx` L782-816：「需人工介入」BentoCard，数据 `data.issues`（ReviewCase 列表）→ `issueMeta` 映射为 IssueItem（key=case_id, no=case_no, code=reason_code, reason=reasonLabel, team, detail=reviewCaseSummary, orderId=order_id, age=ageText(created_at)）。可跳转 `/orders/{orderId}`。CRITICAL_REASONS 决定红/琥珀圆点。
- `DashboardPage.tsx` L188-202：「待人工介入明细」Card 展示 `data.attention`（DashboardAttentionItem：reason_code/count/severity 聚合），与 KPI 卡联动。
- 数据源：analytics API `/api/v1/analytics/...` 返回 issues；dashboard API `/api/v1/dashboard`（DashboardController，JDBC）返回 attention（无单条 issues）。

任务：
1. [x] 经营分析页：删除「需人工介入」BentoCard（含 issueMeta/CRITICAL_REASONS 相关引用清理），保留其余统计卡与口径注脚；确认无残留 import/未用变量（tsc 会拦）。
2. [x] 工作台：把「待人工介入明细」升级为单条明细（case_no/原因/责任团队/订单跳转/停留时长 + 严重圆点），保留 KPI 聚合卡。
3. [x] 数据：单条明细数据源二选一（先探索再选最小改动，并在票内记录理由）：
   - A. DashboardController/查询服务增加 issues 投影（ReviewCase 单条列表，白名单字段）
   - B. 前端工作台复用 analytics API 的 issues（若响应契约可直接复用）
   - 结论：选 B（理由见 Comments），复用既有 `GET /api/v1/review-cases` 契约与 `ReviewCase` 类型，后端零改动。
4. [x] 验证：后端无需改动（无 mvn test 需求）；前端 tsc 0 错误、npm test 全过、npm run build 通过；截图存 output/playwright/ui-fixes/analytics-clean-* 与 dashboard-issues-*。
5. [x] 经营分析页 BentoCard 网格 span 布局需自洽（删卡后 4-span 网格不破版）。

**Scope:** frontend/src/pages/analytics/AnalyticsPage.tsx、frontend/src/pages/dashboard/DashboardPage.tsx、前端 types.ts、可能的 backend dashboard 查询服务与 DTO（若选 A）。不得改其他页面。

**Do not:** commit；修改 saasTheme.ts；改动既有 analytics 统计语义。

## Comments

- 2026-08-15 完成（本票此前被中断过一次：AnalyticsPage 的「需人工介入」BentoCard 删除已在半改状态中完成，但 issues 数据管道死代码未清、Dashboard 明细表未升级。本次从半途状态继续：确认删卡完整后补齐剩余工作，无重复删改）。
- 数据方案选择：**B（前端复用 review-cases API），理由**：
  1. 侦察发现「经营分析 issues」实际来源不是 AnalyticsController（该控制器只有 channels/products/fulfillments 三端点），而是 `frontend/src/api/endpoints.ts` 的 `reviewCasesApi.list`（`GET /api/v1/review-cases`）——票内"已侦察"描述与实现有出入，方案 B 即复用与 analytics 页完全相同的既有端点。
  2. 范围语义已核对：`ReviewCaseController.list` 只暴露 BUSINESS 订单关联（或 draft/message 链事项）的复核事项，支持 `status=OPEN` 过滤、按 `createdAt` 倒序——与工作台 attention 聚合的 review_cases 分支（`rc.status='OPEN'` JOIN BUSINESS orders）同域；KPI 聚合卡（含 operational_alerts 的 attention 明细）保持原状不动。
  3. 零后端改动：不新增 DashboardController issues 投影、不改 OpenAPI/docs/api-contract.md、不补后端测试；`ReviewCase` 类型已存在于前端 types.ts（含 case_no/responsible_team/reason_code/order_id/created_at/status）。
- 改动清单：
  1. `frontend/src/pages/analytics/AnalyticsPage.tsx`：上一中断会话已完成删卡（验证无 issueMeta/IssueItem/CRITICAL_REASONS/issueItems 残留；网格自洽：8+4 / 7+5 / 7+5 / 12，删卡后不破版；顶部注释保留"需人工介入清单已移入工作台"说明）。
  2. `frontend/src/pages/analytics/analyticsTypes.ts`：删 `AnalyticsData.issues: ReviewCase[]` 与 `ReviewCase` import（仅此一处使用）。
  3. `frontend/src/pages/analytics/analyticsTransform.ts`：删 `AnalyticsWindowSnapshot.issues` 与 `assembleAnalyticsData` 的 `issues` 装配。
  4. `frontend/src/pages/analytics/useAnalyticsData.ts`：删 `fetchOpenReviewCasesForChannel`/`fetchAllOpenReviewCases`、`reviewCasesApi` import、fetcher 内 `issues` 并发取数与 `issues: []`。
  5. `frontend/test/analyticsTransform.test.ts`：snapshot fixture 删 `issues: []`。
  6. `frontend/src/pages/dashboard/DashboardPage.tsx`：明细表从 attention 聚合升级为 ReviewCase 单条明细——列：原因（严重色圆点 + reasonLabel）、复核单号（case_no）、责任团队、订单（`/orders/{order_id}` 跳转，无订单显示 —）、停留时长（ageText(created_at) 小时/天）；`CRITICAL_REASONS` 红/琥珀映射对齐工作台 RED/YELLOW 语义（阻断/异常类 → 严重红：OUT_OF_STOCK/PROCUREMENT_FAILED/FULFILLMENT_EXCEPTION/JD_SUBMIT_FAILED/SYNC_FAILED/TRACKING_OVERDUE/RETURN_OVERDUE，其余关注琥珀），颜色取自既有 `ATTENTION_COLORS`；数据与 summary 并行取（跨页取全 OPEN cases）；KPI 聚合卡（attention + pending_review_count）原样保留。
- 验证结果：后端零改动（无 mvn test）；`npx tsc --noEmit` 0 错误；`npm test` 162/162 通过（155 基线 + wecom 新测试，全部未破）；`npm run build` 成功（chunk 体积警告为既有问题）。Playwright（mock /api/v1，vite dev :5199）程序化断言：analytics 页 body 无「需人工介入」字样、dashboard 明细表渲染 RC-20260815-0001 / 订单组 / SKU 未映射 / 查看订单链接，均 0 console error；截图 `output/playwright/ui-fixes/analytics-clean-1440.png` 与 `dashboard-issues-1440.png`。
- 边界遵守：未 commit；未改 saasTheme.ts；analytics 其余统计卡语义未动；未触碰 wecom 已跟踪文件；OpenAPI/docs 无需同步（方案 B）。

## Comments（追加：review 修复）

- 双轴 code review（2026-08-15）后修复：① `analytics.css` 删除 `.analytics-issue-*` 5 条死选择器（删卡后无引用，tsc 拦不住）；② DashboardPage KPI 摘要与明细从 `Promise.all` 硬耦合改为互不拖垮（summary 失败不再连带明细、明细失败不再连带 KPI，明细失败空态文案区分"加载失败"）；③ `CRITICAL_REASONS` 注明为 reason→severity 前端派生表（后端契约无 severity，新增严重码需同步）并强调与 attention 口径一致。
- 最终验证：tsc 0 错误、npm test 162/162、build 通过。
