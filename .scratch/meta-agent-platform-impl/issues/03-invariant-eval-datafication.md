# 03 — INVARIANT 评测数据化 + 基线门禁读 DB

**What to build:** 跑分器从 `agent_eval_cases` 读用例（07 前半）：`AgentEvalScorer` 保留 canned 注册表 + stub 模型，改为数据驱动——按 `metric_kind` 派生 expected schema（INVARIANT → tool_sequence / requires_human / missing_fields / expected_error）并读取时校验、非法拒跑；`AgentEvalBaselineTest` 改为 Testcontainers 读 DB 的集成测试，断言 INVARIANT 基线（schema 100% / 工具序列 / requires_human 召回 / 写工具零调用）；14 例代码 fixture 删除（01 已播种）；基线数字随用例集版本重钉并更新 `docs/agent-eval-baseline.md`（含版本标识）。

**Blocked by:** 01 — V30 迁移与播种（设计源：meta-agent-platform 票 07、11）。

**Status:** ready-for-agent

- [ ] 基线门禁读 DB 全绿；确定性指标两次运行一致（AgentEvalScorerTest 断言保留）
- [ ] expected 结构按 metric_kind 校验，非法用例拒跑并可见
- [ ] 代码 fixture 类删除，归档机制（target/agent-eval-results）保留
- [ ] docs/agent-eval-baseline.md 更新（数据驱动口径、基线数字、运行命令）
