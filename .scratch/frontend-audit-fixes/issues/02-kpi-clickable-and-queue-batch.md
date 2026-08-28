# 02 — 工作台 KPI 补链 + 复核队列批量与连续作业

Type: implementation
Status: ready-for-agent
Priority: P1

> **本票范围已按主线实况收窄（2026-08-28 核实）。**
> 原审计票（落后线 02/06）要求的大部分能力**主线已经具备且做得更好**，不要重做：
> - KpiCard 已有 `valueHref`；「待人工介入」与各 attention 卡已整卡包 `<Link>`，带 reason/team 跳转（`DashboardPage.tsx:216-248`）
> - 复核队列 URL 筛选态已全（status/reason/team/import_batch），且有**岗位默认预筛 + 「看全部」回退**，空态还区分了角色过滤
> - `AlertsQueuePage` 已拆为独立页，`QueueTable` 已抽为共享组件
>
> **只做下面两块真正的缺口。**

## A. 两张订单 KPI 卡不可点

`DashboardPage.tsx:192-215` 的「今日订单数」「今日已发订单」是仅有的两张**没有链接**的卡片，
而同屏的「待人工介入」和 attention 卡都可点。用户看到「今日已发 37 单」却点不进去看是哪 37 单。

修：给这两张卡加跳转，落到订单列表的对应预设视图。
- 「今日订单数」→ 订单列表（按今日业务日期筛）
- 「今日已发订单」→ 订单列表 + 已发货预设

**先核实**：`/orders` 支持哪些 query 预设（主线已把 `/orders/pending`、`/orders/exceptions` 转为 `hideInMenu`，
说明预设可能已走 query 或 preset 机制，见 `navigation.ts:45-46`）。**用既有机制，不要新造参数**。
若订单列表当前无法按业务日期筛，则只跳列表不带日期，并在报告里说明——不要伪造一个不生效的参数。

## B. 复核队列无批量、无连续作业

`ManualReviewPage.tsx:206` 的 `QueueTable` 没有 `rowSelection`；
`queueTable.tsx:16` 的 `QueueTableProps` 也没有暴露该能力。
运营处理 50 条同类事项只能逐条开 Drawer → 处理 → 关 → 回表重新找下一行。

**B1 批量选择**：给 `QueueTable` 增加**可选**的 `rowSelection` 支持（`AlertsQueuePage` 也用这个组件，
不传就完全保持现状，零回归），`ManualReviewPage` 启用。

**B2 批量提交限制**：只允许**同一处理类型**的事项一起提交——不同 `reason_code` 对应不同表单输入，
混选无法构造命令。跨类型选择时禁用批量按钮并说明原因（「批量处理需选择同类事项」）。
优先支持只需备注即可提交的类型（如手工解决 / 关闭）；需要选主数据的类型（客户 / SKU）**不纳入批量**。

**B3 批量语义**：逐行独立事务、**每行独立幂等键**、成功行移除失败行保留并显示原因、可重试。
仓库里已有正确范式可直接参照：`TrackingDraftReviewPanel.tsx:427` 一带的 `confirmBatch`
（逐行事务 + `crypto.randomUUID()` 幂等键 + 成败分流）。
**禁止用 toast 拼字符串汇报失败**——失败项必须留在界面上可查可重试。

> ⚠️ `crypto.randomUUID()` 带 `[SecureContext]`，**只在 HTTPS 与 localhost 存在**；生产是明文 HTTP + IP，
> 该 API 为 `undefined`，直接调用会在请求发出前抛异常且服务端零日志。
> 仓库已有回退实现（见 `writeHeaders.ts` 的 requestId 方案与 `test/insecureContextRequestId.test.ts`）。
> **必须复用既有回退，不要裸调 `crypto.randomUUID()`。** 这是今天刚修过的线上事故。

**B4 连续作业**：Drawer 内提交成功后，若当前筛选下还有下一条，提供「处理下一条」直接载入，不必关闭回表重找。

## Files likely affected

- `frontend/src/pages/dashboard/DashboardPage.tsx`（A）
- `frontend/src/pages/workbench/queueTable.tsx`（B1，新增可选 prop）
- `frontend/src/pages/workbench/ManualReviewPage.tsx`（B1/B2/B4）
- `frontend/src/pages/workbench/ReviewCaseDrawer.tsx`（B4，如需）
- `frontend/src/pages/shared/reviewQueueUrl.ts`（如需扩展 A 的链接构造）
- 相应测试

## Acceptance Criteria

- [ ] A：两张订单 KPI 卡可点并落到订单列表对应视图；若无法按日期筛则如实说明未做
- [ ] A：其余三类卡片行为**零变化**
- [ ] B1：复核主队列可多选；`AlertsQueuePage` 不传 rowSelection 时行为零变化
- [ ] B2：跨类型选择时批量按钮禁用且有明确说明
- [ ] B3：批量逐行独立事务与独立幂等键；部分失败时成功行消失、失败行保留可重试；失败信息不只在 toast
- [ ] B3：**未裸调 `crypto.randomUUID()`**，复用既有安全上下文回退
- [ ] B4：Drawer 内可「处理下一条」
- [ ] 7 种单条处理形态行为零回归
- [ ] `npm run typecheck && npm test && npm run build` 全绿

## 工作区纪律

**多会话并行**：禁 `git add -A` / `git commit` / `git checkout|restore|stash`。只改点名文件。迁移从 V73 起。

## Risk

中。B 涉及真实写操作。建议分步 commit：先 A（纯增量）→ 再 B1 表格能力 → 再 B2/B3 批量提交 → 最后 B4。
每步跑测试。批量提交务必确认幂等键**逐行独立**，不可整批共用一个。
