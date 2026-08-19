# 05 — B/C 路径收敛（采购比价/数据查询）

**What to build:** 采购比价与数据查询两条自研编排路径收敛到统一门面（04 迁移批 B/C）：`ProcurementPriceAgent` 与 `DataQueryAgentService` 的专属 gateway / 编排逻辑删除，改走 `AgentRuntimeFacade` + 定义驱动（输入解析——json 输入 vs 自然语言——在定义 input 约定中表达）；专属输出 record（采购比价推荐、数据查询答案）保留为反序列化目标，不丢类型安全；`DataQueryAgentGuard` 保留为该 Agent 的校验器实现（领域歧义层，05 决策：不进平台默认链）；失败码统一（补数据查询的拒绝审计路径）。迁移前后基线比对。

**Blocked by:** 04 — Runtime Adapter 骨架 + 通用门面（设计源：meta-agent-platform 票 04、05）。

**Status:** ready-for-agent

- [ ] 采购/数据查询全套测试绿；无专属 gateway 类残留（含自建 OpenAI 通道删除）
- [ ] 输入解析两种形态（结构化 json / 自然语言）在定义驱动下正确路由
- [ ] 拒绝审计路径补齐（数据查询 PII/歧义拒绝留审计）；失败码统一（CLARIFICATION/PII_GUARDED 自定 status 消除）
- [ ] 基线比对通过或按流程重钉记录；本票独立提交
