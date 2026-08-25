# 后台 Agent 管理看板接入 MCP 工具调研

> 日期：2026-08-20
> 结论性质：本仓库代码/工单现状核查（一手来源：`agent/` 包源码、`mcp/` 包源码、V33–V38 迁移、`frontend/src` 路由与导航、`.scratch/meta-agent-platform{,-impl}/` 设计票与实施票）。
> 业务背景：子牧订单履约与仓储物流中台（backend = Java 21 + Spring Boot 3 模块化单体）。本报告回答的问题是：**产品/运营人员如何在后台「Agent 管理看板」给某个受管 Agent 接入 MCP 工具（tool_whitelist）**——即仓库内部 meta-agent-platform（Agent 管理平台）能力，**不是**企业微信官方 MCP 接入（见 §2 方向澄清）。

---

## 1. 结论摘要

1. **「Agent 管理看板」目前不存在**：前端（`frontend/src/`）没有任何 Agent 页面（无 `pages/agent*` 目录、`routes.tsx` 无 agent 路由、`navigation.ts` 无「Agent/智能体」导航项）；后端也没有任何 Agent 管理 REST 端点（全库无 AgentController、无 `/api/agents`）。看板前端在设计中明确为**二期后置**（`.scratch/meta-agent-platform/map.md:111`），其消费的管理 REST API 三个实施票（T11/T12/T13）**尚未实现**（均 `ready-for-agent`）。
2. **「给 Agent 添加 MCP 工具」的后端能力链已实现大半（T01–T10，2026-08-19 全部落地）**：数据模型（`agent_definitions.tool_whitelist` JSONB + `allow_write`）→ 草稿写入（`AgentDraftService`，经 MCP 写工具 `create_agent_draft`/`update_agent_draft`）→ 静态门禁（`DefaultAgentGateEngine` 六项阻断，含工具白名单合法性 + 只读不变式）→ 工具绑定（`AgentToolBindingFactory`，白名单 + `allowWrite` 判定）→ 调用期复核与观测（`AgentToolInvoker` + `agent_tool_call` 落库）。**工具名必须来自 `McpToolRegistry`（33 个：25 只读 + 8 写），写工具进白名单必须 `allow_write=true`。**
3. **当前缺口（阻塞「看板点按钮加工具」的三件事）**：
   - 后端管理 REST API 未实现：T11（写端点：`POST /api/agents/drafts` + confirm/reject/set-enabled/rollback，202 异步）、T12（读端点：`GET /api/agents` 列表/详情/版本历史/eval-cases + `GET /api/agent-runs` + /internal 只读镜像）、T13（`POST /api/meta-agent/run` 自然语言建 Agent）全部 open（GitHub #12/#13/#14）；
   - 前端看板页面未实现（二期后置，09 调研已定 UI 方向）；
   - Agent 运行时（`AgentRuntimeFacade`）**无任何生产调用方**——meta-agent 定义已播种但今天没有任何入口能触发它运行。
4. **今天实际能给 Agent 配工具的路径只有两条，都不是「看板 UI」**：① 直接调用 MCP 写工具 `create_agent_draft`/`update_agent_draft`（但 stdio 面一期只读、拒绝写工具，实际只能经 Agent 面即 meta-agent 运行时——而运行时无生产入口，故当前**没有任何可用的人为入口**）；② 直接操作数据库/测试夹具。**结论：要让运营在后台看板给 Agent 加工具，必须先落地 T11/T12/T13（后端 API）+ 前端看板（二期）；本报告 §6 给出补全点与落地路径。**

---

## 2. 方向澄清（外部 MCP 方向，非本需求）

先前调研的「企业微信官方对 MCP 的支持」**不属于本需求**，仅作背景：

- 企微官方（截至 2026-08-20）没有「管理后台给智能机器人配置外部 MCP Server」的能力；智能机器人扩展方式 = 长连接（developer.work.weixin.qq.com/document/path/101463）+ HTTP 回调 + 插件/技能/知识库。2026-08-18 的企微 5.0.10 开放 CLI/MCP，但方向相反：企微作为 MCP **Server** 供外部 Agent（WorkBuddy/DeepSeek/OpenClaw 等）调用办公能力（官方页 work.weixin.qq.com/nl/index/aicli）。
- 本仓库要的 MCP 工具接入发生在**自己平台内部**：受管 Agent（`agent_definitions` 定义）通过 `McpToolRegistry` 工具白名单获得工具能力，与企微官方无关。企微只是 Agent 的未来可选入口之一（map.md:31「企微入口后置」）。

