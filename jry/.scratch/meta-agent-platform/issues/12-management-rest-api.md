# 12 — 管理 REST API 端点与权限设计

**Type:** grilling
**Status:** resolved
**Blocked by:** 03 ✅、04 ✅、06 ✅、07 ✅、08 ✅（设计输入全部落定）

## Question

一期交付面主体：Agent 平台的管理 REST API。已确认决策（直接采用，不再重问）：

- **定义域**（03/06/08）：`agent_definitions` 唯一真源 + 版本链，draft→active→retired 无回边；草稿→人工确认→启用；active 版本不可改（要改=新版本草稿）；`enabled`（启停）与 `status` 正交；`allow_write` 布尔；确认动作记录 activated_by/activated_at；审计只做流水。
- **评测域**（07）：`agent_eval_cases` 绑定 (agent_slug, agent_version)，PENDING/CONFIRMED 联动确认；metric_kind INVARIANT/QUALITY。
- **Meta-Agent**（06）：slug=meta-agent 由 V30 播种，变更只能人工经本 API（草稿→确认），全工具禁改。
- **运行域**（04/08）：`agent_runs` 加 run_mode（LIVE/PREVIEW）+ intent/provider；outcome 维度 SUCCESS/NEEDS_INPUT/REJECTED/FAILED；stdio MCP 面一期只读。
- **权限**：沿用既有 Basic Auth（/api）+ internal-auth（/internal）体系，无新多租户/角色体系（Out of scope）。

待决策点（grilling，一次一个，带推荐答案）：

1. **端点清单与分组**：定义 CRUD（草稿列表/详情/创建草稿(人工)/确认/拒绝/启停/版本历史/回滚预览）、评测用例管理（列表/补用例→新版本草稿）、运行记录查询（agent_runs 按 run_id/slug/时间范围/outcome 过滤）、Meta-Agent 调用端点（自然语言→草稿）——哪些进 /api（Basic Auth）哪些进 /internal（服务身份）。
2. **DTO 形态**：AgentDefinitionDto / DraftConfirmCommand / AgentRunQuery 等的字段与校验；与 03 表字段、04 outcome、08 allow_write 的映射。
3. **确认/拒绝动作的幂等与并发**：同一草稿并发确认、确认后再次确认、拒绝后恢复——语义（参考 MCP 写工具幂等模式）。
4. **回滚 UI 语义**：03 已定回滚=复制成新草稿再确认——API 是否需要「rollback」便捷端点（复制 active 为 draft）还是完全由 create/update 表达。
5. **Meta-Agent 调用端点**：同步返回草稿结果 vs 异步任务（跑 INVARIANT 门禁 + 异步 QUALITY，07 已定两级）——响应形态（202 + 查询端点？）。

## Answer

1. **端点清单与分组**：全部四域 11 端点进 `/api`（Basic Auth 人工面）：①定义域 `GET /api/agents` / `{slug}` / `{slug}/versions` + `POST /api/agents/drafts`（人工建草稿）/ `{slug}/drafts/{version}/confirm` / `reject` / `{slug}/set-enabled` / `{slug}/rollback`；②评测域 `GET /api/agents/{slug}/versions/{version}/eval-cases`；③运行域 `GET /api/agent-runs`（过滤 run_id/slug/时间/outcome/run_mode）+ `/{runId}`（含工具调用序列）；④Meta-Agent `POST /api/meta-agent/run`。`/internal` = **只读镜像面**（agent-runs 查询 + agents 列表/详情/版本历史），写操作只走 /api（操作人语义与人工确认红线一致）。
2. **DTO 形态**：record 直接映射 03 表字段 + jakarta 校验（slug 正则/长度上限/tool_whitelist 元素校验）；草稿创建/更新 = 全量快照体（06）；confirm/reject/set-enabled = 轻量命令体；**operator 一律取自 Basic Auth 身份、不进 body**（延续 MCP「工具参数无 operator」原则）。
3. **幂等与并发**：目标状态幂等——confirm 已 active 的同一版本返回 200+当前状态；retired/不存在 → 409/404；并发确认不同版本由 DB 部分唯一索引 `UNIQUE(slug) WHERE status='active'` 兜底、败者 409；reject 对已拒绝幂等 200、对已 active 409；**启停 = `set-enabled {enabled: bool}` 显式目标值**（避免 toggle 翻转语义的重复调用陷阱）。
4. **回滚**：`POST /api/agents/{slug}/rollback {target_version}`——目标版本须曾 active，服务端全量复制为 v{n+1} draft，走正常草稿→确认流。
5. **Meta-Agent 与建草稿入口 = 202 异步**：任务范围 = Meta 运行（工具发现+建草稿+静态门禁）+ INVARIANT stub 评测**一个闭环**（轮询一次拿「能否确认」全貌）；QUALITY 按 07 草稿创建后另起异步链路（PREVIEW）；轮询复用 `GET /api/agent-runs/{runId}`（run_mode=PREVIEW）；**人工建草稿 POST /api/agents/drafts 同样 202**（两入口一致）。

**与 07 的调和**：07「草稿提交同步快速门禁」在 REST 面调整为「202 任务内」；06 的写工具内嵌静态门禁仍**同步**拒绝落库（不过即 4xx，不产生任务），INVARIANT stub 评测移入异步任务。静态阻断语义不变。
