# 02 — 现有四条路径收敛点审计（事实清单）

> 票：`.scratch/meta-agent-platform/issues/02-four-path-convergence-audit.md`
> 性质：只读代码审计，无任何代码改动。所有结论附代码引用位置（`文件:行号`）。
> 基线：`backend/src/main/java/cn/zimu/fulfillment/agent/`（45 个主类 + `procurement/` 子包）与 `backend/src/main/java/cn/zimu/fulfillment/mcp/`。
> 前置事实（影响所有结论）：四条路径中**只有 IntentRecognitionAgentBridge 有生产调用方**（挂在 `message/InterpretationService`）；`AgentRuntimeFacade` / `ProcurementPriceAgent` / `DataQueryAgentService` 均只装配为 Spring bean，**主源码中无任何生产入口**（grep 全量 `backend/src`，仅在配置类与测试中引用；`DemoScenarioController` 不引用任何 Agent）。

---

## 1. 四条路径编排差异

### 1.1 各路径步骤序列

**路径 A：`AgentRuntimeFacade.invoke`（02 票通用门面，`agent/AgentRuntimeFacade.java`）**

```
invoke(agentSlug, userInput, ctx)
├─ ctx 归一化（null → AgentRunContext.empty()）                        :79
├─ runId = newRunId()（"run_"+UUID-hex，静态工具方法）                 :80, :119-121
├─ registry.bySlug(slug) —— 参数化 slug                                :81
│  ├─ null    → runStarted(unknown) + 审计 AGENT_NOT_FOUND + runFinished → failClosed(AGENT_NOT_FOUND)   :82-87
│  └─ !enabled→ runStarted + 审计 AGENT_DISABLED + runFinished → failClosed(AGENT_DISABLED)               :88-93
├─ runStarted(ctx, runId, definition, userInput) —— 落 RUNNING 观测行  :94, :127-142
├─ binding = toolBindingFactory.bind(runId, definition.toolNames())    :97  （白名单过滤点，见 §4）
├─ result = runtime.run(AgentTaskRequest(systemPrompt, userInput, binding))  :98-99  （AgentRuntime 接缝，schema 固定 AgentStructuredOutput）
├─ 审计 recordAudit（AGENT/agent.{slug}.run）+ runFinished 收口         :100-104
└─ RuntimeException → runFinished("AGENT_RUNTIME_EXCEPTION") + 原样上抛 :105-110
```

- 输入：自由文本 `userInput`；无输入解析、无守卫。
- 模型接缝：`AgentRuntime`（`DefaultAgentRuntime` fail-closed 兜底 / `LangChain4jAgentRuntime` 互斥，`AgentRuntimeConfiguration.java:19-30`）。
- 观测：编排级完整（runStarted/runFinished，`runStarted` 的 `agentVersion` 传 null、input 只存 digest）；工具调用与 token 观测由 `AgentToolInvoker` / `LangChain4jAgentRuntime.recordTokens` 承接。
- 调用方：**无生产调用方**（仅 `AgentRuntimeFacadeTest` / `AgentRuntimeFacadeObservabilityTest` / `AgentObservabilityIntegrationTest`）。

**路径 B：`ProcurementPriceAgent.compare`（05 票采购比价自研编排，`agent/procurement/ProcurementPriceAgent.java`）**

```
compare(jsonInput, ctx)
├─ input = ProcurementPriceInput.parse(jsonInput) —— 结构化 JSON 解析校验   :66, ProcurementPriceInput.java:23-53
│  失败 → BusinessException(INVALID_PARAMETERS)，不审计、不进模型           :80-82
├─ ctx 归一化                                                                 :67
├─ runId = AgentRuntimeFacade.newRunId()（复用门面静态方法）                 :68
├─ registry.bySlug(AGENT_SLUG)（硬编码 slug 常量）                           :69
│  ├─ null    → 审计 AGENT_NOT_FOUND → ProcurementPriceRunResult.failClosed  :70-73
│  └─ !enabled→ 审计 AGENT_DISABLED → failClosed                             :74-77
├─ binding = toolBindingFactory.bind(runId, definition.toolNames())          :79
├─ result = runtime.run(AgentTaskRequest(systemPrompt, input.toUserInput(), binding))  :80-81
│   （ProcurementPriceRuntime 专属接缝，schema=ProcurementPriceRecommendation）
└─ 审计 recordAudit（responsePayload 带 recommendation_summary 可复核摘要）  :82-85, :124-179
```

- 输入：结构化 JSON（`procurement_ticket_id` 或 `sku_id`，可选 `quantity`），解析失败抛 `INVALID_PARAMETERS` 4xx。
- 模型接缝：`ProcurementPriceRuntime`（唯一实现 `ProcurementPriceAgentRuntime`，内置 `ProcurementPricePolicy.enforce` 确定性归一化，`ProcurementPriceAgentRuntime.java:61-64`）。
- 观测：**编排级无** runStarted/runFinished（`ProcurementPriceAgent` 不持有 `AgentObservability`）；工具调用观测经 `AgentToolInvoker` 有。
- 调用方：**无生产调用方**（仅测试）。

**路径 C：`DataQueryAgentService.answer`（06 票数据查询自研编排，`agent/DataQueryAgentService.java`）**