---

## 3. Agent 平台现状全貌（meta-agent-platform）

### 3.1 规划 vs 实施状态

- **设计层**（`.scratch/meta-agent-platform/`）：12/12 决策票全部 resolved（map.md:76-91）——03 数据模型 / 04 Runtime Adapter / 05 门禁 / 06 Meta-Agent / 07 评测 / 08 MCP 权限隔离 / 12 管理 REST API 等。
- **实施层**（`.scratch/meta-agent-platform-impl/`，map.md:37-51）：

| 票 | 内容 | 状态 |
|---|---|---|
| T01 | V33 迁移：agent_definitions / agent_runs / agent_eval_cases 三表落地与播种 | ✅ resolved |
| T02 | 注册表切 DB 真源 + 删代码定义 | ✅ resolved |
| T03 | INVARIANT 评测数据化 + 基线门禁读 DB | ✅ resolved |
| T04 | Runtime Adapter 骨架 + 通用门面（A 路径） | ✅ resolved |
| T05 | B/C 路径收敛（采购比价/数据查询） | ✅ resolved |
| T06 | D 路径意图桥适配（GitHub #7） | ✅ resolved |
| T07 | MCP 权限隔离：读写元数据 + 调用期复核 + stdio 只读（GitHub #8） | ✅ resolved |
| T08 | 门禁引擎 + 运行期 PII 守卫（GitHub #9） | ✅ resolved |
| T09 | QUALITY：promptfoo 执行器 + 异步评测（GitHub #10） | ✅ resolved |
| T10 | Meta-Agent 工具面：list_agent_tools + 定义写工具（GitHub #11） | ✅ resolved |
| **T11** | **异步任务基建 + 定义域写端点（202/confirm/reject/set-enabled/rollback）** | 🔴 **open（ready-for-agent，GitHub #12）** |
| **T12** | **读端点 + /internal 只读镜像（agents 列表/详情/版本/agent-runs）** | 🔴 **open（ready-for-agent，GitHub #13）** |
| **T13** | **Meta-Agent REST 端点（POST /api/meta-agent/run，202 闭环）** | 🔴 **open（ready-for-agent，GitHub #14）** |

### 3.2 后端管理 API：**不存在**（设计已定，见票 12 Answer）

设计（`.scratch/meta-agent-platform/issues/12-management-rest-api.md:27-31`）——全部 11 端点进 `/api`（Basic Auth 人工面），`/internal` 只读镜像：

- 定义域：`GET /api/agents`（列表）/ `{slug}`（详情）/ `{slug}/versions`（版本历史）；`POST /api/agents/drafts`（**人工建草稿，202 异步**）/ `{slug}/drafts/{version}/confirm`（确认前全量门禁复跑 + 联动确认 PENDING 用例）/ `reject` / `{slug}/set-enabled`（显式目标值幂等）/ `{slug}/rollback`（目标版本须曾 active，复制为 v{n+1} draft）；
- 评测域：`GET /api/agents/{slug}/versions/{version}/eval-cases`；
- 运行域：`GET /api/agent-runs`（过滤 run_id/slug/时间/outcome/run_mode）+ `/{runId}`（含工具调用序列，即 202 任务轮询面）；
- Meta-Agent：`POST /api/meta-agent/run`（自然语言 → 草稿，202 异步）。

**核查事实**：全库 grep `@RequestMapping/@GetMapping/@PostMapping` 无任何 agent 端点（仅有 order/inventory/product/message/shipment 等既有域）；`agent/` 包内无 `@RestController`；`AgentRuntimeFacade` 无 agent 包之外的生产调用方（仅 `AgentRegistryConfiguration` 装配 + 测试）。

### 3.3 前端「Agent 管理看板」：**不存在**（二期后置）

