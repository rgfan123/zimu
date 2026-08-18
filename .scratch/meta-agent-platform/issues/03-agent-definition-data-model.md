# 03 — Agent 定义数据模型与版本状态机

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** —

## Question

设计 `app.agent_definitions` 的表结构与版本状态机。已确认方向：DB 唯一真源 + 版本链；草稿 → 人工确认 → 启用；修改走新版本草稿；变更全审计；现有三个 Agent 迁移播种后删除代码定义；08 票的 MCP 权限 profile 表达式并入本模型（schema 预留工具白名单/权限引用两字段，03/08 谁先落定谁补齐，后者对齐）。

待决策点（grilling，一次一个，带推荐答案）：

1. 版本链形态：每次修改新行（完整快照）vs 主行 + 历史行；`prompt_version` 递增规则与 agent_runs 的关联。
2. 状态机：draft / active / retired 转移与约束（只有 active 可被运行？draft 可否预览运行？）；回滚语义（回滚 = 新版本草稿 vs 指针移动）。
3. 确认流程：确认动作记录（谁/何时/确认了哪个版本）；拒绝与删除草稿的处理。
4. 注册表加载：启动全量加载进内存 `AgentRegistry`（沿用不可变模式）vs 每 run 查 DB；`AgentRegistryChangeAuditor` 的 diff 数据源从代码 bean 变为 DB 的适配。
5. 迁移播种：现有三个代码定义 Agent 的播种数据（复用各 Configuration 常量）与代码定义删除策略。
