# 02 — Agent Registry + Runtime

**What to build:** 代码定义的 Agent 注册表与统一运行时门面：`AgentRegistry`（slug / name / description / system prompt / prompt version / model ref / enabled / tool set）与 `AgentRuntime.invoke()` / `resume()`，每次运行生成 `run_id` 并落 AGENT 审计。

**Blocked by:** 01 — LangChain4j 基础接入

**Status:** resolved

## 范围

- `AgentDefinition`：`agent_slug`（唯一）、`name`、`description`、`system_prompt`、`prompt_version`、`model_ref`（引用 `app.agent.*` 或按 agent 覆盖）、`enabled`、`tool_names`（白名单，引用 MCP 工具名）。
- `AgentRegistry`：代码/配置定义的不可变清单，支持按 slug 查询、enabled 判定、枚举；配置变更走审计。
- `AgentRuntime` 门面：
  - `invoke(agentSlug, input, AgentRunContext)` → `AgentRunResult`；
  - 每次运行生成唯一 `run_id`（沿用 `trace_id` 生成模式）；
  - 通过 `AuditLogService` 记录 AGENT 审计（service=`agent`, operation=`agent.{slug}.run`）；
  - 未配置模型或 Agent 未启用时显式拒绝；
  - 支持 `thread_id`（会话延续占位，一期只透传）。
- 运行时只执行 registry 中 enabled 的 Agent；tool 白名单之外的调用在 03 票实现前直接拒绝。
- 文档同步：修订 `CONTEXT.md` 边界行（“Agent 自动决策调度”改为“本系统提供 Agent 决策/调度层，一期 Agent 仅只读建议，写操作必须人工确认”）。

## 非范围

- Agent ↔ MCP 工具绑定（03 票）；
- 具体业务 Agent（05/06/07 票）；
- 低代码编辑、动态 graph、在线创建 Agent。

## 验收标准

- [ ] 可列出全部 Agent Definition；可启停（enabled 切换影响运行）；
- [ ] 未启用 / 未配置模型的 Agent 调用被显式拒绝且留下审计；
- [ ] 每次 `invoke` 生成唯一 `run_id`，审计记录含 agent_slug / run_id / prompt_version / model / status / latency；
- [ ] `thread_id` 透传正确；
- [ ] registry 变更（启停/工具白名单）有审计事件；
- [ ] `CONTEXT.md` 边界行已按方案修订；
- [ ] 自动化测试覆盖拒绝路径与审计路径。

## 验证原则

- 所有关键行为（启停、拒绝、审计、run_id 唯一性）必须有可重复验证的测试；
- 不以“模型输出看起来对”为验收。

## Answer

**状态：** 已交付并通过全部验收标准（新增 26 用例 + 全量回归 574/0/0）。

### 交付内容（类清单）

`backend/src/main/java/cn/zimu/fulfillment/agent/` 新增 6 个类 + 1 处演进：

- `AgentDefinition`（新）：不可变 record（agent_slug/name/description/system_prompt/prompt_version/model_ref/enabled/tool_names），紧凑构造器做归一化与防御性校验（slug 必须 `^[a-z][a-z0-9-]{0,63}$`、文本 strip 后非空、tool_names 防御性拷贝）。
- `AgentRunContext`（新）：`thread_id`（一期只透传进审计）+ `operator`（审计 operator，空时兜底 "agent"）。
- `AgentRegistry`（新）：不可变清单，按 slug 查询 / enabled 判定（未注册 fail-closed）/ 枚举（保序）；构造时校验 slug 唯一性。
- `AgentRegistryChangeAuditor`（新）：before/after 两实例按 slug diff，每个变更（新增/移除/启停/model_ref/prompt_version/工具白名单）一条 AGENT 审计（service=agent, operation=agent.registry.changed，business_code=AGENT_REGISTRY_*）。
- `AgentRuntimeFacade`（新）：invoke/resume 编排门面——registry 解析 + enabled 判定 + 每次运行唯一 run_id（`"run_"+UUID-hex`，沿用 trace_id 生成模式）+ AGENT 审计（service=agent, operation=agent.{slug}.run，requestPayload 含 agent_slug/run_id/thread_id/prompt_version/model_ref/tool_names，responsePayload 含 status/model/provider/prompt_version，businessCode=status，latencyMs 实际耗时，traceId/requestId=run_id）+ 模型元数据 allowlist 投影（未白名单一律 none）。拒绝路径（未注册 AGENT_NOT_FOUND / 未启用 AGENT_DISABLED）不触碰底层接缝且留审计。
- `AgentRegistryConfiguration`（新）：Spring 装配；业务 Agent（05/06/07）只需注册 `AgentDefinition` bean 即入注册表，当前零 bean 时空清单不阻断启动。
- `AgentFailureCode`（演进）：追加 `AGENT_NOT_FOUND` / `AGENT_DISABLED`（append-only，不破坏 01 既有值）。
- `CONTEXT.md`：边界行已修订（见下）。