- `frontend/src/pages/` 无 agent 目录（现有：analytics/dashboard/demo/fulfillment/inventory/orders/procurement/product/shared/system/upload/workbench）；`frontend/src/routes.tsx:55-88` 无 agent 路由；`frontend/src/navigation.ts:18-93` 导航树 = 工作台/作业中心/订单中心/库存中心/主数据/上传平台/经营分析/系统管理/模拟下单/管理驾驶舱，**无「Agent/智能体」入口**（`/workbench` 是人工复核/渠道消息，非 Agent 管理）。
- 设计定位：map.md:111「前端 Agent 管理页的实现（二期，后置为后续 effort）」；09 调研已定 UI 方向（map.md:44）：对话式创建用 `@ant-design/x@1.6.x`，列表/草稿确认/运行看板用 antd 原生 + echarts 自研。

---

## 4. 给 Agent 添加 MCP 工具的完整链路（现状已实现的正确姿势）

工具接入的**数据与执行链路**已全部实现（T07/T08/T10），每个环节的入口与校验如下：

### 环节 1：工具源 — `McpToolRegistry`（唯一工具源，33 个）

- 注册表聚合 4 个工具类（`McpToolRegistry.java:20-38`）：McpReadTools / McpWriteTools / McpDomainReadTools / McpControlReadTools。
- **只读工具（readOnly=true，25 个）**：list_channel_messages、get_channel_message、get_message_submission、list_interpretations、list_message_media、list_order_drafts、get_order_draft、list_tracking_drafts、get_tracking_draft、get_order_draft_candidates、get_tracking_draft_candidates、list_review_cases、get_review_case（McpReadTools.java:83-166）；list_procurement_tickets、get_procurement_ticket、list_procurement_receipts、search_skus、get_sku、list_provider_skus、get_inventory_overview、get_inventory_detail、list_products、list_categories、list_fulfillment_providers（McpDomainReadTools.java:60-148）；list_agent_tools（McpControlReadTools.java:33）。
- **写工具（readOnly=false，8 个）**：reinterpret_submission、submit_order_draft_suggestion、confirm_order_draft、submit_jd_outbound、submit_supplementary_material、submit_review_request、create_agent_draft、update_agent_draft（McpWriteTools.java:82-180）。
- **工具清单可查**：只读工具 `list_agent_tools`（McpControlReadTools.java:33）返回全量工具的名称/描述/参数 schema/读写属性，供人工或元 Agent 规划白名单——工具面增长无需改提示词。

### 环节 2：Agent 定义 — `agent_definitions`（DB 真源）

- 表结构（`V33__agent_platform_definitions.sql:16-38`）：agent_slug / name / description / system_prompt / prompt_version / model_ref / enabled / version / status（draft|active|retired 无回边）/ activated_by / activated_at / **allow_write**（默认 false）/ guard_exemptions / output_schema / **tool_whitelist**（JSONB 数组）/ input_format（V36）。
- 版本状态机：`UNIQUE (agent_slug, version)` 全快照 + 部分唯一索引 `UNIQUE(agent_slug) WHERE status='active'`（V33:41-44）；`enabled`（运维启停）与 `status`（版本生命周期）正交（AgentDefinition.java:14-19）。
- 运行时定义：`AgentDefinition` record（AgentDefinition.java:26-42），`toolNames` 即白名单（一期白名单即权限 profile，08 决策）。

### 环节 3：写草稿 — `AgentDraftService`（经 MCP 写工具）

- 入口 A（Agent 面）：`create_agent_draft` / `update_agent_draft` 两个 MCP 写工具（McpWriteTools.java:164, 173，readOnly=false）→ `AgentDraftService.createDraft/updateDraft`（AgentDraftService.java:63-80）。
- 服务端校验（AgentDraftService.java:82-156）：slug 格式 `^[a-z][a-z0-9-]{0,63}$`；唯一性（重复 409 `AGENT_SLUG_EXISTS`）；版本分配（新 slug=v1；update 对 draft 最新版原地覆盖、active/retired 之上开新版本；含 CONFIRMED 冻结用例的 draft 不覆盖）；`allow_write` 严格布尔（字符串/数字拒绝）；target ≠ meta-agent（`AGENT_TARGET_FORBIDDEN` 403 防自改）；**08 静态门禁不过 → 拒绝落库（不产生脏草稿）**；suggested_eval_cases 落 PENDING QUALITY 用例。
- 幂等 + 审计：写工具外层 `executeWrite`（幂等键 + `mcp.{tool}` AGENT 审计 + REQUIRES_NEW 失败审计）。
- **入口 B（人工 REST）**：`POST /api/agents/drafts` —— **未实现（T11）**。

