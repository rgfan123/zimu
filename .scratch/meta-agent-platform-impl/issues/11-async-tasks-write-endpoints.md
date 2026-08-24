# 11 — 异步任务基建 + 定义域写端点

**What to build:** 管理 REST 的写面与异步链路（12 决策）：① 202 异步任务基建——任务表 + Spring Worker（复用 message-worker 模式），任务 = 草稿落库 + 静态门禁 + INVARIANT stub 评测闭环，`run_mode=PREVIEW` 落运行记录；② 定义域写端点（/api，Basic Auth，operator 取自身份不进 body）：`POST /api/agents/drafts`（人工建草稿，202）、`POST /api/agents/{slug}/drafts/{version}/confirm`（确认前全量门禁复跑 + 联动确认该版本 PENDING 用例）、`reject`、`POST /api/agents/{slug}/set-enabled`（显式目标值幂等）、`POST /api/agents/{slug}/rollback`（目标版本须曾 active，复制为 v{n+1} draft）；幂等语义 = 目标状态幂等（confirm 已 active 同版本返回 200+当前状态；retired/不存在 → 409/404），并发确认不同版本由 DB 部分唯一索引兜底、败者 409。

**Blocked by:** 03 — INVARIANT 评测数据化；05 — B/C 路径收敛（PREVIEW 运行需 Adapter 路径）；08 — 门禁引擎（设计源：meta-agent-platform 票 07、12）。

**Status:** resolved

- [x] 202 → 轮询闭环（任务含门禁结果）；两处任务入口（人工建草稿）行为一致
- [x] confirm 前全量门禁复跑、全绿才可确认；联动确认该版本 PENDING 用例
- [x] 幂等/并发契约测试（重复 confirm 200、并发确认败者 409、set-enabled 目标值幂等、rollback 复制正确）
- [x] operator 来自 Basic Auth 身份，请求体无 operator 字段

## Resolution

**交付**：五个写端点（全部 /api + Basic Auth）+ 异步任务基建（复用 `app.async_tasks` + Spring Worker 模式）。改动未提交（worktree 沙箱不可写共享 .git），留在工作区。

### 端点契约

| 端点 | 请求体 | 202 | 幂等 / 错误语义 |
|---|---|---|---|
| `POST /api/agents/drafts` | 定义全量快照 + `suggested_eval_cases`（与 create_agent_draft 工具同构） | `202 {run_id}` | slug 已存在 409 `AGENT_SLUG_EXISTS`；载荷非法 400（同步，不产生任务） |
| `POST /api/agents/{slug}/drafts/{version}/confirm` | `{}`（不接受任何字段） | `202 {run_id}` | 已 active 同版本 → `200`+当前状态（同步重放，不产生任务）；retired 409 `AGENT_VERSION_RETIRED`；不存在 404 |
| `POST /api/agents/{slug}/drafts/{version}/reject` | `{}` | `202 {run_id}` | 已拒绝 → `200 {status:"rejected"}`；active/retired 409 `AGENT_VERSION_NOT_DRAFT`；不存在 404 |
| `POST /api/agents/{slug}/set-enabled` | `{"enabled": bool}`（显式目标值） | `202 {run_id}` | 已处于目标值 → `200`+当前状态（无任务）；无生效版本 404 |
| `POST /api/agents/{slug}/rollback` | `{"target_version": int}` | `202 {run_id}` | 目标须曾 active（active/retired 行）；草稿目标 409 `AGENT_ROLLBACK_TARGET_NOT_ACTIVE`；不存在 404 |

请求体出现 `operator` 字段一律 400 `OPERATOR_FIELD_FORBIDDEN`；operator 取自 `RequestContext.getAuthenticatedOperator()`（RequestContextFilter 复验 Basic Auth 后的登录主体），入队时捕获进任务载荷供 Worker 使用，绝不进 DTO/日志。

### 202 → 轮询闭环（复用 agent_runs，不另建任务查询面）

