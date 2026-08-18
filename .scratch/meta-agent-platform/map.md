# Agent 平台化：统一架构 + Meta-Agent map

Type: map
Status: charting

## Destination

把现有 agent-decision-layer 的「代码定义、多路径编排」升级为统一 Agent 平台：Agent 定义数据化（DB 唯一真源 + 版本链），四条运行路径收敛为单一「注册表 → 门面 → 运行时 → 观测 → 门禁」路径，工具访问以 MCP 层为权限强制点（MCP 权限隔离），并新增元 Agent（meta-agent）：用户用自然语言创建/修改特定 Agent（产出定义草稿 + 建议评测输入），经平台通用门禁自动校验 + 人工确认后启用。管理面一期 REST API，前端/企微后置（前端优先复用现成 AI/Agent UI 组件库，调研见 09）。

## Notes

- 领域：订单履约 ERP（先读 `CONTEXT.md`；`docs/agents/domain.md`）；技术栈 Java 21 / Spring Boot 3.5 / LangChain4j / MCP / PostgreSQL。
- 每次 session：读 `docs/agents/issue-tracker.md`（本地 Markdown tracker 惯例：map + `issues/NN-slug.md`，`Type:`/`Status:`/`Blocked by:` 行，resolved 时追加 `## Answer` 并更新 map）。
- 决策类票用 `/grilling` + `/domain-modeling`（一次一个问题，带推荐答案）；调研类票用 `/research` 子代理（AFK，findings 写 `.scratch/meta-agent-platform/research/<slug>.md` 并写回票的 `## Answer`，分支整理由主 session 做）。
- 红线（不可谈判）：写操作人工确认（Meta-Agent 只能写草稿）；Agent 写工具零调用不变式（评测钉死）；密钥/凭据绝不进 DB、日志、DTO（沿用 SecretRedactor / AgentPayloadRedactor）；服务端 allowlist 投影 provider/model/prompt-version；审计与观测失败隔离。
- 前置事实：agent-decision-layer 基座（`backend/.../agent/` 45 个主类 + V29 迁移 + 35 个测试 + docs/agents + AGENTS.md + `.scratch/agent-decision-layer/`）目前全部未提交 git（untracked）——统一重构开始前必须先提交基座。
- 已确认方向（用户提出）：前端二期复用现成 AI/Agent UI 组件库（调研见 09）；复用现有 MCP（McpToolRegistry 单一工具源）做权限隔离——工具访问以 MCP 层为强制点，机制细节由 08 票定。
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

## Decisions so far

<!-- 每条已解决票一行：标题 + 一句话结论。 -->

- [02 — 现有四条路径收敛点审计](issues/02-four-path-convergence-audit.md) — 四路径骨架同构可安全收敛；实质差异 = 输出 schema 三处代码硬编码、守卫只在数据查询、失败码不统一、编排级观测覆盖不一致（详见 `research/02-convergence-audit.md`）。**只有意图桥有生产调用方**，其余三条路径无生产入口，收敛无迁移风险；工具白名单过滤只在 Agent 绑定层，MCP stdio 面全量暴露 28 工具（含 4 写工具）无 per-agent 权限，MCP 层强制需动 McpToolRegistry/McpServer/McpRequestContext/McpAgentIdentity/AgentToolInvoker 五点。
- [09 — AI/Agent UI 组件库调研](issues/09-ai-agent-ui-libraries.md) — 前端栈 React 18 + Vite 5 + antd 5.21 + echarts，已有手搓 AI 对话面板可复用（详见 `research/09-ai-agent-ui-libraries.md`）：对话式创建 Agent 用 `@ant-design/x@1.6.x`（锁版本，2.x 需 antd 6）；列表/草稿确认/运行看板用 antd 原生 + echarts 自研；编排画布如做用 `@xyflow/react`；不引入 Vercel AI SDK / shadcn/ui / chat-ui-kit-react。
- [01 — LangChain4j 动态输出 schema 调研](issues/01-langchain4j-dynamic-schema.md) — langchain4j 1.19.0：AiServices 结构化输出绑定静态 record 类型（无公开动态 schema API），但低层 `ChatRequest.responseFormat(ResponseFormat(JSON, JsonSchema))` + `JsonRawSchema.from(String)` 支持运行时动态 schema；langchain4j-open-ai 支持 json_object/json_schema 两模式，但 **DeepSeek 兼容端点只支持 json_object（json_schema 被 400 拒绝，社区一手证据）**。推荐：供应商能力自适应的 json_object/json_schema + 客户端 JSON Schema 校验兜底（networknt，失败映射 AGENT_OUTPUT_INVALID），输出统一 JsonNode/Map 容器、AgentStructuredOutput 保留为通用信封；不做运行时生成 record（详见 `research/01-langchain4j-dynamic-schema.md`）。

## 依赖图

```text
01 动态输出 schema 调研 ──┐
02 四条路径收敛点审计 ────┼──> 04 统一运行时编排设计 ─┐
02 ─────────────────────> 05 通用门禁与守卫泛化设计 ──┤
02 ─────────────────────> 08 MCP 权限隔离设计 ──────┤
03 Agent 定义数据模型 ────┴──> 06 Meta-Agent 设计 ───┴──> （后续实施票）
03 ─────────────────────> 07 评测用例数据化设计
09 AI/Agent UI 组件库调研（独立，服务二期前端）
```

## 实施顺序（预期）

01/02/09 并行（research，AFK）→ 03（grilling，不依赖）→ 04/05/08/06/07（grilling，按解除阻塞顺序）→ 基座提交（task，重构前）→ 实施票（后续 session）

## Tickets

| ID | 票 | 类型 | 依赖 | 状态 |
|---|---|---|---|---|
| 01 | LangChain4j 动态输出 schema 调研 | research (AFK) | — | ✅ resolved |
| 02 | 现有四条路径收敛点审计（含 MCP 权限点） | research (AFK) | — | ✅ resolved |
| 03 | Agent 定义数据模型与版本状态机 | grilling (HITL) | — | open |
| 04 | 统一运行时编排设计 | grilling (HITL) | 01、02 | open |
| 05 | 通用门禁与守卫泛化设计 | grilling (HITL) | 02 | open |
| 06 | Meta-Agent 定义与工具面设计 | grilling (HITL) | 03 | open |
| 07 | 评测用例数据化设计 | grilling (HITL) | 03 | open |
| 08 | MCP 权限隔离设计 | grilling (HITL) | 02 | open |
| 09 | AI/Agent UI 组件库调研 | research (AFK) | — | ✅ resolved |

## Not yet specified

- 管理 REST API 端点清单与权限模型（等 03/04/06/08 落定后可精确化）。
- 现有三个 Agent（采购比价/数据查询/意图识别）迁移播种与回归验证细节（等 03）。
- Meta-Agent 建议评测输入的具体格式与确认流程（等 06）。
- 提示词安全检查的具体实现手段（等 05）。
- 统一门面后 `AgentStructuredOutput` 基础记录的去留（等 04）。

## Out of scope

- 前端 Agent 管理页的**实现**（二期，后置为后续 effort；组件库调研由 09 票先行）。
- 企微入口创建 Agent（后置，倾向不做）。
- Langfuse/LangSmith 观测接入（延续 agent-decision-layer 08 非目标）。
- 低代码拖拽编排平台（延续 spec 非目标）。
- 多租户/多用户权限体系（内部系统单租户，沿用既有 Basic Auth / internal-auth）。
