# 10 — langchain4j-agentic 能力盘点（BOM 1.19.0 → agentic 1.19.0-beta29）

> 票：`.scratch/meta-agent-platform/issues/10-langchain4j-agentic-inventory.md`
> 调研日期：以本仓库为准（环境时间线）
> 证据来源：Maven Central 下载的 `langchain4j-agentic` 系列 jar（`javap` / `unzip -p` 核实类与方法签名，**主证据**）、官方 agents 教程与 apidocs（辅助证据）、本地 `~/.m2` 的 1.19.0 stable 系列 jar。
> 版本：`backend/pom.xml` 的 `langchain4j-bom:1.19.0`。**关键发现：agentic 系列在 BOM 中走 `${langchain4j.beta.version}`（= `1.19.0-beta29`），不是 1.19.0。**

---

## 0. 结论速览

1. **工件坐标**：`dev.langchain4j:langchain4j-agentic` 是**独立 artifact**，但版本为 **`1.19.0-beta29`**（BOM 属性 `langchain4j.beta.version`），与 core 的 stable `1.19.0` 分离。BOM 1.19.0 管理 4 个 agentic 模块：`langchain4j-agentic`、`langchain4j-agentic-mcp`、`langchain4j-agentic-a2a`、`langchain4j-agentic-patterns`（全部 beta29）。`langchain4j-agentic` 编译期依赖 **stable `langchain4j:1.19.0`**（含 core 1.19.0）——「beta agentic + stable core」是 BOM 官方混配。
2. **包名修正**：Agent 运行时在 **`dev.langchain4j.agentic`** 包（不是 `dev.langchain4j.agent`）；`dev.langchain4j.agent` 仅在 core 里承载工具注解子包 `agent/tool`（`Tool` / `ToolSpecification` / `ToolExecutionRequest` / `ToolMemoryId` 等，**与仓库现有绑定用的正是这套工具模型**）。
3. **`dev.langchain4j.agentic.Agent` 是注解（@Target(METHOD)）**，标注接口方法（如 `@UserMessage` 一样用在 AiServices 风格接口上）；运行时核心接口是 `UntypedAgent`（`Object invoke(Map<String,Object>)`）+ `AgenticServices`（入口，功能对应「AiServices 的 agentic 版」）。
4. **agentic 构建在 AiServices 之上**：`AgentBuilder.build(DefaultAgenticScope, AiServiceContext, AiServices<T>)` 字节码证实——单个 agent 就是 AiServices 代理 + AgenticScope 包装（`AgentInvocationHandler` 引用 `AiServiceListener`/`AiServiceResponseReceivedListener`）。工具/记忆/守卫/RAG 配置与 AiServices 同构。
5. **Workflow 原语齐备**：`SequentialAgentService` / `LoopAgentService` / `ParallelAgentService` / `ParallelMapperService` / `ConditionalAgentService`（路由器）全部基于 **Planner 执行环**（`Planner.nextAction(PlanningContext)→Action`），另有 `SupervisorAgentService`（多 agent 编排）、`PlannerBasedService`（自定义 planner）、patterns jar（BDI/GOAP/P2P/blackboard/voting/debate）、a2a jar（A2A 协议客户端）。
6. **HITL 一等能力**：`HumanInTheLoop` record + `SuspendedResponse`（挂起：checkpoint scope + 抛 `AgenticSystemSuspendedException`，恢复 = `completePendingResponse` + 同 memoryId 重调）/ `PendingResponse`（阻塞线程）。
7. **持久化 SPI**：`AgenticScopeStore`（save/load/delete/getAllKeys，key=`AgenticScopeKey(agentId, memoryId)`）经 `AgenticScopePersister.setStore(...)` 或 `META-INF/services` 装配；默认**无 store（纯内存）**；序列化走 `AgenticScopeSerializer`（Jackson，反序列化 allowlist 白名单）；配 store 后支持 crash 恢复（每步 checkpoint + `Planner.executionState()/restoreExecutionState(Map)`）。
8. **MCP**：`langchain4j-agentic-mcp` 的 `McpAgent.builder(McpClient)` 把单个 MCP 工具包装为非 AI agent；`langchain4j-mcp` 提供 `McpClient`（协议客户端）与 `McpToolProvider implements ToolProvider`（**过滤点在 provision 期（每轮模型工具调用动态下发），不是绑定期**）。仓库现有「McpToolRegistry + AgentToolBindingFactory 绑定期白名单 + AgentToolInvoker run_id 身份/审计」**可以且建议保留**——`AgentBuilder.tools(Map<ToolSpecification, ToolExecutor>, Set<String>)` 与现有绑定产物同构，可直接喂入 agentic；原生 `McpClient` 路径不经过仓库的 `McpToolRegistry`/`McpAgentIdentity` 身份门禁，涉及 08 权限隔离需另做。
9. **与票 01 衔接**：agentic 的 Agent 结构化输出**仍绑定静态 record/POJO 返回类型**（AiServices 路径，jar 内无任何 ResponseFormat/JsonSchema 操作）；低层 `ChatRequest.responseFormat` + `JsonRawSchema` 动态 schema 路径与 agentic **不冲突、可共存**——agentic 管编排/状态/工具，票 01 低层路径作为单 agent / 最终输出的动态 schema 通道（可用非 AI agent 内嵌，或保留为输出网关）。

---

## 1. 工件与依赖

### 1.1 BOM 版本事实（`~/.m2/.../langchain4j-bom/1.19.0/langchain4j-bom-1.19.0.pom`）

```xml
<langchain4j.stable.version>1.19.0</langchain4j.stable.version>
<langchain4j.beta.version>1.19.0-beta29</langchain4j.beta.version>
```

BOM dependencyManagement 中与 agentic 相关条目（grep 核实）：