```
answer(question, ctx)
├─ ctx 归一化 / runId                                                        :88-89
├─ registry.bySlug(SLUG)（硬编码）                                           :90
│  ├─ null    → rejected（审计 + DataQueryRunResult(null, code, runId, code, [], 0)）  :91-93, :175-179
│  └─ !enabled→ rejected                                                      :94-96
├─ 空问题 → clarification（不触模型）                                          :98-104
├─ DataQueryAgentGuard.piiProblems → PII_GUARDED 转人工（不触模型）            :106-117
├─ DataQueryAgentGuard.ambiguityProblems → CLARIFICATION（不触模型）          :119-122
├─ !properties.configured() → rejected(AGENT_MODEL_NOT_CONFIGURED)            :124-126
├─ binding = bindWithRecording(...) —— 白名单绑定 + RecordingToolExecutor 包装 :130, :282-290
│   （记录工具调用序列；工具参数级占位拦截 CLARIFICATION_REQUIRED，:303-347）
├─ AiServices.builder(DataQueryAgentGateway).chatModel(chatModel()).tools(...) :131-134
├─ gateway.run(systemPrompt, question)                                        :136
│  ├─ OutputParsingException → failed(AGENT_OUTPUT_INVALID)                   :150-152
│  └─ RuntimeException     → failed(AGENT_MODEL_CALL_FAILED)                  :153-157
└─ finish：审计（responsePayload 带 tool_call_sequence）+ DataQueryRunResult(output, null, runId, status, toolCalls, latency)  :201-213
```

- 输入：自然语言 `question` + 可选 `thread_id`；空文本/守卫命中不触模型。
- 模型接缝：**不经 `AgentRuntime`** —— 直接 `AiServices` + 自建 `OpenAiChatModel`（`chatModel()` `:292-300`），读 `AgentModelProperties`。
- 守卫：`DataQueryAgentGuard` 三层（问题级 PII、问题级歧义、工具参数级占位拦截），见 §2。
- 观测：编排级无 runStarted/runFinished；工具调用观测经 `AgentToolInvoker` 有。
- 调用方：**无生产调用方**（仅测试 + `eval/AgentEvalScorer`）。

**路径 D：`IntentRecognitionAgentBridge`（07 票意图识别观测桥，`agent/IntentRecognitionAgentBridge.java`）**

```
（挂在 message/InterpretationService.interpret 模型调用前后，InterpretationService.java:89-115）
isEnabled() → registry.isEnabled(AGENT_SLUG)                       :55-57
runStarted(threadId=task.id, submissionId, inputContent)
├─ registry.bySlug(SLUG)；null/未启用 → 返回 null（不写观测）       :65-68
├─ runId = AgentRuntimeFacade.newRunId()                            :69
└─ observability.runStarted(..., business_entity=MESSAGE_SUBMISSION/submission_id)  :71-80
runFinished(runId, threadId, submissionId, IntentRecognitionRunMetadata, latency)
├─ recordAudit —— AGENT/agent.intent-recognition.run + intent/error_code  :97, :113-152
└─ observability.runFinished                                        :98-107
```

- 输入：无（调用方是 `InterpretationService`，真实执行由 `MessageInterpreter`/`DeepSeekMessageInterpreter` 完成）。
- 工具绑定：恒空（`IntentRecognitionAgentConfiguration.java:46` toolNames=List.of()）。
- 观测：编排级完整（runStarted/runFinished）；**为补 agent_runs 表缺失的 intent/provider 列，每次运行额外落一条 AGENT 审计**（类注释 `:21-24`）。
- 失败码：`InterpretationFailureCode`（message 层枚举），经 `IntentRecognitionRunMetadata.errorCode` 投影进审计/观测。
- 调用方：`message/InterpretationService`（唯一有生产调用方的路径）。

### 1.2 差异点汇总表

