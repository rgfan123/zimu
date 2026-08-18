# 09 — Agent 评测基线

**What to build:** 采购比价与数据查询 Agent 的固定评测集与跑分器 + 意图识别回归门禁，防止“换模型/改提示词后凭感觉变好”。

**Blocked by:** 05 — 采购比价 Agent；06 — 数据查询 Agent；07 — 意图识别 Agent

**Status:** resolved

## 范围

- 评测集（代码内固定 fixture，版本化）：
  - **采购比价**：`input`（ticket_id 或 sku_id + 数量）→ `expected`（候选价格、推荐、requires_human 期望、missing_fields 期望）；
  - **数据查询**：自然语言问题 → `expected_tool_sequence` + `expected_answer_digest`（数字核对）；
  - **意图识别回归**：直接复用既有意图识别测试用例作为回归门禁（07 票不变式）。
- 跑分器（`mvn test` 内的可重复 runner 或独立脚本）输出指标：
  - schema 通过率；工具选择准确率；答案数字正确率；`requires_human` 召回（低置信度必须转人工）；写工具零调用不变式；latency / token；
  - 每次运行结果按版本归档（不覆盖旧结果）。
- 门禁：Agent 提示词/模型/阈值变更必须跑回归；结果可比较版本。

## 非范围

- Langfuse/LangSmith 数据集接入（后续票）；
- 生产流量在线评测。

## 验收标准

- [ ] 评测集可重复运行，结果确定性（mock 模型或固定种子）；
- [ ] 指标覆盖 05/06 两个 Agent 的验收项，含 `requires_human` 召回与写工具零调用断言；
- [ ] 变更提示词/阈值后能产出可比较的量化结果；
- [ ] 意图识别回归门禁全绿；
- [ ] 文档记录基线数字与运行命令。

## 验证原则

- 评测必须与人工感觉解耦：先定基准，再谈优化；
- 结果版本化，不覆盖历史。

## Answer

主开发与验证（2026-08-16，subagent 完成）：

### 对 05/06 已交付文件的最小改动（引用 fixture 所需，先说明理由）

1. `ProcurementPriceEvalTest.java`（05）：把内嵌私有 `EvalCase`/`EVAL_CASES` 原样抽到新类 `ProcurementPriceEvalFixture.java`（同包，版本 `procurement-eval-v1`），测试改为只读引用。理由：09 票要求「评测集（代码内固定 fixture，版本化）」——7 例 JSON 若在跑分器中再拷贝一份必然漂移，抽取共享 fixture 是唯一不重复的引用方式；用例内容零改动（行为不变）。
2. `DataQueryAgentEvalFixture.java`（06）：仅加一行 `VERSION = "data-query-eval-v1"` 并把类/成员改为 public（跨包只读引用所需）。理由同上——版本标识必须是唯一来源。
3. 主代码零改动；07 文件零改动；其他票文件未动。

### 评测集版本

| 评测集 | 版本 | 内容 | 位置 |
|---|---|---|---|
| 采购比价 | `procurement-eval-v1` | 7 例：正常比价（ticket/sku 输入）×2、无候选、缺价格、低置信度+字段缺失、camelCase 兼容、schema 不符负例 | `ProcurementPriceEvalFixture`（05 测试与 09 跑分器共同只读引用） |
| 数据查询 | `data-query-eval-v1` | 7 条：歧义澄清 3 + PII 拒绝 1 + 可答落地 3 | `DataQueryAgentEvalFixture`（既有，加版本常量） |
| 意图识别回归门禁 | 不新建用例 | 直接复用既有 `MessageInterpretation*Test` 套件（07 不变式） | `message/` 包 |

### 跑分器设计与指标

`AgentEvalScorer`（`backend/src/test/java/cn/zimu/fulfillment/agent/eval/`，mvn test 内可重复 runner）：本地 JDK HttpServer stub 模型 + 迷你只读 MCP 注册表（canned 事实），无真实网络/无数据库/无密钥；canned 数字与 06 票 Testcontainers 数据库种子一致（7 天缺货 3 行、SKU-EVAL-000001 12.34/25.60、工单 9005 缺口 23.500）。指标：schema 通过率、工具选择准确率、答案数字正确率、requires_human 召回、happy 路径误转人工数、写工具零调用不变式、avg latency（实测，信息性）、total tokens（stub 固定注入，每帧 2）。确定性：正确性指标两次运行完全一致（`AgentEvalScorerTest` 断言）。

### 基线数字（2026-08-16 固化）

- 采购 `procurement-eval-v1`：schema 通过率 100%（合法 6/6 + 负例稳定拒绝 AGENT_OUTPUT_INVALID）、requires_human 召回 3/3、happy 路径误转人工 0、写工具零调用 0、avg latency ~1-24ms、tokens 14。
- 数据查询 `data-query-eval-v1`：工具选择准确率 3/3、答案数字正确率 3/3、门禁路径（歧义 3 + PII 1）requires_human 召回 4/4、写工具零调用 0、avg 模型路径 latency ~6-16ms、tokens 12。

### 归档方式

每次运行写入 `backend/target/agent-eval-results/agent-eval-baseline-<yyyyMMdd-HHmmss-SSS>.json`：`target/` 已 gitignore、与测试/文档解耦（机器产物不进 git），文件名含时间戳绝不覆盖旧结果（`AgentEvalScorerTest` 断言文件数单调递增）；文档 `docs/agent-eval-baseline.md` 保存人工固化的基线数字与运行命令。

### 门禁断言（`AgentEvalBaselineTest`，8 例）

- 采购：schema 通过率 == 1.0（6/6 + 负例 1）、召回 == 1.0（3/3）、happy 误转人工 == 0、写工具零调用 == 0；
- 数据查询：工具选择 == 1.0（3/3）、数字正确率 == 1.0（3/3）、门禁召回 == 1.0（4/4）、写工具零调用 == 0；
- 版本/阈值钉死：`procurement-eval-v1` / `data-query-eval-v1` / `agent-foundation-v1` / `data-query-v1` / `LOW_CONFIDENCE_THRESHOLD == 0.6`——改版本号/提示词/阈值必须显式更新并复跑回归，防静默回归。

### 测试类与通过数

- 新增 `AgentEvalScorerTest` 3 例（归档不覆盖、确定性、指标合理）+ `AgentEvalBaselineTest` 8 例 = 11 例全绿；
- 既有 `ProcurementPriceEvalTest`（05，fixture 抽取后行为零变化）、`DataQueryAgentServiceIntegrationTest`（06）等全部保持绿。

### 回归结果

- `mvn -q test-compile` 通过；
- `mvn -q test -Dtest='AgentEval*'`：11 例全绿；
- `mvn -q test -Dtest='Agent*'`：90 例全绿（0 failures/0 errors/0 skipped）；
- `mvn -q test -Dtest='MessageInterpretation*'`（意图识别门禁）：23 例全绿；
- 全量 `mvn -q test`：720 例 / 0 failures / 0 errors / 7 skipped（与 07 票 709 例口径一致，新增 11 例），BUILD SUCCESS。仅有 Testcontainers 关闭期的 Hikari 连接校验 WARN 与 surefire 自 fork 退出提示（均出现在 System.exit 之后、exit 0，与用例无关）。

### 遗留事项

- 真实模型（非 stub）下的 latency/token 基线留待模型接入后补测（stub token 为固定注入，仅做确定性占位）；
- Langfuse/LangSmith 数据集接入、生产流量在线评测按票「非范围」留后续票；
- 归档结果累积于 target/，如需长期留存建议后续接 CI artifact 保存。