- 每个写动作在入队事务内原子落：`agent_runs` 行（`run_mode=PREVIEW`，status RUNNING，含 slug/version/input_digest/业务实体关联）+ `async_tasks` 行（载荷 JSON 存 `payload_ref`——V44 迁移把该列放宽为 TEXT，草稿全量 JSON 放得下；任务幂等键每次请求唯一：任务级去重会把「失败后重试」锁死，幂等由目标状态承担）。
- `AgentDefinitionWorker`（@Scheduled 轮询，按类型领取不抢消息/QUALITY 任务，租约式，maxAttempts=1）执行后收口运行行 SUCCESS/FAILED（`error_type` 稳定码），门禁明细/影响范围经 `agent_tool_calls` 合成行落库（`agent_gate` / `agent_invariant_eval` / `agent_draft_persist` / `agent_confirm` / `agent_reject` / `agent_set_enabled` / `agent_rollback`）——T12 的 `GET /api/agent-runs/{runId}` 详情含工具调用序列（12 票已列明），轮询一次即拿「能否确认」全貌。
- **表达不了的**：门禁 blockers 列表在 `agent_runs` 自身列放不下（error_type 是 VARCHAR(64) 稳定码），用 `agent_tool_calls` 合成行承载；若 T12 详情实现时未含 tool_calls，需在 12 侧补。除此之外任务状态（提交即 RUNNING、终态 SUCCESS/FAILED + 稳定码）完全由 agent_runs 表达，未扩任何查询面。
- 两处任务入口行为一致：人工建草稿（本票）与 Meta-Agent run（13）共享同一任务存储/载荷形态/PREVIEW 运行行与轮询面（13 票建在本票基建之上）。

### confirm 的原子联动与并发

- 任务内先「全量门禁复跑」：`AgentGateEngine`（六项阻断 + output_schema 解析）+ `AgentInvariantEval`（INVARIANT stub 评测，见下），全绿才进入状态迁移；任一阻断 → 任务 FAILED（`AGENT_GATE_BLOCKED` / `AGENT_INVARIANT_BLOCKED`），零状态变化。
- 状态迁移单事务（`confirmStateTx`）：① 只退役「入队时捕获的前任 active 版本」；② 目标 draft → active（activated_by/at 上行）；③ 该版本全部 PENDING 用例（INVARIANT + QUALITY）→ CONFIRMED（confirmed_by/at 上行）。任一步失败整笔回滚——**不会出现「定义已生效但用例还挂 PENDING」的中间态**；审计（`agent.definition.activated`，actor=HUMAN）失败 try/catch 隔离，不影响事务。
- 并发确认不同版本：败者的退役命中 0 行（前任已被先到者退役）、激活语句命中 DB 部分唯一索引 `UNIQUE(agent_slug) WHERE status='active'` → `DuplicateKeyException` → 任务 FAILED `AGENT_CONFLICT`（409 语义）。**零应用层锁**；前任版本捕获在入队时完成，败者绝不可能退役掉先到者刚激活的版本（防静默覆盖）。
- 同版本并发确认：后到者激活 0 行 → 无操作 SUCCESS（目标状态已达成）。

### rollback = 复制为新 draft，不是回边

- 目标版本行 status ∈ {active, retired}（曾 active）才可回滚；服务端把它**全量复制为 v{n+1} 新 draft 行**（status=draft、activated_by/at 置空、enabled 与其余字段逐字复制），并把目标版本的 CONFIRMED 用例集复制为新版本 PENDING 用例（07 冻结集语义：换版本 = 换用例集，评测可复现可回滚）。旧版本行零改动（append-only 版本链）；回滚产物走正常 草稿→确认 流。
- 任务形态与人工建草稿一致：静态门禁 + INVARIANT stub 评测 + PREVIEW 运行行（两处建草稿入口行为一致）。

### INVARIANT stub 评测（AgentInvariantEval，生产侧确定性评测）

