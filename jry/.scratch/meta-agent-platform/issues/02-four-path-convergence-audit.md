# 02 — 现有四条路径收敛点审计

**Type:** research
**Status:** resolved
**Blocked by:** —

## Question

审计 `backend/src/main/java/cn/zimu/fulfillment/agent/`（45 个主类，含 `procurement/` 子包）+ `backend/src/main/java/cn/zimu/fulfillment/mcp/`，产出统一重构所需的事实清单：

1. **四条路径编排差异**：AgentRuntimeFacade（通用门面）/ ProcurementPriceAgent（自研编排）/ DataQueryAgentService（自研编排）/ IntentRecognitionAgentBridge（观测桥）各自的步骤序列（解析定义→enabled→run_id→工具绑定→模型调用→审计→观测→失败码），逐条差异表（schema 固定/专属、守卫、输入解析、失败码映射、审计载荷差异）。
2. **可收敛 vs 必须保留**：哪些逻辑可安全收敛到统一门面；哪些必须保留（DataQueryAgentGuard、IntentRecognitionAgentBridge 的管线钩子、DataQueryAgentService 的 PII 门、procurement 的专属输出记录）。
3. **字段差距**：AgentDefinition / AgentModelMetadataRegistry / AgentObservability / AgentFailureCode / AgentRunResult 现有字段与「数据化定义」目标的差距清单（需新增：output_schema、状态、创建者、权限 profile 引用等——只列差距，不设计方案）。
4. **MCP 权限 enforcement 点审计**（为 08 票提供事实）：McpToolRegistry / McpServer / McpServerRunner / McpAgentIdentity / McpWriteTools 中身份注入、写工具门禁（`McpRequestContext` 的 auth 检查）、审计路径分别在哪些代码行；当前「工具白名单过滤」发生在哪一层（AgentToolBindingFactory.bind）；若改为 MCP 层强制，需要动哪些点。
5. **工具面清单**：McpReadTools / McpDomainReadTools / McpWriteTools 各注册的工具（名称 + 读写属性）；Meta-Agent 做工具发现需要什么只读工具（现有 tools/list 能力在哪）。

输出：结论写为 `## Answer` 追加到本票文件并置 `Status: resolved`；详细审计文档写 `.scratch/meta-agent-platform/research/02-convergence-audit.md`。不要运行任何 git 命令。

## Answer

详细事实清单见 `.scratch/meta-agent-platform/research/02-convergence-audit.md`（含全部代码引用位置）。核心结论：

1. **四条路径编排差异**：A 门面 / B 采购比价 / C 数据查询 / D 意图桥的「解析定义→enabled→run_id→绑定→模型→审计→观测→失败码」骨架同构（run_id 均复用 `AgentRuntimeFacade.newRunId()`；审计 requestPayload 四份完全同构）。实质差异集中在 5 点：① 输出 schema 三处代码硬编码（`AgentStructuredOutput` / `ProcurementPriceRecommendation` / `DataQueryAgentOutput`），无一处来自注册表；② 守卫只在 C（`DataQueryAgentGuard` 三层）；③ 输入解析只在 B（`ProcurementPriceInput.parse` 抛 INVALID_PARAMETERS 且不审计）；④ 失败码不统一（C 用自定义 status 字符串 CLARIFICATION/PII_GUARDED，D 用 message 层 InterpretationFailureCode）；⑤ 编排级观测覆盖不一致（A/D 有 runStarted/runFinished，B/C 只有工具级观测，`V29` 注释明言 B/C 编排层尚未落 agent_run 行）。另：**只有 D 有生产调用方**（`InterpretationService` 管线钩子），A/B/C 均无生产入口。

2. **可收敛 vs 必须保留**：注册表解析/enabled/run_id/绑定/公共审计载荷/失败码映射/观测两段写入均可安全收敛进统一门面；必须保留的是 `DataQueryAgentGuard`（泛化为平台默认守卫）、`RecordingToolExecutor` 的占位参数拦截与 tool_call_sequence 审计、`IntentRecognitionAgentBridge` 管线钩子（桥壳保留，内部可复用统一观测）、`ProcurementPricePolicy.enforce`、专属输出记录（即 03 票 output_schema 数据化本体）、MCP 三组工具实现与脱敏口径。

3. **字段差距**（详见文档 §3）：`AgentDefinition` 缺 output_schema/定义版本/生命周期状态/创建者/权限 profile 引用/input_schema/守卫豁免声明/provider+model（现状只有 `modelRef` 字符串引用）；`AgentObservability`+`agent_runs` 缺 intent/输出摘要列（D 因此每次运行多落一条审计兜底）、status 枚举太窄（只 RUNNING/SUCCESS/FAILED）、operator 不进观测、agent_version 恒 null；`AgentFailureCode` 缺业务态（澄清/PII 门/守卫拦截）与权限拒绝码；`AgentRunResult` 缺 runId/status/latency/toolCalls，output 固定最小 schema（B/C 被迫自建并行结果类型）。

4. **MCP 权限 enforcement 现状**：身份注入在 `McpAgentIdentity.java:20`（`app.mcp.agent-identity`，启动一次不可变）；写门禁在 `McpWriteTools.executeWrite:235` + 4 个 work lambda（:141/:168/:193/:216）经 `McpRequestContext.requireCommandContext()`（:22-28，401 MCP_AUTH_REQUIRED）；写审计在 `executeWrite:239-254` 与 `recordFailureAudit:263-281`（REQUIRES_NEW 独立事务）。**工具白名单过滤目前只发生在 Agent 侧绑定层 `AgentToolBindingFactory.bind`（:60-75）**；MCP stdio 面 `McpServer.toolsList()`（:167-177）全量暴露 28 个工具（含 4 个写工具）无过滤，`tools/call`（:117-121）只按名分发，且 `McpRequestContext` 只有全局 agentIdentity 无 per-agent 权限。改 MCP 层强制需动：`McpToolRegistry`（权限感知 find/all）、`McpServer`（tools/list 过滤 + tools/call 鉴权）、`McpRequestContext`（携带 agent slug/profile）、`McpAgentIdentity`（按 Agent 解析身份）、`AgentToolInvoker.execute:83`、`AgentToolBindingFactory` 签名。

5. **工具面**：`McpToolRegistry` 聚合 28 个工具 = `McpReadTools` 13（全只读）+ `McpDomainReadTools` 11（全只读）+ `McpWriteTools` 4（全只写：reinterpret_submission / submit_order_draft_suggestion / submit_supplementary_material / submit_review_request，均要求 idempotency_key 且经幂等+AGENT 审计）。现有 Agent 白名单零写工具（红线配置层成立）。Meta-Agent 工具发现缺口：`tools/list` 只在 stdio 协议面且全量暴露（含写工具）；进程内只有 `McpToolRegistry.all()` 非 MCP 工具，需新增按权限过滤的只读发现工具（供 06 票）。

审计文档：`.scratch/meta-agent-platform/research/02-convergence-audit.md`
