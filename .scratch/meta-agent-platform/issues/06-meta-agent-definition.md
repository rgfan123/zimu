# 06 — Meta-Agent 定义与工具面设计

**Type:** grilling
**Status:** ready-for-agent
**Blocked by:** 03 — Agent 定义数据模型与版本状态机

## Question

已确认：slug=meta-agent，注册为受管 Agent；工具白名单 = 工具发现只读工具 + 定义写工具（仅写草稿）；不能启用/停用其他 Agent；禁止修改自己的定义；产物 = AgentDefinition + 建议评测输入；模型 provider/model 走 allowlist（传输配置全局 `app.agent.*`）。

待决策点（grilling，一次一个，带推荐答案）：

1. 工具形态：工具发现只读工具（`list_agent_tools`？复用 MCP tools/list？）与定义写工具（`create_agent_draft` / `update_agent_draft`，走 McpWriteTools 模式：幂等 + AGENT 审计 + 身份注入）的具体签名与白名单。
2. 输出 schema：自然语言 → 草稿定义（slug/名称/描述/system prompt/工具白名单/model）+ 建议评测输入（自然语言样例列表）的 JSON 结构；澄清/拒绝行为（信息不足时）。
3. 防自改的实现：白名单不含 meta-agent 自身写路径 + 服务端校验（写工具拒绝目标 slug=meta-agent）。
4. 与 08 权限隔离的关系：meta-agent 自己的权限 profile。