| 维度 | A 门面 (AgentRuntimeFacade) | B 采购比价 (ProcurementPriceAgent) | C 数据查询 (DataQueryAgentService) | D 意图识别桥 (IntentRecognitionAgentBridge) |
|---|---|---|---|---|
| 输出 schema | 固定 `AgentStructuredOutput`（最小 schema，`AgentStructuredOutput.java:10`） | 专属 `ProcurementPriceRecommendation`（8 字段 + 嵌套，JsonProperty snake_case，`ProcurementPriceRecommendation.java:19-54`） | 专属 `DataQueryAgentOutput`（answer/sources/confidence/requires_human/clarification_needed，`DataQueryAgentOutput.java:17-22`） | 无输出记录（仅 `IntentRecognitionRunMetadata` 观测摘要，`IntentRecognitionRunMetadata.java:12-17`） |
| schema 定义位置 | 运行时硬编码（AiServices 接口 `AgentGateway`） | 运行时硬编码（`ProcurementPriceGateway`） | 运行时硬编码（`DataQueryAgentGateway`） | 不适用（声明性定义，无执行） |
| 注册表解析 | 参数化 slug（`invoke(String agentSlug,...)`，:78） | 硬编码 `AGENT_SLUG`（:34） | 硬编码 `SLUG`（:90） | 硬编码 `AGENT_SLUG`（:56, :65） |
| enabled 判定 | `definition.enabled()` | 同左 | 同左 | `registry.isEnabled()`（未注册=未启用，fail-closed） |
| run_id 来源 | `AgentRuntimeFacade.newRunId()`（:119-121） | 复用门面静态方法（:68） | 复用门面静态方法（:89） | 复用门面静态方法（:69） |
| 输入解析 | 无（自由文本） | `ProcurementPriceInput.parse` 结构化校验 → `INVALID_PARAMETERS` 抛异常不审计（`ProcurementPriceInput.java:23-53`） | 空文本检查 + 守卫（:98-122） | 无 |
| 守卫 | 无 | 无（输入校验代替） | `DataQueryAgentGuard` 三层（问题级 PII/歧义 + 工具参数级占位拦截，`DataQueryAgentService.java:106-122, :318-322`） | 无 |
| 模型接缝 | `AgentRuntime`（01 接缝，`AgentRuntimeConfiguration.java`） | `ProcurementPriceRuntime` 专属接缝（`ProcurementPriceRuntime.java:13-15`） | **不经 AgentRuntime**，直接 AiServices + 自建 OpenAiChatModel（:131-134, :292-300） | message 层 `MessageInterpreter`（不属 agent 包） |
| 失败码 | `AgentFailureCode` 5 枚举 + 内部收口码 `AGENT_RUNTIME_EXCEPTION`（:108） | `AgentFailureCode` 5 枚举 + `INVALID_PARAMETERS`（BusinessException，输入路径） | `AgentFailureCode` 5 枚举（rejected/failed）+ **自定义 status 字符串** SUCCESS/CLARIFICATION/PII_GUARDED/FAILURE（:55-57, :198） | `InterpretationFailureCode`（message 层，非 AgentFailureCode） |
| 审计 operation | `agent.{slug}.run`（:182） | `agent.{slug}.run`（:108） | `agent.{slug}.run`（:246） | `agent.{slug}.run`（:138） |
| 审计 requestPayload | agent_slug/run_id/thread_id/prompt_version/model_ref/tool_names（:183-189） | 同左（:109-115） | 同左（:247-253） | 同左（:139-145） |
| 审计 responsePayload | status/provider/model/prompt_version（:190-194） | 同左 + `recommendation_summary`（:124-179） | 同左 + `tool_call_sequence`（:231-236, :262-275） | 同左 + `intent`/`error_code`（:121-129） |
| 审计失败容忍 | try/catch 隔离（:197-199） | try/catch 隔离（:119-121） | try/catch 隔离（:257-259） | try/catch 隔离（:149-151） |
| 编排级观测 | ✅ runStarted/runFinished（:127-151） | ❌ 无（类无 AgentObservability 依赖） | ❌ 无（类无 AgentObservability 依赖） | ✅ runStarted/runFinished（:71-107） |
| 工具调用观测 | ✅ AgentToolInvoker（`AgentToolInvoker.java:105-119`） | ✅ 经同一 invoker | ✅ 经同一 invoker + RecordingToolExecutor 记录序列 | ❌ 无工具（白名单空） |
| 工具调用序列审计 | ❌（不记录调用序列） | ❌ | ✅ `tool_call_sequence`（含 guarded/guard_reason，:262-275） | ❌ |
| 生产调用方 | 无 | 无 | 无 | ✅ `message/InterpretationService`（:89-115） |

### 1.3 关键差异详情（影响收敛的实质差异）

1. **schema 固定 vs 专属（差异核心）**：A 的 `AgentStructuredOutput(summary, reasoning)` 是最小 schema（01 票声明不改，`AgentStructuredOutput.java:8`）；B/C 各自在 `procurement/` 与 `agent/` 下定义专属记录并由专属 AiServices 接口约束。三处 schema 全部是**代码内硬编码**，没有任何一处来自注册表/DB——这是 03 票 `output_schema` 数据化的直接输入。
2. **守卫只在 C**：`DataQueryAgentGuard`（`DataQueryAgentGuard.java:22-94`）实现 PII 关键词、SKU/工单号/实体占位歧义、工具参数占位兜底三层；B 用输入校验替代、A/D 无守卫。守卫与具体 Agent（C）**强绑定**：判定逻辑硬编码在 `agent/` 包，提示词里写死规则（`DataQueryAgentDefinitionConfiguration.java:55-65`），工具参数拦截硬编码在 `DataQueryAgentService.RecordingToolExecutor`（:303-347）。
3. **输入解析只在 B**：`ProcurementPriceInput.parse` 抛 `INVALID_PARAMETERS`（BusinessException）且**不落审计**——与 A/C/D 的「拒绝必审计」契约不一致；C 的拒绝路径（rejected）是审计的。
4. **失败码映射不统一**：A/B 用 `AgentFailureCode`；C 复用 `AgentFailureCode` 但额外引入自定义状态字符串（CLARIFICATION/PII_GUARDED 等，不属于 `AgentFailureCode` 枚举，无法进观测行 `error_type` 之外的稳定枚举）；D 用 message 层 `InterpretationFailureCode`。`AgentFailureCode` 缺业务态（澄清/PII 门/守卫拒绝/权限拒绝）——见 §3。
5. **审计载荷四份近似重复**：requestPayload 四路径完全同构（agent_slug/run_id/thread_id/prompt_version/model_ref/tool_names）；responsePayload 只有 status/provider/model/prompt_version 基底 + 各自的业务扩展（recommendation_summary / tool_call_sequence / intent+error_code）。`blankToDefault(DEFAULT_OPERATOR)` 工具方法在 A/B 各复制一份（`AgentRuntimeFacade.java:202-204`、`ProcurementPriceAgent.java:181-183`），C 内联（:241-243）。
6. **观测覆盖不一致**：A/D 有编排级 runStarted/runFinished；B/C 只有工具级观测——`V29__agent_observability.sql` 注释明确写了「05/06 业务 Agent 的编排层尚未落 agent_run 行（后续票承接）」：`agent_tool_calls.run_id` 无外键（`V29__agent_observability.sql:38-40`），孤儿行按 run_id 关联。
7. **审计与观测分离**：D 因 `app.agent_runs` 表没有 intent/provider 列，每次运行**额外落一条 AGENT 审计**来补可查字段（`IntentRecognitionAgentBridge.java:21-24`）——观测 schema 的字段缺口被审计兜底，属应消除的重复通道。
8. **run_id 生成已统一**：四条路径全部复用 `AgentRuntimeFacade.newRunId()`，无需收敛。

