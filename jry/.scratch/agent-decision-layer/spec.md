# Agent 决策层 / 调度层（LangChain4j + MCP 扩展）

Type: feature
Status: ready-for-agent

## Problem Statement

现有信息化底座（Spring Boot 3.5 / Java 21 单体 + PostgreSQL + Redis + 企微消息接入 + MCP Adapter + 人工复核闭环）已具备确定性的意图分流、模型接缝、MCP 读/写工具与审计。目标是在这个底座之上新增一层 **Agent 决策/调度层**：在几个关键业务环节（采购比价、意图识别、数据查询等）设置不同的 Agent，分配不同职责，并持续增加可供 Agent 调用的 MCP 工具。

约束：语言必须是 Java（不使用 Python/LangGraph 侧车）；Agent 层不重构既有 `MessageInterpreter` 管线；Agent 写操作仍保持人工确认，一期全部 Agent 只读建议。

## Solution

1. **LangChain4j 作为 Agent 运行时**：在 Spring Boot 单体内引入 LangChain4j（OpenAI 兼容 Chat Completions 走 `langchain4j-open-ai`），复用现有模型配置与元数据治理模式（`MessageModelMetadataRegistry` 的服务端 allowlist 模式扩展到 Agent 运行），未配置模型时 fail-closed。
2. **Agent Registry + Runtime facade**：代码定义的 Agent 清单（slug / name / description / system prompt / prompt version / model ref / enabled / tool set），`AgentRuntime.invoke(agentSlug, input)` 统一入口，每次运行生成 `run_id` 并落 AGENT 审计。
3. **Agent ↔ MCP 工具绑定**：LangChain4j 工具直接桥接到既有 `McpToolRegistry`（单一工具源），Agent 与 MCP stdio 进程走完全相同的身份、幂等与审计路径，不建并行工具注册表。
4. **MCP 工具面扩展（核心）**：新增采购工单/回执、SKU 价格、库存、主数据等只读工具，供 Agent 调用；一期不新增写工具。
5. **关键环节 Agent**：采购比价 Agent、数据查询 Agent（查看数据）、意图识别 Agent（复用既有管线，仅注册为受管 Agent 以获得统一观测）。
6. **可观测性**：结构化 Agent 运行记录（run_id / thread_id / agent_slug / 工具调用序列 / token / latency / status），复用 `AuditLog` + `SecretRedactor` 脱敏；一期不接 Langfuse（保留 adapter 接缝）。
7. **评测基线**：采购比价与数据查询 Agent 的固定评测集 + 意图识别回归门禁。

## 与既有架构的关系（复用清单）

| 既有资产 | 位置 | Agent 层的用法 |
|---|---|---|
| MCP 工具注册表 | `mcp/McpToolRegistry.java` | 唯一工具源，Agent 绑定到此 |
| MCP 写工具幂等+AGENT 审计 | `mcp/McpWriteTools.java` | Agent 写路径沿用同一模式 |
| Agent 身份 | `mcp/McpAgentIdentity.java` | 扩展到 Agent 运行级身份 |
| 模型元数据治理 | `message/MessageModelMetadataRegistry.java` | 模式复用，扩展到 Agent 模型/提示词版本 |
| 审计与脱敏 | `common/audit/` | Agent run 审计与载荷脱敏复用 |
| 意图识别管线 | `message/` (`MessageInterpreter` / `IntentRouter`) | 不改行为，注册为受管 Agent |
| 采购/库存/SKU 主数据 | `procurement/` `inventory/` `sku/` `masterdata/` | 只读工具的数据源 |

## 边界更新（需同步 CONTEXT.md）

`CONTEXT.md` 边界行“本系统不负责……Agent 自动决策调度”需修订为：本系统在既有底座之上提供 Agent 决策/调度层，一期 Agent 仅做只读分析与建议，任何业务写操作仍必须经授权人工确认；采购、意图识别、数据查询等 Agent 职责由代码定义并通过 MCP 只读工具执行。

## 非目标

- 不引入 LangGraph / Python sidecar / Langfuse（一期）；
- 不建设低代码拖拽编排平台；
- 不自动确认订单/运单、不自动发起采购；
- 不改写既有 `MessageInterpreter` / `IntentRouter` 行为。

## User Stories

1. 作为采购运营人员，我希望在缺货产生采购工单后由采购比价 Agent 汇总 SKU 进货价、供应商映射与库存上下文并给出结构化比价建议，以便快速决策，且该 Agent 不触发任何写操作。
2. 作为运营人员，我希望用自然语言向数据查询 Agent 提问（如“最近 7 天缺货的订单有多少”），由 Agent 选择正确的只读工具查询并给出结构化答案，以便不熟悉后台也能取数。
3. 作为系统管理员，我希望所有 Agent 在统一注册表中可见、可启停，且每次运行都可从 `run_id` 追溯到工具调用与审计记录，以便排错与追责。
4. 作为审计人员，我希望 Agent 的工具调用与人工操作一样留下 AGENT 审计与脱敏载荷，以便确认 Agent 从未执行未授权写操作。
5. 作为开发人员，我希望新增领域能力时只需在 MCP 工具层注册一次，Agent 即自动获得该能力，避免双份工具定义漂移。
