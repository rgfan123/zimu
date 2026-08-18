# Agent 平台化：统一架构 + Meta-Agent map

Type: map
Status: charting

## Destination

把现有 agent-decision-layer 的「代码定义、多路径编排」升级为统一 Agent 平台：Agent 定义数据化（DB 唯一真源 + 版本链），四条运行路径收敛为「注册表 → 薄门面（AgentFacade）→ LangChain4j 运行时 → MCP → 观测 → 门禁」单一路径，工具访问以 MCP 层为权限强制点（MCP 权限隔离），并新增元 Agent（meta-agent）：用户用自然语言创建/修改特定 Agent（产出定义草稿 + 建议评测输入），经平台通用门禁自动校验 + 人工确认后启用。

**运行时原则（已定调）**：不造 Agent 框架——Agent loop / workflow / tool calling / structured output / memory / 模型集成全部由 LangChain4j（含 langchain4j-agentic）承担；自研仅限子牧特有的薄 **Agent Control Plane**（AgentDefinition/版本链/注册表/AgentFacade/MCP 权限强制/守卫/激活门禁/评测门禁/Meta-Agent 管理/业务审计关联）。管理面一期 REST API，前端/企微后置（前端优先复用现成 AI/Agent UI 组件库，调研见 09）。

## Notes

- 领域：订单履约 ERP（先读 `CONTEXT.md`；`docs/agents/domain.md`）；技术栈 Java 21 / Spring Boot 3.5 / LangChain4j / MCP / PostgreSQL。
- 每次 session：读 `docs/agents/issue-tracker.md`（本地 Markdown tracker 惯例：map + `issues/NN-slug.md`，`Type:`/`Status:`/`Blocked by:` 行，resolved 时追加 `## Answer` 并更新 map）。
- 决策类票用 `/grilling` + `/domain-modeling`（一次一个问题，带推荐答案）；调研类票用 `/research` 子代理（AFK，findings 写 `.scratch/meta-agent-platform/research/<slug>.md` 并写回票的 `## Answer`，分支整理由主 session 做）。
- 红线（不可谈判）：写操作人工确认（Meta-Agent 只能写草稿）；**业务** Agent 写工具零调用不变式（评测按 Agent 钉死：data-query / procurement-price 白名单不得含任何 `McpWriteTools` 工具）；**meta-agent 是唯一例外**（08 决策 3），受 `allow_write=true` 显式声明 + 05 门禁人工确认 + 工具实现内 target-scope 校验（只能写 `agent_definitions` 的 draft 行、拒绝 target slug=meta-agent）三重约束；密钥/凭据绝不进 DB、日志、DTO（沿用 SecretRedactor / AgentPayloadRedactor）；服务端 allowlist 投影 provider/model/prompt-version；审计与观测失败隔离。
- 前置事实（2026-08-18 复核，已变更）：agent-decision-layer 基座**已提交 git**（`backend/src/main/java/cn/zimu/fulfillment/agent/` 45 个文件 tracked，随 `9152d01` 落地）——原「重构前必须先提交基座」的前置条件**已满足**，不再需要单独的基座提交 task。
- 已确认方向（用户提出）：前端二期复用现成 AI/Agent UI 组件库（调研见 09）；复用现有 MCP（McpToolRegistry 单一工具源）做权限隔离——工具访问以 MCP 层为强制点，机制细节由 08 票定。
- 框架优先原则（用户定调）：能用框架的全部用框架——LangChain4j 提供模型抽象/Agent/工具调用/MCP client/结构化输出/AgenticScope/Sequential·Loop·Conditional·Parallel workflow/Supervisor/HITL/持久化 SPI/MCP Tool Agent，均不自研；langchain4j-agentic 在 1.19.0 的真实能力由 10 票盘点核实。平台选型明确排除 Dify/Flowise（第二控制面）与 DeepSeek Harness（Node/TS 生态、coding-agent 导向，仅可 spike 不押注）。
- **事实核查（2026-08-18，10 票 resolved）**：langchain4j-agentic 为**独立 artifact 但走 beta 线**（BOM 1.19.0 实际解析为 `1.19.0-beta29`，Central 无 1.19.0 坐标）——能力真实存在（Agent 注解/AgenticScope/Workflow 原语/HITL/AgenticScopeStore SPI，证据见 `research/10-langchain4j-agentic-inventory.md`）但 API 有漂移风险；**一期不引入 agentic 依赖**（YAGNI + beta），Adapter 预留接缝。MCP 结论：保留进程内 McpToolRegistry + 绑定期白名单（08 权限点），agentic 经 `AgentBuilder.tools(Map, Set)` 喂现有绑定产物，不走 McpClient/McpToolProvider 外连。
- 已有架构资产（复用清单见 `.scratch/agent-decision-layer/spec.md`）：`McpToolRegistry` 唯一工具源、`McpAgentIdentity`、`AuditLogService`(actor=AGENT)、`AgentModelMetadataRegistry`、`AgentObservability`(JdbcAgentObservability)、`AgentEvalScorer` + 09 基线门禁。