---

## 2. 可收敛 vs 必须保留

### 2.1 可安全收敛到统一门面（04 票素材）

| 逻辑 | 现状位置 | 收敛理由 |
|---|---|---|
| 注册表解析 + enabled 判定 + run_id | A:81-93 / B:68-77 / C:90-96 / D:65-68 | 四份同构；仅 A 参数化 slug，其余硬编码——统一门面按 slug 参数化即可 |
| 拒绝路径（AGENT_NOT_FOUND / AGENT_DISABLED）审计 + 观测 | A:82-93 / B:70-77 / C:91-96（仅审计） | 语义一致；C 的 rejected 应补观测行（当前缺失） |
| 工具白名单绑定 | A:97 / B:79 / C:130（+包装）/ D:不适用 | `AgentToolBindingFactory.bind` 已是唯一绑定入口，三路径同一工厂 |
| AGENT 审计公共载荷组装 | A:162-200 / B:88-122 / C:216-260 / D:113-152 | requestPayload 四份同构；仅 responsePayload 业务扩展不同 |
| 模型调用失败码映射（OutputParsingException → AGENT_OUTPUT_INVALID，RuntimeException → AGENT_MODEL_CALL_FAILED） | A（在 LangChain4jAgentRuntime）/ B:66-72 / C:150-157 | 三处语义一致，可收敛进统一运行时 |
| runStarted/runFinished 观测两段写入 | A:127-151 / D:71-107 | 同构；B/C 缺失处由统一门面补齐 |
| 模型 allowlist 投影（publicProjection） | A/B/C/D 均调用 `AgentModelMetadataRegistry.publicProjection` | 已是公共服务，门面统一调用即可 |
| `AgentFailureCode.failClosed` sentinel | A/B 各自 `failClosed`（`AgentRunResult.java:18-20`、`ProcurementPriceRunResult.java:20-22`） | 收敛为统一结果类型（§3 字段差距） |

### 2.2 必须保留（不可/不宜收敛）

| 保留项 | 位置 | 为什么必须保留 |
|---|---|---|
| `DataQueryAgentGuard`（PII 拒绝 + 歧义澄清 + 工具参数兜底） | `DataQueryAgentGuard.java:22-94`；调用点 `DataQueryAgentService.java:106-122, :318-322` | 「歧义不猜参数 / PII 转人工」是不依赖模型自觉的确定性门禁；map 决策 10 将其泛化为平台默认 AgentGuard（定义可声明豁免），逻辑本体不能丢，只是从 C 专属改为通用 |
| `DataQueryAgentService.RecordingToolExecutor` 的占位参数拦截 + 调用序列记录 | `DataQueryAgentService.java:303-347` | 工具参数级兜底（`CLARIFICATION_REQUIRED` 回传，:341-346）与 `tool_call_sequence` 审计载荷（:262-275）是 C 的独有能力；若收敛，该能力须进通用守卫/通用观测（05 票），否则 C 行为退化 |
| `IntentRecognitionAgentBridge` 管线钩子 | `InterpretationService.java:89-115`（调用点）；`IntentRecognitionAgentBridge.java` | 意图识别真实执行在 message 层 `MessageInterpreter`（`IntentRecognitionAgentConfiguration.java:54-58`），agent 层只有观测桥；管线调用点不能移走。可收敛的是桥内部「观测 + 审计」实现（复用统一门面/观测接缝），桥壳与管线钩子保留 |
| `ProcurementPricePolicy.enforce` 确定性归一化 | `ProcurementPriceAgentRuntime.java:61-64`（调用）；`ProcurementPricePolicy` | 无候选/无价格/字段缺失/低置信度 → requires_human 的确定性收口是 B 的核心业务规则，保留在 B 的运行时内 |
| 专属输出记录 `ProcurementPriceRecommendation` / `DataQueryAgentOutput` / `DataQueryAgentToolCall` / `IntentRecognitionRunMetadata` | `procurement/ProcurementPriceRecommendation.java`、`DataQueryAgentOutput.java`、`DataQueryAgentToolCall.java`、`IntentRecognitionRunMetadata.java` | 这些就是 03 票 `output_schema` 数据化要承载的 schema 本体；保留定义、改为数据化引用 |
| 专属运行时 `ProcurementPriceRuntime` / `ProcurementPriceAgentRuntime` | `procurement/` 子包 | 专属 schema 的 AiServices 网关 + 归一化策略；统一运行时需支持注入式 output schema（01 票动态 schema 调研结论对接） |
| MCP 工具实现 `McpReadTools` / `McpDomainReadTools` / `McpWriteTools` | `mcp/` 三文件 | 工具实现是唯一工具源 `McpToolRegistry` 的组成部分，收敛不动工具本体，只动访问控制层（§4） |
| 注册表变更审计 `AgentRegistryChangeAuditor` | `AgentRegistryChangeAuditor.java:47-109` | 定义数据化后变更审计改为 DB 版本链触发，但「变更留痕」职责必须延续 |
| `AgentPayloadRedactor` / `SecretRedactor` 脱敏口径 | `AgentPayloadRedactor.java` | 输入 digest、工具参数/结果脱敏是红线（map.md:15），观测与审计共用，保留 |