| artifact | 版本 | 性质 |
|---|---|---|
| `langchain4j-core` | `${langchain4j.stable.version}` = **1.19.0** | stable |
| `langchain4j`（aggregator） | 1.19.0 | stable |
| `langchain4j-agentic` | `${langchain4j.beta.version}` = **1.19.0-beta29** | beta |
| `langchain4j-agentic-mcp` | 1.19.0-beta29 | beta |
| `langchain4j-agentic-a2a` | 1.19.0-beta29 | beta |
| `langchain4j-agentic-patterns` | 1.19.0-beta29 | beta |
| `langchain4j-mcp` | 1.19.0-beta29 | beta（agentic-mcp 的传递依赖） |
| `langchain4j-guardrails` | 1.19.0-beta29 | beta（agent builder 引用 `dev.langchain4j.guardrail.*`） |

> 推论：**`langchain4j-agentic:1.19.0` 在 Maven Central 上不存在**（实测 404，`x-amz-error-code: NoSuchKey`），正确坐标是 `1.19.0-beta29`。本地 `~/.m2/repository/dev/langchain4j/` 只有 stable 六个模块（langchain4j / bom / core / http-client / http-client-jdk / open-ai），**没有 agentic 系列**——`backend/pom.xml` 目前未引入 agentic 依赖。本次调研 jar 从 Maven Central 下载至 `/tmp/l4j-agentic/`。

### 1.2 各 POM 传递依赖（`unzip -p *.pom` 核实）

- `langchain4j-agentic-1.19.0-beta29.pom`：name = "LangChain4j :: Agentic Framework"，唯一 compile 依赖 `dev.langchain4j:langchain4j:1.19.0`（stable）。
- `langchain4j-agentic-mcp-1.19.0-beta29.pom`：依赖 `langchain4j-agentic:1.19.0-beta29` + `langchain4j-mcp:1.19.0-beta29`。
- `langchain4j-agentic-a2a-1.19.0-beta29.pom`：依赖 `org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final`、`a2a-java-sdk-client-transport-jsonrpc:1.1.0.Final`、`langchain4j-agentic:1.19.0-beta29`。
- `langchain4j-agentic-patterns-1.19.0-beta29.pom`：仅依赖 `langchain4j-agentic:1.19.0-beta29`。
- SPI 装配：`langchain4j-agentic` jar 内 `META-INF/services/` 只有一个条目 —— `dev.langchain4j.service.ParameterNameResolver` → `dev.langchain4j.agentic.internal.AgenticParameterNameResolver`（参数名推断；接口方法参数名在 `-parameters` 编译下可省略 `@V` 注解）。

### 1.3 与 core 的一致性

- 编译期一致：agentic beta29 依赖 stable `langchain4j:1.19.0`（聚合包，含 core 1.19.0），字节码中引用的 `dev.langchain4j.agent.tool.ToolSpecification`/`ToolExecutionRequest`（core 1.19.0）、`dev.langchain4j.service.tool.ToolExecutor`/`ToolProvider`（langchain4j 1.19.0）、`dev.langchain4j.model.chat.ChatModel`（core）均为 1.19.0 stable，javap 均命中。
- 风险：agentic 是 beta 线，API 随 beta 版本（1.19.0-beta29 → …）可能漂移；**接入时必须锁 BOM 版本**（BOM 已锁 `langchain4j.beta.version`，直接声明依赖即可），升级要同时看 beta 线 CHANGELOG。

---

## 2. Agent 与 AgenticScope

### 2.1 `dev.langchain4j.agentic.Agent` —— 注解，不是接口

```java
// javap dev.langchain4j.agentic.Agent
public interface Agent extends java.lang.annotation.Annotation {   // @Target(METHOD), @Retention(RUNTIME)
  String name(); String value(); String description();
  String outputKey();
  Class<? extends TypedKey<?>> typedOutputKey();
  boolean async(); boolean optional(); boolean compensateOnError();
  String[] summarizedContext();
}
```

用法（官方教程）：标注在 AiServices 风格接口的方法上，如

```java
public interface AudienceEditor {
    @UserMessage("...{{story}}...")
    @Agent("Edits a story to better fit a given audience")
    String editStory(@V("story") String story, @V("audience") String audience);
}
```

### 2.2 运行时接口 `UntypedAgent` 与入口 `AgenticServices`

```java
public interface UntypedAgent extends AgenticScopeAccess {
    Object invoke(Map<String, Object>);                          // 输入 map 写入 AgenticScope
    ResultWithAgenticScope<String> invokeWithAgenticScope(Map<String, Object>);
}
public interface AgenticScopeAccess {
    AgenticScope getAgenticScope(Object memoryId);
    boolean evictAgenticScope(Object memoryId);
}
```

`AgenticServices`（入口，功能对应 AiServices 的 agentic 版，javap 全量方法）：

```java
public class AgenticServices {
    static void setWorkflowAgentsBuilder(WorkflowAgentsBuilder);
    UntypedAgentBuilder agentBuilder();                                   // 无类型 agent
    <T> AgentBuilder<T,?> agentBuilder(Class<T>);                          // 类型化 agent
    HumanInTheLoopBuilder humanInTheLoopBuilder();
    SequentialAgentService<UntypedAgent> sequenceBuilder();                // + <T> 泛型版
    ParallelAgentService<UntypedAgent> parallelBuilder();                  // + <T>
    ParallelMapperService<UntypedAgent> parallelMapperBuilder();           // + <T>
    LoopAgentService<UntypedAgent> loopBuilder();                          // + <T>
    ConditionalAgentService<UntypedAgent> conditionalBuilder();            // + <T>
    SupervisorAgentService<SupervisorAgent> supervisorBuilder();           // + <T>
    PlannerBasedService<UntypedAgent> plannerBuilder();                    // + <T>（自定义 planner）
    A2AClientBuilder<UntypedAgent> a2aBuilder(String url);                 // + <T>
    <T> T createAgenticSystem(Class<T>, ChatModel, AgentConfigurator...);  // 声明式（@Agent 接口）
    AgentExecutor createBuiltInAgentExecutor(Class<?>);
    // + agentAction(...) / agenticScopeAction(...) 等辅助
}
```

