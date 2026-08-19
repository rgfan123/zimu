# 02 — 注册表切 DB 真源 + 删代码定义

**What to build:** Agent 定义的唯一真源从代码变为 `agent_definitions`（03 contract 步）：启动时全量加载 DB 行构造 `AgentRegistry`（`AgentRegistryHolder` volatile 换实例——确认/回滚后无需重启即可感知）；内存 `AgentDefinition` 扩展字段（`version`/`status`/`activatedBy`/`activatedAt`/`allowWrite`/`guardExemptions`/`outputSchema`）；`AgentRegistryChangeAuditor` 补 ACTIVATED/RETIRED 审计事件（复用既有 diff 机制）；删除三个代码定义 Configuration（data-query / procurement-price / intent-recognition，`ProcurementPriceAgentConfiguration` 只摘方法保留可用组件），种子成为唯一来源。

**Blocked by:** 01 — V30 迁移与播种（设计源：meta-agent-platform 票 03）。

**Status:** ready-for-agent

- [ ] 启动从 DB 加载注册表，无代码定义 bean 残留；启动失败语义（DB 不可用时 fail-closed）不劣于现状
- [ ] 草稿确认（测试内直接改 DB 行）后 holder 换实例，运行条件 `status='active' AND enabled=true` 判定正确
- [ ] ChangeAuditor 对 ACTIVATED/RETIRED 产生审计事件；AgentRegistryTest / AgentRegistryChangeAuditorTest 全绿
- [ ] 删除代码定义后 `Agent*` 测试与基线仍绿（本票不改运行行为）