## 已确认决策（charting 期 grilling 结论，未单独立票）

1. 目的地 = Agent 平台化：定义数据化 + Meta-Agent + 评测门禁，全收敛单一路径。
2. 定义真源 = DB + 版本链（现有三个 Agent 由迁移播种，代码定义删除）。
3. 生效流程 = 草稿 → 人工确认 → 启用；修改已有 Agent 同样走新版本草稿，确认后替换。
4. Meta-Agent 产物 = AgentDefinition + 建议评测输入（不负责跑分）。
5. 收敛范围 = 四路径全归一（schema 数据化、守卫泛化）。
6. 管理面 = REST 一期、前端二期（复用组件库）、企微入口后置（分期铺，不互斥）。
7. 门禁 = 通用门禁自动 + 评测用例数据化（跑分器数据驱动，09 fixture 逐步迁移）。
8. Meta-Agent 形态 = 注册为受管 Agent（slug=meta-agent）；白名单 = 工具发现只读工具 + 定义写工具（仅写草稿）；不能启停其他 Agent；禁自改。
9. 模型缝 = 定义携带 provider/model（服务端 allowlist 校验），api-key/base-url 等传输配置仍只走全局 `app.agent.*`（环境变量注入，绝不进 DB）。
10. 守卫 = PII 拒绝/歧义澄清泛化为平台默认 AgentGuard（定义可声明豁免，默认不豁免）。
11. 权限隔离 = 复用现有 MCP 做权限隔离（工具访问以 MCP 层为强制点；per-agent 权限表达与强制机制由 08 票定）。
12. 运行时原则 = **LangChain4j 负责运行能力，自研仅限子牧特有 Control Plane**（AgentDefinition/版本链/注册表/AgentFacade/MCP 权限强制/守卫/激活门禁/评测门禁/Meta-Agent 管理/审计关联）；不引入 Dify/Flowise 第二控制面；DeepSeek Harness 仅可 spike、不押注。04 票相应重定为「Runtime Adapter 设计」。**修正（10 票事实）**：langchain4j-agentic 为 beta 线（1.19.0-beta29），一期不引入，workflow 原语仅留接缝，等真实多 Agent 用例 + 稳定版本再评估。

## Decisions so far

<!-- 每条已解决票一行：标题 + 一句话结论。 -->