### 2.3 与 AiServices 的关系（字节码证据）

- `AgentBuilder` 内部持有 `agentServiceClass` / `agenticMethod` / `agentReturnType`，受保护方法签名 `protected void build(DefaultAgenticScope, AiServiceContext, AiServices<T>)` —— **单个 agent = AiServices 代理 + AgenticScope 包装**。
- `AgentInvocationHandler implements InvocationHandler, InternalAgent`，字节码引用 `dev.langchain4j.observability.api.listener.AiServiceListener` / `AiServiceResponseReceivedListener` / `AiServiceResponseReceivedEvent`、`ChatModel` / `StreamingChatModel`、`ChatMemoryAccess` —— 就是 AiServices 代理的调用处理器。
- `AgentBuilder` 配置面与 AiServices 同构（javap 全量）：`chatModel` / `streamingChatModel`（含 `Function<AgenticScope, ChatModel>` 动态选择）、`chatMemory` / `chatMemoryProvider`、`tools(Object...)` / `tools(Map<ToolSpecification, ToolExecutor>)` / `tools(Map, Set<String>)` / `toolProvider(ToolProvider)` / `toolProviders(...)`、`maxToolCallingRoundTrips` / `maxSequentialToolsInvocations`、`contentRetriever` / `retrievalAugmentor`、`inputGuardrails*` / `outputGuardrails*`、`executeToolsConcurrently(Executor)`、`name/description/outputKey/output/context/summarizedContext/systemMessage*/userMessage*`、`errorHandler` / `compensateOnError`、`listener(AgentListener)`、`agentInstanceFactory`、`async` / `optional`。

### 2.4 AgenticScope：进入/退出/子 scope 的真相

`AgenticScope`（接口，extends `dev.langchain4j.invocation.LangChain4jManaged`，javap 全量）：

```java
Object memoryId();
void writeState(String, Object);  <T> void writeState(Class<? extends TypedKey<T>>, T);
void writeStateIfAbsent(...);  void writeStates(Map<String,Object>);
boolean hasState(...);  Object readState(String);  <T> T readState(String, T);
<T> T readState(Class<? extends TypedKey<T>>);  Map<String,Object> state();
String contextAsConversation(String...);  String contextAsConversation(Object...);
List<AgentInvocation> agentInvocations();  // + 按 agentName/Class 过滤
default boolean completePendingResponse(String, Object);  default boolean completePendingResponse(Object);
default Set<String> pendingResponseIds();
void writeExecutionContext(String, Object);  default void writeExecutionContext(Class<?>, Object);
Object executionContext(String);  <T> T executionContextAs(String, Class<T>);
```

**进入/退出语义**（官方教程 + `DefaultAgenticScope`/`AgenticScopeRegistry` 字节码）：
- 没有 `enterScope()/exitScope()` 显式 API。scope 由 root agent 调用时**自动创建**：单一 `AgenticScope` per (user, agentic system)，以 `memoryId` 为键。
- 有记忆（chatMemory）时 scope 存入 `AgenticScopeRegistry`（内存）并长期保留，供会话式交互；**显式退出 = `AgenticScopeAccess.evictAgenticScope(memoryId)`**（root agent 需实现 `AgenticScopeAccess`）。无记忆时运行结束即弃。
- 子 agent **共享同一个 scope**（无独立嵌套 scope 对象）：子 agent 通过 `writeState/readState` 在 scope 键空间中协作；`DefaultAgenticScope` 内部记录 `List<AgentMessage> context()` 与 `List<AgentInvocation>`（agent 名/输入/输出序列），`contextAsConversation(...)` 把历史拼成对话给后续 agent 作上下文。
- `DefaultAgenticScope` 关键方法：`static ephemeralAgenticScope()`、`rootCallStarted(AgenticScopeRegistry)` / `rootCallEnded(AgenticScopeRegistry, AgentListener)`、`checkpoint(AgenticScopeRegistry)`、`getOrCreateAgent(...)`、`registerAgentInvocation(...)`、`withErrorHandler(...)` / `handleError(...)`、`registerCompensableExecution(...)` / `compensateAll()`（跨 agent 补偿）、`completePendingResponse(...)`。
- `AgenticScopeRegistry(String)`：`update(DefaultAgenticScope)` / `get(Object)` / `create(Object)` / `createEphemeralAgenticScope()` / `evict(Object, AgentListener)` / `getAllAgenticScopeKeysInMemory()` / `clearInMemory()`。
- 编排元数据：`AgentInstance`（planner 视图：`type/plannerType/name/agentId/description/outputType/outputKey/async/optional/arguments/parent/subagents/leaf/topology`）+ `AgenticSystemTopology` 枚举 `AI_AGENT / NON_AI_AGENT / HUMAN_IN_THE_LOOP / SEQUENCE / PARALLEL / LOOP / ROUTER / STAR` —— 自研 Control Plane 的编排定义可映射到该拓扑枚举。

---

## 3. Workflow 原语

全部 workflow 服务都基于 **Planner 执行环**：`Planner` 接口（`init(InitPlanningContext)` / `firstAction(PlanningContext)` / `nextAction(PlanningContext) → Action` / `terminated()` / `noOp()` / `call(AgentInstance...)` / `done(...)` / `suspend()` / `executionState()` / `restoreExecutionState(Map)`），`Action` = `isDone()` / `isSuspended()` / `result()`。执行环在 `PlannerBasedInvocationHandler$PlannerLoop`（检测挂起 → `Planner.suspend()` → 释放线程）。

基接口 `AgenticService<T, A>`（所有 builder 的共同超接口，javap）：