---

## 3. 字段差距清单（现有字段 vs 数据化定义需要）

> 只列差距，不设计方案。来源：map 决策（2 定义真源=DB+版本链、3 草稿→确认→启用、8 Meta-Agent、9 模型缝、10 守卫豁免）。

### 3.1 `AgentDefinition`（现状 8 字段，`AgentDefinition.java:16-24`）

现状：`agentSlug, name, description, systemPrompt, promptVersion, modelRef, enabled(boolean), toolNames(List<String>)`

| 数据化需要 | 现状差距 |
|---|---|
| `output_schema`（结构化输出定义，B/C 专属记录 → 数据化） | ❌ 无。三处 schema 硬编码在代码（`AgentStructuredOutput` / `ProcurementPriceRecommendation` / `DataQueryAgentOutput`） |
| 定义版本 / 版本链标识（DB 唯一真源 + 版本链，map 决策 2） | ❌ 无定义版本字段。`promptVersion` 只是提示词版本（`DataQueryAgentDefinitionConfiguration.java:23`），不是定义本身的版本 |
| 生命周期状态（草稿 → 人工确认 → 启用，map 决策 3） | ❌ `enabled:boolean` 二态不够；缺 DRAFT/PUBLISHED/DISABLED 等状态机 |
| 创建者 / 修改者（Meta-Agent 产出定义草稿，map 决策 4/8） | ❌ 无 created_by/updated_by |
| 权限 profile 引用（per-agent 权限表达，map 决策 11 / 08 票） | ❌ 无 permission/scope 引用字段；现状只有扁平 `toolNames` 白名单 |
| 输入 schema / 输入解析声明（B 的 `ProcurementPriceInput` 结构化输入） | ❌ 无 input_schema；B 的输入契约硬编码在 `ProcurementPriceInput.java:17-53` |
| 守卫配置 / 守卫豁免声明（map 决策 10：PII/歧义为平台默认，定义可声明豁免） | ❌ 无 guard 相关字段；`DataQueryAgentGuard` 对 C 无条件生效、对 A/B/D 无条件不生效 |
| provider/model 在定义中携带（map 决策 9：定义携带 provider/model） | ❌ `modelRef` 只是配置引用字符串（`app.agent` / `app.message-interpreter`），定义里没有 provider/model 字段；allowlist 校验在 `AgentModelMetadataRegistry` 而非定义 |
| 评测引用 / 基线（map 决策 7：门禁 + 评测用例数据化） | ❌ 无 eval 引用字段（现状 `DataQueryAgentEvalFixture` 硬编码在测试包） |
| 审计/观测配置声明（可选：是否落 run 级观测） | ❌ 无；观测有无取决于调用方是否用门面（B/C 缺失即因此） |

### 3.2 `AgentModelMetadataRegistry`（`AgentModelMetadataRegistry.java:16-121`）

现状：仅 `publicMetadataAliases`（provider/model/promptVersion 三元组 allowlist）+ `PublicMetadataAlias.isPublishable` 隐式启用。

| 数据化需要 | 现状差距 |
|---|---|
| 按 Agent/定义的模型解析与校验（定义携带 provider/model 时的服务端 allowlist 判定） | ❌ 只有三元组 allowlist，无「定义 → 模型」的解析层 |
| 权限 profile 与模型可见性关联 | ❌ 无 |
| 别名停用状态显式化 | ⚠️ 仅 `isPublishable()` 长度/空白/控制字符校验（:105-116），无显式 active/停用字段 |

### 3.3 `AgentObservability`（`AgentObservability.java:20-66`）与 `app.agent_runs`（`V29__agent_observability.sql:10-34`）

现状：`Start(runId, threadId, agentSlug, agentVersion, promptVersion, model, inputDigest, businessEntityType, businessEntityId)`、`Finish(runId, errorType, latencyMs, model)`、`ToolCall`、`TokenUsage`；表列：run_id/thread_id/agent_slug/agent_version/prompt_version/model/input_digest/status/error_type/latency_ms/token_usage/business_entity_type/id/started_at/finished_at/created_at。

| 数据化需要 | 现状差距 |
|---|---|
| 意图/结构化输出摘要落观测（D 用额外审计补表缺口） | ❌ `agent_runs` 无 intent / output 摘要列（`IntentRecognitionAgentBridge.java:21-24` 明言）；D 因此每次运行多落一条审计 |
| 状态枚举扩展（CLARIFICATION / PII_GUARDED 等业务态） | ❌ status 只允许 RUNNING/SUCCESS/FAILED（`V29:20-21`）；C 的业务态只能进审计 business_code，进不了观测行 |
| agent_version 实际落值 | ⚠️ 字段存在但 A/D 调用时恒传 null（`AgentRuntimeFacade.java:133`、`IntentRecognitionAgentBridge.java:75`） |
| 创建者/调用方身份关联（operator 进观测） | ❌ operator 只在审计，不在观测行；观测行无 operator 列 |

### 3.4 `AgentFailureCode`（`AgentFailureCode.java:13-20`）

现状 5 枚举：`AGENT_MODEL_NOT_CONFIGURED / AGENT_MODEL_CALL_FAILED / AGENT_OUTPUT_INVALID / AGENT_NOT_FOUND / AGENT_DISABLED`。

