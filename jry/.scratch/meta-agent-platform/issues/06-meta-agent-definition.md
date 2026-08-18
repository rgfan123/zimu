# 06 — Meta-Agent 定义与工具面设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 03 — Agent 定义数据模型与版本状态机

## Question

已确认：slug=meta-agent，注册为受管 Agent；工具白名单 = 工具发现只读工具 + 定义写工具（仅写草稿）；不能启用/停用其他 Agent；禁止修改自己的定义；产物 = AgentDefinition + 建议评测输入；模型 provider/model 走 allowlist（传输配置全局 `app.agent.*`）。

待决策点（grilling，一次一个，带推荐答案）：

1. 工具形态：工具发现只读工具（`list_agent_tools`？复用 MCP tools/list？）与定义写工具（`create_agent_draft` / `update_agent_draft`，走 McpWriteTools 模式：幂等 + AGENT 审计 + 身份注入）的具体签名与白名单。
2. 输出 schema：自然语言 → 草稿定义（slug/名称/描述/system prompt/工具白名单/model）+ 建议评测输入（自然语言样例列表）的 JSON 结构；澄清/拒绝行为（信息不足时）。
3. 防自改的实现：白名单不含 meta-agent 自身写路径 + 服务端校验（写工具拒绝目标 slug=meta-agent）。
4. 与 08 权限隔离的关系：meta-agent 自己的权限 profile。

## Answer

1. **工具发现**：新增只读工具 `list_agent_tools`（返回 McpToolRegistry 全部工具的名称/描述/参数 schema/读写属性——读写元数据是 08 已定的），Meta-Agent 白名单只含它 + 两个定义写工具；工具面增长无需改提示词（「注册一次自动获得」延续）。
2. **写工具形态**：`create_agent_draft`（新 slug）+ `update_agent_draft`（已有 slug）两个全量工具，归入 `McpWriteTools`（幂等键 + AGENT 审计 + REQUIRES_NEW 失败审计），input = 完整草稿 JSON 含 `suggested_eval_cases`；服务端校验：slug 格式 `^[a-z][a-z0-9-]{0,63}$`、唯一性、版本分配（新 slug=v1）、`allow_write=true` 判定、target 只能是 draft 行且 ≠ meta-agent。
3. **输出与澄清**：输出 = 全量草稿 JSON（definition 全量字段 + suggested_eval_cases → 落库 PENDING 用例，走 07 联动确认）；缺关键信息 → outcome=NEEDS_INPUT + 明确澄清问题，绝不猜测填充；slug 冲突 → 拒绝并说明、不改名（命名是产品决策）。
4. **自管理**：meta-agent 定义由 V30 与其他三个 Agent 一起播种（allow_write=true）；变更只能人工经管理 REST API（草稿→确认）；全工具禁改（写工具 target 校验拒绝 slug=meta-agent + 白名单不含自身写路径，双重拒绝）；其 output_schema = 「草稿 JSON」schema（自举，随定义数据化）。
5. **毕业**：管理 REST API 票立即 graduate（→ 新票 12）。

**Schema 增量**：无新表（写工具写 `agent_definitions` draft 行 + `agent_eval_cases` PENDING 行，均已有定义）；`list_agent_tools` 为进程内只读工具（控制面工具组，具体类归属留实施票）。