```java
A build();
T subAgents(Object...);  T subAgents(Collection<?>);
T beforeCall(Consumer<AgenticScope>);
T name(String);  T description(String);
T outputKey(String);  T outputKey(Class<? extends TypedKey<?>>);
T output(Function<AgenticScope, Object>);
T errorHandler(Function<ErrorContext, ErrorRecoveryResult>);
T compensateOnError(boolean);
T listener(AgentListener);
```

### 3.1 各原语类名与构造（javap 核实）

| 原语 | 构建入口（AgenticServices） | 服务接口（额外方法） | Planner 实现（构造） |
|---|---|---|---|
| Sequential | `sequenceBuilder()` / `sequenceBuilder(Class<T>)` | `SequentialAgentService<T>`（无额外方法） | `SequentialPlanner()`（无参） |
| Loop | `loopBuilder()` / `loopBuilder(Class<T>)` | `LoopAgentService<T>`：`maxIterations(int)`、`exitCondition(Predicate<AgenticScope>)`、`exitCondition(BiPredicate<AgenticScope,Integer>)`、`exitCondition(String, ...)`、`testExitAtLoopEnd(boolean)` | `LoopPlanner(int maxIterations, boolean testExitAtLoopEnd, BiPredicate<AgenticScope,Integer>, String exitCondition)` |
| Parallel | `parallelBuilder()` / `parallelBuilder(Class<T>)` | `ParallelAgentService<T>`：`executor(Executor)` | `ParallelPlanner()`（无参） |
| Parallel mapper | `parallelMapperBuilder()` / `parallelMapperBuilder(Class<T>)` | `ParallelMapperService<T>`：`executor(Executor)`、`itemsProvider(String)` | `ParallelMapperPlanner` |
| Conditional（路由器） | `conditionalBuilder()` / `conditionalBuilder(Class<T>)` | `ConditionalAgentService<T>`：`subAgents(Predicate<AgenticScope>, Object...)` / `subAgents(String, Predicate, Object...)` / `subAgents(Predicate, List<AgentExecutor>)` / `subAgent(...)` | `ConditionalPlanner(List<ConditionalAgent>)` |
| Supervisor | `supervisorBuilder()` / `supervisorBuilder(Class<T>)` | `SupervisorAgentService<T>`（见 3.2） | `SupervisorPlanner(ChatModel, ChatMemoryProvider, int maxAgentsInvocations, SupervisorContextStrategy, SupervisorResponseStrategy, Function<AgenticScope,String> requestGenerator, String supervisorContext, Function<AgenticScope,Object> output)` |
| 自定义 | `plannerBuilder()` | `PlannerBasedService<T>`：`planner(Supplier<Planner>)` | 用户自定义 `Planner` |
| A2A 客户端 | `a2aBuilder(String url)` | `A2AClientBuilder` → 非 AI agent 调外部 A2A server | — |

构建产物：`build()` 返回 `UntypedAgent`（无类型接口版）或类型化代理 `T`（`sequenceBuilder(Class<T>)` 等），`T` 上每个方法 = 一次 agentic 系统调用。

### 3.2 Supervisor（多 Agent 编排）API

```java
public interface SupervisorAgentService<T> {
    T build();
    SupervisorAgentService<T> chatModel(ChatModel);
    SupervisorAgentService<T> chatMemoryProvider(ChatMemoryProvider);
    SupervisorAgentService<T> name(String);  description(String);  outputKey(String);
    SupervisorAgentService<T> requestGenerator(Function<AgenticScope, String>);
    SupervisorAgentService<T> contextGenerationStrategy(SupervisorContextStrategy);
    SupervisorAgentService<T> responseStrategy(SupervisorResponseStrategy);
    SupervisorAgentService<T> supervisorContext(String);
    SupervisorAgentService<T> subAgents(Object...);  subAgents(Collection<?>);
    SupervisorAgentService<T> maxAgentsInvocations(int);
    SupervisorAgentService<T> output(Function<AgenticScope, Object>);
    SupervisorAgentService<T> errorHandler(...);  listener(AgentListener);
    SupervisorAgentService<T> beforeCall(Consumer<AgenticScope>);  compensateOnError(boolean);
}
public interface SupervisorAgent extends AgenticScopeAccess {
    String invoke(String);  ResultWithAgenticScope<String> invokeWithAgenticScope(String);
}
// 枚举（javap）：
public enum SupervisorContextStrategy { CHAT_MEMORY, SUMMARIZATION, CHAT_MEMORY_AND_SUMMARIZATION }
public enum SupervisorResponseStrategy { SCORED, SUMMARY, LAST }
```

配套：`SupervisorPlanner`（实现 `Planner` + `ChatMemoryAccessProvider`，常量 `SUPERVISOR_CONTEXT_KEY` / `SUPERVISOR_CONTEXT_PREFIX`）、`ResponseAgent` / `ResponseScore`（SCORED 策略的评分响应 agent）、`SupervisorAgentServiceImpl`。

### 3.3 声明式（declarative）注解

`dev.langchain4j.agentic.declarative.*`（javap 核实，全部 @Target(METHOD) RUNTIME）：

