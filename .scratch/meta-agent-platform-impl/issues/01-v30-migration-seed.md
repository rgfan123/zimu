# 01 — V30 迁移：三表落地与播种

**What to build:** 数据库一次性具备平台全部新结构并完成播种，现有系统行为零变化。迁移包含：① `agent_definitions` 新表（03/05/08/04 决策）：现有定义 8 字段 + `id` / `version` / `status`（draft|active|retired）/ `activated_by` / `activated_at` / `allow_write`（默认 false）/ `guard_exemptions` 枚举数组（默认空）+ **`output_schema` JSONB（04 修正：03 Schema 增量遗漏，定义须携带输出 JSON schema）** + `tool_whitelist`，唯一 `(agent_slug, version)` + 部分唯一索引 `UNIQUE (agent_slug) WHERE status='active'`；② `agent_runs` 加列 `run_mode IN ('LIVE','PREVIEW')`（03，隔离草稿试跑）、`intent` / `provider`（04，替代意图桥重复审计通道）；③ `agent_eval_cases` 新表（07）：`agent_slug` / `agent_version` / `metric_kind`（INVARIANT|QUALITY）/ `input` JSONB / `expected` JSONB / `status`（PENDING|CONFIRMED）/ `created_by` / `confirmed_by` / `confirmed_at`。播种：4 个 Agent 定义（procurement-price-agent、data-query-agent、intent-recognition、meta-agent[allow_write=true]）与 14 例评测用例（procurement-eval-v1 7 例 + data-query-eval-v1 7 条，按 07 的 metric_kind 二分映射）；代码定义并行保留（expand 阶段），新增「种子 ↔ 代码常量逐字对照」测试。

**Blocked by:** None — can start immediately（设计源：meta-agent-platform 票 03/04/05/07/08）。

**Status:** resolved

- [x] 迁移可重复执行（Flyway 幂等），三表结构与约束（部分唯一索引、check 约束）与设计一致
- [x] 种子数据与现有代码 Configuration 常量逐字一致（对照测试），meta-agent 种子 allow_write=true
- [x] 14 例评测用例按 metric_kind 正确映射播种为 CONFIRMED
- [x] 现有全量测试仍绿（本票无行为变化）；迁移与删代码分两个 commit（03 两步走纪律）

## Answer

已实现并验证（2026-08-19）。实现要点：

- **版本号修正**：票题写 V30，但 V30–V32 已被既有迁移占用，实际落地为 `V33__agent_platform_definitions.sql`。
- **agent_definitions**（03/05/08/04）：现有 AgentDefinition 8 字段 + `id`/`version`/`status`/`activated_by`/`activated_at`/`allow_write`（默认 false）/`guard_exemptions`（JSONB 数组，默认空）/`output_schema`（JSONB，播种暂 NULL，由 T04/T10 补全）+ `tool_whitelist`（= 现有 toolNames）；唯一 `(agent_slug, version)` + **部分唯一索引** `CREATE UNIQUE INDEX ... ON agent_definitions(agent_slug) WHERE status='active'`（CREATE TABLE 不支持部分约束，用独立索引下沉）。`permission_profile_ref` 按 08 决策未建。
- **agent_runs 加列**：`run_mode`（默认 'LIVE'，CHECK LIVE/PREVIEW）+ `intent` + `provider`（04 差异⑦，替代意图桥重复审计通道）。
- **agent_eval_cases**（07）：`id`/`agent_slug`/`agent_version`/`metric_kind`（INVARIANT|QUALITY）/`input` JSONB/`expected` JSONB/`status`（PENDING|CONFIRMED）/`created_by`/`confirmed_by`/`confirmed_at` + `created_at`；外键 `(agent_slug, agent_version) → agent_definitions`；索引 `(agent_slug, agent_version, metric_kind)`。
- **播种 4 定义**（version=1, status='active', activated_by='system' 表示迁移引导激活）：procurement-price-agent / data-query-agent / intent-recognition 字段与代码 Configuration 常量**逐字一致**（intent 的 prompt_version 以常量 `DEFAULT_PROMPT_VERSION='intent-recognition-v1'` 落库，其运行时配置镜像语义由 T02 接 DB 真源时处理）；meta-agent 无代码定义，本迁移首建，`allow_write=true`，白名单 = `[list_agent_tools, create_agent_draft, update_agent_draft]`（06/08，工具本体由 T10 落地）。
- **播种 14 例评测用例**：全部 `metric_kind=INVARIANT`、`status=CONFIRMED`（07：确定性指标才进基线；QUALITY 由 T09 promptfoo 链路新增）。expected 按 07 的 INVARIANT 派生 schema：`requires_human` / `missing_fields`（负例场景）/ `expected_error`（schema-invalid-output → `AGENT_OUTPUT_INVALID`）/ `tool_sequence`（数据查询可答 3 条）。旧跑分器的「答案数字正确率」断言未入种子（T03 基线清单为 schema 100% / 工具序列 / requires_human 召回 / 写工具零调用）。
- **对照测试** `AgentPlatformSeedVerbatimTest`（Testcontainers + 全应用启动，10 用例全绿）：种子 ↔ 上下文实际 @Bean 逐字对照（含 system_prompt 全文与白名单顺序）；meta-agent allow_write/白名单断言；14 例 input/expected 与 fixture 逐字一致；部分唯一索引 / agent_runs 新列默认值 / 评测用例外键约束生效。
- **行为零变化**：新表与种子在注册表切 DB（T02）前完全休眠；Hibernate validate 通过；全量测试套件绿（含 AgentEval* 基线）。本 commit 只含迁移 + 测试 + 票文档；代码定义删除在 T02 单独 commit（03 两步走纪律）。
- **既有失败说明**：全量套件 768 例中唯一失败为 `ConnectorApiTest.connectorConnectionCheckIsStableAndAuditableAtTheHttpSeam`（期望 4 个连接器配置，实际 5，多出 ZHONGHUI 行）——由已提交的 `e7ec550`（V31/V32 zhonghui 通道 WIP）引入，与本票无因果关系，留给 platform-pull WIP 收尾更新测试期望。
