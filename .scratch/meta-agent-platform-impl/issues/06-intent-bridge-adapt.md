# 06 — D 路径意图桥适配

**What to build:** 意图识别路径适配（04 迁移批 D，唯一有生产调用方的路径）：`IntentRecognitionAgentBridge` 保留桥壳与管线钩子（不改写既有消息管线行为）；`agent_runs` 落 `intent` / `provider`（01 已加列），替代「每次运行多落一条重复 AGENT 审计」的通道——重复审计删除；run_id ↔ 审计 ↔ 业务提交 ↔ agent_runs 的全向关联保持；意图识别回归门禁（MessageInterpretation* 套件）全绿。

**Blocked by:** 05 — B/C 路径收敛（设计源：meta-agent-platform 票 04、07）。

**Status:** resolved
**GitHub:** https://github.com/rgfan123/zimu/issues/7
**Claimed by:** zed-agent (2026-08-19)
**Resolved by:** zed-agent (2026-08-19)

- [x] 桥观测落库完整（intent/provider 可查），重复审计通道删除
- [x] 全向关联（run_id↔审计↔提交↔agent_runs）验证；观测/审计失败隔离不变
- [x] `MessageInterpretation*` 套件全绿（行为零变化）；本票独立提交

## Answer

**交付**（commit `bafaf6f`，独立提交）：

1. **重复审计通道删除**：`IntentRecognitionAgentBridge` 移除 `recordAudit`（operation=agent.intent-recognition.run）与 `AuditLogService` 依赖/常量，不再每次运行额外落一条 AGENT 审计。
2. **intent/provider 落 agent_runs 列（04 差异⑦）**：`AgentObservability.Finish` 扩展 `provider`/`intent`/`promptVersion` 三字段；`JdbcAgentObservability.UPDATE_FINISH` 以 `COALESCE(NULLIF(?, ''), col)` 收口写列。桥 `runFinished` 把运行期才可知的投影 provider/model、归一化 intent、投影后的实际运行 prompt_version 随 Finish 落库（完整替代旧通道元数据）；`AgentRuntimeFacade` 走新增便捷工厂 `Finish.of(...)`（provider/intent/promptVersion 不落，行为不变）。
3. **全向关联口径**：按 04 差异⑦「给 agent_runs 补列、砍掉重复通道」，审计腿由 agent_runs 行直接承载元数据替代——关联链收敛为 run_id ↔ agent_runs ↔ 业务提交（business_entity=MESSAGE_SUBMISSION/submission_id，Start 落）；intent/provider/error_type 在 agent_runs 行上直接可查，无需审计拼装。清单中「run_id↔审计」腿随重复通道删除而按设计切断（设计源 04 差异⑦ 优先于清单措辞）。
4. **启停/失败隔离不变**：注册表 fail-closed（未注册/未启用零写入）与观测 try/catch 隔离语义原样保留；`InterpretationService` 桥调用签名收窄（runFinished 不再需要 threadId/submissionId）。

**测试**：agent 包全量 188 例绿（含桥单元/集成/禁用、门面观测、AgentObservability 集成）；MessageInterpretation 回归（ApiTest/SafetyApiTest）全绿（行为零变化）。

**评审结论**（/code-review，基准 bf41228，Standards+Spec 双轴）：
- Spec：清单「run_id↔审计」腿与「重复审计删除」字面矛盾——设计源 04 差异⑦ 权威，「补列、砍重复通道」，实现满足设计意图；实现后 prompt_version 实际运行值随 Finish 投影落列（首轮评审发现旧通道该元数据丢失，已修复）。
- Standards：无硬违规；门面 Finish 位置 null 改为 `Finish.of` 便捷工厂（消除 null 堆积）；桥参数 `run` 更名 `meta`（消除歧义）。观测隔离 try/catch 双处同形与 javadoc 票号考古为既有仓库惯例，保留。
- 无新增迁移：intent/provider 列由 V33 提供（V33 注释即记载本差异⑦）。
