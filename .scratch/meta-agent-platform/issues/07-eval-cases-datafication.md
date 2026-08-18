# 07 — 评测用例数据化设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 03 — Agent 定义数据模型与版本状态机

## Question

已确认：通用门禁自动 + 评测用例数据化；跑分器（AgentEvalScorer）扩展为数据驱动；09 代码 fixture 逐步迁移；基线门禁（AgentEvalBaselineTest）继续钉；意图识别回归门禁复用既有 MessageInterpretation* 套件。

待决策点（grilling，一次一个，带推荐答案）：

1. `agent_eval_cases` 表结构：关联定义版本、用例类型（input/expected）、状态（待确认/已确认）；确认流程（与定义草稿确认联动？Meta-Agent 建议评测输入的落库路径）。
2. 跑分器数据驱动改造边界：canned MCP 注册表与 stub 模型保留；fixture → DB 行的映射；`procurement-eval-v1` / `data-query-eval-v1` 迁移策略与基线重钉流程（docs/agent-eval-baseline.md 更新）。
3. 意图识别回归门禁是否也数据化（还是维持代码套件引用）。
