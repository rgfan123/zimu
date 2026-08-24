# 08 — 门禁引擎 + 运行期 PII 守卫

**What to build:** 通用门禁与守卫（05 决策）：① 门禁引擎——六项阻断（结构完整性 / 工具白名单合法性 / 只读不变式 / output_schema 可解析（networknt）/ 凭据扫描（密钥/Token 模式）/ 越权指令扫描）+ PII 警告扫描（不阻断，结果供确认流程高亮）；以可复用引擎形式提供（写工具静态门禁与确认前全量复跑共用，实现手段细节自定但判定口径遵循 05）；② 运行期 `AgentGuard` 默认链 = [PII 拒绝]——模型调用前对输入判定，命中 → outcome=REJECTED 转人工、不进模型；`guard_exemptions` 生效（默认空 = PII 守卫生效）；守卫是行为约束，与权限（07）互不替代。

**Blocked by:** 07 — MCP 权限隔离（设计源：meta-agent-platform 票 05）。

**Status:** resolved
**GitHub:** https://github.com/rgfan123/zimu/issues/9
**Claimed by:** zed-agent (2026-08-19)
**Resolved by:** zed-agent (2026-08-19)

- [x] 六项阻断 + PII 警告的判定单测（含凭据模式、越权指令正反例）
- [x] PII 命中 → REJECTED 且不发起模型调用；豁免声明后守卫跳过
- [x] 引擎可被写工具（T10）与确认流程（T11）复用（接口抽象，非内嵌）
- [x] 引擎失败不阻断既有 Agent 运行（失败隔离）

## Answer

**交付**（commit `3e8fd2c`，独立提交；含 CONTEXT.md 术语表补 门禁/运行期守卫）：

1. **运行期守卫（②）**：`AgentGuard` 平台默认守卫链 = [PII 拒绝]（05 决策）——PII 判定单一实现（`DataQueryAgentGuard.piiProblems` 委托平台，关键词口径 05 认可既有）；`AgentGuardExemption` 豁免枚举（`guard_exemptions` 默认空 = 生效）；`AgentFailureCode.PII_GUARDED`。`AgentRuntimeFacade.invoke` 在模型调用前判定：豁免外命中 → outcome=REJECTED 转人工、不进模型，留审计 + FAILED 观测行；守卫故障按失败隔离跳过（05「引擎失败不阻断既有 Agent 运行」）。
2. **门禁引擎（①）**：`AgentGateEngine` 接口（可复用，非内嵌——T10 写工具静态门禁 / T11 确认前全量复跑共用）+ `AgentGateReport`（阻断项 + PII 警告项）+ `AgentGateScan`（凭据/越权/PII 警告保守启发式）+ `DefaultAgentGateEngine`（@Component）六项阻断：结构完整性（含长度上限）/ 工具白名单合法性（必须注册）/ 只读不变式（写工具无 allow_write → 阻断）/ output_schema 可解析（networknt，`JsonSchemaValidator.schemaParses` 新增）/ 凭据扫描（提示词进 DB 红线）/ 越权指令扫描；PII 扫描仅警告（不阻断）。评估失败收敛为阻断（fail-closed 安全默认，不外抛）。
3. **范围**：守卫只对门面驱动运行生效；D 路径（意图识别/消息解释）输入即业务消息内容本身（PII 是业务载荷而非查询），不在默认守卫范围（Javadoc 注明）。

**测试**：agent + mcp 包全量 240 例绿。新增：`AgentGuardTest`（PII/豁免）、`AgentGateScanTest`（凭据/越权正反例 + PII 警告）、`DefaultAgentGateEngineTest`（六项阻断 + 长度 + 失败隔离）、`JsonSchemaValidatorTest`（schemaParses）、门面守卫用例（PII→REJECTED 不进模型 + 审计、豁免跳过）；数据查询领域 PII 分支保留（评测 requires_human 召回口径不变）。

**评审结论**（/code-review，基准 a60bc7d，Standards+Spec 双轴）：
- Spec：结构完整性门禁补「长度」判定（初版仅空转必填复查）；「每 run 生效」范围注明 D 路径豁免；关键词子串匹配的近邻词误报面（如「IP 地址」）与 REJECTED→FAILED 观测行语义（拒绝路径沿用 T04 既有 FAILED 行口径，error_type 区分原因，outcome 维度在结果/审计）均记录为已知口径。
- Standards：0 硬违规；门面收口尾 4 处重复提取 `finalizeRun`；门禁引擎三次 toolNames 遍历合并为单次（每个名称只解析一次）；`AgentGateReport.pass()` 无调用删除；扫描消息统一中文；CONTEXT.md 术语表补 门禁/运行期守卫。守卫 fail-open 与门禁 fail-closed 的非对称为 05 明确要求（运行期失败隔离 vs 确认期安全默认），Javadoc 注明。
