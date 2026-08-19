# 07 — MCP 权限隔离（读写元数据 + 调用期复核 + stdio 只读）

**What to build:** 权限强制点落地（08 决策）：① `McpTool` 增加读写元数据（形态在「接口默认方法 vs 注解 vs 注册表侧配置」中取其一，使「默认禁写」成为可判定不变式）；② **Agent 面调用期复核**——`AgentToolInvoker.execute` 对每次工具调用按绑定白名单复核（防旁路真强制点；现状只有未注册名报错、不是权限判定）；③ **stdio 面一期收紧为只读**——`toolsList` / `handleToolCall` 过滤写工具（外部客户端共用全局 identity、无 per-agent 权限）；④ `allow_write` 接入注册表判定（白名单含写工具且无 allow_write=true → 绑定期拒绝）；⑤ 两个不变式测试的 `WRITE_TOOL_NAMES` 手抄常量改为向注册表按读写元数据查询（防写工具集合增长后静默漏检）。

**Blocked by:** 02 — 注册表切 DB 真源（设计源：meta-agent-platform 票 08）。

**Status:** ready-for-agent

- [ ] 写工具零调用不变式可判定（基于读写元数据）；stdio 面 tools/list 无写工具、调用写工具被拒
- [ ] 调用期复核拒绝越权调用（白名单外工具即使注册存在也拒绝）并留审计
- [ ] allow_write 判定生效；不变式测试不再手抄清单
- [ ] 既有 MCP 协议测试（stdio 路径）适配后全绿
