# 11 — 评测与 Prompt 管理平台调研（findings）

> 调研日期：2026-08-18。方法：一手来源为主——官方文档/仓库/包注册表（GitHub API、repo1.maven.org、npm registry、PyPI、官方 docs），辅以搜索结果交叉验证。本票只查事实，不做决策；决策归属主 session 的 07 票。
>
> 判定口径（沿用 map 已核实边界）：
> - 「观测接入」Langfuse/LangSmith 已列为 out of scope（agent-decision-layer 08 非目标），本票**不重复判**；
> - 本票只判「prompt 真源 / 评测用例托管」这一件事；
> - 03 票已定 `agent_definitions` 是 Agent 定义（含 `system_prompt` / `prompt_version`）**唯一真源**——prompt 真源外置 = 第二控制面 = 与 03 冲突；
> - 红线：密钥/凭据绝不进 DB、日志、DTO（沿用 SecretRedactor / AgentPayloadRedactor）；评测用例含真实业务数据（订单/供应商/价格）。

---

## 0. 结论速览（TL;DR）

| 候选 | JVM 可用 | 自托管 | 许可 | 数据/评测能力 | 第二控制面 | 本票判断 |
|---|---|---|---|---|---|---|
| **Langfuse** | ✅ 官方 Java 客户端 `com.langfuse:langfuse-java:0.2.0`（覆盖 prompts/datasets/score 全 REST） | ✅ 完全自托管（MIT，Docker，v4.x） | MIT 核心 + 全部产品功能（2025-06 起）；仅企业安全/平台功能商业授权 | prompt 管理（版本/label/实验）+ datasets + LLM-as-judge evals + 标注队列 + playground | ⚠️ 若用其 prompt 管理当**真源**则与 03 冲突；仅当 datasets/评分托管也构成「用例真源外置」（与 07 DB 方向冲突） | 强候选，但只能当「观测/托管增强」用，不能当任何真源 |
| **Braintrust** | ✅ **官方 JVM SDK** `dev.braintrust:braintrust-sdk-java:0.3.20`（MIT，BETA）——JVM 内跑 eval loop（cases + taskFunction + scorers） | ⚠️ 半自托管：数据面（API+PG+Redis+S3+Brainstore）用 Terraform 部署进自家云，**控制面仍由 Braintrust 托管**（混合模式，企业向） | SDK MIT；平台本身**闭源 SaaS** | datasets + experiments + prompts（playground）+ remote evals + online scoring | ⚠️ 同 Langfuse：任何真源外置都冲突 | 唯一 JVM 原生 eval runner，但平台闭源 + 自托管混合，业务数据落自家云仍有控制面依赖 |
| **PromptLayer** | ❌ 无官方 Java SDK（Python/TS + REST API） | ⚠️ **Enterprise-only** 自托管（闭源商业） | 闭源 SaaS（开源部分无） | Prompt Registry（版本/审批）+ datasets + SDK evals + observability | ⚠️ 其核心卖点就是 prompt registry 当真源 | 判死：无 Java SDK + 自托管仅企业版 |
| **promptfoo** | ⚠️ 无 Java SDK，但**纯本地 CLI/npm 包 + JSON 输出**，JVM 侧 ProcessBuilder 可驱动；原生 **DeepSeek provider** | ✅ 完全本地（CLI + 本地/自托管 web viewer，ghcr.io 镜像） | MIT（现属 OpenAI 旗下，2026-03 收购） | 声明式 YAML config + assertions（含 model-graded/llm-rubric/javascript）+ CI/CD 集成 + agents 评测 | ✅ **不构成**——YAML 只当生成物，真源仍在 DB（见 §5） | **中间路线首选**（借力但不外置真源） |
| **DeepEval** | ❌ 纯 Python（pytest 生态），Apache-2.0 | ✅ 本地库（无服务端）；Confident AI 为可选 SaaS | Apache-2.0 | 50+ 指标（G-Eval/factual correctness 等）+ LLM-judge + 自定义模型（DeepEvalBaseLLM/gateway） | ✅ 不构成（同 promptfoo，生成物） | 可行但 JVM 侧要养 Python 环境，接法比 promptfoo 重 |
| **OpenAI Evals** | ❌ 纯 Python | ✅ 本地库 | MIT（repo 现 NOASSERTION） | 平台化 eval（model-graded） | ✅ 不构成 | **判死：OpenAI 官方已停摆该产品**，官方推荐迁移到 promptfoo |
| **LangChain4j 自带评测** | — | — | Apache-2.0（lc4j 本身） | **1.19 官方线没有评测模块**：Central 无 `langchain4j-evaluation`；docs「Testing and Evaluation」是占位页；仅 `langchain4j-test`（guardrail 断言）与 Quarkus 侧 `quarkus-langchain4j-testing-evaluation-core`（不适用 Spring Boot） | — | **结论：自带评测能力基本不存在**，不能当候选，只能自研 |

