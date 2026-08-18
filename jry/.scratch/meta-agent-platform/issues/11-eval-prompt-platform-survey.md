# 11 — 评测与 Prompt 管理平台调研

**Type:** research
**Status:** resolved
**Blocked by:** —

## Question

07 票（评测用例数据化）要不要借现成的 prompt 管理 / 评测平台，还是评测用例与跑分全部留在 DB 自研（延续现有 `AgentEvalScorer` + 09 基线门禁）？本票只查事实，不做决策。

**背景与已有边界**：

- 地图已把 **Langfuse/LangSmith 的「观测接入」列为 out of scope**（延续 agent-decision-layer 08 非目标）。但「prompt 真源 / 评测用例托管」是**另一件事**，从未有过结论——本票要把这两件事分开判。
- 03 票刚定：`agent_definitions` 是 Agent 定义（含 `system_prompt` / `prompt_version`）的**唯一真源**。任何把 prompt 真源外置的方案都会构成第二控制面，与该结论直接冲突（同 Dify/Flowise 的排除理由）。
- **关键事实（已核实）**：现有评测资产**全部在 `src/test/` 下**——`backend/src/test/java/cn/zimu/fulfillment/agent/eval/`（`AgentEvalScorer` / `AgentEvalScorerTest` / `AgentEvalBaselineTest`）+ `DataQueryAgentEvalFixture` + `ProcurementPriceEvalFixture` / `ProcurementPriceEvalTest`。也就是说今天的评测是 **JUnit/CI 期资产，不是运行期服务**。而地图要的「草稿提交时自动跑评测门禁」是运行期能力——这个位移本身就是 07 的核心难点，本票需为它提供选型事实。

**待查事实**：

1. **现有能力缺口**（先做这条，别为引平台而引平台）：读 `AgentEvalScorer` + `AgentEvalBaselineTest` + 两个 Fixture，写清楚今天能跑什么（打分维度、基线钉的是什么——已知钉工具调用序列）、以及「用例数据化 + 运行期跑分」相对现状缺什么。
2. **候选清单与 JVM 可用性**：Langfuse（prompt management + datasets/evals）、Braintrust、PromptLayer、promptfoo、DeepEval、OpenAI Evals，以及 LangChain4j 自带的评测能力（若有）。**重点核实是否 Java/JVM 可用**——多数是 Python/TS 生态，若无 Java SDK 且只能走 REST，成本要说清。此项结论若为否，基本可判死大半候选。
3. **self-host 与数据出境**：各候选能否自托管；评测用例含真实业务数据（订单/供应商/价格），SaaS 上传是否触红线。注意红线：密钥/凭据绝不进 DB、日志、DTO。
4. **第二控制面判定**：逐个判断该方案是否要求把 prompt 或 Agent 定义的真源移出 `agent_definitions`。要求真源外置的，直接标记为与 03 冲突。
5. **中间路线（重点）**：把离线评测框架（promptfoo / DeepEval 之流）**只当 CI 工具用、不交出真源**——用例仍存 DB，导出后喂给框架跑分——是否可行？这是「借力但不外置真源」的路，若成立可能是最优解，需要具体到怎么接。
6. **运行期跑分的形态**：若最终自研，「草稿提交时自动跑评测门禁」应该在哪跑（同进程同步 / 异步任务 / 仍走 CI）？各候选平台对这个场景是否有现成支持。

**产出**：findings 写 `.scratch/meta-agent-platform/research/11-eval-prompt-platforms.md`，结论写回本票 `## Answer`，并更新地图 Decisions-so-far。

## Answer

（findings 全文见 `research/11-eval-prompt-platforms.md`，2026-08-18 一手核实）

