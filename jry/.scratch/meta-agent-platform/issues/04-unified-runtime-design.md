# 04 — 基于 LangChain4j Agentic 的 Runtime Adapter 设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 01 — LangChain4j 动态输出 schema 调研；02 — 现有四条路径收敛点审计；10 — langchain4j-agentic 能力盘点

## Question

**已定调（用户决策 #12）**：不造 Agent 框架——Agent loop / workflow / tool calling / structured output / memory / 模型集成全部由 LangChain4j（含 langchain4j-agentic）承担；本票只设计**薄 Runtime Adapter**：把 Control Plane（AgentDefinition 数据模型 + AgentRegistry + AgentFacade）与 LangChain4j 运行时连接起来的那层。自研仅限子牧特有治理（定义/注册表/门面/权限强制/守卫/门禁/审计关联）。

基于 01（动态 schema 路径：供应商自适应 json_object/json_schema + networknt 客户端校验）与 10（agentic 真实能力）的结论，设计：

1. **Adapter 职责边界**：AgentFacade 与 langchain4j Agent/AgenticScope/workflow 的映射——一次 run 怎么表达（单 Agent 调用 vs AgenticScope 会话）；run_id/thread_id 与 AgenticScope 的关系；哪些 langchain4j 能力经 Adapter 透出（结构化输出、MCP 工具绑定、HITL、持久化 SPI），哪些被 Control Plane 拦截（权限、守卫、审计、观测）。
2. **结构化输出接入**：01 的供应商自适应路径如何在 Adapter 落地（低层 ChatRequest.responseFormat vs AiServices 静态 gateway 的去留）；`AgentStructuredOutput` 信封去留。
3. **既有代码迁移**：LangChain4jAgentRuntime / ProcurementPriceGateway / DataQueryAgentGateway 如何并入 Adapter；02 的收敛点清单哪些落在 Adapter、哪些落在 Control Plane。
4. **HITL 与持久化接缝**：langchain4j-agentic 的 HITL 与「草稿→人工确认」流程的关系（运行中人工介入 vs 平台级激活门禁）；持久化 SPI 与 agent_runs/agent_tool_calls 的关系（谁是真源）。
5. 失败码统一（02 差异④）在 Adapter 层的映射。
6. **MCP 接入路径分叉**（关键）：langchain4j 的 MCP 接入若走「client 外连 MCP server」（stdio/SSE），与现有「进程内 McpToolRegistry 直接调用」是两条路——必须二选一或明确分层，不能都当"现成"用；决定 08 权限强制点落在哪、McpServer 是否需要作为真实 stdio server 暴露。
7. **默认最简路径（YAGNI 修正）**：现有四个用例全是单次调用 + 工具调用（意图识别无工具），无 Loop/Sequential/Supervisor 需求——Adapter 默认走低层 ChatRequest + 动态 schema + 现有工具绑定；agentic workflow 原语仅留接缝，等真实多 Agent 用例出现再启用。注意 09 评测基线钉的是工具调用序列，agentic loop 可能改变序列产出，评测与运行时须一起验证。

待决策点（grilling，一次一个，带推荐答案）。

## Comments

### 决策 1–7 — 用户「都按推荐」，全部采纳

**1）Adapter 职责边界**：10 票已定一期不引 agentic，`AgenticScope` 不存在，「单 Agent 调用 vs AgenticScope 会话」塌缩为前者。一次 run = 一次 `AgentRuntime.run(AgentTaskRequest)`，`thread_id` 保持现有无状态会话语义。`LangChain4jRuntimeAdapter implements AgentRuntime` 替换 `LangChain4jAgentRuntime`；**Adapter 知道 `AgentDefinition`**——`AgentTaskRequest` 扩展为携带 definition（output_schema + tool bindings），`AgentRuntime` 接口签名不变。分层：Control Plane（Facade）拦权限/守卫/审计/观测，Adapter 只管「定义 → ChatRequest → 结果」。

**2）结构化输出 + `AgentStructuredOutput` 去留**：三个 AiServices 网关（`AgentGateway` / `ProcurementPriceGateway` / `DataQueryAgentGateway`）**全部废弃**，统一低层 `ChatRequest.responseFormat` + 供应商能力自适应（DeepSeek 只吃 json_object）+ networknt 客户端校验，传输层统一 `JsonNode`。**`AgentStructuredOutput(summary, reasoning)` 删除**——它是 A 路径占位 schema，而 A 无生产调用方，留着即留特例。**关键配套**：`JsonNode` 仅为传输层，业务侧保留 record 做反序列化目标（`mapper.treeToValue(node, ProcurementPriceRecommendation.class)`），schema 数据化只影响「怎么约束模型输出」，不影响业务代码怎么读——`ProcurementPricePolicy.enforce` 继续吃强类型 record，不丢类型安全。

