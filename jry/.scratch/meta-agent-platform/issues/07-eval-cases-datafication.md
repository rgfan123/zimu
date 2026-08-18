# 07 — 评测用例数据化设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 03 — Agent 定义数据模型与版本状态机（✅ resolved）；11 — 评测与 Prompt 管理平台调研（✅ resolved）

## 输入（11 票结论，见 `research/11-eval-prompt-platforms.md`）

- 外部评测平台全部出局（JVM 可用仅 Langfuse/Braintrust 且真源外置冲突；LangChain4j 无评测模块；OpenAI Evals 停摆）。
- **首选中间路线**：promptfoo 只当 CI/本地跑分执行器——用例存 DB、prompt 真源留 `agent_definitions`、由 DB 状态生成 promptfoo YAML（deepseek provider + llm-rubric/javascript 断言，密钥走环境变量）、`npx promptfoo eval` 本地执行后回写 DB 比对基线；JVM 侧仅 ProcessBuilder + YAML 生成器。
- **建议拆分指标**：不变式指标（JUnit/自研，stub 模型——工具调用序列、schema 通过率、写工具零调用）与质量指标（外部框架/真实模型——答案质量）分开。
- 运行期跑分建议**异步任务**（配 03 已预留 `agent_runs.run_mode=PREVIEW`）；CI 形态保留给 09 基线门禁。

## Question

已确认：通用门禁自动 + 评测用例数据化；跑分器（AgentEvalScorer）扩展为数据驱动；09 代码 fixture 逐步迁移；基线门禁（AgentEvalBaselineTest）继续钉；意图识别回归门禁复用既有 MessageInterpretation* 套件。

待决策点（grilling，一次一个，带推荐答案）：

1. `agent_eval_cases` 表结构：关联定义版本、用例类型（input/expected）、状态（待确认/已确认）；确认流程（与定义草稿确认联动？Meta-Agent 建议评测输入的落库路径）。
2. 跑分器数据驱动改造边界：canned MCP 注册表与 stub 模型保留；fixture → DB 行的映射；`procurement-eval-v1` / `data-query-eval-v1` 迁移策略与基线重钉流程（docs/agent-eval-baseline.md 更新）。
3. 意图识别回归门禁是否也数据化（还是维持代码套件引用）。
4. 指标二分（11 建议）：不变式指标（stub，JUnit）与质量指标（真实模型，promptfoo 执行器）各自的用例表/跑分流程/门禁归属。
5. 「草稿提交自动跑评测门禁」运行期形态：异步任务 + `run_mode=PREVIEW` 的具体接法；与 09 CI 门禁的分工。

## Answer

1. **指标二分**：一张 `agent_eval_cases` 表 + `metric_kind` 枚举（INVARIANT/QUALITY），执行器按类型路由——INVARIANT（工具序列/schema/写工具零调用/requires_human）→ 现有 stub 跑分器 + CI 基线门禁（09 只钉这类，确定性）；QUALITY（答案质量）→ 异步 PREVIEW + promptfoo 执行器（真实模型，有波动不进基线）。
2. **用例关联粒度**：绑定 `(agent_slug, agent_version)`——每定义版本冻结一份用例集；换例=新版本（与 09「换例即换版本」及 03 全快照模型同构），评测可复现可回滚。
3. **断言表达**：`input`/`expected` 均 JSONB；expected schema 由 metric_kind 派生（INVARIANT → tool_sequence / requires_human / missing_fields / expected_error（负例）；QUALITY → answer_contains），跑分器读取时校验、非法拒跑（与 03 output_schema 同构）。
4. **基线门禁形态**：`AgentEvalBaselineTest` 改造为「DB 用例集（Testcontainers）+ stub 跑分器」集成测试，断言 INVARIANT 基线（schema 100% / 工具序列 / requires_human 召回 / 写工具零调用），基线数字随用例集版本重钉；现有 14 例 fixture（procurement-eval-v1 / data-query-eval-v1）由 V 迁移播种后删除（与 03 播种两步走同构）。
5. **用例确认流程**：与定义草稿确认**联动**——确认定义草稿时同一动作确认该版本全部待确认用例；active 版本不可追加/修改（要加=新版本草稿）。
6. **运行期形态（两级）**：草稿提交【同步】快速门禁（INVARIANT stub 跑分器 + 结构/白名单/只读校验，不过则阻断）；【异步】QUALITY 评测（真实模型 + promptfoo，run_mode=PREVIEW 落 agent_runs，复用现有 Spring Worker 模式），结果供确认人参考、不硬阻断。
7. **意图识别回归门禁**：不数据化——维持 `MessageInterpretation*` 代码套件引用（其提示词真源在 `app.message-interpreter.*`，不在 agent_definitions 闭环内，数据化会制造第二真源）。

**Schema 增量（实施票据此写迁移）**：`agent_eval_cases`（新表）：`id` / `agent_slug` / `agent_version`（引用 `agent_definitions (agent_slug, version)`）/ `metric_kind`（INVARIANT|QUALITY）/ `input` JSONB / `expected` JSONB / `status`（PENDING|CONFIRMED）/ `created_by` / `confirmed_by` / `confirmed_at`；唯一性与索引细节留实施票。
