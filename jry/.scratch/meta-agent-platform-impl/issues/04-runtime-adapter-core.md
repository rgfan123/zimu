# 04 — Runtime Adapter 骨架 + 通用门面（A 路径）

**What to build:** 运行时收敛的第一步（04 迁移批 A）：`LangChain4jRuntimeAdapter implements AgentRuntime`（携带 `AgentDefinition`——扩展 `AgentTaskRequest` 但不改接口签名），职责 = 定义 → ChatRequest → 结果，权限/守卫/审计/观测留在 Control Plane。实现：低层 `ChatRequest.responseFormat` + 供应商能力自适应（OpenAI 原生 json_schema / DeepSeek 兼容端点 json_object）+ networknt 客户端 JSON Schema 校验（失败映射 `AGENT_OUTPUT_INVALID`），输出统一 `JsonNode` 容器；`AgentRunResult` 加 outcome 维度（SUCCESS / NEEDS_INPUT / REJECTED / FAILED），`AgentFailureCode` 降为仅失败时有值；删除 `AgentStructuredOutput`（无生产调用方）；`AgentRuntimeFacade` 改走 Adapter（其编排层不动）。迁移前后用基线门禁比对工具调用序列——换低层 ChatRequest 会改序列，按 09 流程重钉并记录。

**Blocked by:** 02 — 注册表切 DB 真源；03 — INVARIANT 评测数据化（设计源：meta-agent-platform 票 01、04）。

**Status:** ready-for-agent

- [ ] 通用门面经 Adapter 运行，结构化输出可被定义携带的 output_schema 动态约束并客户端校验
- [ ] 供应商自适应双路径单测（json_schema 能力探测 + json_object 降级）；`AGENT_OUTPUT_INVALID` 映射正确
- [ ] outcome 维度生效（NEEDS_INPUT 不再是失败）；`AgentStructuredOutput` 删除无残留引用
- [ ] `AgentEvalBaselineTest` 比对通过（或按流程重钉并记录差异）；本票独立提交