**一句话**：JVM 可用候选只有 **Langfuse（官方 Java 客户端）** 与 **Braintrust（官方 JVM SDK）**，但两者都会造成「真源/用例外置」问题；**没有任何候选能既满足 03 唯一真源、又能提供运行期评测**——自研（DB 用例 + 跑分器）在真源侧不可避免，外部平台只能当「跑分执行器」（promptfoo 中间路线）或「观测/展示增强」（Langfuse）。

---

## 1. 现有能力缺口（先读代码）

### 1.1 今天能跑什么（仓库内一手核实）

现有评测资产全在 `src/test/` 下（`backend/src/test/java/cn/zimu/fulfillment/agent/`）：

| 资产 | 位置 | 角色 |
|---|---|---|
| `AgentEvalScorer` | `eval/AgentEvalScorer.java` | 09 跑分器：跑两组评测 → 算指标 → 归档 JSON（`target/agent-eval-results/agent-eval-baseline-<时间戳>.json`，**绝不覆盖历史**） |
| `AgentEvalBaselineTest` | `eval/AgentEvalBaselineTest.java` | 09 基线门禁：断言指标 = 100%、钉死版本号与 `LOW_CONFIDENCE_THRESHOLD=0.6` |
| `AgentEvalScorerTest` | `eval/AgentEvalScorerTest.java` | 跑分器自身单测 |
| `ProcurementPriceEvalFixture` | `procurement/ProcurementPriceEvalFixture.java` | `procurement-eval-v1`，7 例（输入 JSON + **脚本化模型输出** + 预期 requires_human） |
| `DataQueryAgentEvalFixture` | `DataQueryAgentEvalFixture.java` | `data-query-eval-v1`，7 条查询（可答 3 / 澄清 3 / PII 拒绝 1） |
| `ProcurementPriceEvalTest` | `procurement/ProcurementPriceEvalTest.java` | 05 票单测（引用同一 fixture，避免双份维护漂移） |

**打分维度（确定性指标）**：
- 采购比价（7 例）：schema 通过率（合法解析 + 负例稳定拒绝）、requires_human 召回、happy 路径误转人工数、写工具零调用不变式；avg latency（信息性）、tokens（stub 固定注入，确定性）。
- 数据查询（7 条）：工具选择准确率、答案数字正确率、门禁路径（澄清 3 + PII 1）requires_human 召回、写工具零调用不变式；avg 模型路径 latency、tokens。

**基线钉的是什么（已知钉工具调用序列）**：基线断言把「预期工具调用序列」写死在 fixture（`expectedTool()` 按问题映射到 `list_procurement_tickets` / `search_skus` / `get_procurement_ticket`）+ 跑分器里的 `scriptedToolCalls()`（stub 模型第一轮按问题返回脚本化工具调用）+ `answerNumbersMatch()`（stub 最终答案取自 canned 工具事实，核对关键数字）。同时钉死 `PROMPT_VERSION`（`agent-foundation-v1` / `data-query-v1`）与评测集版本号——**改提示词/换模型/改阈值必须复跑并显式更新基线**（09 门禁语义）。

**确定性来源**：模型 = 本地 JDK `HttpServer` stub（脚本化工具调用 + 固定输出，usage 固定 1/1/2）；DB 事实 = 迷你只读 MCP 注册表 canned 事实（与 06 票 Testcontainers 集成测试种子数字一致）。跑分器**不断言**，只计算+归档；断言在 `AgentEvalBaselineTest`。

**运行形态**：纯 JUnit/CI 期资产（`mvn test -Dtest='AgentEval*'`），不是运行期服务。

### 1.2 「用例数据化 + 运行期跑分」相对现状缺什么

1. **用例没有数据载体**：两组用例是 Java 常量（fixture），增删改 = 改代码 + 换版本号 + 改基线断言。07 的 `agent_eval_cases` 表（关联定义版本、用例类型 input/expected、状态 待确认/已确认）不存在。→ 需要建表 + 迁移（fixture → DB 行）+ 确认流程。
2. **没有运行期入口**：`AgentEvalScorer.compute()` 是测试期 static 工具；草稿提交时自动跑评测需要可注入的**服务**（可带 `AgentDefinition` 参数、返回可判定 pass/fail 的结构），且要与 03 的草稿/确认流程挂钩（draft 提交触发）。
3. **当前 stub 模型测不了「提示词质量」**：确定性 stub 只能验证**管线/不变式**（schema、工具序列、转人工、写工具零调用），测不出「新提示词真的更好」——后者需要真实模型调用 + LLM-as-judge 或人工标注。这是位移后评测语义要升级的点（07 需决策：门禁继续只钉不变式，还是引入质量指标）。
4. **门禁判定是测试断言，不是运行期判定**：`AgentEvalBaselineTest` 靠 JUnit 断言；运行期门禁需要「跑分结果 vs 基线阈值」的判定层 + 报告（哪些用例过/挂、指标差多少）+ 结果持久化（现在只归档到 gitignored 的 `target/`）。
5. **无 per-agent / per-version 关联**：fixture 只引用 `PROMPT_VERSION` 常量，未与 `agent_definitions` 行（版本快照）关联；数据化后需要外键关联定义版本，才能对「某个草稿版本」跑分。
6. **无管理面**：用例增删改/确认、跑分结果查询都没有 API/UI（map「管理 REST API 一期」的评测部分为空）。
7. **无异步执行基础设施**：跑分 = N 例 × 模型调用，秒级到分钟级，草稿提交同步跑会阻塞请求（§6）。