- `@SequenceAgent` / `@ParallelAgent` / `@ConditionalAgent`：`name/description/outputKey/typedOutputKey/subAgents(Class<?>[])/compensateOnError`。
- `@LoopAgent`：多 `maxIterations()`；`@SupervisorAgent`：多 `maxAgentsInvocations()` + `contextStrategy()` + `responseStrategy()`。
- `@HumanInTheLoop`：`name/value/description/outputKey/typedOutputKey/async`（标注静态方法实现用户交互）。
- `@ExitCondition`（`testExitAtLoopEnd` / `description`）、`@Output`、`@LoopCounter`、`@Agent`。
- 装配辅助注解：`ChatModelSupplier` / `StreamingChatModelSupplier` / `ToolsSupplier` / `ToolProviderSupplier` / `ChatMemorySupplier` / `ChatMemoryProviderSupplier` / `ContentRetrieverSupplier` / `RetrievalAugmentorSupplier` / `SystemMessageProviderSupplier` / `UserMessageProviderSupplier` / `McpClientSupplier` / `PlannerSupplier` / `AgentListenerSupplier` / `A2AServerUrlSupplier` / `A2AClientCustomizer` / `BeforeCall` / `ErrorHandler` / `ActivationCondition` / `RegistryAgent` / `TypedKey` / `K` 等。
- 声明式系统装配：`AgenticServices.createAgenticSystem(Class<T>, ChatModel, AgentConfigurator...)`，`AgentConfigurator` record（`configurator(Consumer<DeclarativeAgentCreationContext<?>>)` / `subAgentResolver(Function<Class<?>, Object>)` / `agentInstanceFactory` / `defaultMemoryIdSupplier`）——**这是把 Spring 容器 bean 注入 agentic 系统的接缝**（`subAgentResolver` 可用 Spring context 按 Class 解析子 agent）。

### 3.4 patterns / a2a jar

- `langchain4j-agentic-patterns`：`patterns.bdi`（`Desire`/`BDIPlanner`）、`patterns.goap`（`GoalOrientedPlanner`/`GoalOrientedSearchGraph`）、`patterns.p2p`（`P2PPlanner`/`P2PAgent`）、`patterns.blackboard`（`BlackboardPlanner`/`ConflictResolutionStrategy`）、`patterns.voting`（`VotingPlanner`/`VotingStrategy`）、`patterns.debate`（`DebatePlanner`/`ConvergenceStrategy`）。
- `langchain4j-agentic-a2a`：`A2AClientAgent`（@Agent 注解，调用远端 A2A server）、`A2AClientCustomizer`、`A2AServerUrlSupplier`，SDK 来自 `org.a2aproject.sdk`。

---

## 4. HITL：human-in-the-loop

### 4.1 API 形态

```java
// dev.langchain4j.agentic.workflow.HumanInTheLoop —— record，即 HITL 节点
public final record HumanInTheLoop(String outputKey, String description, boolean async,
        Function<AgenticScope, ?> responseProvider, AgentListener listener, List<AgentArgument> arguments)
        implements AgentSpecsProvider {
    public Object askUser(AgenticScope);   // 字节码：直接调用 responseProvider.apply(scope)
}
// builder：AgenticServices.humanInTheLoopBuilder()
public class HumanInTheLoopBuilder {
    HumanInTheLoopBuilder responseProvider(Supplier<?>);
    HumanInTheLoopBuilder responseProvider(Function<AgenticScope, ?>);
    HumanInTheLoopBuilder outputKey(String);  description(String);  async(boolean);
    HumanInTheLoopBuilder inputs(List<AgentArgument>);  inputKey(Class<?>, String);  inputKeys(...);
    HumanInTheLoopBuilder listener(AgentListener);
    HumanInTheLoop build();
}
```

### 4.2 挂起 / 恢复机制（awaiting input / interrupt）

两种响应模式（`dev.langchain4j.agentic.internal.DeferredResponse<T>` 的子类 + 官方教程核实）：

| 返回类型 | 语义 | 恢复方式 |
|---|---|---|
| `SuspendedResponse<T>(String responseId)` | **挂起**：scope checkpoint 到 store（若配置）→ 抛 `AgenticSystemSuspendedException`（若方法返回 `ResultWithAgenticScope` 则不抛，`suspended()==true`）→ 释放调用线程 | `scope.completePendingResponse(responseId, value)`（或 `ResultWithAgenticScope.completePendingResponse(value)`）→ **以相同 memoryId 重新调用**，planner 从断点恢复 |
| `PendingResponse<T>(String responseId)` | **阻塞**：`DeferredResponse.blockingGet()` 阻塞线程直到 `complete(value)` | 后台线程/消息回调 `complete` |

- `AgenticSystemSuspendedException extends RuntimeException`：`scope()` 访问器；触发点由 `PlannerLoop` 检测 `hasSuspendedResponses(scope)` 后返回 `Planner.suspend()` 动作（字节码 `PlannerBasedInvocationHandler$PlannerLoop` 证实）；`AgentListener.onAgenticSystemSuspended(scope)` 可观察。
- `ResultWithAgenticScope<T>`：`suspended()` / `result()` / `completePendingResponse(Object)` / `completePendingResponse(String, Object)` —— 连续多道 HITL 门可链式调用（官方教程：每个 complete 返回新的 `ResultWithAgenticScope`，可能再次 suspended）。
- 声明式：`@HumanInTheLoop` 标注静态方法，由框架识别为 HITL 节点。
- 官方建议：长时间交互（小时/天，需要崩溃恢复）用 `SuspendedResponse`；进程内短等待用 `PendingResponse`。

**与仓库衔接**：HITL 天然适合「审批门 / 缺失信息收集」场景——REST 语义下：`SuspendedResponse` + `AgenticScopeStore`（Postgres 实现）+ 首次调用捕获 `AgenticSystemSuspendedException`（或 `suspended()==true`）把 pendingIds 暴露给前端 → 用户在 UI 批准 → 后端 `completePendingResponse` + 同 memoryId 重调。这正好是自研 Control Plane 薄层可以承接的「挂起/恢复」协议，无需自研中断机制。

---

## 5. 持久化 SPI

### 5.1 接口与方法（javap 核实）

```java
// dev.langchain4j.agentic.scope.AgenticScopeStore —— 唯一持久化 SPI
public interface AgenticScopeStore {
    boolean save(AgenticScopeKey, DefaultAgenticScope);
    Optional<DefaultAgenticScope> load(AgenticScopeKey);
    boolean delete(AgenticScopeKey);
    Set<AgenticScopeKey> getAllKeys();
}
public final record AgenticScopeKey(String agentId, Object memoryId) { }
```

