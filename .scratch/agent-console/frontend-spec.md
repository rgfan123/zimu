# Agent 中心前端实施规格（绑定 T12 真实契约）

Type: design-refinement
Status: ready
日期：2026-08-20
上游：`.scratch/agent-console/design.md`（信息架构与三项用户裁定）

## 0. 本文与设计稿的关系

设计稿定的是「做什么、为什么、防哪些坑」；本文把它落到**T12 已实现的真实字段**上，让前端不必猜契约。T12 交付后有两处与设计稿的差异，已在本文吸收：

1. **`state` 由服务端派生**。设计稿 §4 要求前端按 `status` × `enabled` 四种组合推导显示状态（这是最容易做错的地方）。T12 直接返回 `state ∈ {RUNNING, DISABLED, NO_ACTIVE_VERSION}`，**前端只渲染不推导**——坑在 API 层就关掉了。前端不得再自行组合 `status`/`enabled` 得出第二套状态口径。
2. **模型元数据是三态**。`model_metadata.visibility ∈ {EXPOSED, NOT_PUBLIC, NOT_CONFIGURED}`，三种文案必须不同（见 §5）。

## 1. 归属与路由（沿用裁定）

顶级板块「Agent 中心」。分组节点与叶子共用 `/agents` 沿用既有 `~` 后缀机制（票 18 全局清理，本板块不另建）。

| 页面 | 路由 | 菜单 | 端点 |
|---|---|---|---|
| P1 Agent 列表 | `/agents` | 可见 | `GET /api/v1/agents` |
| P3 运行记录 | `/agents/runs` | 可见 | `GET /api/v1/agent-runs` |
| P2 Agent 详情 | `/agents/:slug` | 隐藏 | `GET /api/v1/agents/{slug}` + `/versions` |
| P4 运行详情 | `/agents/runs/:runId` | 隐藏 | `GET /api/v1/agent-runs/{runId}` |
| P5 评测 | `/agents/:slug/evals` | 隐藏（P2 内 tab） | `GET /api/v1/agents/{slug}/versions/{version}/eval-cases` |

**筛选与分页一律进 query string**，刷新与分享可复现同一视图。详情走真实路由，不用本地状态抽屉。

## 2. P1 Agent 列表 — 字段映射

响应 `AgentListItem`：`slug` / `name` / `state` / `enabled` / `current_version` / `draft_count` / `seven_day_run_count` / `seven_day_failure_count` / `allow_write` / `model_ref` / `prompt_version` / `tools[]`。

| 列 | 字段 | 呈现规则 |
|---|---|---|
| Agent | `name` + `slug` | slug 等宽字体，是身份键 |
| 运行状态 | `state` | RUNNING→「运行中」绿；DISABLED→「已停用」灰；NO_ACTIVE_VERSION→「无生效版本」橙。**直接映射，不得再用 enabled 二次推导** |
| 当前版本 | `current_version` | 为 null 时显示「—」而非 0 |
| 待确认草稿 | `draft_count` | **>0 才显示徽标**，是主要行动召唤；=0 不占视觉 |
| 工具 | `tools.length` | 悬停展开清单，**`read_only=false` 的工具要视觉区分**；`registered=false` 标注「未注册」（配置漂移） |
| 写权限 | `allow_write` | true 才显示醒目标记，且**界面不提供修改入口** |
| 近 7 日 | `seven_day_run_count` / `seven_day_failure_count` | 失败数 >0 时着色；两者皆 0 时显示「无运行」 |

筛选（进 URL）：`state`、有无待确认草稿、有无写权限。
主操作：「新建 Agent」→ `/agents/new`（**P6 后置，本期按钮禁用并注明"即将开放"，不要跳到 404**）。

## 3. P2 Agent 详情 — 两个 tab

**当前生效**：`system_prompt`（长文本、等宽、可折叠）、`prompt_version`、`model_ref`、`output_schema`（JSON 折叠视图）、`guard_exemptions`（**空数组时显示「默认守卫全部生效」，不是空白**）、`input_format`、`activated_by` / `activated_at`、`tools[]`（逐个列出并标注读写属性）。

**版本链**：`GET /versions` 返回 `version` / `status`（DRAFT/ACTIVE/RETIRED）/ `activated_by` / `activated_at`，画成时间线。

**写动作本期一律不做**（T11 尚未合并）。版本链上不得出现「回滚」按钮——即使后续加，文案也必须是「以此版本创建草稿」，因为状态机无回边，回滚是复制成新草稿再确认。

**页内运行摘要**：只放「最近 5 次运行」+「查看全部」跳 `/agents/runs?slug=<slug>`，**不在本页重复实现运行表格**（裁定二）。

## 4. P3/P4 运行记录 — 隔离 LIVE 与 PREVIEW

响应 `RunListItem`：`run_id` / `agent_slug` / `agent_version` / `status` / `outcome` / `run_mode` / `error_type` / `latency_ms` / `token_usage` / `business_entity_type` / `business_entity_id` / `intent` / `model_metadata` / `started_at`。

- **默认只看 LIVE**（后端默认即 LIVE，前端不传 `run_mode` 即可）。切到 PREVIEW 必须有明显视觉标识——`run_mode` 存在的全部理由就是防止草稿试跑污染对线上行为的判断。
- `outcome` 为 null（RUNNING 中）显示「进行中」，不要显示成空。
- 筛选进 URL：`slug` / `outcome` / `run_mode` / 时间范围 / 业务实体 + 分页（`limit` 1..500 / `offset`）。

**P4 详情**：`tool_calls[]` 按 `sequence_no` 升序画 Timeline（工具名、耗时、状态、已脱敏的参数与结果摘要）；`error_type` 放最显眼处；`eval_result` 有则展示。

**`input_digest` 是 SHA-256 摘要、没有原文**——必须显式说明「输入原文不留存，仅存摘要用于比对」，**不得渲染成空白**让人误以为丢了数据。

## 5. 模型元数据的三态文案（不得折叠）

| visibility | 文案 | 含义 |
|---|---|---|
| `EXPOSED` | 显示真实 provider/model/prompt_version | 命中服务端 allowlist |
| `NOT_PUBLIC` | 「未公开」 | 三元组存在但未登记 allowlist |
| `NOT_CONFIGURED` | 「未配置」 | 本来就没配模型 |

后两者看起来都是"空"，但运维含义完全不同——一个是没登记，一个是没配。折叠成同一个空值就丢掉了这个区分，而 T12 特意做出三态就是为了让界面能分。

## 6. 复用与不做

**复用**：`PageShell` / `FilterBar` / `DataTable` 三件套（jry 线票 17 已实现，本期一并移植到 master）。DataTable 自带 loading/空态/错误态/横向滚动默认值，页面不写也对。

**不做**：写动作（等 T11）、对话式创建（P6 后置）、提示词在线编辑、任何暴露 provider/model/api-key 真实值的入口（红线）、Agent 编排画布。
