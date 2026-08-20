# 12 — 读端点 + /internal 只读镜像

**What to build:** 管理 REST 的读面（12 决策）：① /api（Basic Auth）读端点——`GET /api/agents`（列表：slug/name/status/enabled/version）、`/api/agents/{slug}`（详情）、`/api/agents/{slug}/versions`（版本历史）、`/api/agents/{slug}/versions/{version}/eval-cases`（评测用例查看）、`GET /api/agent-runs`（过滤 run_id/slug/时间范围/outcome/run_mode）+ `/{runId}`（详情含工具调用序列）；DTO record 直映射 + 校验；② `/internal`（服务身份）只读镜像——agent-runs 查询 + agents 列表/详情/版本历史，无任何写端点；③ 与 13 的轮询复用关系：agent-runs 端点即 202 任务轮询面。

**Blocked by:** 02 — 注册表切 DB 真源；03 — INVARIANT 评测数据化（设计源：meta-agent-platform 票 12）。

**Status:** resolved

- [x] 读端点契约测试（过滤条件生效、字段投影正确、无 PII/凭据外泄）
- [x] /internal 镜像只读（无写端点）；Basic Auth 与 internal-auth 各自鉴权正确
- [x] agent-runs 详情含工具调用序列（可支撑 202 任务轮询展示）

## Resolution

实现于分支 `dsh/t12-read-endpoints`（worktree，未提交，改动留在工作区）。端点清单与响应投影见下表；消费方五条要求逐条落实；本票测试全绿（既有基线 3 例非本票失败见文末说明）。

**端点清单（全部 GET，DTO record 直映射 + 服务端校验）**

| 端点 | 投影 |
|---|---|
| `GET /api/v1/agents` | 一行一个 slug：slug/name/state（RUNNING/DISABLED/NO_ACTIVE_VERSION）/enabled/current_version/draft_count/seven_day_run_count/seven_day_failure_count/allow_write/model_ref/prompt_version/tools[{name,read_only,registered}] |
| `GET /api/v1/agents/{slug}` | 代表行（active 优先，否则最新）全量定义事实：system_prompt/prompt_version/model_ref/output_schema/guard_exemptions/activated_by/activated_at/input_format/tools 等；404 未知 slug |
| `GET /api/v1/agents/{slug}/versions` | 版本链：version/status（draft/active/retired）/activated_by/activated_at（时间线）；404 未知 slug |
| `GET /api/v1/agents/{slug}/versions/{version}/eval-cases` | 冻结用例集：id/metric_kind/input/expected/status/created_by/confirmed_by/confirmed_at；可选 metric_kind 过滤；404 未知版本 |
| `GET /api/v1/agent-runs` | 过滤 run_id/slug/outcome/run_mode/时间范围/业务实体 + limit/offset 分页；默认 run_mode=LIVE（不返回 PREVIEW）；outcome 由 (status, error_type) 派生（FAILED+PII_GUARDED=REJECTED） |
| `GET /api/v1/agent-runs/{runId}` | 详情：元信息 + input_digest（SHA-256，无原文）+ 工具调用序列（tool_calls 按序号升序）+ eval_result 摘要 + model_metadata 三态投影 |
| `/internal/v1/agents`、`/internal/v1/agents/{slug}`、`/internal/v1/agents/{slug}/versions`、`/internal/v1/agent-runs`、`/internal/v1/agent-runs/{runId}` | 与 /api 同一 `AgentReadService`/`AgentRunReadService` 投影的只读镜像（12 决策：不含评测用例面） |

**消费方五条要求**

1. 列表一次拿全：三次 SQL（全量版本定义按 slug 分组 + draft 计数聚合 + 近 7 日 LIVE 运行统计聚合），Java 侧合并，无 N+1；`seven_day_run_count`/`seven_day_failure_count` 只统计 `run_mode='LIVE'`。
2. 版本链：`versions` 返回全部版本及 status 与 activated_by/activated_at（确认事实与 status='active' 同事务）。
3. `run_mode` 过滤：`AgentRunFilter.effectiveRunMode()` 默认 LIVE；`run_mode=PREVIEW` 显式请求才返回草稿试跑；`/internal` 镜像同一语义。
4. 工具白名单读写属性：`tools[{name, read_only, registered}]` 取 `McpToolRegistry` 的 08 决策元数据（meta-agent 的 create_agent_draft/update_agent_draft 投影 read_only=false；未注册工具 registered=false 不误标）。
5. 模型元数据三态投影：`model_metadata{provider, model, prompt_version, visibility}`——EXPOSED（命中 allowlist，真实值）/ NOT_PUBLIC（存在但未命中 allowlist，折叠 none）/ NOT_CONFIGURED（存储三元组即 none）；未配置与未公开不再折叠成同一个空值。

**红线落实**：密钥/凭据绝不进 DTO（运行三元组仅经 `AgentModelMetadataRegistry` allowlist 投影后暴露）；`/internal` 无任何写端点（结构性扫描 RequestMappingHandlerMapping + HTTP 405 双层断言）；收件人/客户 PII 绝不出现在响应（运行输入只有 `input_digest`，负例断言验证原文不出现）。

**测试**：`AgentReadEndpointsApiTest`（18 例：/api 鉴权、列表聚合、工具读/写属性、详情/版本链/评测用例、run_mode 默认排除 PREVIEW、outcome/slug/run_id/时间范围/业务实体过滤、非法参数 400、工具调用序列、模型元数据三态、PII/凭据负例、404）+ `AgentInternalMirrorApiTest`（7 例：internal-auth 鉴权、Basic↔Bearer 互斥、镜像只读结构+HTTP 断言、镜像与 /api 同投影、过滤语义一致）。全量 `mvn test`：829 例，本票 25 例全绿；仅 3 例非本票引入的既有失败——`ConnectorApiTest`（SourceChannel 含 ZHONGHUI 后 hasSize(4) 断言未同步，基座 e7ec550 引入，单独运行亦失败）与 `CaishixianSourceFileParserTest`/`ZhonghuiSourceFileParserTest`（依赖 git-ignored 夹具目录 `待发货订单-测试/`，仅存在于主工作树、worktree 缺失）。
