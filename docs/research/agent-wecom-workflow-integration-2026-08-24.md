# 后台 Agent × 企业微信卡片/Excel 接入当前系统调研

> 日期：2026-08-24（Asia/Shanghai）  
> 研究范围：当前检出版本 `1e6ec093dfef35adb064ecf96bcd7b75258b58f3`、远端 `master` 快照 `68958e00fc8d9a5916d3607a4d190be3e26f33a3`、开放 PR [#126](https://github.com/rgfan123/zimu/pull/126) head `923beea84ec6d904b8e6ee39e4c866bad3262728`，以及企业微信团队官方 [AI Bot Node SDK](https://github.com/WecomTeam/aibot-node-sdk/tree/80615b987ef69c6028ad764924609247c0725955)。  
> 边界：这是接入研究，不是实施规格；本报告没有修改业务代码、数据库或 GitHub Issue。PR #126 在调研时为 OPEN、CLEAN、前后端检查为 SUCCESS，不能据此称为已合并、已部署或生产验收通过。

## 1. 结论

卡片不能作为一个独立的“企微通知功能”接在订单表后面。正确接法是把企微作为后台 Agent 工作流的**人工控制面**：Agent 只读取证据、整理业务草稿、生成摘要/建议；卡片展示数据库中的确定性事实与 Agent 建议；点击者经内部人员授权后才能确认；正式落库、任务分派、Excel 生成与对外发送均由确定性应用服务执行。

建议闭环：

```text
原始材料 / 面谈大体草稿
  → 持久化 MessageSubmission / BusinessFollowUp
  → AsyncTask（租约、重试、代际 fence）
  → AgentRuntimeFacade（整理 Agent）
       └─ 只读 MCP：查询客户、商品、历史事实
  → 业务编排服务校验 Agent 结构化输出并持久化“业务草稿建议版本”
  → 待 +1 确认卡（事实 + Agent 摘要 + 待确认项）
  → 授权的 +1 点击确认 / 要求重做 / 要求补充
  → 确定性应用服务落正式业务事实
  → Assignment / 后续任务
  → 确定性卡片或 Excel 生成
  → 持久化 Outbox → WecomOutboundGateway → ACK / UNKNOWN 对账
```

但当前系统只完整覆盖其中一条订单子链：`MessageSubmission → 模型解释 → OrderDraft + ReviewCase → 人工确认 → Order`。仓库中没有通用 `BusinessFollowUp`、没有“+1 审批”领域对象、也没有通用 `Assignment` 聚合；因此不能把现有 `AgentDraftService` 当成业务草稿直接复用。

## 2. 必须先分清的两个“草稿”

### 2.1 `AgentDraftService` 是 AgentDefinition 草稿，不是业务草稿

`backend/src/main/java/cn/zimu/fulfillment/agent/AgentDraftService.java:19-22,245` 写的是 `agent_definitions`：提示词、模型、工具白名单、`allow_write`、守卫豁免、输出 schema 等 Agent 配置版本。它的确认意味着“这个 Agent 定义可以上线”，不是“客户面谈结论可以进 ERP”。PR #126 中 `AgentDefinitionWriteController` / `AgentDefinitionWorker` 已补定义草稿异步写入口，但仍属于 Agent 控制平面。

### 2.2 当前业务草稿是具体领域对象

目前与本需求最接近的是 `OrderDraft`：`InterpretationService` 在事务外调用模型，在事务内追加不可覆盖的解释版本并路由（PR head，`backend/src/main/java/cn/zimu/fulfillment/message/InterpretationService.java:70-115,203-244`）；`WecomOrderDraftFactory` 只接受模型白名单原始描述，忽略模型给出的内部 ID，再确定性匹配客户/SKU、计算缺失字段、创建 `OrderDraft + ReviewCase`（同 commit，`.../WecomOrderDraftFactory.java:34-40,90-133,177-204,236-272`）。

这条链证明当前系统已有“模型整理 → 业务草稿 → 人工复核”的局部能力，但它只覆盖订单意图。面谈、样品、报价、账期和后续动作需要新增通用 `BusinessFollowUp`（业务事实主体）及版本化 `BusinessFollowUpDraft`；+1 确认后才投影为订单草稿、样品申请、报价任务等具体对象。

## 3. 当前后台 Agent 能力及其接入职责

| 现有能力 | 已有事实 | 在卡片工作流中的职责 | 不应承担 |
|---|---|---|---|
| `AgentRuntimeFacade` | 按生效 AgentDefinition 解析 slug、检查 enabled、生成 run_id、绑定白名单工具、调用模型、记录审计与 `agent_run`（PR head，`AgentRuntimeFacade.java:11-39,83-137,159-180`） | 运行“业务整理 Agent”；输入必须引用明确的业务主体/版本，输出必须过 schema；run_id 关联 draft version 与卡片 | 不直接拼卡、不发企微、不确认订单、不写正式 ERP 事实 |
| `AgentToolInvoker` | 工具身份由服务端注入；参数不能伪造 operator；运行时再次按白名单复核；白名单外返回 `TOOL_NOT_AUTHORIZED`；工具调用序列脱敏留痕（`AgentToolInvoker.java:18-38,79-136`） | 给整理 Agent 只暴露读取客户、商品和历史事实的工具；首版保持 `allow_write=false` | 不把提交草稿、确认订单、京东建单或企微发送工具放进整理 Agent 白名单；Agent 输出由业务编排服务验证后落为草稿 |
| `DefaultAgentGateEngine` | Agent 定义确认前检查结构、工具存在性、写工具与 `allow_write`、schema、凭据、越权提示词；失败 fail-closed（`DefaultAgentGateEngine.java:9-24,53-95`） | 约束“这个整理 Agent 能否上线” | 不替代每次运行时的业务权限与点击人授权 |
| `AgentGuard` | 默认用关键词拒绝含客户、收货人、手机、地址等 PII 的输入；D 路径消息解释明确不走此默认守卫（`AgentGuard.java:6-22,26-51`） | 运行时保护输入；普通查询 Agent 继续默认拒绝 PII | 不能为了方便给所有 Agent 全局豁免 PII |
| `AsyncTaskStore` | PostgreSQL 持久化任务，幂等键唯一；`FOR UPDATE SKIP LOCKED` 领取；租约、重试、重启恢复、代际/所有权 fence（`AsyncTaskStore.java:17-43,52-93,133-202`） | 承载 Agent 重做、补充后重跑、卡片发送、Excel 生成/发送；外部调用前续租 | 不把模型/企微调用放在数据库长事务内 |
| MCP stdio 边界 | 外部 stdio 面只暴露只读工具，写工具调用被拒（`McpServer.java:111-129`） | 继续作为外部客户端只读边界 | 不为了企微按钮开放全局 MCP 写面 |

### PII 的建议方案

订单草稿包含姓名、手机号、地址，而 `AgentRuntimeFacade` 默认守卫会拒绝这些字段。不能简单给“所有后台 Agent”增加 `PII` exemption。应采取两级方案：

1. 优先把姓名/手机号/地址由确定性代码从已解析结构或受控业务表读取，传给 Agent 的是引用、掩码或最小必要片段；卡片渲染器始终从当前业务草稿读取完整事实，不从 Agent 文本反解析 PII。
2. 如果整理 Agent 确实必须读原始含 PII 面谈材料，只为专属 AgentDefinition 显式配置 PII 豁免，并同时要求：固定业务域、固定 output schema、最小只读工具白名单、无正式写工具、模型/留存合规、输入输出脱敏审计。`guard_exemptions` 只是允许进入模型，不等于获得业务写权限。

## 4. 卡片内容：确定性事实与 Agent 内容必须分栏

| 卡片区块 | 来源 |
|---|---|
| 草稿号、版本、状态、客户、收货人、手机号、详细地址、商品、SKU、数量、结算、缺失字段、负责人、截止时间 | 业务数据库的当前版本；确定性渲染 |
| AI 摘要、本次沟通结论、风险提示、待确认问题、建议后续动作 | 对应 `agent_run` 的结构化输出；持久关联 AgentDefinition version / run_id，但卡面不必直接暴露内部 run_id |
| “可确认/不可确认”、是否存在开放 ReviewCase、按钮集合 | 确定性规则；Agent 不能决定 |
| 发送对象与隐私形态 | 路由策略：用户已确认单聊可显示完整收货信息，群聊必须脱敏；这与 PR #126 当前卡片“始终不显示电话/地址”不同，需形成显式规格变更（`docs/agents/wecom-order-draft-cards.md:3-5`） |

建议首批卡片只有四类：

1. **业务草稿待 +1 确认卡**：核心入口，按钮“确认”“让 Agent 重做”“需要补充”。
2. **订单草稿确认卡**：显示用户要求的收货人姓名、手机号、详细地址、商品名称/规格/数量；单聊全量，群聊脱敏；资料不完整时不显示“确认订单”。
3. **执行任务/异常卡**：来自 Assignment、ReviewCase 或履约异常；Agent 可给处置建议，状态与按钮由领域规则生成。
4. **Excel 就绪卡/文件消息**：先发摘要卡，文件由确定性代码生成并作为媒体消息发送。

PR #126 已有 `REVIEW_CASE / ORDER_CREATED / SHIPMENT_COMPLETED` 五分钟窗口通知 outbox，但它是事实汇总而非 Agent 工作台；outbox 不复制 PII（`docs/agents/wecom-business-notifications.md:5-22,39-58`）。建议复用其持久化投递和 UNKNOWN 对账纪律，不让 Agent 自己调用企微传输。

## 5. 按钮工作流与权限边界

### 5.1 确认

`confirm` 必须调用确定性应用用例，以卡片固化的 `business_entity_id + expected_revision + approval_id` 作版本断言；重新读取当前事实后落库。不能让整理 Agent 调 MCP `confirm_order_draft`。

PR #126 已经重读草稿、校验 revision/缺失字段并复用 `OrderDraftService.confirm`（`OrderDraftCardConfirmationService.java:33-141`），也校验卡片是平台 ACK 成功的 SENT 记录与发送路由匹配（`WecomOrderDraftCardInteractionService.java:156-217`）。但点击人授权仍有真实缺口：它只检查 `from.userid` 非空；单聊确保点击人是收件人，群聊只确保群 chatid 相同，群内任意成员都可能成为 `wecom:{userid}` 操作人。虽然 PR 已有 `InternalOperator` / `OperatorResolver`，卡片确认链未按 userid 反查 active 内部人员、责任团队和审批角色。上线前必须新增 fail-closed 的 `resolveActiveByWecomUserid(userid)` 与“是否为该业务的 +1/授权审批人”校验。

### 5.2 Agent 重做 / 需要补充

回调线程不直接调用模型：

1. 持久化 event claim，校验 SENT 卡、版本、点击人授权；
2. 入队 `BUSINESS_DRAFT_REGENERATE`，幂等键建议 `followup-redo:{followupId}:{sourceRevision}:{eventMsgId}`；
3. 5 秒内把原卡更新为“已受理，Agent 正在重新整理”；
4. Worker 调 `AgentRuntimeFacade`，形成新 draft version；
5. 主动发送一张新的待确认卡，旧版本自动 `SUPERSEDED`。

“需要补充”只创建补充请求或打开受认证详情页；新的文字/图片/文件先作为新证据版本进入系统，再触发 Agent 重整，不能把按钮 key 当成业务事实。

### 5.3 企业微信协议限制与 PR #126 缺口

- 官方 SDK 的模板卡 `task_id` 要求同一机器人不重复，只能使用数字、字母、`_ - @`，最长 128 字节（[官方类型定义](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/api.ts#L360-L402)）。PR #126 使用 `order-draft:{id}`，冒号不合法，而且没有把草稿 revision 放进 ID（`JdbcOrderDraftCardStore.java:50`、`WecomOrderDraftCardInteractionService.java:18,306-315`）。应改为合法且版本化的不可猜授权引用，如 `od_{cardDeliveryId}_{randomNonce}`；实体/版本从数据库记录解析，不能依赖可伪造的字符串 ID。
- 主动发送联合类型只有 Markdown、模板卡片和媒体，不含 `text`（[官方类型定义](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/api.ts#L412-L465)）。PR #126 的 `WecomOutboundMessage` 仍允许主动 `TEXT`，卡片 update 失败时也调用主动 text 兜底（`WecomOutboundMessage.java:20-24,57-67`；`WecomMessageDispatchHandler.java:270-285`）。该兜底应改为 Markdown 或模板卡片，并在 gateway 构造层拒绝主动 text。
- 官方 update 要使用事件原 `req_id`、保持同一 `task_id`，且收到事件后 5 秒内发送（[官方客户端实现说明](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/client.ts#L300-L320)）。PR 当前先同步执行完整业务确认，再尝试 4.5 秒 update（`WecomMessageDispatchHandler.java:146-201`）。本地事务慢或未来加入 Agent 后必然挤占窗口；Agent 重做必须先快速更新“已受理”再异步执行，最终结果另发新卡。真实租户仍须验收事件帧形状、`from.userid`、update ACK 与超时表现；官方 SDK 的 event 类型把 actor 放在 `body.from`，把 `event_key/task_id` 放在 `body.event`（[官方事件类型](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/event.ts#L42-L88)），但实际版本差异不能只靠单元测试假设。

## 6. Excel：确定性生成，Agent 只做辅助

Excel 不是 Agent 输出格式，而是正式业务事实的确定性投影。PR #126 的现有第三方履约导出已经如此：`ProviderFileService` 从计划行生成固定 24 列 XLSX、写内容寻址文件存储、保存 SHA-256，再在同一业务事务登记企微 initial delivery（`ProviderFileService.java:484-557,1025-1071`）；Worker 上传文件、拿 `media_id` 后发送，成功 ACK 才推进时间线（`FulfillmentExportWecomDeliveryRunner.java:212-285`）。官方 SDK 也定义上传为 `init → chunk × N → finish`，再以媒体消息主动发送（[官方实现](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/client.ts#L355-L395)、[主动发送类型](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/api.ts#L446-L465)）。

Agent 可以：建议导出范围、生成人类可读摘要、解释异常、提出待复核行；这些结果必须另存为建议并可追溯到 run_id。Agent 不可以：直接填写正式单元格、决定 SKU/数量/地址、生成公式后未经校验发送、持有 `media_id`、调用上传/发送、把自然语言当成 Excel 真源。

## 7. 建议新增的领域状态、审计与幂等边界

### 7.1 领域对象

```text
BusinessFollowUp
  id, customer_id?, owner_id, plus_one_operator_id, stage,
  source_revision, confirmed_draft_version?, status

BusinessFollowUpDraft
  followup_id, version, source_revision, agent_run_id,
  structured_facts, summary, open_questions, suggested_actions,
  status = GENERATING | READY | SUPERSEDED | CONFIRMED | REJECTED

Approval
  entity_type/id, expected_version, approver_operator_id,
  decision, decided_at, source_event_msgid

Assignment
  source_entity_type/id/version, task_type, assignee_type/id,
  status, due_at, idempotency_key
```

`CONFIRMED` draft 才能投影正式事实和创建 Assignment；每个 Assignment 的创建键应包含 `followup_id + confirmed_version + task_type + logical_target`，防止卡片重投重复拆任务。

### 7.2 三层证据链

1. `agent_run / agent_tool_call`：模型与工具证据，operator 是服务端 Agent 身份；
2. `Approval + audit_log`：谁以哪个当前版本作出人工决定；
3. `wecom_card_delivery / wecom_event / notification_delivery`：发给谁、ACK/UNKNOWN、谁点击、快更新和后续新卡结果。

任何一层失败都不能伪造另一层成功。尤其“企微 ACK 成功”只证明平台接受消息，不等于用户已阅读；“Agent SUCCEEDED”只证明生成了建议，不等于 ERP 已确认落库。

## 8. PR #126 / master 能力与真实缺口

| 能力 | 远端 master/current branch | PR #126 | 接入结论 |
|---|---|---|---|
| Agent 运行门面、门禁、守卫、工具白名单/观测 | 基础能力已存在 | 补 Agent 定义写/读 API、console、更多生产调用方 | 可复用，但尚无 BusinessFollowUp 专属 Agent 与入口 |
| 消息解释 → OrderDraft/ReviewCase | 已有 | 与卡片入队打通 | 是订单子流程，不是通用面谈跟进流程 |
| AgentDefinition 草稿管理 | 基础数据/服务 | T11/T12 API 与前端看板已在 PR 中 | 不等于业务草稿；PR 中仍无 `/api/meta-agent/run` 通用自然语言入口 |
| 主动卡片、文件上传、通知 outbox | master 未具备完整闭环 | 已实现 gateway、上传、订单草稿卡、Excel 出站、通知 outbox | 应作为传输/投递层复用；PR 仍 OPEN，未合并/部署 |
| 订单卡完整收货详情 | 无 | 只显示草稿号、行数、缺失数，不显示电话/地址（`OrderDraftCardRunner.java:106-119`） | 与用户需求不符；需单聊全量/群聊脱敏策略 |
| `task_id` | 无真实门禁 | `order-draft:{id}` | 冒号违反官方约束；需修复并真实发卡/点击验收 |
| 主动 text | 未形成正式能力 | gateway 接受 TEXT，update 失败 text 兜底 | 官方主动联合类型不含 text；改 Markdown/卡片 |
| 点击者授权 | 无 | 验 actor 存在与路由，但未验 active 内部 Operator/+1 角色 | 上线阻塞项 |
| 5 秒更新 | 无 | 独立快通道与 4.5 秒 deadline 已实现，但业务先执行 | 对 Agent/慢业务不成立；先受理 update，再异步，最终新卡 |
| Excel | 已有确定性生成基础 | 已打通上传/发送/ACK/UNKNOWN | 可直接作为标准实现，Agent 不介入文件真源 |
| BusinessFollowUp / +1 / Assignment | 无 | 无 | 这是把本轮卡片设计接入“完整后台 Agent 能力”的核心新增领域工作 |

## 9. 最高层自动化测试接缝（只设一条）

建议唯一核心 E2E seam：

> 从真实业务应用入口提交一份含原始面谈材料与员工大体草稿的 `MessageSubmission/BusinessFollowUp`，运行真实 PostgreSQL、AsyncTaskStore、AgentRuntimeFacade 和完整应用服务；这是唯一的应用级 E2E 接缝，其中模型 adapter 使用契约稳定的 stub，企微外部端使用本地 RFC6455 假服务器。测试必须断言：Agent draft version 与 run/tool-call 证据 → 待确认卡确定性字段/隐私投影 → 平台 ACK → 授权 +1 点击 → 5 秒“已受理”update → 确定性确认落库 → Assignment 幂等生成 → 确定性 Excel 的单元格/sha256 → upload 三段帧 → file ACK → 全链审计关联。再分别用重投、旧 revision、未授权 userid、模型重试、ACK timeout、Worker 崩溃验证无重复落库、无重复发送和 UNKNOWN 对账。

这条接缝覆盖最高风险的跨层契约，同时不把真实企微或真实模型混进 CI。部署后再做独立外部验收：单聊完整/群聊脱敏卡、授权与未授权点击、5 秒 update、Excel 可打开、连接踢线恢复。当前会话只证明了“测试 Excel 被真实企微接收”，尚未形成“用户可打开”的独立验收证据，也不替代上述业务闭环验收。

## 10. 建议实施顺序

1. 先修 PR #126 的协议阻塞项：合法唯一 `task_id`、禁止主动 text、点击者 Operator/+1 授权、5 秒先受理后异步；补真实租户点击验收。
2. 定义 `BusinessFollowUp / BusinessFollowUpDraft / Approval / Assignment`，不要复用 `AgentDraftService` 名义或表。
3. 新建专属整理 AgentDefinition：最小只读 MCP、`allow_write=false`；业务编排服务负责把经 schema 校验的 Agent 输出持久化为草稿版本；完成 PII 最小化与必要时的专属豁免评审。
4. 打通 `BusinessFollowUp` 的 AsyncTask Worker → AgentRuntimeFacade → 草稿版本 → +1 卡。
5. +1 确认后确定性创建订单/样品/报价/回访 Assignment；再复用通知 outbox 与 Excel 出站。
6. 通过 §9 单一 E2E seam 后，才做真实企微卡片点击与 Excel 外部验收；PR 合并、部署和生产通过分别记录，不混为一谈。

## 一手来源索引

- 当前仓库 PR 候选：[rgfan123/zimu PR #126](https://github.com/rgfan123/zimu/pull/126)，head `923beea84ec6d904b8e6ee39e4c866bad3262728`。
- 企业微信团队官方 AI Bot Node SDK（固定 commit）：[README](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/README.md)、[协议/API 类型](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/api.ts)、[事件类型](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/types/event.ts)、[客户端实现](https://github.com/WecomTeam/aibot-node-sdk/blob/80615b987ef69c6028ad764924609247c0725955/src/client.ts)。
- 本仓库既有研究背景：`docs/research/wecom-agent-mcp.md`；PR 实施事实：`docs/agents/wecom-order-draft-cards.md`、`docs/agents/wecom-business-notifications.md`、`docs/agents/wecom-outbound-send.md`（均以 PR head 为准，尚未合并）。
