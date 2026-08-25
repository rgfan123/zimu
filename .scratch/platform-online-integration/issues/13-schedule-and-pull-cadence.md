# 13 — 调度与拉取节奏（Phase 1 形态）

> 本票由合并产生：原 06「Phase 0 调度与告警」随 Phase 0 出范围，其中**仍然成立的内核**（调度、防重入、重试、节奏）转移到这里，按 Phase 1 in-process 形态重做。告警部分随 D3 已定（只做系统内可见），归 10。

**What to build:** 三平台在线拉取的定时调度：Spring `@Scheduled` 单实例、渠道错峰、失败重试 2 次指数退避、拉取结果写 `connector_configs.last_pull_at` / `last_error`；拉取窗口口径由各 Connector 票（07/08/09）自行裁定并在此统一编排。

**合规红线（硬约束）：** 这些是供应商后台官方功能的接口化，不是开放 API，无 SLA。**每平台每日 ≤2 次拉取**、不绕过限流、不抓取权限外数据。调度配置必须让这条红线可审计。

**Blocked by:** 07 或 08 或 09（任一 Connector 落地）

**Status:** ready-for-agent
**GitHub:** https://github.com/rgfan123/zimu/issues/28

- [ ] 三渠道按错峰表定时执行，单实例不重入
- [ ] 单次失败自动重试，最终失败写 `last_error`，不产生空批次
- [ ] 每平台每日拉取次数 ≤2 可从配置与审计中核验
- [ ] 连续运行 3 天无人工介入正常闭环（拉取 → 批次 → 待确认）
- [ ] 双通道互斥问题已随 Phase 0 出范围而消失（不再有脚本与 Connector 同日双拉）
