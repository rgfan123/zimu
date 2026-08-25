# 03 — 既有页面批次迁移到共享展示组件

**What to build:** 把既有页面从各自手搓的 Card + 筛选区 + Table 迁到共享的 PageShell / FilterBar / DataTable，止住页面继续膨胀。

**Blocked by:** None — 共享组件（expand 阶段）已合入

**Status:** ready-for-agent

## 为什么现在做

共享组件已经落地，但采纳率是 **PageShell 5/53、DataTable 4/53、FilterBar 3/53** ——只有试点页与 Agent 中心在用。

证据：`ManualReviewPage` 在共享组件合入之后**反而从 833 行涨到 880 行**。组件建好了但没人迁，新需求继续手搓，页面只会继续长。这不是组件不好，是缺一次迁移。

## 范围

按页面目录分批，每批独立可合、CI 保持绿。开工时先列出批次划分写进 Resolution，建议顺序：先 `pages/fulfillment`（页面最多、最长），再 `pages/workbench`，再 `pages/product` 与其余。

**迁移不改变行为**：视觉与交互不得倒退，只换承载结构。发现某页确实无法套用共享组件时，记录原因而不是强行套。

## 验收标准

- [ ] 批次划分写在 Resolution 里，逐批列出
- [ ] 每批合并后 typecheck、test、build 全绿
- [ ] 迁移后的页面行数与手写样板显著减少，Resolution 里给出前后对比
- [ ] 加载态、空态、错误态统一走 DataTable 默认行为，不再各页自定义
- [ ] 无法套用共享组件的页面逐个记录原因
- [ ] 迁移不改变任何页面的可见行为
