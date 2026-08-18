# 10 — langchain4j-agentic 能力盘点（1.19.0）

**Type:** research
**Status:** resolved
**Blocked by:** —

## Question

用户已定调运行时原则：LangChain4j 负责 Agent 运行能力（含 agentic），自研仅限薄 Control Plane。本票核实 `langchain4j-agentic` 在 1.19.0（backend/pom.xml 的 langchain4j-bom 版本，与票 01 一致）的真实能力与 API，为 04 Runtime Adapter 设计提供事实。

背景：仓库已有 LangChain4j 基础用法（AiServices 结构化输出 + ToolSpecification/ToolExecutor 工具绑定，见 `backend/src/main/java/cn/zimu/fulfillment/agent/` 的 LangChain4jAgentRuntime / AgentGateway / AgentToolBindingFactory / AgentToolInvoker / McpToolSchemaConverter）。

任务（查本地 ~/.m2 的 langchain4j-agentic jar 为主，官方文档/源码为辅，javap/unzip 核实 API）：

1. **工件与依赖**：langchain4j-agentic 是否独立 artifact？1.19.0 的坐标与传递依赖？与 langchain4j-core 的版本一致性？
2. **Agent 与 AgenticScope**：`dev.langchain4j.agent` 包的 Agent 接口、AgenticScope 的 API（进入/退出/子 scope）、与现有 AiServices 的关系（agentic 是否基于 AiServices 构建）。
3. **Workflow 原语**：Sequential / Loop / Parallel / Conditional 的具体类名与构造方式；Supervisor 原语（多 Agent 编排）的 API。
4. **HITL**：human-in-the-loop 的 API 形态（awaiting input / interrupt 机制）。
5. **持久化 SPI**：可插拔持久化的接口（接口名、方法、默认实现）。
6. **MCP Tool Agent**：MCP 工具接入 Agent 的 API（McpToolProvider / McpToolSpecification？），与仓库现有 `McpToolRegistry` + `AgentToolBindingFactory` 手工绑定的关系——能否替换成 agentic 原生 MCP 接入，还是保留现有绑定（白名单过滤点在绑定期，涉及 08 权限隔离）。
7. **与票 01 结论的衔接**：agentic 的 Agent 是否也走静态 record 结构化输出？低层 `ChatRequest.responseFormat` 动态 schema 路径与 agentic 能否共存？

输出：结论写为 `## Answer` 追加到本票文件并置 `Status: resolved`；详细盘点文档写 `.scratch/meta-agent-platform/research/10-langchain4j-agentic-inventory.md`（中文，类名/签名/版本证据 + 与现有代码的衔接判断）。不要运行任何 git 命令。

## Answer

**版本事实（首要发现）**：`langchain4j-agentic` 是独立 artifact 但走 **beta 线**——BOM 1.19.0 中 `langchain4j.beta.version` = **`1.19.0-beta29`**（不存在 `langchain4j-agentic:1.19.0`，Central 404 核实）；`langchain4j-agentic` 编译期依赖 stable `langchain4j:1.19.0`（core 1.19.0），BOM 官方混配「beta agentic + stable core」。本地 ~/.m2 无 agentic jar（pom 未引入），本次从 Central 下载至 `/tmp/l4j-agentic/`。BOM 还管理 `langchain4j-agentic-mcp`/`-a2a`/`-patterns`（均 beta29）。