- [02 — 现有四条路径收敛点审计](issues/02-four-path-convergence-audit.md) — 四路径骨架同构可安全收敛；实质差异 = 输出 schema 三处代码硬编码、守卫只在数据查询、失败码不统一、编排级观测覆盖不一致（详见 `research/02-convergence-audit.md`）。**只有意图桥有生产调用方**，其余三条路径无生产入口，收敛无迁移风险；工具白名单过滤只在 Agent 绑定层，MCP stdio 面全量暴露 28 工具（含 4 写工具）无 per-agent 权限，MCP 层强制需动 McpToolRegistry/McpServer/McpRequestContext/McpAgentIdentity/AgentToolInvoker 五点。
- [09 — AI/Agent UI 组件库调研](issues/09-ai-agent-ui-libraries.md) — 前端栈 React 18 + Vite 5 + antd 5.21 + echarts，已有手搓 AI 对话面板可复用（详见 `research/09-ai-agent-ui-libraries.md`）：对话式创建 Agent 用 `@ant-design/x@1.6.x`（锁版本，2.x 需 antd 6）；列表/草稿确认/运行看板用 antd 原生 + echarts 自研；编排画布如做用 `@xyflow/react`；不引入 Vercel AI SDK / shadcn/ui / chat-ui-kit-react。
- [01 — LangChain4j 动态输出 schema 调研](issues/01-langchain4j-dynamic-schema.md) — langchain4j 1.19.0：AiServices 结构化输出绑定静态 record 类型（无公开动态 schema API），但低层 `ChatRequest.responseFormat(ResponseFormat(JSON, JsonSchema))` + `JsonRawSchema.from(String)` 支持运行时动态 schema；langchain4j-open-ai 支持 json_object/json_schema 两模式，但 **DeepSeek 兼容端点只支持 json_object（json_schema 被 400 拒绝，社区一手证据）**。推荐：供应商能力自适应的 json_object/json_schema + 客户端 JSON Schema 校验兜底（networknt，失败映射 AGENT_OUTPUT_INVALID），输出统一 JsonNode/Map 容器、AgentStructuredOutput 保留为通用信封；不做运行时生成 record（详见 `research/01-langchain4j-dynamic-schema.md`）。
- [10 — langchain4j-agentic 能力盘点（1.19.0）](issues/10-langchain4j-agentic-inventory.md) — agentic 是独立 artifact 但 **beta 线（1.19.0-beta29，Central 无 1.19.0）**；构建在 AiServices 之上（Agent 注解 + AgenticScope + Sequential/Loop/Parallel/Conditional/Supervisor 全套 Planner 原语 + HITL 挂起恢复 + AgenticScopeStore SPI，默认无 store）；**MCP 保留现有进程内绑定（McpToolRegistry + AgentToolBindingFactory 白名单，即 08 权限点），agentic 经 AgentBuilder.tools(Map, Set) 喂现有绑定产物，不引入 McpClient/McpToolProvider 外连**；结构化输出仍绑静态 record，与 01 的低层 JsonRawSchema 分层共存（详见 `research/10-langchain4j-agentic-inventory.md`）。
- [11 — 评测与 Prompt 管理平台调研](issues/11-eval-prompt-platform-survey.md) — JVM 可用候选仅 Langfuse（langfuse-java 0.2.0，可自托管）与 Braintrust（braintrust-sdk-java 0.3.20，BETA），但两者 prompt/datasets 托管均造成真源外置（与 03/07 冲突）；LangChain4j 1.19 无评测模块、OpenAI Evals 官方停摆、DeepEval 纯 Python、PromptLayer 无 Java SDK 且自托管 Enterprise-only——**全部出局**。**首选中间路线**：promptfoo 只当 CI/本地跑分执行器（用例存 DB、prompt 真源留 agent_definitions、由 DB 状态生成 promptfoo YAML、`npx promptfoo eval` 后回写 DB 比对基线，JVM 侧仅 ProcessBuilder + YAML 生成器，零外置）。限制：工具调用序列非 promptfoo 天然断言对象 → 07 应把「不变式指标（JUnit/自研/stub 模型）」与「质量指标（外部框架/真实模型）」分开；运行期跑分建议异步任务（配 run_mode=PREVIEW），CI 形态保留给 09 基线门禁（详见 `research/11-eval-prompt-platforms.md`）。
- [03 — Agent 定义数据模型与版本状态机](issues/03-agent-definition-data-model.md) — `agent_definitions` **单表 append-only 全快照**（唯一 `(agent_slug, version)`，当前生效靠 `UNIQUE (agent_slug) WHERE status='active'` 下沉 DB，不做主行+历史行）；状态机 `draft→active→retired` **无回边**，回滚 = 复制成新草稿再确认；`enabled`（运维启停）与 `status`（版本生命周期）**正交**，运行条件 `status='active' AND enabled=true`；确认事实上定义行（`activated_by`/`activated_at`）、审计只做流水（红线「审计失败隔离」⇒ 审计不能当真源）；注册表启动全量加载 + `AgentRegistryHolder` volatile 换实例，`AgentRegistryChangeAuditor` 原样复用补 ACTIVATED/RETIRED；播种两步走（V30 播种 → 验证 → 单独 commit 删代码定义，`ProcurementPriceAgentConfiguration` 只能摘方法）。**Schema 增量**：`agent_runs` 加 `run_mode IN ('LIVE','PREVIEW')` 隔离草稿试跑，避免污染 09 评测基线。**组件库已核实无现成件**（Envers 只差一依赖但 `_AUD` 主行+历史行形态被决策 1 否掉，且 agent 包整包零 @Entity 全 JdbcTemplate；javers/Spring Statemachine 净负债）。
- [04 — 基于 LangChain4j Agentic 的 Runtime Adapter 设计](issues/04-unified-runtime-design.md) — `LangChain4jRuntimeAdapter implements AgentRuntime` 替换 `LangChain4jAgentRuntime`，**携带 `AgentDefinition`**（扩展 `AgentTaskRequest`，接口签名不变），只管「定义 → ChatRequest → 结果」，权限/守卫/审计/观测全留 Control Plane。**三个 AiServices 网关全废**（AgentGateway/ProcurementPriceGateway/DataQueryAgentGateway）→ 低层 `ChatRequest.responseFormat` + 供应商自适应 + networknt 校验，传输层 `JsonNode`；**`AgentStructuredOutput` 删除**（A 路径占位 schema 且无生产调用方），业务侧保留 record 作反序列化目标不丢类型安全。迁移三批：A+B+C 先行（无生产调用方，C 须删自建 `OpenAiChatModel` 私有通道）→ D 最后（唯一生产调用方，桥壳与管线钩子保留）。**一期不做运行中 HITL**，且明确它与 03 的「草稿→人工确认」激活门禁是两回事；持久化真源 = `agent_runs`/`agent_tool_calls`，不引 `AgenticScopeStore`。**失败码推翻直觉方案**：「澄清」不是失败，改为 `AgentRunResult` 加 outcome（SUCCESS/NEEDS_INPUT/REJECTED/FAILED），`AgentFailureCode` 降为仅失败时有值；补 B 的拒绝审计、砍 D 的重复审计通道。MCP 确认 10 票结论（进程内绑定，不外连）。**实施约束**：每批迁移前后用 `AgentEvalBaselineTest` 比对工具调用序列——换低层 ChatRequest 本身就会改序列，而 09 基线钉的正是序列。
- [08 — MCP 权限隔离设计](issues/08-mcp-permission-isolation.md) — **消歧地图决策 11**：Agent 根本不经过 `McpServer`（`AgentToolInvoker` 直查 `McpToolRegistry`），故「MCP 层强制」是两个面。**Agent 面** = 绑定期白名单 + `AgentToolInvoker.execute` 调用期复核双层（后者是防旁路的真强制点，现状只有未注册名报错、不是权限判定）；**stdio 面** = 一期收紧为只读（`toolsList`/`handleToolCall` 过滤写工具），不套 per-agent 权限——外部客户端共用全局 identity、无 slug。**权限表达** = 一期白名单即 profile，**删除 03 预留的 `permission_profile_ref`**（V30 未写，删是免费的），新增 `allow_write` 布尔；`McpTool` 加读写元数据使「默认禁写」成为可判定不变式。**meta-agent（用户选 B）**：定义写工具归入 `McpWriteTools`，白拿幂等 + AGENT 审计 + REQUIRES_NEW 失败审计三样能力，代价是业务面类引入控制面依赖（已知并接受）；须声明 `allow_write=true` 过 05 门禁，工具实现内强制 target 只能是 `agent_definitions` draft 行且拒绝 target slug=meta-agent。**必配改动**：`DataQueryAgentDefinitionTest`/`ProcurementPriceAgentInvariantTest` 的 `WRITE_TOOL_NAMES` 手抄常量改为向 registry 按读写元数据查询——写工具集合增长后手抄清单漏更新会让不变式测试静默漏检。
- [07 — 评测用例数据化设计](issues/07-eval-cases-datafication.md) — **一张 `agent_eval_cases` 表 + `metric_kind`（INVARIANT/QUALITY）**：INVARIANT（工具序列/schema/写工具零调用/requires_human）→ stub 跑分器 + CI 基线门禁（09 只钉这类）；QUALITY → 异步 PREVIEW + promptfoo（真实模型，不进基线）。用例**绑定 `(agent_slug, agent_version)` 每版本冻结**（换例=新版本）；`input`/`expected` JSONB，expected schema 由 metric_kind 派生、读取时校验；**基线门禁读 DB**（Testcontainers + stub，fixture 14 例由 V 迁移播种后删，03 两步走同构）；确认**联动**（确认定义草稿=确认该版本用例，active 不可追加）；运行期**两级**：草稿提交同步快速门禁（不过阻断）+ 异步 QUALITY（run_mode=PREVIEW 落 agent_runs，Spring Worker，参考不阻断）；意图识别**不数据化**（提示词真源在 app.message-interpreter.*，维持代码套件）。
- [06 — Meta-Agent 定义与工具面设计](issues/06-meta-agent-definition.md) — 工具发现 = 新增只读工具 `list_agent_tools`（全量工具名/描述/参数 schema/读写属性，工具面增长不改提示词）；写工具 = `create_agent_draft` + `update_agent_draft` 两个全量工具（归入 McpWriteTools：幂等 + AGENT 审计 + REQUIRES_NEW，input 含 `suggested_eval_cases`，服务端校验 slug 格式/唯一性/版本分配/allow_write/target≠meta-agent）；输出 = 全量草稿 JSON，缺信息 → outcome=NEEDS_INPUT + 澄清，slug 冲突拒绝不改名；自管理 = V30 播种（allow_write=true）+ 仅人工经管理 API 变更，全工具禁改（target 校验 + 白名单双重拒绝），output_schema 自举（「草稿 JSON」schema）。**管理 REST API 票已 graduate → 票 12**。

