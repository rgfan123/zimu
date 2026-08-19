# 10 — Meta-Agent 工具面（list_agent_tools + 定义写工具）

**What to build:** Meta-Agent 的能力面（06 决策）：① `list_agent_tools` 只读工具——返回注册表全部工具的名称/描述/参数 schema/读写属性（工具面增长不改提示词）；② `create_agent_draft` / `update_agent_draft` 两个全量写工具——归入写工具集（幂等键 + AGENT 审计 + 失败审计），input = 完整草稿 JSON 含 `suggested_eval_cases`（落 PENDING 用例）；服务端校验：slug 格式、唯一性、版本分配（新 slug=v1）、`allow_write` 判定、target 只能是 draft 行且 ≠ meta-agent；接入 08 静态门禁（不过拒绝落库）；③ meta-agent 定义已在 01 播种（allow_write=true），本票使其工具白名单可实际绑定运行。

**Blocked by:** 02 — 注册表切 DB 真源；08 — 门禁引擎（设计源：meta-agent-platform 票 06、08）。

**Status:** ready-for-agent

- [ ] 工具调用可创建/更新草稿（含建议用例落 PENDING），全量快照语义正确
- [ ] 幂等/审计/失败审计路径测试；meta-agent 禁改（target 拒绝）与 allow_write 校验生效
- [ ] 静态门禁不过 → 拒绝落库（不产生脏草稿）
- [ ] `list_agent_tools` 返回读写属性正确（与 07 元数据一致）
