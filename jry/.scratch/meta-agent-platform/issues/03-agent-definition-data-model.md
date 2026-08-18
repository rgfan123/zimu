# 03 — Agent 定义数据模型与版本状态机

**Type:** grilling
**Status:** resolved
**Blocked by:** —

## Question

设计 `app.agent_definitions` 的表结构与版本状态机。已确认方向：DB 唯一真源 + 版本链；草稿 → 人工确认 → 启用；修改走新版本草稿；变更全审计；现有三个 Agent 迁移播种后删除代码定义；08 票的 MCP 权限 profile 表达式并入本模型（schema 预留工具白名单/权限引用两字段，03/08 谁先落定谁补齐，后者对齐）。

待决策点（grilling，一次一个，带推荐答案）：

1. 版本链形态：每次修改新行（完整快照）vs 主行 + 历史行；`prompt_version` 递增规则与 agent_runs 的关联。
2. 状态机：draft / active / retired 转移与约束（只有 active 可被运行？draft 可否预览运行？）；回滚语义（回滚 = 新版本草稿 vs 指针移动）。
3. 确认流程：确认动作记录（谁/何时/确认了哪个版本）；拒绝与删除草稿的处理。
4. 注册表加载：启动全量加载进内存 `AgentRegistry`（沿用不可变模式）vs 每 run 查 DB；`AgentRegistryChangeAuditor` 的 diff 数据源从代码 bean 变为 DB 的适配。
5. 迁移播种：现有三个代码定义 Agent 的播种数据（复用各 Configuration 常量）与代码定义删除策略。

## Comments

### 决策 1（版本链形态）— 用户答「随便」，采纳推荐

**单表 append-only 全快照**：`app.agent_definitions` 每次修改追加一整行完整快照，不做主行 + 历史行分表。

- 主键 `id`；唯一约束 `(agent_slug, version)`，`version` 整数从 1 递增。
- 「当前生效版本」不靠主行表达，靠 `status` 列 + 部分唯一索引 `UNIQUE (agent_slug) WHERE status='active'`，把「每 slug 至多一个 active」下沉到 DB。

理由：与现有 `AgentRegistry` 不可变模式同构（变更 = 构造新实例 + `AgentRegistryChangeAuditor` diff，diff 数据源从两个 code bean 平移为两行快照）；主行+历史行需双写且回滚要原地改主行，破坏「版本链只增不改」不变式；跑错版本属红线，约束交给 DB 比交给应用层硬。

代价：查当前版本需带 `WHERE status='active'`；全快照有字段冗余。Agent 数量个位数、改动频率低，可忽略。

### 决策 2–5 — 用户「按推荐来」，全部采纳

**2）状态机与回滚**：三态 `draft / active / retired`，四条转移（draft→active 人工确认；active→retired 被替换或下线；draft→删除；**无 retired→active**）。回滚 = 复制旧版本内容成新 draft → 确认 → active，不做指针移动，保「版本链只增不改」，`agent_runs.agent_version` 历史可线性重放。配套：`enabled` 与 `status` **正交**，运行条件 `status='active' AND enabled=true`（enabled=false 是运维临时停用、不铸版本；facade 的 `AGENT_DISABLED` 分支与现有测试零改动）；draft 预览走独立入口 `preview(definitionId, ...)`，`agent_runs` **新增 `run_mode IN ('LIVE','PREVIEW')` 列**，避免草稿试跑污染 09 评测基线与运行看板。

**3）确认流程**：确认事实落定义行（`activated_by` / `activated_at`，与 `status='active'` 同事务），审计只做流水（`operation=agent.definition.activated`, `actor=HUMAN`）。理由：红线要求「审计失败隔离」，审计可能缺行，不能当唯一真源。拒绝草稿 = 硬删行 + 发 `actor=HUMAN` 审计。

**4）注册表加载**：启动全量加载进内存，改动经 **`AgentRegistryHolder`**（volatile 引用）换实例；`AgentRegistry` 保持不可变，`AgentRuntimeFacade` 改注 holder（现在是构造注入 final registry，必须改）。`AgentRegistryChangeAuditor.recordChanges(before, after)` **原样复用**，仅补 `ACTIVATED` / `RETIRED` 两个 Kind。代价：多实例部署缓存短暂不一致（单租户内部系统可接受）。

**5）迁移播种与代码定义删除**：两步走。第一步 Flyway V30 播种三行 `version=1, status='active'`（常量抄自各 Configuration），注册表数据源切 DB，代码定义 bean 暂留不进注册表；验证通过后第二步单独 commit 删除。**三个类不对称**（已核实）：`DataQueryAgentDefinitionConfiguration`、`IntentRecognitionAgentConfiguration` 只有定义 bean，可整类删；`ProcurementPriceAgentConfiguration` 另有 2 个 @Bean（runtime/gateway 装配），**只能摘掉 `procurementPriceAgentDefinition()` 一个方法**。保留一轮是为了让播种数据（尤其 systemPrompt 全文与 toolNames 白名单）能与代码常量逐字对照。

## Answer

`app.agent_definitions` 采用**单表 append-only 全快照 + 三态版本状态机**：

- 每次修改追加完整快照行，唯一约束 `(agent_slug, version)`，`version` 整数递增；「当前生效」靠 `status` + 部分唯一索引 `UNIQUE (agent_slug) WHERE status='active'` 下沉到 DB，不设主行/历史行分表。
- 状态机 `draft → active → retired`，无 retired→active 边；回滚即新草稿。`enabled`（运维启停）与 `status`（版本生命周期）正交。
- 确认事实上行（`activated_by`/`activated_at`）、审计做流水；拒绝草稿硬删。
- 注册表启动全量加载，`AgentRegistryHolder` 持 volatile 引用换实例，`AgentRegistryChangeAuditor` 复用并补 ACTIVATED/RETIRED。
- 播种两步走，`ProcurementPriceAgentConfiguration` 只能摘方法不能整删。

**Schema 增量**（本票产出，08 权限字段待对齐）：`agent_definitions` 全部现有 `AgentDefinition` 字段 + `id` / `version` / `status` / `activated_by` / `activated_at` + 预留 `tool_whitelist`、`permission_profile_ref`（08 谁先落定谁补齐）；`agent_runs` 加 `run_mode`。

**组件库结论（用户追问，同 session 核实）**：Control Plane 无现成件可买，且这是 10 票「框架跑运行时、自研只留薄控制面」结论的同一面。Hibernate Envers 虽只差一个依赖（`spring-boot-starter-data-jpa` 已在 pom），但其 `_AUD` 影子表模型正是决策 1 拒绝的主行+历史行形态，且 `agent/` 包**整包零 @Entity、全 JdbcTemplate**（其余 23 个类用 @Entity，agent 包是刻意例外），引入需把控制面改 JPA。javers / Spring Statemachine 同样净负债（diff 器七种 Kind 已写完有测试；3 态 3 边用库比手写长）。Dify/Flowise/Langflow/LangGraph Platform 属已排除的第二控制面。