## 依赖图

```text
01 动态输出 schema 调研 ──┐
02 四条路径收敛点审计 ────┼──> 04 Runtime Adapter 设计 ─┐
10 langchain4j-agentic 盘点 ┘   （基于 LangChain4j Agentic）│
02 ─────────────────────> 05 通用门禁与守卫泛化设计 ──┤
02 ─────────────────────> 08 MCP 权限隔离设计 ──────┤
03 Agent 定义数据模型 ────┴──> 06 Meta-Agent 设计 ───┴──> 12 管理 REST API ─┐
03 ─────────────────────┬──> 07 评测用例数据化设计 ────┘（04/08 同步输入）│
11 评测/Prompt 平台调研 ──┘                                          │
09 AI/Agent UI 组件库调研（独立，服务二期前端）                        │
05 通用门禁与守卫泛化 ───────────────────────────────────────────> 实施票
```

## 实施顺序（预期）

research 阶段：01/02/09/10/11 全部 ✅ resolved。
grilling 阶段：03、04、06、07、08 ✅ resolved → **前沿现为 05（grilling）+ 12（grilling，REST API 已 graduate）** → 实施票（后续 session）。基座提交前置条件已满足（见 Notes），该 task 取消。

## Tickets

| ID | 票 | 类型 | 依赖 | 状态 |
|---|---|---|---|---|
| 01 | LangChain4j 动态输出 schema 调研 | research (AFK) | — | ✅ resolved |
| 02 | 现有四条路径收敛点审计（含 MCP 权限点） | research (AFK) | — | ✅ resolved |
| 03 | Agent 定义数据模型与版本状态机 | grilling (HITL) | — | ✅ resolved |
| 04 | 基于 LangChain4j Agentic 的 Runtime Adapter 设计 | grilling (HITL) | 01、02、10 | ✅ resolved |
| 05 | 通用门禁与守卫泛化设计 | grilling (HITL) | 02 | open |
| 06 | Meta-Agent 定义与工具面设计 | grilling (HITL) | 03 | ✅ resolved |
| 07 | 评测用例数据化设计 | grilling (HITL) | 03 ✅、11 ✅ | ✅ resolved |
| 08 | MCP 权限隔离设计 | grilling (HITL) | 02 | ✅ resolved |
| 09 | AI/Agent UI 组件库调研 | research (AFK) | — | ✅ resolved |
| 10 | langchain4j-agentic 能力盘点（1.19.0） | research (AFK) | — | ✅ resolved |
| 11 | 评测与 Prompt 管理平台调研 | research (AFK) | — | ✅ resolved |
| 12 | 管理 REST API 端点与权限设计 | grilling (HITL) | 03/04/06/07/08 ✅ | open |