### 关键设计决策

1. **AgentRuntime 接口演进取舍**：01 的 `AgentRuntime.run(AgentTaskRequest)` 保持零改动——它是纯模型接缝（不知 slug/registry），擅自加注册表语义会破坏 01 互斥注册与 fail-closed 测试。注册表语义全部收敛到新的 `AgentRuntimeFacade`，拒绝与审计路径因此可用 mock 独立验证。`resume()` 一期与 `invoke()` 行为一致（无状态会话），仅语义表示延续 thread_id 会话。
2. **json_schema 强约束评估结论（01 遗留）**：业务 Agent 记录**继续走 01 的 AiServices + PojoOutputParser 结构化输出路径**，一期不启用 OpenAI `response_format: json_schema`。理由：(a) 02 验收只要求"输出可 schema 校验 + 拒绝路径可测"，01 已达成且有测试；(b) AiServices 自动生成 JSON schema 指令，若叠加 response_format 需手工维护第二份完整 JSON Schema，存在双份定义漂移；(c) 03 票接入工具后 AiServices 形态会变，过早绑定增加迁移成本。升级路径单点：`OpenAiChatModel.builder().responseFormat(...)` 一行 + 保留 PojoOutputParser 兜底解析，后续可按需平滑开启。
3. **工具白名单**：03 票之前门面不暴露任何工具执行入口（结构性拒绝），tool_names 仅作声明与审计；白名单变更本身由 `AgentRegistryChangeAuditor` 审计，03 票在暴露工具时按 tool_names 过滤。registry 启停/白名单变更以新实例 + diff 审计表达（运行期不可变），与"代码/配置定义"语义一致；一期不做启动时自动比对（无持久化基准），diff 能力与审计已就绪，如需自动比对可在可观测性票以 audit_logs 快照为基准接入。
4. **审计字段**：AuditLog 实体无 agent_slug/run_id 专用列，按既有 payload 模式放入 request/response payload；traceId/requestId 直接复用 run_id（与 03 票"run_id 作为 request/trace id"对齐）。

### 测试（backend/src/test/java/cn/zimu/fulfillment/agent/，新增 4 类 26 用例全绿）

- `AgentRegistryTest`（9）：列出全部并保序、bySlug 命中/未命中、enabled 判定（含未注册 fail-closed）、slug 重复/非法（大写/空/含空格）/null 拒绝、必填文本校验、空注册表合法、不可变性。
- `AgentRegistryChangeAuditorTest`（5）：启停翻转审计事件（字段断言）、工具白名单变更审计、新增/移除、无变更零审计、多字段变更逐条审计。
- `AgentRuntimeFacadeTest`（11）：成功 invoke 审计字段齐全（agent_slug/run_id/prompt_version/model/status/latency/thread_id）、systemPrompt/userInput 透传到底层、run_id 唯一且格式 `run_`+32hex、未启用 AGENT_DISABLED 拒绝+审计+不触碰底层、未注册 AGENT_NOT_FOUND、未配置模型（真实 DefaultAgentRuntime 组合）AGENT_MODEL_NOT_CONFIGURED+审计、元数据未白名单投影 none、白名单投影真实值、resume 等价+thread_id 透传、null context operator 兜底、底层失败码审计。
- `AgentContextDocTest`（1）：断言 CONTEXT.md 边界行含"本系统提供 Agent 决策/调度层""一期 Agent 仅做只读分析与建议""任何业务写操作仍必须经授权人工确认""采购、意图识别、数据查询等 Agent 职责由代码定义并通过 MCP 只读工具执行"，且不含旧表述"Agent 自动决策调度"（文件定位按 surefire basedir 相对仓库根，未找到则跳过）。

### 回归结果

- `mvn -q test-compile` 通过。
- 新增测试：26/26 通过。
- `Agent*,*Registry*`：31/31 通过（含 01 既有 agent 测试）。
- message 包：15 类 88 用例全绿（与 01 基线一致）。
- 全量 `mvn test`：**574 run / 0 failures / 0 errors / 7 skipped，BUILD SUCCESS**（本次无 Testcontainers 资源错误；若未来单 fork 资源紧张复现 01 记录的 context error，属环境问题非本票代码）。

### 遗留事项

- 启动时 registry 快照自动比对（以 audit_logs 最近快照为基准 diff）留待可观测性票；diff+审计能力已就绪。
- 工具执行/白名单过滤为 03 票范围；json_schema 强约束升级点已在上面记录（responseFormat 单行接入）。
