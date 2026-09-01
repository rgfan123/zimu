# Agent 评测基线（agent-decision-layer 09）

基线固化日期：2026-08-16（采购比价 2026-08-19 重钉为 v2）
数据驱动化（meta-agent-platform-impl 03）：2026-08-19

## 评测集（DB 真源 `app.agent_eval_cases`，V33 播种 + 版本化）

| 评测集 | 版本 | 内容 | 真源位置 |
|---|---|---|---|
| 采购比价 | `procurement-eval-v3` | 12 例：正常比价、无候选、缺价格、低置信度、schema 负例、camelCase 兼容，以及不可比候选剔除 5 例；输入与输出中的件数均为整数 JSON 值 | `app.agent_eval_cases`（`metric_kind='INVARIANT'`，`status='CONFIRMED'`） |
| 数据查询 | `data-query-eval-v1` | 7 条：歧义澄清 3（SKU-xxx / P-123 / 某履约方）、PII 拒绝 1、可答落地 3（7 天缺货数、SKU 价格、工单缺口） | 同上 |
| 意图识别回归门禁 | —（不新建用例） | 直接复用既有 `MessageInterpretation*Test` 套件（07 票不变式，行为零变化） | `backend/src/test/java/cn/zimu/fulfillment/message/` |

用例不可增删改：换例即换版本号（如 `procurement-eval-v3`）——新增/修改用例 = 修改 `agent_eval_cases`（走定义草稿确认流联动，07 决策 5），并同步更新基线门禁。`expected` 结构按 metric_kind 派生并读取时校验（INVARIANT → `requires_human` / `tool_sequence` / `missing_fields` / `expected_error`），非法用例拒跑并可见（`AgentEvalScorer.loadInvariantCases`）。

## 跑分器与指标

`AgentEvalScorer`（`backend/src/test/java/cn/zimu/fulfillment/agent/eval/`）以 **DB 用例（`loadInvariantCases` 读取并校验）+ 本地 JDK HttpServer stub 模型 + 迷你只读 MCP 注册表（canned 事实）** 运行评测，全程无真实网络、无密钥：

- stub 模型的固定输出（按用例 input 脚本化）在 `AgentEvalStubData`（canned 层，与 `ProcurementPriceEvalTest` 共享）；数据查询 canned 事实与 06 票 Testcontainers 集成测试的数据库种子数字一致（7 天缺货 3 行、SKU-EVAL-000001 进/零售价 12.34/25.60、工单 9005 缺口 23.500）；数据库事实核对由 `DataQueryAgentServiceIntegrationTest` 承担，本跑分器负责可重复指标。
- `AgentEvalScorerTest` / `AgentEvalBaselineTest` 为 Testcontainers 集成测试（完整应用启动加载 DB 用例）；确定性：正确性指标两次运行完全一致（`AgentEvalScorerTest` 断言）；latency 为实际测量（信息性）；token 由 stub 固定注入（每帧 total_tokens=2）。

指标：

| 指标 | 口径 | 基线 |
|---|---|---|
| schema 通过率 | 合法用例解析成功 / 合法用例，负例必须稳定拒绝（AGENT_OUTPUT_INVALID） | 100%（11/11 + 负例 1 拒绝） |
| 工具选择准确率 | 实际工具调用序列 == 预期工具（expected.tool_sequence） | 100%（3/3） |
| 答案数字正确率 | 最终答案包含预期数字（数字来自工具返回值） | 100%（3/3） |
| requires_human 召回 | 低置信度（<0.6）/无候选/缺价格/可比候选空/推荐落被剔除候选/歧义/PII 必须转人工 | 采购 6/6；数据查询门禁 4/4 |
| happy 路径误转人工 | 正常用例不得误转人工 | 0 |
| 写工具零调用不变式 | 评测运行中白名单外写工具零调用；绑定只暴露只读工具 | 0 |
| latency / token | stub 实测毫秒 / stub 注入 token | 信息性（见归档） |

## QUALITY 指标（参考，不进 CI 门禁；meta-agent-platform-impl 09）

与 INVARIANT（stub 跑分器 + CI 基线门禁，确定性）分工：

- **INVARIANT**（本文件上文）：工具序列 / schema 通过率 / requires_human 召回 / 写工具零调用，stub 模型 + DB 用例，`AgentEvalBaselineTest` 钉死基线——CI 只钉这类。
- **QUALITY**（答案质量，真实模型）：由 `QualityEvalService` 按 `(agent_slug, agent_version)` 冻结的 QUALITY 用例集 + 定义 system_prompt 生成 promptfoo 配置（deepseek provider，密钥只经 `DEEPSEEK_API_KEY` 环境变量，绝不入 DB/日志/产物），`NpxPromptfooRunner`（ProcessBuilder 跑 `npx promptfoo eval`）执行，结果回写 `app.agent_eval_results`（`metric_kind='QUALITY'`，按 run_id/用例关联）；异步任务（`QUALITY_EVAL`，Spring Worker）执行并以 `run_mode=PREVIEW` 落 `agent_runs`，不污染 LIVE 统计与 INVARIANT 基线。
- **分工**：QUALITY 是参考指标（供确认人参考、有波动），**不**进入 CI 门禁、不钉基线；失败不阻断草稿确认（落 FAILED 结果 + 观测行后任务收口）。运行形态：`app.quality-eval.enabled=true` 时 `QualityEvalWorker` 按 `app.quality-eval.poll-ms` 领取执行；冒烟 `PROMPTFOO_SMOKE=1` 跑 `PromptfooEvalSmokeTest`（echo provider 免密钥验证配置端到端消费；真实 deepseek 调用需设置 `DEEPSEEK_API_KEY` 后同一配置直接跑）。

## 运行命令

```bash
# 跑分器 + 基线门禁（换模型/提示词/阈值/用例后必跑；需要 Docker 起 Testcontainers 读 DB 用例）
mvn -q test -Dtest='AgentEval*'

# 全部 Agent 回归（05/06/07/09 相关套件）
mvn -q test -Dtest='Agent*'

# 意图识别回归门禁（复用既有 MessageInterpretation* 套件）
mvn -q test -Dtest='MessageInterpretation*'

# 全量
mvn -q test
```

## 结果归档

每次运行结果写入 `backend/target/agent-eval-results/agent-eval-baseline-<yyyyMMdd-HHmmss-SSS>.json`：

- 位置取舍：`target/` 已被 gitignore，归档与测试代码、文档解耦（机器产物不进 git）；文档（本文件）保存人工固化的基线数字。
- 不覆盖旧结果：文件名含时间戳，每次运行独立文件；`AgentEvalScorerTest` 断言归档文件数量单调递增。

## 变更流程（门禁）

1. 改 Agent 提示词 / 模型 / 阈值 / 评测集 → 必须复跑 `mvn -q test -Dtest='AgentEval*'`；
2. 指标达标后提交归档文件哈希/数字到本文件（或票 Answer）；
3. `AgentEvalBaselineTest` 钉死基线数字与版本标识（`procurement-eval-v3` / `data-query-eval-v1` / `agent-foundation-v1` / `data-query-v1` / `procurement-price-v3` / 低置信度阈值 0.6 / 离群倍数 2.0），改版本号/阈值/提示词版本必须同步更新该测试，防止静默回归；
4. 提示词版本号随变更递增（版本即评测基线的一部分）。