## Schema 增量（累计，实施票据此写迁移）

- `agent_definitions`（新表，03）：现有 `AgentDefinition` 8 字段 + `id` / `version` / `status` / `activated_by` / `activated_at`，唯一 `(agent_slug, version)` + 部分唯一索引 `UNIQUE (agent_slug) WHERE status='active'`；**`permission_profile_ref` 已由 08 删除**（一期白名单即 profile）；保留 `tool_whitelist`（= 现有 `toolNames`），**新增 `allow_write` 布尔**（默认 false，仅 meta-agent 为 true 且需过 05 门禁）。
- `agent_runs`（改表）：加 `run_mode IN ('LIVE','PREVIEW')`（03，隔离草稿试跑避免污染 09 基线）+ `intent` / `provider`（04，替代 D 的重复审计通道）。
- `AgentRunResult`（改类型，04）：加 outcome 维度 SUCCESS/NEEDS_INPUT/REJECTED/FAILED。
- `agent_eval_cases`（新表，07）：`id` / `agent_slug` / `agent_version`（引用 `agent_definitions (agent_slug, version)`）/ `metric_kind`（INVARIANT|QUALITY）/ `input` JSONB / `expected` JSONB（schema 由 metric_kind 派生）/ `status`（PENDING|CONFIRMED）/ `created_by` / `confirmed_by` / `confirmed_at`；确认与定义草稿联动，active 版本不可追加；唯一性与索引细节留实施票。

