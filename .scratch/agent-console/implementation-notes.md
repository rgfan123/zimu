# Agent 中心前端实施笔记（只读部分）

日期：2026-08-20
范围：`.scratch/agent-console/frontend-spec.md` 的 P1 / P2 / P3 / P4 / P5 五个页面（只读），
导航与路由注册、API 客户端与类型。**不含**任何写动作（T11 未合并）与 P6 对话式创建（后置裁定）。

## 0. 契约来源

所有类型直接读自本分支后端源码（`backend/src/main/java/cn/zimu/fulfillment/agent/dto/`），
未凭 spec 猜测字段。JSON 命名确认走全契约 SNAKE_CASE
（`backend/.../common/web/JacksonConfig.java` 全局 `PropertyNamingStrategies.SNAKE_CASE`），
故前端类型为 snake_case，与既有 `frontend/src/api/types.ts` 约定一致。

## 1. 页面 × 端点 × 关键字段映射

| 页面 | 路由 | 端点 | 关键字段 |
|---|---|---|---|
| P1 Agent 列表 | `/agents` | `GET /api/v1/agents` | `slug`/`name`/`state`/`enabled`/`current_version`(null→「—」)/`draft_count`(>0 徽标)/`tools[]`(`read_only`,`registered`)/`allow_write`/`seven_day_run_count`/`seven_day_failure_count` |
| P2 Agent 详情 | `/agents/:slug`（tab 进 URL：`?tab=versions`） | `GET /api/v1/agents/{slug}` + `GET /api/v1/agents/{slug}/versions` + `GET /api/v1/agent-runs?slug=&limit=5`（最近 5 次运行摘要） | `system_prompt`/`prompt_version`/`model_ref`/`output_schema`/`guard_exemptions`/`input_format`/`activated_by`/`activated_at`/`tools[]`；版本链 `version`/`status`/`activated_by`/`activated_at` |
| P3 运行记录 | `/agents/runs` | `GET /api/v1/agent-runs`（`slug`/`outcome`/`run_mode`/`business_entity_type`/`business_entity_id`/`started_from`/`started_to`/`limit`/`offset`） | `run_id`/`agent_slug`/`agent_version`/`status`/`outcome`(null→「进行中」)/`run_mode`/`latency_ms`/`token_usage`/`business_entity_type`/`business_entity_id`/`intent`/`started_at`；`total` 分页 |
| P4 运行详情 | `/agents/runs/:runId` | `GET /api/v1/agent-runs/{runId}` | 列表字段 + `thread_id`/`input_digest`/`tool_calls[]`(`sequence_no`/`tool_name`/`args_summary`/`result_summary`/`latency_ms`/`status`)/`eval_result` |
| P5 评测 | `/agents/:slug/evals`（P2 内 tab，独立路由深链） | `GET /api/v1/agents/{slug}/versions/{version}/eval-cases`（默认选 active 版本；Segmented 切换版本） | `id`/`metric_kind`/`input`/`expected`/`status`/`created_by`/`confirmed_by`/`confirmed_at` |

模型元数据（P3/P4 行与详情）：`ModelMetadataItem { provider, model, prompt_version, visibility }`。

## 2. 三条易错点各自怎么落实的

### 2.1 `state` 服务端派生，前端只渲染不推导

- `AgentListState = RUNNING | DISABLED | NO_ACTIVE_VERSION`，类型直接来自 `dto/AgentListState.java`。
- `agentPresentation.ts` 的 `statePresentation(state)` 是**穷举三值 → 文案/色调**的直映
  （运行中-绿 / 已停用-灰 / 无生效版本-橙），函数签名只接受 `state`，**不接受 `enabled`**；
  契约外值只回显原文，绝不依据 `status`×`enabled` 组合出第二套口径。
- P1 表格「运行状态」列只读 `state` 一个字段。
- 测试：`agentPresentation.test.ts`「state 三值直映」「state 呈现不依赖 enabled」。

### 2.2 模型元数据三态不得折叠

