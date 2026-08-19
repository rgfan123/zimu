# 03 — INVARIANT 评测数据化 + 基线门禁读 DB

**What to build:** 跑分器从 `agent_eval_cases` 读用例（07 前半）：`AgentEvalScorer` 保留 canned 注册表 + stub 模型，改为数据驱动——按 `metric_kind` 派生 expected schema（INVARIANT → tool_sequence / requires_human / missing_fields / expected_error）并读取时校验、非法拒跑；`AgentEvalBaselineTest` 改为 Testcontainers 读 DB 的集成测试，断言 INVARIANT 基线（schema 100% / 工具序列 / requires_human 召回 / 写工具零调用）；14 例代码 fixture 删除（01 已播种）；基线数字随用例集版本重钉并更新 `docs/agent-eval-baseline.md`（含版本标识）。

**Blocked by:** 01 — V30 迁移与播种（设计源：meta-agent-platform 票 07、11）。

**Status:** resolved

- [x] 基线门禁读 DB 全绿；确定性指标两次运行一致（AgentEvalScorerTest 断言保留）
- [x] expected 结构按 metric_kind 校验，非法用例拒跑并可见
- [x] 代码 fixture 类删除，归档机制（target/agent-eval-results）保留
- [x] docs/agent-eval-baseline.md 更新（数据驱动口径、基线数字、运行命令）

## Answer

已实现并验证（2026-08-19），经 /code-review（Standards + Spec 双轴）后按发现修复。实现要点：

- **跑分器数据驱动**：`AgentEvalScorer` 新增 `AgentEvalCase` 记录与 `loadInvariantCases(JdbcTemplate)`（读 `agent_eval_cases` INVARIANT/CONFIRMED，按 metric_kind 派生 expected schema 校验）；`compute(List<AgentEvalCase>)` 与 DB 无关。校验覆盖：expected 字段白名单（requires_human/tool_sequence/missing_fields/expected_error）与类型、语义一致性（tool_sequence 与 requires_human=true 互斥、data-query 无法归类拒跑）、同 slug 用例版本一致（防 v1/v2 混跑）、`compute` 拒跑未知 agent_slug（配置漂移可见）——非法一律整体拒跑并列出全部非法项。
- **stub canned 层**：`AgentEvalStubData` 按 input（JSON 语义等价匹配）脚本化采购比价 7 例最终输出；数据查询脚本按问题脚本化（共用 `DataQueryEvalInputs` 单一问题字面量源）。canned MCP 事实与归档机制保留。
- **基线/跑分器测试改 Testcontainers**：`AgentEvalScorerTest`（确定性断言保留：两次运行正确性指标一致 + 归档不覆盖）与 `AgentEvalBaselineTest`（schema 100% 6/6+负例拒绝 / 工具序列 3/3 / requires_human 召回 3/3+4/4 / 写工具零调用 / 版本与阈值钉死）均读 DB 用例；共享基座 `AgentTestcontainersBase`（收敛 5 处 boot/close 重复）。
- **fixture 删除与适配**：删除 `DataQueryAgentEvalFixture` / `ProcurementPriceEvalFixture`（14 例真源在 DB）；`ProcurementPriceEvalTest` 改 DB 用例 + AgentEvalStubData（负例检测按 expected.expected_error、缺失字段按 expected.missing_fields），并显式恢复 camelCase 兼容覆盖（数据化后 stub 按 input 只返回 snake_case，camelCase 解析由独立用例测试真实 runtime）；`DataQueryAgentServiceIntegrationTest` 内联问题字面量改共用 `DataQueryEvalInputs`；`AgentPlatformSeedVerbatimTest` 移除随 fixture 失效的 mirror 断言，改为**种子内容钉死测试**（14 例 input 集合 + 负例/可答/门禁 expected 形态，canonical 键排序序列化对齐 jsonb，防种子静默漂移）。
- **文档**：`docs/agent-eval-baseline.md` 更新（DB 真源口径、expected 校验、运行命令注明需 Docker Testcontainers、版本化说明）。
- **评审遗留说明**：评测集版本标签（procurement-eval-v1 / data-query-eval-v1）仍为跑分器常量映射（当前种子仅 version=1；多版本用例出现时（T09/管理流）按 (slug, version) 冻结语义扩展）；`compute` 的答案数字校验来自 canned 事实（stub 答案取自工具返回值），非 expected 字段（07 的 INVARIANT 派生 schema 不含 answer_contains）。
- **测试**：180 例 agent 相关测试全绿（含新增种子钉死/camelCase/拒跑测试）。
