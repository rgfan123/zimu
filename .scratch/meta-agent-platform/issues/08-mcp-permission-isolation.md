# 08 — MCP 权限隔离设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 02 — 现有四条路径收敛点审计

## Question

已确认方向（用户提出）：复用现有 MCP 做权限隔离——工具访问以 MCP 层为权限强制点，而非仅绑定期白名单；McpToolRegistry 保持唯一工具源。02 票提供 enforcement 点事实（身份注入、写工具门禁、审计路径的代码位置）。

待决策点（grilling，一次一个，带推荐答案）：

1. per-agent 权限表达：工具白名单即 profile（一期）vs 独立权限 profile 表 + 定义引用；写工具/读工具分区表达。
2. 强制点：McpToolRegistry / McpServer 分发时校验 vs AgentToolBindingFactory 绑定期过滤 vs 两者结合（绑定期省 token，分发期兜底防旁路）；stdio MCP 路径（McpServerRunner）与 Agent 内联路径是否同一套校验。
3. 写工具门禁演进：McpWriteTools 现有「agent-identity 存在性检查」→「身份 + profile 白名单」；meta-agent 的定义写工具如何纳入。
4. 与 03 数据模型、05 门禁、06 Meta-Agent 的接口边界（本票只定权限机制；字段落 03，门禁校验落 05）。
