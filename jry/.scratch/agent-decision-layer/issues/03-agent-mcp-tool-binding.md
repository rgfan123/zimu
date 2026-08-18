# 03 — Agent ↔ MCP 工具绑定

**What to build:** 将 LangChain4j Agent 的工具调用桥接到既有 `McpToolRegistry`，使 Agent 与 MCP stdio 进程使用同一套工具定义、同一套身份/幂等/审计路径；不建立并行工具注册表。

**Blocked by:** 02 — Agent Registry + Runtime

**Status:** resolved

## Answer

**交付内容（agent 包 + mcp 一个文件扩展）：**

- `McpToolSchemaConverter`：MCP 工具 JSON Schema（Jackson JsonNode）→ LangChain4j `JsonObjectSchema` 确定性转换（object/string/integer/number/boolean/array + 嵌套 properties/required/items），未知类型与结构缺失 fail-fast。Agent 侧零手工工具定义。
- `AgentToolBindingFactory`（Spring bean）：从 `McpToolRegistry.all()` 按 `AgentDefinition.tool_names` 白名单生成 `AgentToolBinding`（run_id + `Map<ToolSpecification, ToolExecutor>`）；白名单引用未知工具抛 `IllegalArgumentException` 直接暴露漂移；重复白名单项去重；注册表集合变更后重新 bind 即自动同步。
- `AgentToolInvoker`（实现 LangChain4j `ToolExecutor`，按 run 创建）：经 `McpTool.invoke(McpRequestContext, args)` 执行；错误契约与 `McpServer` 对齐（业务失败返回 code/http_status/message 稳定码 JSON，意外失败返回 `MCP_INTERNAL_ERROR`，与 stdio 客户端收到的工具结果结构一致）。
- `McpAgentIdentity.newContext(String requestId)` 扩展：Agent 路径 requestId=traceId=run_id，stdio 路径仍走无参自生成（向后兼容，`McpServer` 零改动）。
- `AgentTaskRequest` 增加可选 `AgentToolBinding`（保留双参构造）；`LangChain4jAgentRuntime` 在绑定非空时按绑定构建带工具的 AiServices（无绑定/空绑定走原缓存网关，01 行为不变）；`AgentRuntimeFacade` 每次运行按白名单 bind（run_id 即绑定关联键）并随请求透传。
- 写工具未分配给任何业务 Agent（现状保持）；写路径若被白名单引用，经同一 `McpTool.invoke` 自动获得幂等 + AGENT 审计（见验收测试）。

**设计决策：**

- schema 生成方式：Jackson → LangChain4j 手写递归转换（当前 classpath 无 `JsonCodecFactory` SPI provider，不依赖 langchain4j 内部 JSON codec），等价性由 round-trip 测试断言。
- 空 `required` 数组语义等价于缺席，等价性比较前递归归一化。
- 白名单引用未知工具 fail-fast（注册表是唯一工具源，漂移必须立刻暴露）而非静默跳过。
- 注册表 `all()` 顺序不保证（`Map.copyOf`），所有等价性断言按集合语义而非顺序。

**测试（新增 4 个类 + 1 个支持类 + 扩展现有 1 个类）：**

| 类 | 断言 | 数量 |
|---|---|---|
| `McpToolSchemaConverterTest` | schema round-trip 等价、类型/结构 fail-fast | 7 |
| `AgentToolBindingFactoryTest` | 注册表↔Agent 工具一一对应、白名单过滤、去重、未知工具 fail-fast、注册表变更自动同步 | 9 |
| `AgentToolInvokerTest` | run_id 即 request/trace id、参数解析、成功/业务失败与 MCP stdio 逐字节等价、未知工具稳定兜底 | 7 |
| `AgentToolRuntimeTest` | stub HTTP server 端到端：暴露给模型的工具恰为白名单、schema 与注册表一致、工具结果回传模型、空绑定不暴露工具 | 2 |
| `AgentMcpToolBindingAcceptanceTest`（Testcontainers） | 真实注册表一一对应、只读工具调用与 stdio 结果逐字节等价、写工具 run_id 进审计（request/trace=run_id、operator=注入身份、幂等重放） | 3 |
| `AgentRuntimeFacadeTest`（扩展） | 绑定透传（run_id 与审计一致）、未知工具 fail-fast、空白名单 | +3 → 14 |

**回归结果（backend 目录）：** `mvn -q test-compile` 通过；`Agent*` 55/55 绿；`McpProtocolAcceptanceTest` 14/14 绿；全量 `mvn test` 605/605 绿（0 失败 0 错误 7 跳过，单 fork Testcontainers 无资源问题）。

**对既有文件的合理演进（票"不改变 McpTool/McpToolRegistry 接口"约束内）：** `McpAgentIdentity` 仅新增 `newContext(String)` 重载；`AgentTaskRequest`/`LangChain4jAgentRuntime`/`AgentRuntimeFacade`/`AgentRegistryConfiguration` 增加绑定透传（接口签名扩展，行为向后兼容）；`McpTool`/`McpToolRegistry` 未改动。

**遗留事项：** 写工具仅经测试验证行为等价，一期业务 Agent 未分配；Agent 工具调用序列的可观测性（run_id 关联）由 08 票承接。

## 范围

- 从 `McpToolRegistry.all()`（`McpReadTools` + `McpWriteTools` + 04 票新增工具）自动生成 LangChain4j 工具描述（name / description / JSON Schema），Agent 侧不再手工维护工具定义。
- `AgentToolInvoker`：调用 LangChain4j 工具时经 `McpTool.invoke(McpRequestContext, args)` 执行，`McpRequestContext` 由 Agent 运行级身份生成：
  - `requestId` / `traceId` = Agent run 的 `run_id`（沿用 `McpAgentIdentity.newContext()` 模式扩展为带 run_id 的上下文）；
  - 操作人身份来自 Agent 身份（沿用 `McpAgentIdentity` 注入的 `app.mcp.agent-identity`），工具参数不接受 operator。
- 工具白名单：Agent 只能调用其 `AgentDefinition.tool_names` 中声明的工具；白名单之外的调用在 LangChain4j 侧不暴露。
- 写工具沿用既有幂等（`IdempotencyService`）+ AGENT 审计（`McpWriteTools.executeWrite` 模式），一期业务 Agent 不分配写工具。
- 单元测试：注册表工具与 Agent 可见工具一一对应；invoke 路径与 MCP stdio 等价。

## 非范围

- 新增领域工具（04 票）；
- 改变 `McpTool` / `McpToolRegistry` 既有接口。

## 验收标准

- [x] `McpToolRegistry` 中全部工具对 Agent 可见（按 tool_names 过滤后）；
- [x] Agent 调用只读工具返回与 MCP 调用一致的结构化结果；
- [x] Agent run 上下文携带 `run_id` 作为 request/trace id；
- [x] 未声明到白名单的工具不被 LangChain4j 暴露；
- [x] 写工具被调用时（若未来授权）幂等与审计行为与 MCP 路径完全一致；
- [x] 注册表工具集合变更时 Agent 描述自动同步，无手工维护漂移。

## 验证原则

- 工具等价性必须有自动化测试（同一工具名、同一 schema、同一 invoke 结果）；
- 不以“模型碰巧调对了工具”为验收。
