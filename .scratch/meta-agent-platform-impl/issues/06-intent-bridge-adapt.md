# 06 — D 路径意图桥适配

**What to build:** 意图识别路径适配（04 迁移批 D，唯一有生产调用方的路径）：`IntentRecognitionAgentBridge` 保留桥壳与管线钩子（不改写既有消息管线行为）；`agent_runs` 落 `intent` / `provider`（01 已加列），替代「每次运行多落一条重复 AGENT 审计」的通道——重复审计删除；run_id ↔ 审计 ↔ 业务提交 ↔ agent_runs 的全向关联保持；意图识别回归门禁（MessageInterpretation* 套件）全绿。

**Blocked by:** 05 — B/C 路径收敛（设计源：meta-agent-platform 票 04、07）。

**Status:** ready-for-agent

- [ ] 桥观测落库完整（intent/provider 可查），重复审计通道删除
- [ ] 全向关联（run_id↔审计↔提交↔agent_runs）验证；观测/审计失败隔离不变
- [ ] `MessageInterpretation*` 套件全绿（行为零变化）；本票独立提交