不调用模型，对版本冻结的 INVARIANT 用例集 + 定义做可判定静态核对：用例归属冻结集、expected 结构（07 派生 schema）、工具选择 ⊆ 白名单、写工具零调用（allow_write 判定）、PII 守卫一致性（输入含 PII 且未豁免时必须 requires_human=true）；用例非法拒跑（fail-closed）。版本无 INVARIANT 用例时 vacuous pass。QUALITY（真实模型）不参与，由 09 异步链路承担、不阻断确认。

### 幂等/并发契约测试（AgentDefinitionWriteApiTest，真实 HTTP + Testcontainers + Worker 开启）

- **重复 confirm → 200 + 当前状态**：confirm 成功后重发同一请求，断言 200、body 含 status=active/version，且不产生新任务/新运行。
- **并发确认败者 409 真实触发**：基座 active v1 + 两个草稿 v2/v3，双线程 CountDownLatch 同时 POST confirm；两任务在 Worker 上并发执行，DB 部分唯一索引保证恰一个 SUCCESS、败者运行行 `error_type=AGENT_CONFLICT`，且 DB 恰一行 active。
- **set-enabled 目标值幂等**：目标值已是当前值 → 200 重放且不产生任务；翻转 → 202 → SUCCESS；`enabled` 与 `status` 正交（改 enabled 后 status 仍 active，注册表 reload 后运行路径感知）。
- **rollback 复制正确**：断言新 draft 行内容与目标逐字段一致、旧行（含 activated_at）零改动、用例集复制为 PENDING；草稿目标 409、不存在 404。
- **operator 身份**：全量审计断言 operator/activated_by/confirmed_by == Basic Auth 用户名；请求体带 operator 的五个端点全部 400，且不产生任何任务/运行。
- 门禁任务测试：建草稿门禁不过 → 任务 FAILED + 无脏行 + blockers 可轮询；绕过门禁直接插脏草稿 → confirm 全量复跑阻断、零状态变化；PENDING INVARIANT 用例非法 → confirm 阻断。

**测试数字**：全量 `mvn test` 831 例，827 绿；新增 AgentInvariantEvalTest 8 例 + AgentDefinitionWriteApiTest 15 例全绿。4 例失败均为本环境/本分支既有问题、与本票无关（逐一复现定位）：`ConnectorApiTest` 断言 connectors 恰 4 条，V31 已播种第 5 条 ZHONGHUI（陈旧断言，任意环境必失败）；`Zhonghui/CaishixianSourceFileParserTest` 依赖工作区外 fixture 目录 `待发货订单-测试/`（本 worktree 不存在）；`ShipmentJdOutboundWriteModeDisabledTest` 在操作环境设 `JD_LOP_WRITE_MODE=ON` 时失败（env -u 后通过）。

### 其他

- V44 迁移（部署兼容修复前原编号 V40）：`async_tasks.payload_ref` VARCHAR(512) → TEXT（草稿全量载荷）。不新增表/列。
- `AgentDraftService.insertDefinition` 改包可见（rollback 复制复用同一落库 SQL），无行为变化。
- 门禁引擎与工具注册表懒解析（`ObjectProvider`，与 AgentDraftService 同款语义）：`AgentDefinitionWriteService` 经 `ObjectProvider<AgentGateEngine>`、`AgentInvariantEval` 经 `ObjectProvider<McpToolRegistry>` 取依赖——McpToolRegistry → McpWriteTools → ShipmentJdOutboundService → JdWriteOpsClient（`@ConditionalOnProperty app.jd.client-mode=REAL` 条件装配）这条链只在任务真正执行时拉起，避免无 JD 配置的上下文（client-mode 缺失/为空时两个客户端 bean 都不装配）启动即失败。
- 已知口径：rollback 复制的草稿携带目标版本当时的 enabled 值（全量复制语义，启停是正交运维动作，确认前可再 set-enabled）。
- 环境备注：`ReviewCaseResolutionApiTest` 等上下文在 `JD_LOP_CLIENT_MODE` 为空字符串时无法装配 JdWriteOpsClient/MockJdWriteOpsClient（`matchIfMissing` 只对缺失生效），属既有 JD SDK 开关行为，与本票无关。
