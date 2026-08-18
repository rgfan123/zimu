# 08 — MCP 权限隔离设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 02 — 现有四条路径收敛点审计

## Question

已确认方向（用户提出）：复用现有 MCP 做权限隔离——工具访问以 MCP 层为权限强制点，而非仅绑定期白名单；McpToolRegistry 保持唯一工具源。02 票提供 enforcement 点事实（身份注入、写工具门禁、审计路径的代码位置）。

待决策点（grilling，一次一个，带推荐答案）：

1. per-agent 权限表达：工具白名单即 profile（一期）vs 独立权限 profile 表 + 定义引用；写工具/读工具分区表达。
2. 强制点：McpToolRegistry / McpServer 分发时校验 vs AgentToolBindingFactory 绑定期过滤 vs 两者结合（绑定期省 token，分发期兜底防旁路）；stdio MCP 路径（McpServerRunner）与 Agent 内联路径是否同一套校验。
3. 写工具门禁演进：McpWriteTools 现有「agent-identity 存在性检查」→「身份 + profile 白名单」；meta-agent 的定义写工具如何纳入。
4. 与 03 数据模型、05 门禁、06 Meta-Agent 的接口边界（本票只定权限机制；字段落 03，门禁校验落 05）。

## Comments

### 决策 1–3 — 用户：1、2 按推荐，3 选 B（meta-agent 走 McpWriteTools）

**1）强制点：分面处理，不用一套方案套两个面。**

前置事实（§4.4）：**Agent 根本不经过 `McpServer`**——`AgentToolInvoker` 直查 `McpToolRegistry` 分发；10 票又已定不引 `McpClient` 外连。故「工具访问以 MCP 层为强制点」（地图决策 11）需消歧：在 `McpServer.handleToolCall` 加强制对 Agent 无任何作用。

- **Agent 面**（进程内 `AgentToolInvoker` → `McpToolRegistry`）：绑定期白名单**保留**（省 token，模型看不见白名单外工具）+ **`AgentToolInvoker.execute` 加调用期复核**（真强制点，防旁路）。现状该处只有「未注册名称 → `MCP_INTERNAL_ERROR`」，不是权限判定。
- **stdio 面**（`McpServer`）：调用方是外部 MCP 客户端，共用全局 `app.mcp.agent-identity`、**无 slug**，per-agent 权限无从谈起。一期**收紧为只读**：`toolsList()`（:167-177）与 `handleToolCall`（:111-147）两处过滤掉写工具。

**2）per-agent 权限表达：一期「工具白名单即 profile」，删除 03 预留的 `permission_profile_ref`。**

三个现有 Agent 白名单为 11 / 13 / 0 个工具的明确清单，无共享复用需求；独立 profile 表要到「多 Agent 共享同一套权限且需集中改」才有价值。V30 尚未写，现在删预留字段是免费的。

**配套必加**：`McpTool` 接口增加**读写元数据**（§4.5 第 1 条指出的缺口），使「默认禁写」成为平台可判定的不变式，而非人眼检查白名单里有无写工具名。

**3）meta-agent 的定义写工具归入 `McpWriteTools`（用户选 B，推翻推荐的「业务面/控制面」分类）。**

**对现有测试的影响（已核实，不破）**：写工具零调用不变式是**按 Agent 断言**的，不是全局「任何 Agent 不得有写工具」——`DataQueryAgentDefinitionTest:37-42` 与 `ProcurementPriceAgentInvariantTest` 各自断言自己的白名单不含 `WRITE_TOOL_NAMES`。meta-agent 新增定义写工具后，两个业务 Agent 的断言照常通过。

**但暴露一个脆弱点**：`WRITE_TOOL_NAMES` 是手抄的 4 个字符串常量（注释「与 mcp/McpWriteTools.java 一致」）。3B 之后写工具集合会增长，手抄清单漏更新 → 测试静默漏检。**修复正好是决策 2 的产物**：`McpTool` 有了 readOnly 元数据后，测试改为向 `McpToolRegistry` 查询写工具集合，不再手抄常量。**此项列为 3B 的必配改动。**

**3B 的收益**：meta-agent 的定义写入白拿 `executeWrite` 的三样能力——幂等重放、AGENT 审计（service=mcp, operation=mcp.{toolName}, actorType=AGENT）、`REQUIRES_NEW` 独立事务的失败审计（防随业务回滚丢失，:263-281）。另起一套要重新实现这三样。

**3B 的代价**：`McpWriteTools` 是业务面类（依赖 `MessageSubmissionService` / `OrderDraftService` / `McpReviewRequestService`），加入 `agent_definitions` 写入引入控制面依赖，包耦合变差。已知并接受。

**3B 必须补的 target-scope 校验**（白名单表达不了，落工具实现内）：定义写工具只能写 `agent_definitions` 且只能写 `status='draft'` 行；**拒绝 target slug = meta-agent**（06 决策 3 防自改）。

**与决策 1 的联动**：stdio 面已收紧为只读，定义写工具因此自动不对外暴露——正确且符合预期。
**与决策 2 的联动**：meta-agent 定义须显式声明 `allow_write=true`，由 05 门禁在草稿提交时判定（默认阻断、需人工确认）。

## Answer

**强制点分两个面**：Agent 面（进程内）= 绑定期白名单 + `AgentToolInvoker.execute` 调用期复核双层；stdio 面（`McpServer`）= 一期收紧为只读，`toolsList` 与 `handleToolCall` 过滤写工具，不套 per-agent 权限（外部客户端无 slug）。

**权限表达**：一期工具白名单即 profile，**删除 03 预留的 `permission_profile_ref`**，保留 `tool_whitelist`（即现有 `AgentDefinition.toolNames`），**新增 `allow_write` 布尔**。`McpTool` 接口加读写元数据，使「默认禁写」成为平台可判定不变式。

**写工具门禁**：`McpWriteTools` 内的 `requireCommandContext` 降为纵深防御；强制点上移到 `AgentToolInvoker`。绑定期规则 = 白名单含写工具且 `allow_write != true` → 拒绝。

**meta-agent**：定义写工具**归入 `McpWriteTools`**（用户决策），白拿幂等 + 审计 + 独立事务失败审计；须声明 `allow_write=true` 过 05 门禁；工具实现内强制 target 只能是 `agent_definitions` 的 draft 行且拒绝 target slug=meta-agent。**必配改动**：`DataQueryAgentDefinitionTest` / `ProcurementPriceAgentInvariantTest` 的 `WRITE_TOOL_NAMES` 手抄常量改为向 `McpToolRegistry` 按读写元数据查询。

**红线措辞更新**（见地图 Notes）：「Agent 写工具零调用」→「**业务** Agent 写工具零调用，meta-agent 是唯一例外且受 allow_write + 门禁 + target-scope 三重约束」。
