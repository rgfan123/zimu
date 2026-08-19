# 05 — B/C 路径收敛（采购比价/数据查询）

**What to build:** 采购比价与数据查询两条自研编排路径收敛到统一门面（04 迁移批 B/C）：`ProcurementPriceAgent` 与 `DataQueryAgentService` 的专属 gateway / 编排逻辑删除，改走 `AgentRuntimeFacade` + 定义驱动（输入解析——json 输入 vs 自然语言——在定义 input 约定中表达）；专属输出 record（采购比价推荐、数据查询答案）保留为反序列化目标，不丢类型安全；`DataQueryAgentGuard` 保留为该 Agent 的校验器实现（领域歧义层，05 决策：不进平台默认链）；失败码统一（补数据查询的拒绝审计路径）。迁移前后基线比对。

**Blocked by:** 04 — Runtime Adapter 骨架 + 通用门面（设计源：meta-agent-platform 票 04、05）。

**Status:** resolved

- [x] 采购/数据查询全套测试绿；无专属 gateway 类残留（含自建 OpenAI 通道删除）
- [x] 输入解析两种形态（结构化 json / 自然语言）在定义驱动下正确路由
- [x] 拒绝审计路径补齐（数据查询 PII/歧义拒绝留审计）；失败码统一（CLARIFICATION/PII_GUARDED 自定 status 消除）
- [x] 基线比对通过或按流程重钉记录；本票独立提交

## Answer

已实现并验证（2026-08-19），经 /code-review（Standards + Spec 双轴）后按发现修复。实现要点：

- **收敛为门面薄包装**：`ProcurementPriceAgent` / `DataQueryAgentService` 的专属 gateway / 编排逻辑删除，改走 `AgentRuntimeFacade`（定义驱动）——注册表/enabled/run_id/工具绑定/模型运行/审计/观测在模型路径全部由门面承接；包装只保留领域层：输入解析（`ProcurementPriceInput`）、守卫（`DataQueryAgentGuard`，PII→REJECTED / 歧义→NEEDS_INPUT + 拒绝审计，决策 05 不进平台链）、策略（`ProcurementPricePolicy.enforce`）、输出 record 反序列化（`treeToValue` 到 `ProcurementPriceRecommendation` / `DataQueryAgentOutput`，不丢类型安全）。
- **删除 5 类**：`ProcurementPriceGateway` / `DataQueryAgentGateway` / `ProcurementPriceAgentRuntime` / `ProcurementPriceRuntime` / `ProcurementPriceAgentConfiguration`（自建 `OpenAiChatModel` 通道、`RecordingToolExecutor`、AiServices 网关全部清除，仅共享 `LangChain4jRuntimeAdapter` 保留）。
- **定义驱动输入路由**（04 决策 2，评审修复）：V36 迁移新增 `agent_definitions.input_format`（STRUCTURED_JSON / NATURAL_LANGUAGE，采购显式声明）+ `AgentInputFormat` 枚举 + `AgentRuntimeFacade.definitionOf`；包装按定义 input 约定路由（采购校验 STRUCTURED_JSON，配置漂移 fail-fast）。
- **拒绝审计统一**（04 决策 5，评审修复）：数据查询守卫拒绝留审计；采购 `INVALID_PARAMETERS` parse 失败留拒绝审计后原样上抛。
- **失败码统一**：`CLARIFICATION`/`PII_GUARDED` 自定 status 消除 → `AgentOutcome.NEEDS_INPUT`/`REJECTED`；模型路径澄清同样映射 NEEDS_INPUT（评审修复）。
- **控制面富化**：`AgentRunResult` 扩展 runId/latencyMs + `withRunMetadata`（门面收口回填，含拒绝路径——评审修复）；业务 run-result 回填观测字段。
- **基线比对记录**：迁移前后复跑 `AgentEvalBaselineTest` 全绿；prompt 版本断言从已删运行时的 `agent-foundation-v1` 改钉定义驱动的 `procurement-price-v1`（评审修复 + 本 Answer 记录）；工具序列指标改严格口径（canned 注册表实际调用序列 == 预期序列）。
- **遗留说明**：工具参数级占位拦截（`toolArgumentProblem`）随 `RecordingToolExecutor` 删除——输入级歧义守卫保留，工具参数级兜底列入 T08 门禁引擎；守卫审计与门面审计同构双写是「拒绝必审计」契约的两面（守卫路径不进门面）。
- **测试**：`AgentEvalScorer`/`AgentEvalBaselineTest` 改测门面（facade + stub Adapter + canned 注册表，经领域包装）；`ProcurementPriceAgentTest`/`DataQueryAgentServiceTest` 重写为门面 mock 的领域包装验收；`DataQueryAgentServiceIntegrationTest`/`ProcurementPriceEvalTest` 改经门面 + 真实/迷你注册表（记录式绑定捕获工具序列）；**180 例 agent 相关测试全绿**。期间并发会话多次删除/回滚 agent 测试目录，已恢复并即时提交防丢；本票迁移因与并发 WIP 的 V35 冲突顺延为 V36。