- `modelVisibilityPresentation(visibility)` 三态各有独立 label 与 note：
  - `EXPOSED` → 「已公开」+ 命中服务端 allowlist；
  - `NOT_PUBLIC` → 「未公开」+ **模型已配置但未登记 allowlist，不对外公开**；
  - `NOT_CONFIGURED` → 「未配置」+ **该运行未携带任何模型元数据**。
- P4 详情里 EXPOSED 展示真实 provider/model/prompt_version，后两态只显示 label + note，
  不渲染成同一个空值。
- 测试断言三个 label 与三个 note 两两不同，且 NOT_PUBLIC 文案含「allowlist」、
  NOT_CONFIGURED 含「未配置」。

### 2.3 LIVE 与 PREVIEW 视觉隔离

- **默认只看 LIVE**：URL 构造（`runsLocation`/`runsSearchParams`）只在 `runMode==='PREVIEW'`
  时写 `run_mode=PREVIEW`；解析（`runsFiltersFromParams`）把缺省/显式 `LIVE` 都归一为
  undefined（= 不传参，后端默认即 LIVE）。
- 切 PREVIEW 后：筛选区下方渲染 warning Alert
  「正在查看 PREVIEW（草稿试跑）记录 —— 仅用于验证草稿行为，不计入对线上运行状态的判断」，
  且每行「模式」列有 `PREVIEW` Tag（warning 色调），LIVE 行默认色 Tag。
- 测试：默认请求不带 `run_mode`；切换后请求带 `run_mode=PREVIEW` 且出现醒目标识。

### 2.4 input_digest 隐私说明（spec 之外的第四条硬要求）

- P4 详情「输入摘要」卡固定渲染 `INPUT_DIGEST_EXPLANATION = '输入原文不留存，仅存摘要用于比对'`
  说明文字 + 等宽字体的摘要本体；null 显示「—」，绝不渲染成空白。
- 测试断言常量文案与摘要展示。

## 3. 与 spec / 设计稿的差异（如实记录，未改后端迁就前端）

1. **P5 的 `case_key` 不存在**。设计稿 §3 P5 写「每条用例：case_key…」，但 T12 的
   `AgentEvalCaseItem` 只有 `id`（DB 主键）。UI 列名用「用例 ID」，展示 `#id`。
2. **P1 近 7 日无法画迷你趋势**。设计稿 §3 P1 要「迷你趋势 + 失败数」且 §7 指定 echarts，
   但 `AgentListItem` 只有聚合值 `seven_day_run_count`/`seven_day_failure_count`
   （无逐日序列）。UI 显示「N 次运行」+ 失败数（非零着色），两者皆 0 显示「无运行」，
   不做无数据支撑的图表。
3. **P4 底部「关联审计日志」未实现**。设计稿 §3 P4 要「关联审计日志（同 run_id 双向可追溯）」，
   但 T12 两个读控制器（`AgentReadController`/`AgentRunReadController`）都没有
   「按 run_id 查审计」的端点，现有 `/api/v1/audit-logs` 也不支持 run_id 过滤。
   前端不凭空捏造该区块，等后端契约补齐。
4. **版本链 tab 没有独立路由**。spec §1 路由表只给了 `/agents/:slug` 与 `/agents/:slug/evals`，
   没有版本链路由。为满足「刷新与分享可复现」，版本链 tab 用 `?tab=versions` query 深链
   （P1 的「当前版本」与「待确认草稿」徽标都链到 `/agents/:slug?tab=versions`）。
5. **P1 筛选是客户端过滤**。`GET /api/v1/agents` 无任何查询参数（一次拿全量聚合），
   spec 要求筛选进 URL：实现为 URL 携带 `state`/`draft=yes|no`/`write=yes|no`，
   列表数据在客户端用 `filterAgentItems` 过滤（纯函数，已测）。
6. **`enabled` 的展示口径**：P1 列表行不显示 enabled 列（状态列已被 `state` 覆盖）；
   P2 详情显示「已启用/已停用」Tag 并附「停用不改变版本状态，重新启用回到原版本」说明，
   无启停开关（写动作等 T11）。