### 环节 4：静态门禁 — `DefaultAgentGateEngine`（六项阻断）

（DefaultAgentGateEngine.java:9-24，T08 落地）：① 结构完整性（长度上限）② **工具白名单合法性（每个名称必须在 McpToolRegistry）** ③ **只读不变式（白名单含写工具且无 allow_write=true → 阻断）** ④ output_schema 可解析（networknt）⑤ 凭据扫描 ⑥ 越权指令扫描；PII 仅警告。写工具落库前与 confirm 前全量复跑共用。

### 环节 5：工具绑定 — `AgentToolBindingFactory`

（AgentToolBindingFactory.java:16-34, 78-99）：每个 Agent run 按 `AgentDefinition.tool_names` 从 `McpToolRegistry` 生成 LangChain4j `ToolSpecification` + 执行器；**未知工具 fail-fast**（「Agent 工具白名单引用未知 MCP 工具」）；**白名单含写工具且 `allowWrite=false` → 绑定期拒绝（fail-fast）**。`McpToolSchemaConverter` 负责 MCP JSON Schema → LangChain4j JsonSchemaElement（McpToolSchemaConverter.java:15-22）。

### 环节 6：调用期复核与观测 — `AgentToolInvoker`

（AgentToolInvoker.java:19-39）：每次工具调用按绑定白名单复核（白名单外 `TOOL_NOT_AUTHORIZED`，防旁路真强制点，fail-closed）；requestId=traceId=run_id；操作人来自服务端注入的 `McpAgentIdentity`（工具参数无 operator）；观测经 `AgentObservability`（JdbcAgentObservability）落 `agent_tool_call` 行（参数/结果脱敏摘要），失败隔离不影响工具结果。

### 环节 7：运行入口 — `AgentRuntimeFacade`（当前无生产调用方）

`AgentRuntimeFacade.invoke` 是唯一驱动 Agent run 的门面（含守卫/审计/观测/绑定，AgentGuard.java:15）。**核查：除装配与测试外无生产调用方**——即今天没有任何 REST/聊天/企微入口能触发一个 Agent（含 meta-agent）真正运行。既有「意图桥」（IntentRecognitionAgentBridge）只写解释任务的观测记录（agent_runs），不走工具调用。

---

## 5. 现状缺口（为什么「看板没有添加 MCP 工具的按钮」）

| # | 缺口 | 证据 | 阻塞点 |
|---|---|---|---|
| 1 | 后端管理 REST API 未实现 | T11/T12/T13 均 `ready-for-agent`（impl/map.md:49-51；GitHub #12/#13/#14）；全库无 AgentController | 「看板」无 API 可调；草稿**无法确认上线**（confirm 未实现） |
| 2 | 前端 Agent 看板页面未实现 | `frontend/src/pages/`、`routes.tsx`、`navigation.ts` 均无 agent 条目；map.md:111 二期后置 | 无 UI 入口 |
| 3 | Agent 运行时无生产入口 | `AgentRuntimeFacade` 无 agent 包外调用方 | meta-agent 已播种（V33，allow_write=true）但无人能触发 |
| 4 | MCP stdio 面只读（一期决策） | McpServer.java:122-129：stdio 面 tools/call 拒绝写工具 | 即使想用 `create_agent_draft` 走 stdio 也不行（写工具被拒），只能经 Agent 面 |

**因此今天（2026-08-20）运营/产品在后台给 Agent 接入 MCP 工具的实际操作路径是：没有可用的 UI 或 API 路径。** 工具白名单在数据层面的「正确填法」已经定义清楚（§4），但写入只能经未上线的 Agent 面或直接改库。

---

## 6. 落地路径（补全点 + 分步）

### 6.1 近期：落地 T11/T12/T13（后端 API，`ready-for-agent`，可直接开工）