装配方式（两种，官方教程）：
1. 程序化：`AgenticScopePersister.setStore(new MyAgenticScopeStore());`（`AgenticScopePersister` 是枚举单例 `INSTANCE`，持有静态 `AgenticScopeStore store`，默认 **null = 不持久化**）。
2. SPI：`META-INF/services/dev.langchain4j.agentic.scope.AgenticScopeStore` 文件写实现类全名（jar 内目前没有内置实现，需自研或第三方）。

### 5.2 默认行为与配套

- **默认无 store**：`AgenticScope` 纯内存；无记忆时运行结束即弃；有记忆时存 `AgenticScopeRegistry`（内存）直到 `evictAgenticScope(memoryId)`。
- 序列化：`AgenticScopeSerializer.toJson(DefaultAgenticScope)` / `fromJson(String)`，底层 `JacksonAgenticScopeJsonCodec`；**反序列化 allowlist 白名单**（安全）：`allowDeserializationPackagePrefix(String)` / `allowDeserializationType(Class<?>)`，未注册类型抛 `UnserializableAgenticScopeException`；默认允许 JDK 类型（`java.util.*`/`java.math.*`/包装类/枚举）+ 内部类型（`AgentMessage`/`AgentInvocation`）。
- Crash 恢复：配置 store 后**每步 checkpoint**（每次 agent 调用后把 scope 落 store）+ planner 执行状态持久化：`Planner.executionState() → Map<String,Object>` / `restoreExecutionState(Map)`（默认 no-op；`SequentialPlanner`/`LoopPlanner` 保存游标/迭代计数，`ParallelPlanner`/`ConditionalPlanner` 用默认）。
- 跨进程挂起恢复：`SuspendedResponse` 场景下 scope + pendingIds 一并 checkpoint，重启后 `scope.completePendingResponse` + 同 memoryId 重调即可恢复（官方教程订单审批示例）。

**与仓库衔接**：仓库已有 PostgreSQL + JPA/Flyway，可实现 `AgenticScopeStore` 落库（按 `AgenticScopeKey(agentId, memoryId)` 主键）；注意 scope 内容为任意对象（Jackson 多态），需按 allowlist 机制登记仓库业务类型（或先只存可序列化基础类型）；一期也可先用内存 registry（`getAllAgenticScopeKeysInMemory`），持久化作为可选演进。

---

## 6. MCP Tool Agent

### 6.1 agentic 原生 MCP 能力（两个 jar）

**`langchain4j-agentic-mcp-1.19.0-beta29.jar`**（把 MCP 工具包装成 agent）：

```java
public class McpAgent {
    public static McpClientBuilder<UntypedAgent> builder(McpClient);              // 单工具 → 非 AI agent
    public static <T> McpClientBuilder<T> builder(McpClient, Class<T>);
}
public interface McpClientInstance extends InternalAgent {
    String[] inputKeys();  String toolName();  String toolDescription();
}
public class McpClientAgentInvoker implements AgentInvoker { ... }   // 执行器
```

**`langchain4j-mcp-1.19.0-beta29.jar`**（协议客户端 + 工具提供器）：

```java
public interface McpClient extends AutoCloseable {
    String key();
    List<ToolSpecification> listTools();                       // dev.langchain4j.agent.tool.ToolSpecification（core 1.19.0）
    ToolExecutionResult executeTool(ToolExecutionRequest);     // dev.langchain4j.agent.tool.ToolExecutionRequest
    List<McpResource> listResources();  McpReadResourceResult readResource(String);
    List<McpPrompt> listPrompts();  McpGetPromptResult getPrompt(String, Map<String,Object>);
    void checkHealth();  void setRoots(List<McpRoot>);
}
// DefaultMcpClient.Builder：transport(McpTransport)（stdio/sse 等）、key、clientName、各 timeout、
// logHandler、autoHealthCheck、roots、cacheToolList、listener、progressHandler、metaSupplier、toolResultExtractor ...

public class McpToolProvider implements ToolProvider {        // 动态工具提供器（ToolProvider 见 langchain4j 1.19.0）
    // Builder：mcpClients(List<McpClient>) / mcpClients(McpClient...)
    //          filter(BiPredicate<McpClient, ToolSpecification>)   ← 白名单过滤点
    //          filterToolNames(List<String> / String...)
    //          alwaysVisibleToolNames(Set<String> / String...)
    //          toolWrapper(Function<ToolExecutor, ToolExecutor>)    ← 执行器包装（权限/审计钩子）
    //          toolNameMapper / toolSpecificationMapper(BiFunction<McpClient, ToolSpecification, ...>)
    //          failIfOneServerFails(boolean) / resourcesAsToolsPresenter(...) / returnToolResultAttributes(...)
    ToolProviderResult provideTools(ToolProviderRequest);      // 每次模型工具调用轮询时动态下发
    void addMcpClient(McpClient);  void removeMcpClient(McpClient);
    void addFilter(...);  void setFilter(...);  void resetFilters();  ...
}
```

**工具模型一致性（关键）**：`McpClient.listTools()` 返回的正是仓库 `AgentToolBindingFactory` 用的 `dev.langchain4j.agent.tool.ToolSpecification`（core），`ToolExecutor` / `ToolProvider` 在 `langchain4j` 1.19.0 —— 与仓库现有绑定**同一套类型**。

### 6.2 与现有「McpToolRegistry + AgentToolBindingFactory 手工绑定」的关系

