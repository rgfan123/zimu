# 09 — QUALITY 链路：promptfoo 执行器 + 异步评测

**What to build:** QUALITY 指标评测（07 后半 + 11 中间路线）：① promptfoo YAML 生成器——由 `agent_eval_cases` 的 QUALITY 用例 + 定义 system_prompt 生成（deepseek provider + llm-rubric/javascript 断言，密钥走环境变量，绝不入 DB/日志/产物）；② 执行器——`ProcessBuilder` 跑 `npx promptfoo eval`（本地/CI 形态），结果回写 DB 比对；③ 异步任务（Spring Worker 模式）跑 QUALITY，`run_mode=PREVIEW` 落 `agent_runs`（03 已加列），不污染 LIVE 统计与 09 基线；④ 与 09 CI 门禁分工：CI 只钉 INVARIANT（03），QUALITY 是参考指标。

**Blocked by:** 03 — INVARIANT 评测数据化；05 — B/C 路径收敛（QUALITY 跑真实模型需 Adapter 运行路径）（设计源：meta-agent-platform 票 07、11）。

**Status:** resolved
**GitHub:** https://github.com/rgfan123/zimu/issues/10
**Claimed by:** zed-agent (2026-08-19)
**Resolved by:** zed-agent (2026-08-19)

- [x] 生成的 YAML 可被 promptfoo eval 消费（可用样例冒烟）；密钥只经环境变量
- [x] 结果回写 DB 可查（按 run_id / 用例关联）；PREVIEW 不污染 LIVE 与基线
- [x] 异步任务失败不阻断草稿确认（参考指标语义）；与 CI 门禁分工文档化
- [x] 单测覆盖 YAML 生成与结果解析（执行器可 mock）

## Answer

**交付**（commit `ba972fb`，独立提交；含 V38 迁移、docs/agent-eval-baseline.md 分工文档）：

1. **生成器（①）**：`PromptfooYamlGenerator`——定义 system_prompt（提示词真源）+ QUALITY 用例（expected.answer_contains）生成 config YAML + chat 消息 JSON 双文件；deepseek provider（`deepseek:deepseek-chat`），apiKey 只经 `{{env.DEEPSEEK_API_KEY}}` 引用，**密钥绝不入 DB/日志/产物**（测试断言无字面密钥）；断言用 promptfoo 内置 `contains` 实现 answer_contains（确定性，不依赖判分模型——取舍记录：票面「llm-rubric/javascript」以设计 07 #3「QUALITY → answer_contains」为准，llm-rubric 需额外判分模型调用，留待需要时扩展）。
2. **执行器（②）**：`QualityEvalRunner` 接口（可 mock）+ `NpxPromptfooRunner`（ProcessBuilder 跑 `npx promptfoo eval`，超时强杀含 npx→node 后代进程，executor 收口）；`PromptfooEvalResult` 按 promptfoo 0.120 真实输出结构（`results.results[].gradingResult`）解析。
3. **异步 + PREVIEW（③）**：`QualityEvalService.submit` 异步入队（载荷冻结 `slug:version:runId`）；`QualityEvalWorker`（@Scheduled，`app.quality-eval.enabled` 默认关）按 `QUALITY_EVAL` 类型领取；`AsyncTaskStore` 新增按 task_type 领取重载（`InterpretationWorker` 改 typed claim，多 Worker 不互抢）；执行以 `run_mode=PREVIEW` 落 `agent_runs`（`AgentObservability.Start.runMode`），**不污染 LIVE 统计与基线**；结果按 run_id + **用例 id** 回写 `app.agent_eval_results`（V38 新表，details 只存逐条得分与稳定错误摘要）。
4. **分工（④）**：`docs/agent-eval-baseline.md` 新增 QUALITY/INVARIANT 分工章节——CI 只钉 INVARIANT（stub 跑分器 + `AgentEvalBaselineTest`），QUALITY 是参考指标（真实模型、有波动、不进门禁、失败不阻断草稿确认）。

**测试**：agent + mcp + message 全量 340 例绿。新增：`PromptfooYamlGeneratorTest`（结构/密钥/转义，SnakeYAML 回读）、`PromptfooEvalResultTest`（真实结构解析）、`QualityEvalIntegrationTest`（Testcontainers：PREVIEW 不污染 LIVE + typed claim 隔离 + 失败路径落 FAILED 结果与观测行）、`PromptfooEvalSmokeTest`（`PROMPTFOO_SMOKE=1` 真实跑 `npx promptfoo eval`——已本机通过：生成配置端到端消费 + 结果 JSON 解析；echo provider 免密钥验证配置形态，deepseek 真实调用需设 `DEEPSEEK_API_KEY` 后同一配置直接跑）。

**评审结论**（/code-review，基准 7dc1217，Standards+Spec 双轴）：
- Spec：生产触发点（草稿提交后自动入队）属 T11「异步任务基建 + 定义域写端点」接线范围，本票交付机器（service/worker/generator/结果表），边界在 Answer 注明；断言类型取舍（contains vs llm-rubric）以设计 07 #3 为准并记录；details 补用例 id 关联；执行改按**冻结版本**取定义与用例集（新增 `AgentDefinitionRepository.findVersion`，可复现可回滚）；CI 分工补文档。
- Standards：0 硬违规；修复 3 项自契约违规——失败路径 Javadoc「记 fail 重试」与 maxAttempts=1 对齐为「失败落 FAILED 结果、任务收口成功、不重试」；`errorDetails` 不再存控制台原文（防模型输出泄漏，只存退出码/异常摘要）；观测 runStarted/runFinished 包 try/catch（失败隔离 + 防双写）；另修 quoted() C1/DEL 转义、runner executor 收口与后代进程杀、repository 错误消息。
