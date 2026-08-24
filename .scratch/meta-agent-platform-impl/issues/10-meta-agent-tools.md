# 10 — Meta-Agent 工具面（list_agent_tools + 定义写工具）

**What to build:** Meta-Agent 的能力面（06 决策）：① `list_agent_tools` 只读工具——返回注册表全部工具的名称/描述/参数 schema/读写属性（工具面增长不改提示词）；② `create_agent_draft` / `update_agent_draft` 两个全量写工具——归入写工具集（幂等键 + AGENT 审计 + 失败审计），input = 完整草稿 JSON 含 `suggested_eval_cases`（落 PENDING 用例）；服务端校验：slug 格式、唯一性、版本分配（新 slug=v1）、`allow_write` 判定、target 只能是 draft 行且 ≠ meta-agent；接入 08 静态门禁（不过拒绝落库）；③ meta-agent 定义已在 01 播种（allow_write=true），本票使其工具白名单可实际绑定运行。

**Blocked by:** 02 — 注册表切 DB 真源；08 — 门禁引擎（设计源：meta-agent-platform 票 06、08）。

**Status:** resolved
**GitHub:** https://github.com/rgfan123/zimu/issues/11
**Claimed by:** zed-agent (2026-08-19)
**Resolved by:** zed-agent (2026-08-19)

- [x] 工具调用可创建/更新草稿（含建议用例落 PENDING），全量快照语义正确
- [x] 幂等/审计/失败审计路径测试；meta-agent 禁改（target 拒绝）与 allow_write 校验生效
- [x] 静态门禁不过 → 拒绝落库（不产生脏草稿）
- [x] `list_agent_tools` 返回读写属性正确（与 07 元数据一致）

## Answer

**交付**（commit `a1783f2`，独立提交；含 CONTEXT.md 术语表补 Agent 草稿）：

1. **list_agent_tools（①）**：`McpControlReadTools` 只读工具——返回注册表全部工具的名称/描述/参数 schema/读写属性（07 读写元数据，`registry.writeToolNames()` 一致）；`ObjectProvider<McpToolRegistry>` 懒解析打破注册表构造期循环依赖；stdio 面照常暴露（只读）。
2. **定义写工具（②）**：`create_agent_draft` / `update_agent_draft` 归入 `McpWriteTools`（readOnly=false，executeWrite 幂等 + `mcp.{tool}` AGENT 审计 + REQUIRES_NEW 失败审计；公共流程提取 `agentDraftWrite`），委托 `AgentDraftService`：服务端校验 slug 格式（复用 `AgentDefinition.SLUG_PATTERN`）/唯一性（重复 → `AGENT_SLUG_EXISTS` 409）/版本分配（新 slug=v1；update 对 draft 最新版原地覆盖、active/retired 之上开新版本）/allow_write 严格布尔（字符串/数字拒绝）/target 只能是 draft 行且 ≠ meta-agent（`AGENT_TARGET_FORBIDDEN` 403，防自改）；`suggested_eval_cases` 落 PENDING QUALITY 用例（expected 占位待确认）；幂等经 `IdempotencyService`。
3. **08 门禁接入（③）**：草稿先跑 `DefaultAgentGateEngine` 六项阻断，任一命中 → `AGENT_GATE_BLOCKED` 拒绝落库（不产生脏草稿；`ObjectProvider<AgentGateEngine>` 打破与写工具循环依赖）。
4. **全量快照语义**：draft 覆盖时 UPDATE 原地更新全量字段（保留 PENDING 用例外键引用的定义行）；**含 CONFIRMED 冻结用例的 draft 不覆盖**（转开新版本，冻结评测集不被改写）；PENDING 建议用例随覆盖替换（旧建议不再代表新内容）。
5. **绑定运行（③）**：meta-agent（V33 种子 allow_write=true + 三工具白名单）经 T07 bind 校验（allowWrite=true + 工具存在）可实际绑定，测试断言白名单三工具全部解析。

**测试**：agent + mcp + message 全量 349 例绿。新增 `MetaAgentDraftIntegrationTest` 9 例：list 读写元数据一致（07）、创建草稿 + PENDING 用例 + 审计 + 幂等重放、update 原地覆盖（PENDING 替换断言）/active 开新版本/retired 开新版本、唯一性冲突 409、allow_write 非布尔拒绝、meta-agent 禁改 403 无副作用、门禁拒绝零脏行、meta-agent 白名单绑定。

**评审结论**（/code-review，基准 8e8b9a5，Standards+Spec 双轴）：
- Spec：allow_write 判定由 08 门禁只读不变式承担（设计 08 口径一致），补严格布尔类型校验；update 覆盖不删 CONFIRMED 冻结用例（改：含 CONFIRMED 的 draft 转开新版本）；PENDING 替换、唯一性冲突、retired 路径补测试；绑定运行验证补齐。
- Standards：0 硬违规；写工具 create/update 重复形状提取 `agentDraftWrite`；slug 常量复用 `AgentDefinition.SLUG_PATTERN`；`emptyControlTools` 收敛进 `McpToolTestSupport`；`McpWriteTools` 全限定引用改 import；CONTEXT.md 补 Agent 草稿术语（区别于订单草稿）；`PENDING_CASE_EXPECTED` 改 JsonNode 构造（初版链式 putArray 返回子节点的坑已在实现期修掉，与 `objectProperty` 同款教训）。