仓库现状（agent-decision-layer 03）：
- `McpToolRegistry` = 唯一工具源（进程内注册表，聚合 read/write/domainRead 工具）。
- `AgentToolBindingFactory.bind(runId, toolNames)` = **run 级静态绑定**：按 `AgentDefinition.tool_names` 白名单从 registry 取工具 → `McpToolSchemaConverter` 转 `JsonSchemaElement` → `Map<ToolSpecification, ToolExecutor>`（白名单外不暴露；引用漂移 fail-fast）。
- `AgentToolInvoker implements ToolExecutor` = 桥接执行器：`McpTool.invoke(McpRequestContext, args)`，身份/幂等/审计经 `McpAgentIdentity.newContext(runId)`（requestId=traceId=run_id，operator 服务端注入，工具参数不接受 operator）。

对比与判断表：

| 维度 | 现有手工绑定 | agentic 原生（McpToolProvider / McpAgent） |
|---|---|---|
| 工具来源 | `McpToolRegistry`（进程内唯一源） | `McpClient`（MCP 协议客户端，stdio/sse 连接外部 server） |
| 白名单过滤点 | **绑定期**（run 级静态，省 token，fail-fast） | **provision 期**（每轮模型工具调用动态 `provideTools`，`filter`/`filterToolNames`/`alwaysVisibleToolNames`） |
| 执行器挂钩 | `AgentToolInvoker`（run_id 身份/审计/幂等） | `toolWrapper(Function<ToolExecutor, ToolExecutor>)`（可在此桥接回身份注入，但非默认） |
| 身份/门禁 | 仓库 `McpAgentIdentity`/`McpTool.invoke` 路径（08 票强制点） | `McpClient.executeTool` 不经过仓库身份路径，需自行重做 |
| schema 转换 | `McpToolSchemaConverter`（JsonNode→JsonSchemaElement） | `McpClient.listTools()` 已返回 `ToolSpecification`（免转换） |
| 与 agentic 对接 | `AgentBuilder.tools(Map<ToolSpecification, ToolExecutor>, Set<String>)` 直接吃现有绑定产物 | `AgentBuilder.toolProvider(McpToolProvider)` 或 `McpAgent.builder(McpClient)` 子 agent |

**结论（建议保留现有绑定）**：
1. 权限隔离（08 票）的强制点在 MCP 层（`McpServer` 分发/写工具门禁）+ 绑定期白名单。`McpToolProvider` 的 filter 是「每轮动态下发」的白名单，语义与「run 级静态白名单」不同，且不经过仓库身份/审计路径 —— **替换会破坏 08 的权限设计**。
2. 接入 agentic 时复用现有工具源：`AgentBuilder.tools(Map<ToolSpecification, ToolExecutor>, Set<String>)`（2 参重载支持工具名集合）与 `AgentToolBindingFactory` 的产物同构，直接喂入；或实现一个 `ToolProvider` 适配器（内部仍从 `McpToolRegistry` 按白名单 provision，可复用 `AgentToolInvoker`）。
3. `McpClient`/`McpAgent` 原生路径仅当未来需要**直连外部 MCP server**（stdio/sse，非仓库进程内工具）时再评估，且需单独设计身份/门禁/审计（不能复用 `McpAgentIdentity` 语义）。
4. `McpToolSchemaConverter` 在原生 `McpClient` 路径下不再需要（`listTools()` 已给 `ToolSpecification`），但现有 registry 路径保留。

---

## 7. 与票 01 结论的衔接（结构化输出）

### 7.1 agentic 的结构化输出是否绑定静态 record？

**是**。证据：
- `AgentBuilder.agentReturnType` / `UntypedAgentBuilder.returnType(Class<?>)` —— 输出类型是**固定 Class**；`@Agent` 方法返回类型（record/POJO/String/枚举）经 AiServices 常规路径（`JsonSchemas.jsonSchemaFrom(Type)` 反射生成 schema + `PojoOutputParser` 反射解析，见票 01 research §2.1）。
- `langchain4j-agentic` jar 内**没有** `ResponseFormat`/`JsonSchema` 相关类与操作（`unzip -l` 与 `javap -c AgentInvocationHandler` 均无命中），`AgentBuilder` 也不暴露 `chatRequestTransformer` —— agentic 层不提供动态 schema 入口。
- 因此票 01 的结论 a（AiServices 绑定静态 record）**对 agentic 同样成立**；`AgentStructuredOutput` 信封可继续作为固定 gateway 类型在 agentic 中使用。

### 7.2 与低层 ChatRequest.responseFormat 动态 schema（JsonRawSchema）能否共存？

**能，二者分层不冲突**。票 01 推荐路径 b（provider 自适应 response_format：OpenAI `json_schema`/`JsonRawSchema`，DeepSeek `json_object` + 客户端 JSON Schema 校验兜底，统一 JsonNode/Map 容器）是**低层 `ChatModel.chat(ChatRequest)` 通道**；agentic 是**高层编排/状态/工具层**（AiServices 之上）。共存方案：

| 层次 | 承载 | 说明 |
|---|---|---|
| 编排/状态/工具 | agentic（`AgenticServices` + `AgenticScope`） | 多 agent 编排、scope 状态传递、HITL、持久化、工具绑定 |
| 动态 schema 输出 | 票 01 低层通道 | 需要动态 schema 的 agent 用**非 AI agent**（`AgentInstance` / `agentInstanceFactory` / `NON_AI_AGENT` 拓扑）内嵌 `ChatModel.chat(ChatRequest)`（`JsonRawSchema.from(schema)` + `OpenAiChatRequestParameters.responseFormat(...)` + 校验兜底），或保留为最终输出网关 |
| 静态 record 输出 | agentic 原生（AiServices 路径） | 固定 schema 的 agent 直接 `@Agent` 方法返回 record |

注意事项：agentic 的 builder 配 `OpenAiChatModel` 时，`supportedCapabilities(RESPONSE_FORMAT_JSON_SCHEMA)` / `strictJsonSchema` 等能力配置仍按票 01 的供应商能力表设置；DeepSeek 端点的 `json_schema` 400 限制（票 01 §3.2）在 agentic 路径下同样适用——agentic 不会绕过低层 response_format 序列化。

