# 05 — 通用门禁与守卫泛化设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 02 — 现有四条路径收敛点审计

## Question

已确认方向：草稿提交时平台自动跑「通用门禁」；PII 拒绝/歧义澄清泛化为平台默认 AgentGuard（定义可豁免，默认不豁免）。

待决策点（grilling，一次一个，带推荐答案）：

1. 门禁清单与判定：结构完整性、工具白名单合法性（必须在 McpToolRegistry 中）、只读不变式（默认禁写工具，白名单含写工具时如何处置）、output_schema 可解析、提示词安全检查（PII/凭据/越权指令扫描）——各自判定口径、**阻断 vs 警告**、跑在哪一层（提交时 / 确认时 / 运行时）。
2. AgentGuard 泛化：PII 拒绝与歧义澄清从 DataQueryAgentGuard 提升为平台默认守卫的接口设计、豁免机制（默认不豁免）、与 08 权限隔离的协作边界（守卫是行为约束，权限是访问控制）。