1. **现有能力缺口**：今天能跑的是确定性不变式评测（两组 fixture，`procurement-eval-v1` 7 例 / `data-query-eval-v1` 7 条；指标 = schema 通过率、工具选择准确率、答案数字正确率、requires_human 召回、写工具零调用，基线全 100% 钉死在 `AgentEvalBaselineTest`，模型为本地 stub、DB 事实 canned）——测的是**管线/不变式，测不出提示词质量**。「用例数据化 + 运行期跑分」缺：① 用例数据载体（fixture 是 Java 常量，`agent_eval_cases` 表不存在）；② 运行期服务入口（`AgentEvalScorer` 是测试期 static）；③ 运行期门禁判定层（现在是 JUnit 断言，结果只归档到 gitignored `target/`）；④ per-version 关联（fixture 只引用 `PROMPT_VERSION` 常量，未关联 `agent_definitions` 行）；⑤ 管理面与异步执行设施。这些**无论选不选平台都必须在自研侧解决**。
2. **JVM 可用性（结论表见 research 文档 §2.8）**：JVM 可用候选仅两个——**Langfuse**（官方 Java 客户端 `com.langfuse:langfuse-java:0.2.0`，MIT，可完全自托管，覆盖 prompts/datasets/score 全 REST）与 **Braintrust**（官方 JVM SDK `dev.braintrust:braintrust-sdk-java:0.3.20`，MIT/BETA，可 JVM 内跑 eval loop）。其余判死或降级：**PromptLayer**（无 Java SDK，自托管 Enterprise-only）、**OpenAI Evals**（官方停摆，官方推荐迁移 promptfoo）、**DeepEval**（纯 Python）、**LangChain4j 自带评测**（1.19 官方线无评测模块，Central 无 `langchain4j-evaluation`，docs 是占位页——不存在可借能力）。
3. **self-host 与数据出境**：评测用例含真实业务数据；只有 **Langfuse 完全自托管（MIT）** 与 **promptfoo/DeepEval 本地执行** 能彻底避免数据出境；Braintrust 自托管是**混合模式**（数据面进自家云，控制面仍 Braintrust 托管），PromptLayer 自托管仅企业版——两者对单租户内部系统偏重。若用例用脱敏/合成数据（现有 fixture 即如此），上云风险可控。
4. **第二控制面判定**：Langfuse prompt 管理、Braintrust Prompts、PromptLayer Registry 的定位都是「在平台维护 prompt 真源 + 运行时取用」→ **若当 prompt 真源一律与 03 冲突**；promptfoo/DeepEval 的 YAML/测试代码**仅当生成物不冲突**（每次跑分由 DB 重新生成，不做人工编辑源）；另外 Langfuse/Braintrust 的 **datasets 托管会把用例真源也外置**，与 07 的 DB 方向冲突。分界线 = 「真源托管」（冲突）vs 「跑分执行器」（不冲突）。
5. **中间路线（成立，首选）**：把 promptfoo 只当 CI/本地**跑分执行器**——用例存 DB（07 的 `agent_eval_cases`）、被测 prompt 存 `agent_definitions`（03）、每次跑分由 DB 状态**生成** promptfoo YAML（含 `deepseek:deepseek-chat` 原生 provider、`llm-rubric`/`javascript` 断言、密钥走环境变量），`npx promptfoo eval -c generated.yaml --output results.json` 本地执行，解析回写 DB 并比对钉死基线。JVM 侧仅 `ProcessBuilder` + YAML 生成器，零网络依赖。**注意限制**：现有基线钉的**工具调用序列不是 promptfoo 天然断言对象**——建议 07 把「管线/不变式指标（JUnit/自研，stub 模型）」与「质量指标（外部框架，真实模型）」分开。DeepEval 同思路但要多养 Python 环境；Braintrust Java SDK 是 JVM 原生变体但结果须进 Braintrust（SaaS 依赖）。
6. **运行期跑分形态**：自研应取**异步任务**（提交草稿 → 落任务记录 → 后台 `@Async`/任务表跑分 → 结果回写，配合 03 已预留的 `agent_runs.run_mode=PREVIEW`），同步只留预览/调试入口，CI 形态保留给 09 基线门禁（两者并存）。候选平台中 Langfuse（scheduled evals）与 Braintrust（remote evals/online scoring）对该场景有原生支持，但都以交出用例/结果为代价。

**一句话结论**：没有任何候选能同时满足「03 唯一真源 + 运行期评测」——**真源（prompt/用例）必须留在 DB 自研**；平台侧最优解是 **promptfoo 走生成物中间路线当跑分执行器**（借力不外置），Langfuse 完全自托管可作观测/展示增强，Braintrust 的 JVM SDK 是接受 SaaS 依赖时的次选。
