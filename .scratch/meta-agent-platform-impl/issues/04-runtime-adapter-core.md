# 04 — Runtime Adapter 骨架 + 通用门面（A 路径）

**What to build:** 运行时收敛的第一步（04 迁移批 A）：`LangChain4jRuntimeAdapter implements AgentRuntime`（携带 `AgentDefinition`——扩展 `AgentTaskRequest` 但不改接口签名），职责 = 定义 → ChatRequest → 结果，权限/守卫/审计/观测留在 Control Plane。实现：低层 `ChatRequest.responseFormat` + 供应商能力自适应（OpenAI 原生 json_schema / DeepSeek 兼容端点 json_object）+ networknt 客户端 JSON Schema 校验（失败映射 `AGENT_OUTPUT_INVALID`），输出统一 `JsonNode` 容器；`AgentRunResult` 加 outcome 维度（SUCCESS / NEEDS_INPUT / REJECTED / FAILED），`AgentFailureCode` 降为仅失败时有值；删除 `AgentStructuredOutput`（无生产调用方）；`AgentRuntimeFacade` 改走 Adapter（其编排层不动）。迁移前后用基线门禁比对工具调用序列——换低层 ChatRequest 会改序列，按 09 流程重钉并记录。

**Blocked by:** 02 — 注册表切 DB 真源；03 — INVARIANT 评测数据化（设计源：meta-agent-platform 票 01、04）。

**Status:** resolved

- [x] 通用门面经 Adapter 运行，结构化输出可被定义携带的 output_schema 动态约束并客户端校验
- [x] 供应商自适应双路径单测（json_schema 能力探测 + json_object 降级）；`AGENT_OUTPUT_INVALID` 映射正确
- [x] outcome 维度生效（NEEDS_INPUT 不再是失败）；`AgentStructuredOutput` 删除无残留引用
- [x] `AgentEvalBaselineTest` 比对通过（或按流程重钉并记录差异）；本票独立提交

## Answer

已实现并验证（2026-08-19），经 /code-review（Standards + Spec 双轴）后按发现修复。实现要点：

- **`LangChain4jRuntimeAdapter implements AgentRuntime`**（替换 `LangChain4jAgentRuntime`）：职责 = 定义 → ChatRequest → 结果，权限/守卫/审计/观测留在 Control Plane（Facade）。低层 `ChatRequest.responseFormat` + 供应商能力自适应——`JsonSchemaCapability` 保守 allowlist（仅 openai 走 `json_schema`，经 `JsonRawSchema` 动态 schema；DeepSeek 等未知/兼容端点降级 `json_object`——降级永远安全，客户端校验兜底）；`JsonSchemaValidator`（networknt 1.5.9，pom 新增）客户端 JSON Schema 校验，失败映射 `AGENT_OUTPUT_INVALID` 不重试。
- **手写 tool-calling loop**：每轮执行绑定内 `ToolExecutor`（`AgentToolBinding.executorFor`，run_id 关联 MCP 上下文），白名单外/未绑定工具回传与 `McpServer` 一致的稳定错误信封（`McpToolErrorEnvelope`，与 `AgentToolInvoker` 共用）；`MAX_TOOL_TURNS=8` 防死循环，超限按输出无效收口。换低层 ChatRequest 会改工具调用序列——本批只动 A 路径，B/C 路径不动。
- **`AgentTaskRequest` 扩展携带 `AgentDefinition`**（接口签名不变，2/3 参构造器保留）；**`AgentRunResult` 加 `outcome` 维度**（`AgentOutcome`：SUCCESS/NEEDS_INPUT/REJECTED/FAILED）+ 传输层统一 `JsonNode`，`AgentFailureCode` 仅 REJECTED/FAILED 时有值（构造器强制不变量）；Facade 状态改用 outcome（NEEDS_INPUT 不再是失败）。`needsInput`/`rejected` 工厂为 T05/T06（B/C/D 路径）预留的 API 面。
- **删除** `AgentStructuredOutput` / `AgentGateway` / `LangChain4jAgentRuntime`（A 路径占位 schema 与 AiServices 网关）；`AgentRuntimeConfiguration` 互斥注册切到 Adapter。
- **评审修复**：fail-fast 契约（output_schema 非法等配置漂移 `IllegalStateException` 上抛，不被 catch-all 吞成模型调用失败）；工具信封收敛共用；executor 查找内聚；能力判定翻转保守默认。
- **基线比对记录**：迁移前后复跑 `AgentEvalBaselineTest`（Testcontainers 读 DB，8 例全绿，schema 100% / 工具序列 3/3 / requires_human 召回 / 写工具零调用口径不变）——A 路径无评测集、不影响 B/C 基线，无重钉。
- **遗留说明**：C 路径自建 `OpenAiChatModel` 通道（`DataQueryAgentService`）与 `DataQueryAgentGateway` 属 T05（B/C 收敛）范围；观测的 token 记录沿用 08 票运行时语义（仅最终响应轮，与旧 AiServices 路径一致）。
- **测试**：`LangChain4jRuntimeAdapterTest`（json_schema/json_object 双路径 + networknt 校验拦截 + 稳定码 + api-key 零泄漏，9 例）、Adapter 观测测试、Facade/AgentToolRuntime/AgentModelMetadataRegistry 适配 outcome+JsonNode；58 例 T04 相关测试全绿。