1. **T11 写端点**（impl/issues/11）：异步任务基建（任务表 + Spring Worker，复用 message-worker 模式）+ `POST /api/agents/drafts`（202）/ confirm（确认前全量门禁复跑 + 联动确认 PENDING 用例）/ reject / set-enabled（显式目标值幂等）/ rollback；operator 取自 Basic Auth 身份不进 body；幂等 = 目标状态幂等，并发确认败者 409（DB 部分唯一索引兜底）。
2. **T12 读端点**（impl/issues/12）：`GET /api/agents`（列表）/ `{slug}` / `{slug}/versions` / `{slug}/versions/{version}/eval-cases` + `GET /api/agent-runs`（202 轮询面，详情含工具调用序列）；`/internal` 只读镜像（internal-auth Bearer）。
3. **T13 Meta-Agent 端点**（impl/issues/13）：`POST /api/meta-agent/run`（自然语言 → 202 异步任务：list_agent_tools 工具发现 + create/update_agent_draft 建草稿 + 静态门禁 + INVARIANT stub 评测闭环；NEEDS_INPUT/REJECTED 完整返回）。

> 落地后运营路径：`POST /api/agents/drafts`（人工建草稿，tool_whitelist 填工具名数组）或 `POST /api/meta-agent/run`（自然语言：「新建一个 Agent 叫 xxx，白名单含 search_skus、get_inventory_overview…」）→ `GET /api/agents` 看列表/详情 → `POST /api/agents/{slug}/drafts/{version}/confirm` 人工确认 → 该版本 `status=active` 生效。

### 6.2 中期：前端 Agent 管理看板（二期，09 调研已定方向）

- 对话式创建：`@ant-design/x@1.6.x`（锁版本）；列表/草稿确认/运行看板：antd 原生 + echarts；编排画布（如做）：`@xyflow/react`（map.md:44）。
- 页面建议挂载点：导航树新增一级「Agent 平台」（或并入「系统管理」），路由如 `/agents`、`/agents/:slug`、`/agents/:slug/versions`、`/agents/runs`——消费 T11/T12/T13 端点；「添加 MCP 工具」即草稿编辑中的 `tool_whitelist` 多选（选项来自 `GET /api/agents/...` 侧的工具清单端点，或先由 `list_agent_tools` 语义提供——注意：`list_agent_tools` 是 MCP 工具，前端看板更自然的来源是新增一个 `GET /api/mcp-tools` 只读端点或在 `GET /api/agents` DTO 里带可用工具元数据，可并入 T12 范围）。
- 写工具可见性：看板 UI 上对写工具应提示「需 allow_write=true」，避免运营误配后被 08 门禁拒。

### 6.3 远期：企微入口（后置，倾向不做）

map.md:112「企微入口创建 Agent（后置，倾向不做）」——企微消息 → meta-agent 的接入等看板/API 稳定后再评估。

---

## 7. 验证方式（现状能力自检）

| 层 | 手段 |
|---|---|
| 工具源清单 | `list_agent_tools`（MCP 只读工具）或 `McpControlReadToolsTest`；注册表 33 工具、读写元数据一致（07 元数据，`registry.writeToolNames()`） |
| 草稿写入 + 门禁 + 幂等 + 审计 | `MetaAgentDraftIntegrationTest`（9 例：创建草稿+PENDING 用例+审计+幂等重放、update 原地覆盖/active 开新版本、唯一性 409、allow_write 非布尔拒绝、meta-agent 禁改 403 无副作用、门禁拒绝零脏行、meta-agent 白名单绑定） |
| 绑定判定 | `AgentToolBindingFactoryTest`（未知工具 fail-fast、写工具无 allow_write 拒绝） |
| 调用期复核 + 观测 | `AgentToolInvokerTest` / `AgentMcpToolBindingAcceptanceTest`（TOOL_NOT_AUTHORIZED、agent_tool_call 落库） |
| 门禁 | `DefaultAgentGateEngineTest` / `AgentGateScanTest`（六项阻断、PII 警告） |
| 数据模型 | `AgentPlatformSeedVerbatimTest`（种子与代码定义逐字一致） |
| 端到端 | T13 验收标准（impl/issues/13:13）：「自然语言建 Agent → 确认 → 上线运行」，落地 T11/T12/T13 后以此为准 |

---

## 8. 相关工单清单