**API 盘点（javap 核实）**：
1. 包名是 `dev.langchain4j.agentic`（非 `dev.langchain4j.agent`，后者仅 core 的 `agent/tool` 工具注解，与仓库现有绑定同类型）。`Agent` 是 **@Target(METHOD) 注解**（name/description/outputKey/typedOutputKey/async/optional/compensateOnError/summarizedContext）；运行时核心为 `UntypedAgent`（`Object invoke(Map)`）+ 入口 `AgenticServices`（agentBuilder / sequenceBuilder / loopBuilder / parallelBuilder / parallelMapperBuilder / conditionalBuilder / supervisorBuilder / plannerBuilder / a2aBuilder / humanInTheLoopBuilder / createAgenticSystem）。
2. **agentic 构建在 AiServices 之上**：`AgentBuilder.build(DefaultAgenticScope, AiServiceContext, AiServices<T>)` 字节码证实；工具/记忆/守卫/RAG 配置与 AiServices 同构。
3. **AgenticScope** 无显式 enter/exit：root 调用时按 memoryId 自动创建（单一 scope per user+system），子 agent 共享同一 scope（key 空间协作）；显式退出 = `AgenticScopeAccess.evictAgenticScope(memoryId)`；无记忆时运行结束即弃，有记忆时存内存 `AgenticScopeRegistry`。
4. **Workflow 原语**（均基于 Planner 执行环）：`SequentialAgentService`（SequentialPlanner 无参）、`LoopAgentService`（maxIterations/exitCondition/testExitAtLoopEnd；LoopPlanner(int, boolean, BiPredicate, String)）、`ParallelAgentService`（executor；ParallelPlanner 无参）、`ParallelMapperService`（executor/itemsProvider）、`ConditionalAgentService`（Predicate<AgenticScope> 路由；ConditionalPlanner(List)）；**Supervisor**：`SupervisorAgentService`（chatModel/chatMemoryProvider/requestGenerator/contextGenerationStrategy[CHAT_MEMORY|SUMMARIZATION|…]/responseStrategy[SCORED|SUMMARY|LAST]/maxAgentsInvocations/subAgents），`SupervisorAgent{invoke(String)}`。另有声明式注解（@SequenceAgent/@LoopAgent/@SupervisorAgent/…）、patterns（BDI/GOAP/P2P/blackboard/voting/debate）、A2A 客户端。
5. **HITL**：`HumanInTheLoop` record（responseProvider）+ `SuspendedResponse`（挂起：checkpoint scope + 抛 `AgenticSystemSuspendedException` 或 `ResultWithAgenticScope.suspended()==true`；恢复 = `completePendingResponse` + 同 memoryId 重调）/ `PendingResponse`（blockingGet 阻塞线程）。
6. **持久化 SPI**：`AgenticScopeStore`（save/load/delete/getAllKeys，key=`AgenticScopeKey(agentId, memoryId)`），经 `AgenticScopePersister.setStore(...)` 或 `META-INF/services` 装配；**默认无 store（纯内存）**；`AgenticScopeSerializer` Jackson 序列化带反序列化 allowlist；配 store 后支持 crash 恢复（每步 checkpoint + `Planner.executionState()/restoreExecutionState(Map)`）。
7. **MCP**：`langchain4j-agentic-mcp` 的 `McpAgent.builder(McpClient)` 把单工具包装为非 AI agent；`langchain4j-mcp` 的 `McpToolProvider implements ToolProvider` 提供 `filter`/`filterToolNames`/`alwaysVisibleToolNames`/`toolWrapper`——**白名单过滤点在 provision 期（每轮工具调用动态下发），不是绑定期**，且不经过仓库 `McpAgentIdentity` 身份/审计路径。

**衔接判断（对 04/06/08 的结论）**：
- **MCP 工具接入：保留现有「McpToolRegistry + AgentToolBindingFactory 绑定期白名单 + AgentToolInvoker」**；agentic 接入用 `AgentBuilder.tools(Map<ToolSpecification, ToolExecutor>, Set<String>)` 直接喂入现有绑定产物（类型同构），或自研 `ToolProvider` 适配器。`McpClient`/`McpToolProvider` 原生路径替换会破坏 08 权限隔离（身份注入/审计在绑定期与 MCP 层），仅当未来需直连外部 MCP server 时再评估。
- **结构化输出（票 01 衔接）**：agentic 的 Agent 结构化输出**仍绑定静态 record/POJO 返回类型**（AiServices 路径，jar 内无 ResponseFormat/JsonSchema 操作，不暴露动态 schema 入口）；低层 `ChatRequest.responseFormat` + `JsonRawSchema` 动态 schema 路径（票 01 推荐）与 agentic **分层共存**：agentic 管编排/状态/工具，动态 schema 用非 AI agent 内嵌低层通道或保留为最终输出网关；DeepSeek 的 json_schema 400 限制在 agentic 路径下同样适用。
- 依赖接入：pom 增加 `dev.langchain4j:langchain4j-agentic`（BOM 已锁 1.19.0-beta29，无需写版本）；注意 beta 线 API 漂移风险，升级需跟随 beta CHANGELOG。

详细证据（类名/签名/版本/构造方式/衔接判断表）：`.scratch/meta-agent-platform/research/10-langchain4j-agentic-inventory.md`