| 数据化需要 | 现状差距 |
|---|---|
| 业务态失败码（澄清 / PII 门 / 守卫拦截） | ❌ C 用自定义 status 字符串（CLARIFICATION/PII_GUARDED）绕开枚举；守卫拦截回传 `CLARIFICATION_REQUIRED` 是工具层字符串（`DataQueryAgentService.java:343`），与 `AgentFailureCode` 无关联 |
| 权限拒绝码（MCP 层强制时的统一失败语义） | ❌ MCP 写门禁返回 `MCP_AUTH_REQUIRED` BusinessException（`McpAgentIdentity.java:47-48`、`McpRequestContext.java:24-25`），不属于 `AgentFailureCode`；Agent 侧无对应稳定码 |

### 3.5 `AgentRunResult`（`AgentRunResult.java:10-20`）

现状：`output(AgentStructuredOutput), provider, model, promptVersion, error`。

| 数据化需要 | 现状差距 |
|---|---|
| runId / status / latency / 工具调用序列随结果返回 | ❌ 均无；C 的 `DataQueryRunResult` 有 runId/status/toolCalls/latencyMs（`DataQueryRunResult.java:13-19`），A/B 的结果没有 |
| 结果类型承载业务专属 output（B/C 专属记录） | ❌ output 固定 `AgentStructuredOutput`；B/C 只能自建 `ProcurementPriceRunResult` / `DataQueryRunResult`（`ProcurementPriceRunResult.java:12-17`）——两个并行结果类型即 schema 未数据化的直接产物 |

---

## 4. MCP 权限 enforcement 点审计（为 08 票提供事实）

### 4.1 身份注入点

| 点 | 位置 | 说明 |
|---|---|---|
| 进程级身份捕获 | `McpAgentIdentity.java:20` `@Value("${app.mcp.agent-identity:}")` | 启动时注入一次，不可变；空白=未认证 |
| 环境变量来源 | `McpServerRunner.java:17`（注释：`MCP_AGENT_IDENTITY`） | stdio 启动入口 |
| 上下文生成 | `McpAgentIdentity.newContext()` :29-31（随机 requestId）、`newContext(requestId)` :39-42（requestId=traceId=传入值） | **Agent 路径传入 run_id**（`AgentToolInvoker.java:83`）；stdio 路径自生成（`McpServer.java:126`） |
| 写身份校验 | `McpAgentIdentity.requireAuthenticatedContext()` :45-51（401 MCP_AUTH_REQUIRED） | 目前主源码无调用方（仅测试） |

### 4.2 写工具门禁检查位置（逐行）

| 检查 | 位置 | 说明 |
|---|---|---|
| `McpWriteTools.executeWrite` 公共前置 | `McpWriteTools.java:235` `context.requireCommandContext()` | 每次写工具执行必经；身份缺失抛 401 |
| `reinterpretSubmission` 内 | `McpWriteTools.java:141` | work 内再校验（幂等 scope 内） |
| `submitOrderDraftSuggestion` 内 | `McpWriteTools.java:168` | 同上 |
| `submitSupplementaryMaterial` 内 | `McpWriteTools.java:193` | 同上 |
| `submitReviewRequest` 内 | `McpWriteTools.java:216` | 同上 |
| 门禁实现本体 | `McpRequestContext.requireCommandContext()` :22-28 | `operator = authenticatedOperator = agentIdentity`（与 HTTP 面 X-Operator 复验语义对齐，类注释 :8-11） |

### 4.3 审计路径

| 路径 | 位置 | 说明 |
|---|---|---|
| 写成功/重放审计 | `McpWriteTools.executeWrite` :239-254 | service=mcp, operation=mcp.{toolName}, actorType=AGENT, operator=context.agentIdentity()；responsePayload 带 replayed/http_status/result；businessCode=IDEMPOTENT_REPLAY 或 successCode |
| 写失败审计 | `McpWriteTools.recordFailureAudit` :263-281 | REQUIRES_NEW 独立事务（:265），防随业务回滚丢失；审计自身失败不掩盖原始异常（:278-280） |
| Agent 运行审计（非 MCP 层） | `AgentRuntimeFacade.recordAudit` :162-200 等四路径 | service=agent, operation=agent.{slug}.run（见 §1.2） |
| 工具调用观测（Agent 侧） | `AgentToolInvoker.recordToolCall` :105-119 | agent_tool_calls 表，脱敏摘要 |

### 4.4 当前「工具白名单过滤」发生在哪一层

- **唯一过滤点：Agent 侧绑定层** `AgentToolBindingFactory.bind`（`AgentToolBindingFactory.java:60-75`）：按 `AgentDefinition.toolNames()` 从 `McpToolRegistry.all()` 挑工具生成 LangChain4j `ToolSpecification`；引用未知工具抛 `IllegalArgumentException`（:65-67，配置漂移 fail-fast）。
- 白名单之外的工具「不暴露给模型」依赖两条间接保证：① 绑定只放行白名单工具（上述）；② 即便模型喊出白名单外工具名，`AgentToolInvoker.execute` 对未注册名称返回 `MCP_INTERNAL_ERROR`（:76-79）。
- **MCP stdio 面（`McpServer`）没有任何过滤**：`toolsList()` 返回 `registry.all()` 全部 28 个工具（`McpServer.java:167-177`），`handleToolCall` 按名查 `registry.find` 即分发（:117-121）——任何能连上 stdio 的客户端可发现并调用全部工具；写工具只靠「身份缺失 401」门禁，**不区分调用方权限**（单一全局 `app.mcp.agent-identity`）。
- **现状没有 per-agent 权限表**：`McpRequestContext` 只携带 `agentIdentity` 字符串（`McpRequestContext.java:12`），无 agent slug / 权限 profile；`McpToolRegistry` 无权限查询能力（`McpToolRegistry.java:33-39` 只有 all()/find()）。