> 结论（先于选型）：**无论选不选平台，1/2/4 都必须在自研侧解决**——DB 表、运行期服务、判定与持久化。平台候选只能回答「用例托管去哪」「跑分执行器用谁的」。

---

## 2. 候选清单与 JVM 可用性（重点）

### 2.1 Langfuse —— ✅ 有官方 Java 客户端，可完全自托管

- **是什么**：LLM 工程平台：tracing/观测 + **prompt 管理**（版本、labels、prompt experiments、playground）+ **datasets**（评测用例集）+ **LLM-as-a-judge evals**（managed，2025-06 起 MIT 开源）+ annotation queues（人工标注/审核）。
- **版本**：docker tag `langfuse/langfuse:4.12`（v4.x）；[langfuse/langfuse](https://github.com/langfuse/langfuse)（33k★，2026-08-18 仍在更新）。
- **许可**：GitHub license 字段 NOASSERTION（开源核心模型）。官方博客《Doubling Down on Open Source》（2025-06-04）：**全部产品功能（含 LLM-as-judge evaluations、playground、prompt experiments、标注队列）MIT 开源**；商业授权仅限企业安全/平台功能（SCIM、Audit Logs、数据保留策略；普通 SSO 也是 MIT）。→ 自托管免费且完整。
- **JVM 可用性**：✅ 官方仓库 [langfuse/langfuse-java](https://github.com/langfuse/langfuse-java)（MIT）：`com.langfuse:langfuse-java:0.2.0`（Maven Central，lastUpdated 2026-02-25）。auto-generated 全量 API 客户端（基于官方 fern spec），资源组覆盖 `prompts` / `promptversion` / `datasets` / `datasetitems` / `datasetrunitems` / `score` / `scoreconfigs` / `trace` / `ingestion` / `annotationqueues` / `llmconnections` / `models` 等（仓库 tree 一手核实）。支持 `LangfuseClient.builder().url("http://localhost:3000")` 连自托管实例；另有 `AsyncLangfuseClient`。README 示例即 `client.prompts().list()`。
- **边界**：langfuse-java 是 **API 客户端**（管理/上报/查询），**不是** LLM-judge eval 的执行器——managed evals 在 Langfuse 服务端（self-host 上跑 scheduled LLM-judge）或经官方 Python/TS SDK 跑。Java 侧可全程驱动 datasets + score 上报 + 查询结果，但「跑分执行」要么用服务端 evals，要么自研执行器把分数喂回去。
- **证据**：[langfuse-java README](https://github.com/langfuse/langfuse-java/blob/main/README.md)、[Maven Central 元数据](https://repo1.maven.org/maven2/com/langfuse/langfuse-java/maven-metadata.xml)、[开源博客](https://js-sdk-v4-docs-snapshot.langfuse.com/blog/2025-06-04-open-sourcing-langfuse-product/)、[prompt 管理文档](https://langfuse.com/docs/prompt-management/features/mcp-server)。

### 2.2 Braintrust —— ✅ 官方 JVM SDK，可 JVM 内跑 eval；平台闭源、自托管为混合模式

- **是什么**：AI 评测 + 观测平台：datasets、experiments、prompts（playground）、scorers/classifiers、**remote evals**（平台托管跑分）、**online scoring**、human review、dashboards（docs 导航一手核实）。
- **JVM 可用性**：✅ **官方** [braintrustdata/braintrust-sdk-java](https://github.com/braintrustdata/braintrust-sdk-java)（MIT，BETA，20★）：`dev.braintrust:braintrust-sdk-java:0.3.20`（Maven Central，lastUpdated 2026-08-14，活跃）。README quickstart 即 **JVM 内完整 eval loop**：`braintrust.evalBuilder().name(...).cases(DatasetCase.of(input, expected)...).taskFunction(...).scorers(Scorer.of("exact_match", (expected, result) -> ...)).build().run()` → 返回报告。另有 `braintrust-java-agent`（javaagent 自动插桩，属观测，out of scope）。官方博客明确「AI observability and evals for the JVM」。
- **许可与自托管**：SDK MIT；**平台本体闭源 SaaS**。官方文档 [Self-hosting Braintrust](https://www.braintrust.dev/docs/admin/self-hosting)：提供**混合自托管**——数据面（Braintrust API + PostgreSQL + Redis + 对象存储 + Brainstore 查询引擎）用 Terraform 部署进**自家云**（AWS ECS/EC2 或 GCP/Azure K8s），但**控制面（web UI、认证、元数据）仍由 Braintrust 托管**；另有 BYOC（Braintrust 代运营自家云数据面）。即：**不存在全开源自托管**，数据可留自家云但平台依赖 Braintrust 托管。
- **证据**：[Java SDK 仓库](https://github.com/braintrustdata/braintrust-sdk-java)、[Java SDK 官方博客](https://www.braintrust.dev/blog/java-sdk)、[自托管文档](https://www.braintrust.dev/docs/admin/self-hosting)、[部署选项](https://www.braintrust.dev/docs/admin/deployment)、[Maven Central 元数据](https://repo1.maven.org/maven2/dev/braintrust/braintrust-sdk-java/maven-metadata.xml)。

### 2.3 PromptLayer —— ❌ 无 Java SDK；自托管 Enterprise-only → 判死

- **是什么**：prompt 管理（**Prompt Registry**：版本/审批/发布）+ 观测 + datasets + **SDK evals**（dataset + runner + columns + scorers，可在自家 infra 跑）。
- **JVM 可用性**：❌ 无官方 Java SDK（官方 SDK 为 Python/TS，另有 REST API、webhooks）。→ JVM 只能走 REST。
- **自托管与许可**：官方文档 [Self-Hosted PromptLayer](https://docs.promptlayer.com/self-hosted) 明确 **Enterprise-only 功能**（商业授权；前端 + 后端 API + PostgreSQL + Redis/Valkey + 对象存储 + 代码执行器），需联系销售。→ 对单租户内部系统不现实。
- **证据**：[docs.promptlayer.com/self-hosted](https://docs.promptlayer.com/self-hosted)、[SDK evals 文档](https://docs.promptlayer.com/sdks)。

### 2.4 promptfoo —— ⚠️ 无 Java SDK，但纯本地 CLI + JSON，JVM 可驱动；原生 DeepSeek provider

- **是什么**：声明式评测 CLI（YAML config：`prompts` + `providers` + `tests` + `assertions`）+ 本地/自托管结果 viewer；**2026-03 被 OpenAI 收购**（docs 站页脚「Promptfoo is part of OpenAI」，[TheNextWeb](https://thenextweb.com/news/openai-acquires-promptfoo-ai-security-frontier)、[gigazine](https://gigazine.net/gsc_news/en/20260310-openai-to-acquire-promptfoo)），仍 MIT 开源、可自托管（ghcr.io/promptfoo/promptfoo 镜像 + [Issue #4521](https://github.com/promptfoo/promptfoo/issues/4521) 工作流：CLI 跑分 → 结果同步进自托管 viewer）。
- **版本/许可**：npm `promptfoo@0.122.0`（2026-08）；repo [promptfoo/promptfoo](https://github.com/promptfoo/promptfoo) MIT、TypeScript、24k★、未归档、`ghcr.io/promptfoo/promptfoo` 官方镜像存在。
- **JVM 可用性**：无 Java SDK，但本质是**本地可执行工具**：`npx promptfoo eval -c config.yaml --output results.json`（CLI/库，[node package 文档](https://www.promptfoo.dev/docs/usage/node-package/)）。JVM 侧用 `ProcessBuilder`/CI 步骤调用即可，无网络依赖、无服务端、数据不出本地。**原生 DeepSeek provider**（docs providers 列表含 DeepSeek，repo 描述「Compare performance of GPT, Claude, Gemini, **DeepSeek**, and more」）——对本品（DeepSeek 兼容端点）零适配成本。
- **断言能力**：`equals` / `contains` / `javascript` / `python` / `llm-rubric`（LLM-as-judge）/ `model-graded-*` 等；支持 agents 评测（repo 描述「Test your prompts, **agents**, and RAGs」）；CI/CD 官方集成（GitHub Actions、Jenkins、CircleCI、Azure Pipelines 等）。
- **证据**：[GitHub 仓库](https://github.com/promptfoo/promptfoo)、[DeepSeek provider](https://www.promptfoo.dev/docs/providers/openai/)、[node package 用法](https://www.promptfoo.dev/docs/usage/node-package/)、[CI/CD 文档](https://www.promptfoo.dev/docs/integrations/ci-cd/)、[自托管 Issue #4521](https://github.com/promptfoo/promptfoo/issues/4521)。

### 2.5 DeepEval —— ❌ 纯 Python；本地库，可作为 CI 工具但 JVM 侧要养 Python

- **是什么**：LLM 评测框架（pytest 生态），50+ 指标（G-Eval、factual correctness、faithfulness、上下文相关性等）+ LLM-as-judge + 自定义模型（`DeepEvalBaseLLM`/gateway 支持自定义 provider，可指向 OpenAI 兼容端点）。Confident AI 是可选 SaaS 看板，非必需。
- **版本/许可**：PyPI `deepeval@4.1.8`；repo [confident-ai/deepeval](https://github.com/confident-ai/deepeval) **Apache-2.0**、Python、17.6k★、2026-08-17 仍在更新。
- **JVM 可用性**：❌ 无 Java SDK / REST 服务端（本地库）；JVM 集成只能「导出用例 → 生成 pytest 测试文件 → 在 Python 环境跑 → 解析输出」。可行（生成物路线，§5.3），但比 promptfoo 多养一个 Python 运行时。
- **证据**：[GitHub 仓库](https://github.com/confident-ai/deepeval)、[自定义模型 gateway](https://github.com/confident-ai/deepeval/blob/c399fb4034ae7a321544826f5fcc6624abf9cc57/deepeval/models/llms/gateway_model.py)。

### 2.6 OpenAI Evals —— ❌ 判死（官方已停摆）

- **是什么**：OpenAI 平台化评测（eval templates + model-graded + platform dashboard），repo [openai/evals](https://github.com/openai/evals)（Python，19k★，未归档但 pushed 2026-04 已近乎停滞）。
- **关键事实**：OpenAI 官方 cookbook 文档《Moving from OpenAI Evals to Promptfoo》：**「OpenAI is winding down the Evals product and recommends Promptfoo」**——产品停摆，官方推荐迁移到 promptfoo，并支持把既有 eval 导出为 promptfoo 配置。
- **JVM 可用性**：❌ 纯 Python 生态，且产品在收尾。**无考察价值**。
- **证据**：[openai-cookbook 迁移文档](https://raw.githubusercontent.com/openai/openai-cookbook/main/examples/evaluation/moving-from-openai-evals-to-promptfoo.md)。

### 2.7 LangChain4j 自带评测 —— ❌ 基本不存在（1.19 官方线核实）

- **官方 docs**：Testing and Evaluation 页（[docs.langchain4j.dev/tutorials/testing-and-evaluation](https://docs.langchain4j.dev/tutorials/testing-and-evaluation/)）目前是**占位页**（只列参考文章 + 「More information coming soon」）。
- **Maven Central 核实**（repo1.maven.org 一手，2026-08-18）：
  - `dev.langchain4j:langchain4j-evaluation` → **404**（该坐标从未存在）；
  - `dev.langchain4j:langchain4j-evaluation-core` → **404**；
  - `dev.langchain4j:langchain4j-test` → 存在，latest `1.19.0-beta29`（与 agentic 同一 beta 线），内容仅 guardrail 断言（`GuardrailAssertions` / `GuardrailResultAssert` / Input/OutputGuardrailResultAssert），**不是评测打分器**；
  - `io.quarkiverse.langchain4j:quarkus-langchain4j-testing-evaluation-core` → 存在（200），但属 **Quarkus 扩展**（Quarkus 专属测试/评估模块），不适用于 Spring Boot 3.5。
- **仓库 tree 核实**：langchain4j 主仓库 main 分支 6260 个路径中 **main 源码 0 个 Evaluator 类**（只搜到无关的 judge0 代码执行引擎等）。
- **结论**：LangChain4j 1.19 官方线**没有自带评测能力**可借；现有 `AgentEvalScorer` 是仓库自研资产，不是框架能力。→ 「LangChain4j 自带评测」这一候选直接出局，07 的自研路线不与之重叠。
- **证据**：[langchain4j 仓库](https://github.com/langchain4j/langchain4j)、[langchain4j-test 元数据](https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-test/maven-metadata.xml)、[Quarkus 扩展坐标](https://mvnrepository.com/artifact/io.quarkiverse.langchain4j/quarkus-langchain4j-testing-evaluation-core)。

### 2.8 JVM 可用性结论表

| 候选 | Java SDK？ | 坐标 / 方式 | JVM 跑分能力 | 结论 |
|---|---|---|---|---|
| Langfuse | ✅ 官方 | `com.langfuse:langfuse-java:0.2.0`（MIT） | 客户端（datasets/score 上报+查询）；跑分执行在服务端 evals | 可接入，仅托管/展示侧 |
| Braintrust | ✅ 官方 | `dev.braintrust:braintrust-sdk-java:0.3.20`（MIT, BETA） | **JVM 内跑 eval loop**（cases+taskFunction+scorers） | 唯一 JVM 原生执行器 |
| PromptLayer | ❌ | 仅 REST | 无（Python/TS SDK 才有 eval runner） | 判死 |
| promptfoo | ⚠️ 无 SDK 但可驱动 | CLI/npm（`npx promptfoo eval`）+ JSON 输出 | JVM 经 ProcessBuilder 驱动，本地执行 | 可作 CI 跑分器（中间路线首选） |
| DeepEval | ❌ | 仅 Python（pytest） | 需 Python 环境 | 可行但重 |
| OpenAI Evals | ❌ | 仅 Python | 产品停摆 | 判死 |
| LangChain4j 自带 | — | 无评测模块 | 无 | 出局 |

---

## 3. self-host 与数据出境

**红线对照（沿用 map）**：密钥/凭据绝不进 DB、日志、DTO——这条约束的是**秘密本身**，与用例托管平台无直接关系（无论选哪条路，用例/跑分数据都要过 SecretRedactor / AgentPayloadRedactor，秘密本来就不该出现在用例里）。本项真正要判的是：**含真实业务数据（订单/供应商/价格）的评测用例上传到第三方 SaaS，是否可接受**——map 红线未明文禁止业务数据出境，但这是数据治理层面的风险，且与「观测接入 out of scope」的既有姿态一致（不把业务面数据交给外部平台）。

| 候选 | 自托管 | 数据出境 |
|---|---|---|
| Langfuse | ✅ **完全自托管**（MIT，docker `langfuse/langfuse:4.12`，stack：Postgres+ClickHouse+Redis+S3，>8000 活跃自托管实例） | 云版可选 EU（cloud.langfuse.com）/ US 区域；自托管则数据完全不出本地 |
| Braintrust | ⚠️ **混合**：数据面进自家云（Terraform），控制面仍 Braintrust 托管；BYOC 亦然 | SaaS 默认数据在 Braintrust；自托管/ BYOC 数据留自家云但依赖其控制面 |
| PromptLayer | ⚠️ **Enterprise-only** 自托管（商业） | SaaS 默认上传；自托管需企业授权 |
| promptfoo | ✅ **完全本地**（CLI 无服务端；web viewer 可本地/自托管镜像） | 不出本地（除非用其云端产品） |
| DeepEval | ✅ 本地库（无服务端）；Confident AI 可选 | 不用 Confident AI 则不出本地 |
| OpenAI Evals | ✅ 本地库 | 平台化版本数据在 OpenAI（产品停摆） |

**结论**：数据出境只有两条路能**彻底避免**——Langfuse 完全自托管、或 promptfoo/DeepEval 本地执行。Braintrust/PromptLayer 的「自托管」都带商业/托管依赖，对单租户内部系统偏重。若用例用**脱敏/合成数据**（如现有 fixture 的 SKU-EVAL-000001、canned 数字），上云风险大幅下降，但 07 若要把**真实订单/供应商**落进用例，自托管/本地是唯一稳的答案。

---

## 4. 第二控制面判定（逐个 vs 03）

判定标准：该方案是否**要求把 prompt 或 Agent 定义的真源移出 `agent_definitions`**（即：人类/流程在外部平台维护 prompt，运行时从外部平台取）。要求外置 = 与 03 冲突（第二控制面，双份漂移，同 Dify/Flowise 排除理由）。

| 候选 | prompt 真源处理方式 | 判定 |
|---|---|---|
| Langfuse prompt 管理 | 官方定位就是「在 Langfuse 维护 prompt 版本 + 运行时 API 取用」——整个 prompt-version-control 功能就是干这个的 | ⚠️ **若当 prompt 真源：与 03 冲突**。仅当「Langfuse 只是观测/展示层、prompt 仍在 agent_definitions」才不冲突 |
| Braintrust Prompts | prompts playground 可存 prompt 版本/做 prompt 实验 | ⚠️ **若当 prompt 真源：与 03 冲突**（同上） |
| PromptLayer Prompt Registry | 核心卖点即 prompt 注册/审批/发布 = 真源 | 🔴 **冲突**（且已因无 Java SDK 判死） |
| promptfoo | prompt 写在 YAML config 的 `prompts` 段 | ✅ **不冲突（有条件）**：若 YAML 是「由 DB 真源**生成**的产物、每次跑分重新生成、不做人工编辑源」，真源仍是 agent_definitions（§5）；若人类开始在 YAML 里改 prompt，则退化为第二控制面 |
| DeepEval | prompt 写在 pytest 测试代码里 | ✅ 同 promptfoo：生成物路线不冲突 |
| OpenAI Evals | 平台化 eval 配置 | 判死，不评 |
| LangChain4j 自带 | 无评测能力 | 不适用 |

**另一个维度的外置**（07 相关，非 03）：Langfuse/Braintrust 的 **datasets（评测用例托管）** 会把**用例真源**也外置——这与 07 已确认方向「评测用例数据化（DB）」冲突。若走这两家，等于同时交出「prompt 真源 + 用例真源」两个控制面，且运行时还依赖对方服务可用性。这是**把平台当「真源托管」与「跑分执行器」的关键分界**：前者冲突，后者（§5）不冲突。

---

## 5. 中间路线（重点）：离线评测框架只当 CI 工具、不交出真源

### 5.1 可行性结论：成立，且是「借力但不外置真源」的最优解

- **成立条件**：① 用例真源在 DB（`agent_eval_cases`，07）；② 被测 prompt 真源在 `agent_definitions`（03）；③ 外部框架只做「**执行器**」——接收导出数据、跑分、吐结果，**不存储任何真源、不做人工编辑入口**；④ 喂给框架的配置（promptfoo YAML / deepeval 测试文件）是**每次跑分时由 DB 状态重新生成的产物**（生成器是纯函数，不落库为真源）。
- 在该条件下，框架对 03/07 的耦合是**单向、只读、可再生**的——不构成第二控制面，也不构成运行期依赖（框架挂了不影响主服务，只是门禁跑不了/降级）。
- **主要限制（必须写进 07）**：现有基线钉的**工具调用序列**不是 promptfoo/DeepEval 的天然断言对象（它们断言的是 prompt→输出文本/JSON）。接法上工具序列要么用 JS/Python 断言在导出的输出上检查，要么维持 JUnit 侧不变式（`AgentEvalScorer` 那套）+ 用外部框架只补「提示词质量」指标。**建议 07 决策时把两类指标分开**：管线/不变式指标留在 JUnit/自研（stub 模型即可，快且确定），质量指标走外部框架（真实模型，贵且非确定）。

### 5.2 promptfoo 接法（首选，最轻）

1. **导出**：从 `agent_eval_cases` 读用例集 + 从 `agent_definitions` 读当前 active 版本（或待验草稿版本）的 `system_prompt`/`prompt_version`。
2. **生成 config**（每次跑分重新生成，进程内或 CI 步骤生成）：
   ```yaml
   # generated by EvalCaseExporter（每次跑分由 DB 状态生成，非手写源）
   description: data-query-eval-v1 @ data-query-v1（agent_definitions 版本）
   prompts:
     - file://generated/prompt-<version>.txt        # 内容 = agent_definitions.system_prompt
   providers:
     - id: deepseek:deepseek-chat                    # promptfoo 原生 DeepSeek provider
       config:
         apiBaseUrl: ${DEEPSEEK_BASE_URL}            # 环境变量注入，密钥不进文件
   defaultTest:
     assert:
       - type: llm-rubric                            # LLM-as-judge（可选，需判模型）
         value: 输出必须是合法 JSON 且 requires_human 语义正确
   tests:
     - vars: { question: "SKU-EVAL-000001 的进货价和零售价是多少" }
       assert:
         - type: contains-json                        # 结构断言
         - type: javascript                           # 数字核对 / 工具序列检查（在输出上做）
           value: "JSON.parse(output).answer.includes('12.34')"
     - vars: { question: "查一下客户张三的收货地址" }
       assert:
         - type: javascript
           value: "JSON.parse(output).requires_human === true"
   ```
3. **执行**：CI 步骤或 JVM `ProcessBuilder` 调 `npx promptfoo eval -c generated.yaml --output results.json --no-cache`（`--no-cache` 强制真实跑分；CLI 本地执行，无网络依赖除了模型 API）。
4. **收口**：解析 `results.json`（逐用例 pass/fail + 断言详情）→ 与钉死基线阈值比对 → 门禁判定 → 结果回写 DB（`agent_eval_runs` 之类，07 定表）。
5. **配套**：GitHub Actions/Jenkins 官方集成；本地 web viewer（`promptfoo view` / ghcr.io 镜像）可给人工看结果，但它只读生成物、不存真源。

### 5.3 DeepEval 接法（备选，较重）

1. 导出同 5.2；2. 生成 pytest 模块（`@pytest.mark.parametrize` 用例 + `G-Eval`/`FactualCorrectness`/自定义 metric，自定义 metric 用 `DeepEvalBaseMetric` 实现工具序列/数字核对）；3. CI 中起 Python 环境 `pip install deepeval && pytest -q --json-report`；4. 解析 JSON 报告回写 DB。
- 代价：CI 多一个 Python 运行时 + deepeval 依赖 + 指标用 LLM-judge（成本/抖动）；收益与 promptfoo 大体相当。**对本品（Java 单体）promptfoo 明显更顺**。

### 5.4 Braintrust Java SDK 变体（JVM 原生，但结果落 Braintrust）

- 若接受「跑分在 JVM 内、结果/实验记录在 Braintrust（SaaS 或自家云数据面）」，`evalBuilder().cases(从 DB 导出).taskFunction(调真实/ stub 运行时).scorers(Java 实现工具序列/数字核对)` 是最自然的 JVM 执行形态，甚至可以在**运行期**（非 CI）直接调。
- 代价：需要 Braintrust 账号/端点；实验与结果数据进 Braintrust（§3 数据出境讨论）；平台闭源、控制面托管。**它解决的是「执行器」而非「真源」，且是 SaaS 依赖**——与「本地优先」的姿态相比，优先级低于 promptfoo。

### 5.5 中间路线小结

| 方案 | 真源 | 执行器 | JVM 集成成本 | 数据出境 | 结论 |
|---|---|---|---|---|---|
| promptfoo 生成物路线 | DB（03/07） | 本地 CLI | 低（ProcessBuilder + YAML 生成器） | 无 | ✅ 首选 |
| DeepEval 生成物路线 | DB | Python pytest | 中（多养 Python 环境） | 无 | 备选 |
| Braintrust JVM SDK | DB | JVM 内 | 低（SDK 直接调） | 结果进 Braintrust | 次选（接受 SaaS 依赖时） |
| Langfuse datasets/evals | 移到 Langfuse | Langfuse 服务端 | 中（langfuse-java 驱动） | 自托管可避免 | 🔴 真源外置，与 07/03 冲突（除非只当展示层） |

---

## 6. 运行期跑分形态（若最终自研）

「草稿提交时自动跑评测门禁」的三种形态对比（与候选平台原生支持对照）：

| 形态 | 说明 | 优点 | 缺点 | 候选平台原生支持 |
|---|---|---|---|---|
| **同进程同步** | 草稿提交请求内直接跑完 N 例评测再返回 | 实现最简、语义直观 | 阻塞请求（N × 模型调用 = 秒级到分钟级）；评测故障会拖垮提交路径；与 03 草稿确认的事务边界纠缠 | 无（任何平台都要异步化） |
| **异步任务（推荐）** | 提交草稿 → 落 `run_mode=PREVIEW`/评测任务记录（status=PENDING）→ 后台执行器（Spring `@Async`/`@Scheduled`/任务表）跑分 → 结果回写（PASS/FAIL + 报告）→ 人工确认页可见 | 不阻塞提交；失败隔离（评测挂了草稿仍可提交）；可复用现有 `agent_runs`/审计模式；可限流控制成本 | 需要任务表/状态机 + 结果查询面（一期管理 REST API 的一部分） | **Langfuse**：datasets + scheduled evals（服务端跑 LLM-judge，可定时/手动触发）；**Braintrust**：remote evals / online scoring（平台托管或在线打分）；promptfoo：无原生触发，自己调 CLI |
| **仍走 CI** | 维持现状（`mvn test -Dtest='AgentEval*'`），草稿提交只登记、门禁结果等 CI 回执 | 零新基础设施；与现有 09 基线门禁同轨 | **不是运行期能力**——草稿提交与门禁结果之间有时间窗与通道（CI 状态回传），违背 map「草稿提交自动跑」的语义；草稿预览（PREVIEW）无法即时反馈 | promptfoo/DeepEval 原生就是 CI 形态 |

**结论（供 07 参考，非本票决策）**：自研跑分应取**异步任务**形态（推荐 `@Async`/任务表 + `agent_runs.run_mode=PREVIEW` 配套，03 已预留该列），同步只用于预览/调试入口；CI 形态保留给 09 基线门禁（不变式 + 版本钉死），两者并存不冲突。外部平台里，Langfuse（scheduled evals）与 Braintrust（remote evals/online scoring）对该场景有现成支持，但都要求把用例/结果交给平台（§3/§4 的代价）。

---

## 7. 证据清单（链接）

- Langfuse：仓库 <https://github.com/langfuse/langfuse>；开源化博客 <https://js-sdk-v4-docs-snapshot.langfuse.com/blog/2025-06-04-open-sourcing-langfuse-product/>；Java 客户端 <https://github.com/langfuse/langfuse-java>（Maven Central：<https://repo1.maven.org/maven2/com/langfuse/langfuse-java/maven-metadata.xml>）；prompt 管理 <https://langfuse.com/docs/prompt-management/features/mcp-server>。
- Braintrust：Java SDK <https://github.com/braintrustdata/braintrust-sdk-java> + 博客 <https://www.braintrust.dev/blog/java-sdk>（Maven Central：<https://repo1.maven.org/maven2/dev/braintrust/braintrust-sdk-java/maven-metadata.xml>）；自托管 <https://www.braintrust.dev/docs/admin/self-hosting>；部署选项 <https://www.braintrust.dev/docs/admin/deployment>。
- PromptLayer：自托管（Enterprise-only）<https://docs.promptlayer.com/self-hosted>；SDK evals <https://docs.promptlayer.com/sdks>。
- promptfoo：仓库 <https://github.com/promptfoo/promptfoo>；DeepSeek provider <https://www.promptfoo.dev/docs/providers/openai/>；node package <https://www.promptfoo.dev/docs/usage/node-package/>；CI/CD <https://www.promptfoo.dev/docs/integrations/ci-cd/>；自托管工作流 <https://github.com/promptfoo/promptfoo/issues/4521>；被 OpenAI 收购 <https://thenextweb.com/news/openai-acquires-promptfoo-ai-security-frontier>、<https://gigazine.net/gsc_news/en/20260310-openai-to-acquire-promptfoo>。
- OpenAI Evals 停摆：<https://raw.githubusercontent.com/openai/openai-cookbook/main/examples/evaluation/moving-from-openai-evals-to-promptfoo.md>。
- DeepEval：<https://github.com/confident-ai/deepeval>。
- LangChain4j 评测：docs 占位页 <https://docs.langchain4j.dev/tutorials/testing-and-evaluation/>；`langchain4j-test` 元数据 <https://repo1.maven.org/maven2/dev/langchain4j/langchain4j-test/maven-metadata.xml>；Quarkus 评估扩展 <https://mvnrepository.com/artifact/io.quarkiverse.langchain4j/quarkus-langchain4j-testing-evaluation-core>。
- 仓库一手核实：`backend/src/test/java/cn/zimu/fulfillment/agent/eval/AgentEvalScorer.java`、`AgentEvalBaselineTest.java`、`procurement/ProcurementPriceEvalFixture.java`、`DataQueryAgentEvalFixture.java`；`backend/pom.xml`（langchain4j-bom 1.19.0）。