- 设计层（`.scratch/meta-agent-platform/issues/`，12/12 resolved）：03 数据模型 / 04 Runtime Adapter / 05 门禁守卫 / 06 Meta-Agent 定义 / 07 评测数据化 / 08 MCP 权限隔离 / 12 管理 REST API（`issues/12-management-rest-api.md` Answer 即 §3.2 端点设计）。
- 实施层（`.scratch/meta-agent-platform-impl/issues/`）：T01–T10 resolved（含 T10 Answer 见 `issues/10-meta-agent-tools.md`）；**T11/T12/T13 open**（GitHub https://github.com/rgfan123/zimu/issues/12、/13、/14），这是「看板给 Agent 加工具」的主阻塞。
- 前端：无对应工单（二期后置）；UI 方向见 `.scratch/meta-agent-platform/research/09-ai-agent-ui-libraries.md`（map.md:44 摘要）。

## 9. 来源链接清单

### 本仓库一手来源（路径 + 关键行号）
- 后端 agent 平台：
  - `backend/src/main/java/cn/zimu/fulfillment/agent/AgentDefinition.java`（26-42 字段；14-19 版本链/allow_write/guard_exemptions/output_schema 语义）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/AgentDraftService.java`（63-80 入口；82-156 校验与门禁；243-363 草稿载荷解析）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/AgentToolBindingFactory.java`（78-99 绑定与 allow_write 判定；101-107 schema 转换）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/AgentToolInvoker.java`（19-39 调用期复核 + 观测）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/DefaultAgentGateEngine.java`（9-24 六项阻断；29-34 长度上限）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/AgentRuntimeFacade.java` / `AgentRegistryConfiguration.java`（运行门面，无生产调用方）
  - `backend/src/main/java/cn/zimu/fulfillment/agent/IntentRecognitionAgentBridge.java`（意图桥只写观测，不走工具调用）
- 后端 MCP：
  - `backend/src/main/java/cn/zimu/fulfillment/mcp/McpToolRegistry.java`（20-38 聚合；52-57 writeToolNames）
  - `backend/src/main/java/cn/zimu/fulfillment/mcp/McpControlReadTools.java`（33 list_agent_tools）
  - `backend/src/main/java/cn/zimu/fulfillment/mcp/McpWriteTools.java`（82-180 八个写工具，含 create/update_agent_draft）
  - `backend/src/main/java/cn/zimu/fulfillment/mcp/McpServer.java`（122-129 stdio 只读拒绝写工具）
- 迁移：`backend/src/main/resources/db/migration/V33__agent_platform_definitions.sql`（16-38 表结构；90-98 播种注释；180-207 meta-agent 种子，tool_whitelist = `["list_agent_tools","create_agent_draft","update_agent_draft"]`，allow_write=true）、`V36__agent_definition_input_format.sql`、`V38__agent_eval_results.sql`
- 前端：`frontend/src/navigation.ts`（18-93 导航树，无 agent 条目）、`frontend/src/routes.tsx`（55-88 路由，无 agent 路由）、`frontend/src/pages/`（无 agent 目录）
- 工单：`.scratch/meta-agent-platform/map.md`（76-91 票表；111 前端二期后置）、`.scratch/meta-agent-platform/issues/12-management-rest-api.md`（27-31 端点设计）、`.scratch/meta-agent-platform-impl/map.md`（37-51 票表）、`.scratch/meta-agent-platform-impl/issues/10/11/12/13-*.md`
- 测试：`backend/src/test/java/cn/zimu/fulfillment/agent/MetaAgentDraftIntegrationTest.java`、`AgentMcpToolBindingAcceptanceTest.java`、`AgentToolBindingFactoryTest.java`、`DefaultAgentGateEngineTest.java`、`AgentPlatformSeedVerbatimTest.java`

### 外部背景来源（企微官方方向，非本需求，仅存档）
- [企业微信 智能机器人长连接 - 开发者文档](https://developer.work.weixin.qq.com/document/path/101463)
- [WeCom AI 开放能力（官方页，5.0.10 CLI/MCP）](https://work.weixin.qq.com/nl/index/aicli)
- [社区问答：智能机器人未来是否有支持 MCP 的计划？](https://developer.work.weixin.qq.com/community/question/detail?content_id=16822925482912990855)
- [MCP Transports（2025-06-18 规范）](https://modelcontextprotocol.org/specification/2025-06-18/basic/transports)
