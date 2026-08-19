# 01 — V30 迁移：三表落地与播种

**What to build:** 数据库一次性具备平台全部新结构并完成播种，现有系统行为零变化。迁移包含：① `agent_definitions` 新表（03/05/08/04 决策）：现有定义 8 字段 + `id` / `version` / `status`（draft|active|retired）/ `activated_by` / `activated_at` / `allow_write`（默认 false）/ `guard_exemptions` 枚举数组（默认空）+ **`output_schema` JSONB（04 修正：03 Schema 增量遗漏，定义须携带输出 JSON schema）** + `tool_whitelist`，唯一 `(agent_slug, version)` + 部分唯一索引 `UNIQUE (agent_slug) WHERE status='active'`；② `agent_runs` 加列 `run_mode IN ('LIVE','PREVIEW')`（03，隔离草稿试跑）、`intent` / `provider`（04，替代意图桥重复审计通道）；③ `agent_eval_cases` 新表（07）：`agent_slug` / `agent_version` / `metric_kind`（INVARIANT|QUALITY）/ `input` JSONB / `expected` JSONB / `status`（PENDING|CONFIRMED）/ `created_by` / `confirmed_by` / `confirmed_at`。播种：4 个 Agent 定义（procurement-price-agent、data-query-agent、intent-recognition、meta-agent[allow_write=true]）与 14 例评测用例（procurement-eval-v1 7 例 + data-query-eval-v1 7 条，按 07 的 metric_kind 二分映射）；代码定义并行保留（expand 阶段），新增「种子 ↔ 代码常量逐字对照」测试。

**Blocked by:** None — can start immediately（设计源：meta-agent-platform 票 03/04/05/07/08）。

**Status:** ready-for-agent

- [ ] 迁移可重复执行（Flyway 幂等），三表结构与约束（部分唯一索引、check 约束）与设计一致
- [ ] 种子数据与现有代码 Configuration 常量逐字一致（对照测试），meta-agent 种子 allow_write=true
- [ ] 14 例评测用例按 metric_kind 正确映射播种为 CONFIRMED
- [ ] 现有全量测试仍绿（本票无行为变化）；迁移与删代码分两个 commit（03 两步走纪律）