---

## 8. 与现有代码的衔接判断表

| # | 现有代码 | agentic 1.19.0-beta29 事实 | 衔接判断 |
|---|---|---|---|
| 1 | `LangChain4jAgentRuntime`（AiServices gateway + 错误映射） | agentic Agent = AiServices 代理 + AgenticScope | agentic 可逐步替换/叠加：单 agent 保留 AiServices 语义，编排用 agentic；`OutputParsingException`→`AGENT_OUTPUT_INVALID` 错误映射仍适用（同一解析器） |
| 2 | `AgentGateway`（`Result<AgentStructuredOutput>`） | `@Agent` 方法返回 record/POJO，`ResultWithAgenticScope<T>` 携带 scope | `AgentStructuredOutput` 可作 agentic 方法的返回类型继续使用；或按票 01 改 JsonNode/Map |
| 3 | `AgentToolBindingFactory`（绑定期白名单，产物 `Map<ToolSpecification, ToolExecutor>`） | `AgentBuilder.tools(Map, Set)` / `toolProvider(ToolProvider)`；`McpToolProvider` filter 在 provision 期 | **保留现有绑定**；agentic 接入用 `tools(Map, Set)` 喂入；权限隔离点（08）不动 |
| 4 | `AgentToolInvoker`（run_id 身份/审计/幂等） | `ToolExecutor.execute(ToolExecutionRequest, Object)` 同签名；`toolWrapper` 可挂钩 | 直接复用；`ToolExecutionRequest`/`ToolExecutor` 同类型（core/langchain4j 1.19.0） |
| 5 | `McpToolSchemaConverter`（JsonNode→JsonSchemaElement） | `McpClient.listTools()` 直接返回 `ToolSpecification` | 现有 registry 路径保留；原生 McpClient 路径免转换 |
| 6 | `AgentRegistry`/`AgentDefinition`（02/03 票注册表） | `AgenticServices.createAgenticSystem(Class, ChatModel, AgentConfigurator)` + `@Agent` 声明式 | Control Plane 把注册表定义映射到 agentic builder 调用；`subAgentResolver` 可注入 Spring bean |
| 7 | `AgentObservability`（08 票） | `AgentListener`（before/after agent invocation、tool execution、scope 创建/销毁、suspended） | 适配 `AgentListener` 实现复用现有观测落库（`AgentMonitor` 内置会话保留） |
| 8 | 08 权限隔离（MCP 层强制点） | `McpToolProvider.filter` 是 provision 期过滤，非绑定期 | 不替换；agentic 侧仍走现有 registry + 绑定 |
| 9 | 票 01 动态 schema（JsonRawSchema） | agentic 不暴露动态 schema 入口 | 分层共存：agentic 编排 + 非 AI agent 内嵌低层通道 |
| 10 | pom（BOM 1.19.0） | agentic = `1.19.0-beta29`（beta 线） | 新增依赖 `dev.langchain4j:langchain4j-agentic`（BOM 已管版本）；如需 MCP 原生再引 `langchain4j-agentic-mcp`/`langchain4j-mcp` |

---

## 9. 证据来源

**本地 jar（~/.m2，1.19.0 stable，javap 核实）**
- `~/.m2/repository/dev/langchain4j/langchain4j-core/1.19.0/langchain4j-core-1.19.0.jar`（`dev.langchain4j.agent.tool.*`：`Tool`/`ToolSpecification`/`ToolExecutionRequest`/`ToolMemoryId`/`ToolSpecifications`）
- `~/.m2/repository/dev/langchain4j/langchain4j/1.19.0/langchain4j-1.19.0.jar`（`dev.langchain4j.service.tool.ToolProvider`/`ToolExecutor`/`ToolProviderResult`、`AiServiceContext`）
- `~/.m2/repository/dev/langchain4j/langchain4j-bom/1.19.0/langchain4j-bom-1.19.0.pom`（stable/beta 版本属性与 agentic 条目）
- `~/.m2/repository/dev/langchain4j/langchain4j-open-ai/1.19.0/...`（票 01 已核实）

**本次下载 jar（Maven Central，主证据，/tmp/l4j-agentic/）**
- `langchain4j-agentic-1.19.0-beta29.jar`（299,858 B）+ `.pom`
- `langchain4j-agentic-mcp-1.19.0-beta29.jar` + `.pom`
- `langchain4j-agentic-a2a-1.19.0-beta29.jar` + `.pom`
- `langchain4j-agentic-patterns-1.19.0-beta29.jar` + `.pom`
- `/tmp/l4j-mcp/langchain4j-mcp-1.19.0-beta29.jar`（`McpClient`/`McpToolProvider`/`DefaultMcpClient`）
- 下载坐标示例：`curl -o ... https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-agentic/1.19.0-beta29/langchain4j-agentic-1.19.0-beta29.jar`

**官方文档（辅助证据）**
- Agents 教程（AgenticScope / workflow / HITL / Supervisor / 持久化 / recoverability / 声明式 API）：<https://docs.langchain4j.dev/tutorials/agents/>（原始 md：<https://github.com/langchain4j/langchain4j/blob/main/docs/docs/tutorials/agents.md>）
- apidocs：AgenticScopeStore <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/AgenticScopeStore.html>、AgenticScopePersister <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/AgenticScopePersister.html>、AgenticSystemSuspendedException <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/AgenticSystemSuspendedException.html>、SuspendedResponse <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/internal/SuspendedResponse.html>、ResultWithAgenticScope <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/scope/ResultWithAgenticScope.html>、HumanInTheLoop <https://docs.langchain4j.dev/apidocs/dev/langchain4j/agentic/declarative/HumanInTheLoop.html>
- 仓库相关票：01（动态 schema）research/01-langchain4j-dynamic-schema.md；08（MCP 权限隔离）issues/08-mcp-permission-isolation.md
