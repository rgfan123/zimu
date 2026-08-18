# 08 — Agent 可观测性

**What to build:** 结构化 Agent 运行记录（run/tool call 序列、token/latency、状态、关联业务实体），复用 `AuditLog` + `SecretRedactor` 脱敏；一期不接 Langfuse，保留 provider adapter 接缝。

**Blocked by:** 02 — Agent Registry + Runtime；03 — Agent ↔ MCP 工具绑定

**Status:** resolved

## 范围

- 新增 `agent_run`（`run_id`、`thread_id`、`agent_slug`、`agent_version`、`prompt_version`、`model`、`input_digest`、`status`、`error_type`、`latency_ms`、`token_usage`（jsonb）、`started_at`/`finished_at`）与 `agent_tool_call`（`run_id`、序号、tool_name、参数摘要、结果摘要、latency、状态）两张表（Flyway 迁移）。
- `AgentRuntime` 与工具桥接埋点：模型调用、工具调用、中断/恢复均记录。
- 关联：`trace_id` = `run_id`；可携带 `business_entity_type` / `business_entity_id`（如 `PROCUREMENT_TICKET` / id），供双向追溯；Agent run 与既有 `AuditLog` 通过 `run_id` 关联。
- 脱敏：输入/输出/工具参数一律经 `SecretRedactor` + 白名单投影；默认不记录敏感原文（参考 `MessagePublicProjectionSanitizer` 的既有做法）；提供 redaction hook，payload 大字段单独处理。
- Provider 接缝：`AgentObservability` 接口 + 默认 DB 实现；未来可加 Langfuse/OTLP 实现，业务代码不 import 第三方 SDK。
- 失败隔离：观测写入失败不得影响 Agent 运行结果与业务（try/catch + 独立审计失败容忍模式，参照 `McpWriteTools.recordFailureAudit`）。

## 非范围

- Langfuse 自托管接入（后续票）；
- 管理看板 UI（可在既有管理后台后续加只读视图）。

## 验收标准

- [x] 一次 Agent run 产出完整 `run_id` + 工具调用序列 + 模型/token/latency；
- [x] 从 `run_id` 可关联到对应审计记录与业务实体 id（双向）；
- [x] 脱敏生效：敏感字段不落 `agent_run` / `agent_tool_call`（有负向测试）；
- [x] 观测写入失败不影响业务执行（故障注入测试）；
- [x] provider 可切换/可关闭（默认 DB 实现，关闭时业务正常）；
- [x] 真实 PostgreSQL 迁移 V+1 与集成测试通过。

## 验证原则

- 脱敏与故障隔离必须有负向/故障注入测试；
- 不以“能打印出日志”为可观测性验收，要求结构化、可关联、可重放。

## Answer

主开发与验证（2026-08-16，多轮 subagent 中断后由主线程收尾）：

- **Flyway V29**：`app.agent_runs`（run_id/thread_id/agent_slug/agent_version/prompt_version/model/input_digest SHA-256/status RUNNING|SUCCESS|FAILED/error_type/latency_ms/token_usage jsonb/business_entity_type/id/started_at/finished_at，含约束与三索引）与 `app.agent_tool_calls`（run_id+sequence_no 唯一、脱敏摘要、SUCCESS|FAILED）。生命周期两段写入：runStarted 先落 RUNNING 行（进程中断可检出），runFinished 收口；input 只存 digest。
- **AgentObservability 接口 + 默认 DB 实现**：`AgentObservability`（Start/Finish/ToolCall 记录 + `disabled()` sentinel）、`JdbcAgentObservability`（JdbcTemplate 实现）、`NoopAgentObservability`；装配 `AgentObservabilityConfiguration` 按 `app.agent.observability.enabled`（默认 true，matchIfMissing）互斥注册 DB/noop，未来换 Langfuse/OTLP 只改此处。
- **埋点（兼容性保持）**：`AgentRuntimeFacade` 以 `@Autowired setObservability` 可选 setter 注入（02 票 5 参构造器装配零改动，单测直接 new 时默认 no-op）；`AgentToolInvoker` 构造器重载注入 observability（默认 disabled）。模型调用 runStarted/runFinished 与工具调用 toolCallFinished 均记录，观测失败 try/catch 隔离不影响业务与审计。
- **关联**：trace_id=run_id（与 AuditLog 双向）；business_entity_type/id 透传（PROCUREMENT_TICKET 等）。
- **测试 29 例全绿**：AgentObservabilityIntegrationTest 4（完整 run 关联审计与业务实体、工具序列有序脱敏落库、失败工具调用、Spring 装配）+ AgentObservabilityDisabledIntegrationTest 1（开关关闭业务正常）+ AgentPayloadRedactorTest 9（脱敏负向）+ AgentRuntimeFacadeObservabilityTest 10（成功/拒绝/失败/白名单漂移路径埋点、故障注入、run_id=审计 trace_id、默认 no-op）+ LangChain4jAgentRuntimeObservabilityTest 5（token/latency 埋点）。
- **回归**：全量 `mvn test` 695 run / 0 failures / 0 errors / 7 skipped，BUILD SUCCESS。
- 遗留：05/06 业务 Agent 编排层的运行尚未写 agent_runs 行（其编排与门面共用工具桥接，工具调用已可观测；run 行收口留待统一编排收敛票）；管理看板 UI 留后续票。

## Comments

- 08 票 subagent 分派累计 4 次被中断（部分轮次留有半成品：V29 迁移、Observability 组件、门面/桥接埋点与 5 个测试类）；主线程接手补齐验证并收尾（全量回归确认、票状态置 resolved）。
