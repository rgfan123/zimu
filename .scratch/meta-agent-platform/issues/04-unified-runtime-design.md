# 04 — 统一运行时编排设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 01 — LangChain4j 动态输出 schema 调研；02 — 现有四条路径收敛点审计

## Question

基于 01 的动态 schema 结论与 02 的收敛点清单，设计统一运行时：

- AgentRuntimeFacade 如何按定义携带的 output_schema 驱动输出约束（专属 gateway 消除或数据化；`AgentStructuredOutput` 基础记录去留）。
- 采购比价 / 数据查询如何迁移：专属输出记录（ProcurementPriceRunResult / DataQueryRunResult）与输入解析（json 输入 vs 自然语言）如何表达在定义里。
- 失败码映射统一（AGENT_NOT_FOUND / AGENT_DISABLED / AGENT_MODEL_NOT_CONFIGURED / AGENT_OUTPUT_INVALID 等）。
- 与 08 MCP 权限隔离的边界：绑定期（AgentToolBindingFactory）与 MCP 层（McpToolRegistry 分发）各做什么。

待决策点（grilling，一次一个，带推荐答案）。
