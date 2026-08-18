# Agent 评测基线（agent-decision-layer 09）

基线固化日期：2026-08-16

## 评测集（代码内固定 fixture，版本化）

| 评测集 | 版本 | 内容 | 位置 |
|---|---|---|---|
| 采购比价 | `procurement-eval-v1` | 7 例：正常比价（ticket / sku 输入）、无候选、缺价格、低置信度+字段缺失、schema 不符（负例）、camelCase 模型输出兼容 | `backend/src/test/java/cn/zimu/fulfillment/agent/procurement/ProcurementPriceEvalFixture.java`（05 票测试只读引用） |
| 数据查询 | `data-query-eval-v1` | 7 条：歧义澄清 3（SKU-xxx / P-123 / 某履约方）、PII 拒绝 1、可答落地 3（7 天缺货数、SKU 价格、工单缺口） | `backend/src/test/java/cn/zimu/fulfillment/agent/DataQueryAgentEvalFixture.java` |
| 意图识别回归门禁 | —（不新建用例） | 直接复用既有 `MessageInterpretation*Test` 套件（07 票不变式，行为零变化） | `backend/src/test/java/cn/zimu/fulfillment/message/` |

评测集不可增删改：换例即换版本号（如 `procurement-eval-v2`），并同步更新基线门禁。

## 跑分器与指标

`AgentEvalScorer`（`backend/src/test/java/cn/zimu/fulfillment/agent/eval/`）以 **本地 JDK HttpServer stub 模型 + 迷你只读 MCP 注册表（canned 事实）** 运行评测，全程无真实网络、无数据库、无密钥：

- 数据查询 canned 事实与 06 票 Testcontainers 集成测试的数据库种子数字一致（7 天缺货 3 行、SKU-EVAL-000001 进/零售价 12.34/25.60、工单 9005 缺口 23.500）；数据库事实核对由 `DataQueryAgentServiceIntegrationTest` 承担，本跑分器负责可重复指标。
- 确定性：正确性指标两次运行完全一致（`AgentEvalScorerTest` 断言）；latency 为实际测量（信息性）；token 由 stub 固定注入（每帧 total_tokens=2）。

指标：

| 指标 | 口径 | 基线 |
|---|---|---|
| schema 通过率 | 合法用例解析成功 / 合法用例，负例必须稳定拒绝（AGENT_OUTPUT_INVALID） | 100%（6/6 + 负例 1 拒绝） |
| 工具选择准确率 | 实际工具调用序列 == 预期工具 | 100%（3/3） |
| 答案数字正确率 | 最终答案包含预期数字（数字来自工具返回值） | 100%（3/3） |
| requires_human 召回 | 低置信度（<0.6）/无候选/缺价格/歧义/PII 必须转人工 | 采购 3/3；数据查询门禁 4/4 |
| happy 路径误转人工 | 正常用例不得误转人工 | 0 |
| 写工具零调用不变式 | 评测运行中白名单外写工具零调用；绑定只暴露只读工具 | 0 |
| latency / token | stub 实测毫秒 / stub 注入 token | 信息性（见归档） |

## 运行命令

```bash
# 跑分器 + 基线门禁（换模型/提示词/阈值后必跑）
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
3. `AgentEvalBaselineTest` 钉死基线数字与版本标识（`procurement-eval-v1` / `data-query-eval-v1` / `agent-foundation-v1` / `data-query-v1` / 低置信度阈值 0.6），改版本号/阈值/提示词版本必须同步更新该测试，防止静默回归；
4. 提示词版本号随变更递增（版本即评测基线的一部分）。