7. **TS 防御性可空**：`RunListItem.model_metadata` 在 Java record 中声明为非空
   （`ModelMetadataItem` 无 `@Nullable`），但前端类型按 `ModelMetadataItem | null` 防御处理，
   渲染层有 `—` 兜底，不影响契约语义。

## 4. 工程约束落实

- **详情走真实路由**：`/agents/:slug`、`/agents/runs/:runId`、`/agents/:slug/evals` 全部是
  独立注册的路由（隐藏于菜单）；筛选与分页全部进 query string（P1 `state/draft/write`，
  P3 `slug/outcome/run_mode/业务实体/时间/limit/offset`），无本地状态抽屉。
- **复用三件套**：P1/P3/P5 全部用 `PageShell` + `FilterBar` + `DataTable`
  （loading/空态/错误态/横向滚动默认值均未重写）；P2/P4 用 PageShell + antd
  Descriptions/Timeline/Collapse（非表格页，不套 DataTable）。
- **`~` 后缀机制**：`navigation.ts` 新增顶级分组「Agent 中心」`path:'/agents'`，
  叶子 `'/agents'`（Agent 列表）与分组共用路径，菜单 key 由既有 `NAVIGATION_GROUP_SUFFIX`
  机制区分；`/agents/runs` 与 `/agents/:slug` 的匹配优先级由既有
  `routeMatchScore` 静态段计数保证。未另建并行机制。
- **写动作一律不做**：P1「新建 Agent」按钮 disabled + 「对话式创建即将开放」；
  版本链无任何回滚/确认按钮，附注「版本状态机无回边」；P5 active 版本显示
  「新增或修改用例需先创建草稿版本」提示。

## 5. 测试

- `frontend/test/agentPresentation.test.ts`：19 个纯函数测试 —— state 三值映射、
  模型三态文案两两不同、run_mode 默认 LIVE 与显式 PREVIEW 的 URL 构造/解析、
  outcome=null「进行中」、input_digest 说明文案、近 7 日「无运行」、
  工具调用升序排序、评测分组、JSON/时间/耗时格式化、P1 筛选 URL 往返。
- `frontend/test/agentConsoleRoute.test.ts`：10 个 jsdom 结构/路由测试 ——
  导航分组与 `~` 后缀 openKeys、五个路由注册、P1 空态/错误态+重试/state 三值渲染/
  新建按钮禁用、P3 LIVE 默认与 PREVIEW 切换（请求带 `run_mode=PREVIEW` + 醒目标识）、
  P4 digest 说明 + 工具序列顺序 + error_type 置顶 + 错误态、P5 分组渲染 +
  active 只读提示、P2 守卫豁免空态 + 最近运行摘要 + tab 切换。
- 全量：`node --test --experimental-strip-types test/*.test.ts` 219 个用例全部通过；
  `tsc --noEmit` 通过；`vite build` 通过（chunk 体积警告为既有 manualChunks 策略所致）。

## 6. 改动文件清单

新增：
- `frontend/src/api/agentTypes.ts` —— T12 契约类型（对照 dto/ 逐字段）
- `frontend/src/pages/agents/agentPresentation.ts` —— 展示层纯函数（易错点落点）
- `frontend/src/pages/agents/AgentsListPage.tsx`（P1）
- `frontend/src/pages/agents/AgentDetailPage.tsx`（P2，含 tab 路由逻辑）
- `frontend/src/pages/agents/AgentRunsPage.tsx`（P3）
- `frontend/src/pages/agents/RunDetailPage.tsx`（P4）
- `frontend/src/pages/agents/EvalsTab.tsx`（P5）
- `frontend/src/pages/agents/index.ts`（barrel）
- `frontend/test/agentPresentation.test.ts`
- `frontend/test/agentConsoleRoute.test.ts`

修改：
- `frontend/src/api/endpoints.ts` —— 追加 `agentsApi` / `agentRunsApi` 与 `AgentRunsQuery`
- `frontend/src/navigation.ts` —— 顶级分组「Agent 中心」+ 五个叶子（`~` 后缀机制）
- `frontend/src/routes.tsx` —— 五个路由元素 + `RobotOutlined` 菜单图标

后端零改动。