### 4.5 若改为 MCP 层强制（工具访问以 MCP 层为强制点），需动的点

1. `McpToolRegistry`（或新增权限解析层）：`find/all` 需按「调用方身份 → 允许工具集」过滤/校验——为 `tools/list` 与 `tools/call` 提供权限感知查询；`McpTool` 接口（`McpTool.java:13-23`）或增加权限元数据（读写属性/scope）。
2. `McpServer.handleToolCall`（:111-147）：`tools/call` 分发前按权限拒绝（现状只查 `registry.find`）；`toolsList`（:167-177）按调用方过滤工具清单（现状全量）。
3. `McpRequestContext`（:12-28）：需携带 Agent slug / 权限 profile（现状只有全局 agentIdentity 字符串），否则 MCP 层无法区分调用方。
4. `McpAgentIdentity`（:20-51）：单一全局身份 → 按 Agent 运行的调用方注入/解析（Agent 路径 `newContext(runId)` 目前只有 run_id，没有 slug）。
5. `AgentToolInvoker.execute`（:67-102）：`identity.newContext(runId)`（:83）处改为携带 Agent 身份；MCP 层强制后，`AgentToolBindingFactory.bind` 的白名单过滤（:60-75）降级为「绑定期校验/预检」，强制点在每次调用（或保留 bind 校验 + MCP 层复核双保险）。
6. `McpWriteTools` 门禁（:235 等 5 处 `requireCommandContext`）：权限强制上移到统一入口后，内层校验可保留为纵深防御；`executeWrite`（:227-260）需适配新的权限上下文来源。
7. 依赖面：`AgentToolBindingFactory` 构造签名（:36-51，registry/identity/mapper/observability）需感知新权限上下文；`DataQueryAgentService`/`ProcurementPriceAgent`/门面共用该工厂（§1.1），改动会波及四条路径。

---

## 5. 工具面清单

> 全量聚合入口：`McpToolRegistry` 构造（`McpToolRegistry.java:18-31`）聚合 `McpReadTools.tools()` + `McpWriteTools.tools()` + `McpDomainReadTools.tools()`，重名抛 `IllegalStateException`（:25-28）。当前共 **28 个工具**（读 24 / 写 4）。

### 5.1 `McpReadTools`（13 个，全部只读，`McpReadTools.java:81-169`）

| 工具名 | 输入要点 | 读写 |
|---|---|---|
| `list_channel_messages` | page/size | R |
| `get_channel_message` | message_id | R |
| `get_message_submission` | submission_id | R |
| `list_interpretations` | submission_id | R |
| `list_message_media` | submission_id | R |
| `list_order_drafts` | status/submission_id/page/size | R |
| `get_order_draft` | draft_id | R |
| `list_tracking_drafts` | status/submission_id/page/size | R |
| `get_tracking_draft` | draft_id | R |
| `get_order_draft_candidates` | draft_id | R |
| `get_tracking_draft_candidates` | draft_id | R |
| `list_review_cases` | status/reason_code/responsible_team/page/size | R |
| `get_review_case` | case_id | R |

### 5.2 `McpDomainReadTools`（11 个，全部只读，`McpDomainReadTools.java:58-151`）

| 工具名 | 输入要点 | 读写 |
|---|---|---|
| `list_procurement_tickets` | status/date_from/date_to/page/size | R |
| `get_procurement_ticket` | ticket_id | R |
| `list_procurement_receipts` | ticket_id | R |
| `search_skus` | query/provider_id/page/size | R |
| `get_sku` | sku_id | R |
| `list_provider_skus` | provider_id/page/size | R |
| `get_inventory_overview` | provider_id/sku_id/warehouse_code/page/size | R |
| `get_inventory_detail` | provider_id/sku_id/warehouse_code | R |
| `list_products` | page/size | R |
| `list_categories` | page/size | R |
| `list_fulfillment_providers` | （无参） | R |

### 5.3 `McpWriteTools`（4 个，全部只写，`McpWriteTools.java:68-114`）

| 工具名 | 输入要点 | 读写 | 底层用例 |
|---|---|---|---|
| `reinterpret_submission` | submission_id/idempotency_key | W | `MessageSubmissionService.reinterpret`（:141） |
| `submit_order_draft_suggestion` | draft_id/expected_revision/idempotency_key/items | W | `OrderDraftService.supplement`（:164-168） |
| `submit_supplementary_material` | draft_id/expected_revision/idempotency_key/receiver/settlement_method | W | `OrderDraftService.supplement`（:189-193） |
| `submit_review_request` | submission_id/idempotency_key/note | W | `McpReviewRequestService.submitForReview`（:215-216） |

### 5.4 已有白名单（Agent 定义 → 工具）

- 采购比价 `procurement-price-agent`：11 个 DomainRead 工具（`ProcurementPriceAgentConfiguration.java:31-42`）。
- 数据查询 `data-query-agent`：11 个 DomainRead + `list_interpretations` + `list_message_media`（`DataQueryAgentDefinitionConfiguration.java:27-40`）。
- 意图识别 `intent-recognition`：空（`IntentRecognitionAgentConfiguration.java:46`）。
- 写工具在现有 Agent 白名单中**零引用**（红线「Agent 写工具零调用」在配置层即成立）。