**3）既有代码迁移批次**：三批，先动无生产调用方的。批 1 = A（Facade）+ B（采购比价）+ C（数据查询），02 已核实三者无生产调用方；**C 额外必须删除自建 `OpenAiChatModel` 私有通道**（`DataQueryAgentService:131-134, :292-300`），否则 allowlist 投影与 provider 统一是假的。批 2 = D（意图识别桥，唯一有生产调用方 `InterpretationService:89-115`），**桥壳与管线钩子保留**，只换内部观测/审计实现。每批之间跑 `AgentEvalBaselineTest`。

**4）HITL 与持久化接缝**：**一期不做运行中 HITL**。agentic 不引入，其 HITL 挂起/恢复机制随之不引。明确区分两件常被混淆的事——「运行中暂停等人回话」（不做）vs「上线前人工点确认」（= 03 已定的平台级激活门禁，`activated_by`/`activated_at` + status 转移，发生在运行之外）。持久化真源 = `agent_runs` / `agent_tool_calls`，不引 `AgenticScopeStore`。

**5）失败码统一（02 差异④）**：**推翻「把 C 的自定义状态塞进 `AgentFailureCode`」的直觉做法**——「澄清」不是失败，塞进去会让 `agent_runs.status='FAILED'`、污染成功率指标。改为给 `AgentRunResult` 加 **outcome 维度**：

```
outcome:     SUCCESS | NEEDS_INPUT | REJECTED | FAILED
failureCode: 仅 REJECTED / FAILED 时非空
```

`NEEDS_INPUT` 吃掉 C 的 CLARIFICATION；`REJECTED` 吃掉 PII_GUARDED / 守卫拒绝 / 权限拒绝 / `INVALID_PARAMETERS`。映射分层：守卫类拒绝落 Control Plane，模型/输出类错误落 Adapter。配套修两个不一致：B 的 `ProcurementPriceInput.parse` 抛 `INVALID_PARAMETERS` **不落审计**（违反其余三路径「拒绝必审计」契约）→ 统一审计；D 因 `agent_runs` 缺 intent/provider 列而**额外落一条审计补字段**（差异⑦）→ 给 `agent_runs` 补列、砍掉重复通道。

**6）MCP 路径分叉**：确认 10 票结论，不重议——保留进程内 `McpToolRegistry` + 绑定期白名单（即 08 权限点），不引 `McpClient`/`McpToolProvider` 外连，`McpServer` 无需作为真实 stdio server 暴露给 Agent 路径。

**7）默认最简路径 + 新增风险约束**：Adapter 默认走低层 ChatRequest + 动态 schema + 现有工具绑定，workflow 原语仅留接缝。**新增票里没有的风险**：原担心「agentic loop 改变工具调用序列污染 09 基线」随不引 agentic 而消失，**但换低层 ChatRequest 本身同样会改序列**——AiServices 自带 tool-calling loop 换成手写 loop，工具调用顺序与轮次都可能变，而 09 基线钉的恰好是工具调用序列。**实施约束**：迁移每一批前先跑 `AgentEvalBaselineTest` 存档，改完逐条比对序列差异，序列变化须人工判定是退化还是等价。

## Answer

薄 **Runtime Adapter** 设计：`LangChain4jRuntimeAdapter implements AgentRuntime` 替换 `LangChain4jAgentRuntime`，携带 `AgentDefinition`（经扩展后的 `AgentTaskRequest`，接口签名不变），只负责「定义 → ChatRequest → 结果」；权限/守卫/审计/观测全部留在 Control Plane。

- **结构化输出**：三个 AiServices 网关全废，统一低层 `ChatRequest.responseFormat` + 供应商自适应 + networknt 校验，传输层 `JsonNode`；`AgentStructuredOutput` 删除；业务侧保留 record 作反序列化目标，不丢类型安全。
- **迁移**：三批（A+B+C → D），C 必须删自建 `OpenAiChatModel`，D 保留桥壳与管线钩子。
- **HITL**：一期不做运行中 HITL；「草稿→人工确认」是 03 的激活门禁，与之无关。持久化真源 = `agent_runs`/`agent_tool_calls`。
- **失败码**：`AgentRunResult` 加 outcome（SUCCESS/NEEDS_INPUT/REJECTED/FAILED），`AgentFailureCode` 降为仅失败时有值；补齐 B 的拒绝审计、砍掉 D 的重复审计通道。
- **MCP**：确认 10 票结论，进程内绑定，不外连。
- **实施约束**：每批迁移前后用 `AgentEvalBaselineTest` 比对工具调用序列。

**Schema 增量（累计）**：`agent_runs` 加 `run_mode`（03）+ `intent` / `provider`（本票，替代 D 的重复审计通道）。
