# 07 — 意图识别 Agent（复用注册）

**What to build:** 不重写既有意图识别管线，将其作为受管 Agent 注册进 `AgentRegistry`，获得统一启停视图、运行元数据与可观测性；`MessageInterpreter` / `IntentRouter` 行为完全不变。

**Blocked by:** 02 — Agent Registry + Runtime；08 — Agent 可观测性

**Status:** resolved

## 范围

- 新增 Agent Definition `intent-recognition`：描述“企业微信消息意图分类与分流”，system prompt 引用既有提示词（版本号与 `app.message-interpreter.prompt-version` 对齐或引用同一版本语义），model ref 指向 `app.message-interpreter.*` 配置。
- 运行桥：解释任务执行时（`InterpretationWorker` 路径）向 Agent 运行记录写入对应元数据（provider / model / prompt_version / intent / error_code），与既有 `MessageInterpretation` 持久化并存，不替代。
- `AgentRegistry` 中该 Agent 默认 enabled；启停只影响观测/注册视图，不影响既有消息管线执行（管线由既有配置驱动）。
- 不改 `IntentRouter` 分流逻辑、不改 `MessageIntent` 枚举、不改草稿工厂。

## 非范围

- 把意图识别改成多步工具调用 Agent（保持单次分类接缝）；
- 迁移/替换 `MessageInterpreter` 实现。

## 验收标准

- [x] 注册表中可见 `intent-recognition`，可启停，启停不影响既有解释任务；
- [x] 既有意图识别测试套件（`MessageInterpretation*Test` 等）全部保持绿，行为零变化；
- [x] 每次解释运行可在 Agent 观测中按 run/trace 关联到 provider/model/prompt_version/intent；
- [x] 元数据投影遵循 allowlist（未白名单版本显示 `none`）。

## 验证原则

- 行为不变性是最重要的验收：回归测试必须全绿；
- 不以“换了个方式跑得更快”为理由改动分流。

## Answer

主开发与验证（2026-08-16，subagent 中断后由主线程验证收尾）：

- **Agent Definition**：`IntentRecognitionAgentConfiguration` 注册 slug=`intent-recognition`（name=意图识别，tool_names 恒为空——单次分类接缝，无工具调用；model_ref=`app.message-interpreter`（声明性引用，不解析为 Spring 配置）；prompt_version 与 `app.message-interpreter.prompt-version` 对齐，未配置时回退 `intent-recognition-v1`）。默认 enabled，经 `List<AgentDefinition>` bean 自动进 02 票注册表。
- **运行桥**：`IntentRecognitionAgentBridge`（`InterpretationService` 模型调用前后 runStarted/runFinished 两处桥接点）：run_id 沿用门面生成模式，thread_id=异步任务 id（重试聚组），business_entity=MESSAGE_SUBMISSION/submission_id；经 08 票 `AgentObservability` 接缝落 `app.agent_runs`（Start 先落 RUNNING、Finish 收口 status/error_type/latency，input 只存 digest）；因 agent_runs 无 provider/intent 列，每次运行另落一条 AGENT 审计（`agent.intent-recognition.run`，provider/model/prompt_version/intent/error_code 经 allowlist 投影后进 responsePayload），run_id ↔ 审计 ↔ agent_runs ↔ 业务提交全向关联。启停 fail-closed（未注册=未启用）；观测/审计失败 try/catch 隔离不影响解释结果。
- **行为不变性**：`message/` 包仅 `InterpretationService.java` 接入桥接点（纯加法）；`IntentRouter` / `MessageIntent` / `MessageInterpreter` / `DeepSeekMessageInterpreter` / 三个草稿工厂 grep 零引用、零改动。
- **测试 17 例全绿**：IntentRecognitionAgentBridgeTest 7（启停判定、runStarted/runFinished 写入、失败隔离）+ IntentRecognitionAgentDefinitionTest 5（注册表可见、prompt_version 对齐/回退、tool_names 空）+ IntentRecognitionBridgeDisabledIntegrationTest 1（停用时既有解释任务照常）+ IntentRecognitionBridgeIntegrationTest 4（真实 PostgreSQL：run 行 + 审计 + allowlist 投影 none）。
- **回归**：全量 `mvn test` 709 run / 0 failures / 0 errors / 7 skipped，BUILD SUCCESS（含全部既有 `MessageInterpretation*Test` 行为零变化）。
- 遗留：无阻塞项；`agent_runs` 已可被 05/06/07 共同观测（业务编排 run 行收口仍留统一编排收敛票）。