### 5.5 Meta-Agent 做工具发现需要什么只读能力（现状 tools/list）

- **现有 tools/list**：`McpServer.toolsList()`（`McpServer.java:167-177`）返回 `registry.all()` 的 name/description/inputSchema（JSON-RPC over stdio，`initialize` capabilities 声明 tools.listChanged=false，:156-165）。**只存在于 stdio 协议面，进程内无等价 API**；且对任何 stdio 客户端**全量暴露（含 4 个写工具）**，无按调用方过滤（§4.4）。
- **Meta-Agent 所需只读发现能力（现状缺口）**：
  - 进程内工具清单（name/description/inputSchema）：现状只有 `McpToolRegistry.all()`（`McpToolRegistry.java:33-35`）可用，但它不是 MCP 工具（Meta-Agent 走工具调用拿不到注册表对象引用）；需要新增一个只读发现工具（如 `list_tools`/`get_tool_schema`）暴露**只读子集**（08 票 MCP 层强制后按 Meta-Agent 权限过滤）。
  - 现有 13+11=24 个只读工具已覆盖 Meta-Agent 定义 Agent 所需的领域事实（SKU/价格/库存/采购/主数据）；写工具面（reinterpret/draft supplement/review）对 Meta-Agent 的「只写定义草稿」职责不适用，需在 06 票单独设计定义写工具（map 决策 8：白名单 = 工具发现只读工具 + 定义写工具）。

---

## 6. 对后续票的事实输入（速查）

- **04 统一运行时编排设计**：四路径步骤差异见 §1.2 表；收敛面见 §2.1；最大结构性差异 = schema 硬编码（§1.3-1）与 C 不经 `AgentRuntime`（§1.2 模型接缝行）。
- **05 通用门禁与守卫泛化**：守卫现状 = `DataQueryAgentGuard`（§2.2）三层，硬编码于 C；工具参数兜底在 `RecordingToolExecutor`（`DataQueryAgentService.java:303-347`）；失败码缺业务态（§3.4）。
- **03 定义数据模型**：字段差距全表见 §3；核心 = AgentDefinition 缺 output_schema/版本/状态/创建者/权限引用/输入 schema/守卫声明（§3.1）。
- **08 MCP 权限隔离**：enforcement 现状与改动点见 §4；工具面清单见 §5；「白名单过滤目前在绑定层」= `AgentToolBindingFactory.bind`（`AgentToolBindingFactory.java:60-75`），MCP stdio 面无过滤（`McpServer.java:167-177`）。
- **06 Meta-Agent**：工具发现缺口见 §5.5；Meta-Agent 自身也走统一门面（定义数据化后的新 Agent）。

---

## 参考文件清单（本次审计读取）

- `agent/AgentRuntimeFacade.java`、`agent/AgentDefinition.java`、`agent/AgentRegistry.java`、`agent/AgentRegistryConfiguration.java`、`agent/AgentRegistryChangeAuditor.java`
- `agent/AgentRunResult.java`、`agent/AgentFailureCode.java`、`agent/AgentStructuredOutput.java`、`agent/AgentRunContext.java`、`agent/AgentTaskRequest.java`
- `agent/AgentToolBindingFactory.java`、`agent/AgentToolBinding.java`、`agent/AgentToolInvoker.java`、`agent/McpToolSchemaConverter.java`（引用）、`agent/AgentPayloadRedactor.java`
- `agent/AgentRuntime.java`、`agent/DefaultAgentRuntime.java`、`agent/LangChain4jAgentRuntime.java`、`agent/AgentRuntimeConfiguration.java`、`agent/AgentGateway.java`、`agent/AgentModelProperties.java`
- `agent/AgentModelMetadataRegistry.java`、`agent/AgentObservability.java`、`agent/JdbcAgentObservability.java`、`agent/AgentObservabilityConfiguration.java`、`agent/NoopAgentObservability.java`
- `agent/DataQueryAgentService.java`、`agent/DataQueryAgentGuard.java`、`agent/DataQueryAgentOutput.java`、`agent/DataQueryAgentToolCall.java`、`agent/DataQueryRunResult.java`、`agent/DataQueryAgentGateway.java`、`agent/DataQueryAgentDefinitionConfiguration.java`
- `agent/procurement/ProcurementPriceAgent.java`、`ProcurementPriceAgentRuntime.java`、`ProcurementPriceRuntime.java`、`ProcurementPriceRunResult.java`、`ProcurementPriceInput.java`、`ProcurementPriceRecommendation.java`、`ProcurementPriceGateway.java`（引用）、`ProcurementPriceAgentConfiguration.java`
- `agent/IntentRecognitionAgentBridge.java`、`IntentRecognitionAgentConfiguration.java`、`IntentRecognitionRunMetadata.java`
- `message/InterpretationService.java`
- `mcp/McpToolRegistry.java`、`McpTool.java`、`McpServer.java`、`McpServerRunner.java`、`McpAgentIdentity.java`、`McpRequestContext.java`、`McpReadTools.java`、`McpDomainReadTools.java`、`McpWriteTools.java`
- `resources/db/migration/V29__agent_observability.sql`
- `.scratch/meta-agent-platform/map.md`、`issues/02-four-path-convergence-audit.md`、`docs/agents/issue-tracker.md`