## Not yet specified

- 现有三个 Agent 迁移播种的**回归验证口径**（播种策略已由 03 定：两步走 + 逐字对照；剩「验证到什么程度算通过」，等实施票）。
- 提示词安全检查的具体实现手段（等 05）。
- 07 已定运行期两级形态（同步 INVARIANT 门禁 + 异步 QUALITY/PREVIEW）；「同步门禁与 09 CI 门禁的共用跑分器实现」细节留实施票。
- 12 已 graduate：管理 REST API 端点与权限设计（见票 12，open）。

- `McpTool` 读写元数据的具体形态（接口默认方法 vs 注解 vs 注册表侧配置）——08 定了必须有，形态留实施票。

## Out of scope

- 前端 Agent 管理页的**实现**（二期，后置为后续 effort；组件库调研由 09 票先行）。
- 企微入口创建 Agent（后置，倾向不做）。
- **Dify / Flowise 等现成 Agent 平台**——会成为第二控制面（定义真源/版本/权限/审计双份漂移），明确排除。
- **DeepSeek Harness 作主 Runtime**——Node/TS 生态、coding-agent 导向、API 仍演进；仅可后续 spike 观察，不引入本 effort。
- Langfuse/LangSmith 观测接入（延续 agent-decision-layer 08 非目标）。
- 低代码拖拽编排平台（延续 spec 非目标）。
- 多租户/多用户权限体系（内部系统单租户，沿用既有 Basic Auth / internal-auth）。
