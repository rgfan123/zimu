# 企业微信消息投递路由调研

> 调研日期：2026-08-28  
> 范围：当前 checkout `/Users/jerry/zimu-work/main`、企业微信第一方文档/SDK、仓库内历史只读记录  
> 性质：只读研究，不包含实现、提交、部署或生产变更

## 结论摘要

结论先行：

1. **“谁发文件，就把结果回给谁”只能判定为“部分能做”。** 企业微信入站链已经保存发送者 `userid`、会话类型和群 `chatid`；官方协议也支持主动向单聊 `userid` 或群 `chatid` 发送。但企微文件被识别为来源订单后，代码把 `import_batches.uploaded_by` 固定写成 `wecom-file`，批次没有指向 `message_submission/channel_message` 的外键，发送者信息在“消息证据 → 导入批次”这一跳丢失了。
2. **在线拉取批次没有“文件发送者”。** 这类批次必须使用显式的运营归属或投递路由作为 fallback；不能把 `system:platform-pull` 当成企微收件人，也不能默认广播到某个群。
3. **自定义投递目标应复用两块既有基建，而不是另起一套人员系统：**
   - 单聊归属复用 `internal_operators`，路由保存 `operator_id`，实际 `userid` 由启用中的运营人员档案解析；
   - 群聊候选复用 `wecom_chat_reply_policies` 的会话档案和会话目录。
   群 `chatid` 不应塞进人员主表，因为“人”和“群”不是同一种实体。
4. **推荐最终方案是混合解析：** 企微来源优先回原发送者；没有可信来源时，按“投递用途 + 来源渠道”解析运营人员/已知会话；仍未配置则持久记录 `BLOCKED_NOT_CONFIGURED` / `UNROUTED`，不发送、不猜、不广播。
5. **PII 必须由系统结构阻断。** `PRESHIP`、`PRESHIP_BATCH`、`SHIPPED`、`SOURCE_RETURN_FILE` 必须是 `SINGLE_ONLY`。保障应同时存在于中心敏感度枚举、写入 API 校验、数据库 `CHECK`、Runner 发送前二次校验和群聊专用安全 renderer，不能只靠操作员记得。

---

## 1. 现状地图：所有企微消息出口与目标选择

### 1.1 业务卡不是“五个 domain”，而是 7 个显式路由键、9 个运行 domain

Spring 配置声明了 7 个显式 route key：

| route key | 缺省类型 | chat-id 配置 |
|---|---:|---|
| `review` | `GROUP` | `WECOM_CARD_REVIEW_CHAT_ID` |
| `alert` | `GROUP` | `WECOM_CARD_ALERT_CHAT_ID` |
| `batch` | `GROUP` | `WECOM_CARD_BATCH_CHAT_ID` |
| `jd-outbound` | `GROUP` | `WECOM_CARD_JD_OUTBOUND_CHAT_ID` |
| `followup-draft` | `SINGLE` | `WECOM_CARD_FOLLOWUP_DRAFT_CHAT_ID` |
| `followup-result` | `SINGLE` | `WECOM_CARD_FOLLOWUP_RESULT_CHAT_ID` |
| `preship` | `SINGLE` | `WECOM_CARD_PRESHIP_CHAT_ID` |

证据：`backend/src/main/resources/application.yml:174-210`。

当前 Spring 注册的业务卡 domain 共 9 个：

| domain | 当前如何确定目标 |
|---|---|
| `review` | 直接解析 `routes.review`。`ReviewCaseCardSource.java:41-48` |
| `alert` | 直接解析 `routes.alert`。`OperationalAlertCardSource.java:35-42` |
| `batch` | 直接解析 `routes.batch`。`BatchConfirmedCardSource.java:38-45` |
| `jd-outbound` | 直接解析 `routes.jd-outbound`。`JdOutboundFailureCardSource.java:39-46` |
| `followup-draft` | 先查业务跟进指定审核人对应的启用运营人员 `wecom_userid`；只有配置类型为 `GROUP` 时才用配置群覆盖。`BusinessFollowUpDraftCardSource.java:41-65` |
| `followup-result` | 与 draft 相同，按 approval 的指定审核人解析；只有 `GROUP` 配置会覆盖。`BusinessFollowUpResultCardSource.java:39-63` |
| `preship` | 只接受 `SINGLE`；配置为 `GROUP` 时直接拒绝发卡。`PreShipConfirmCardSource.java:54-67` |
| `preship-batch` | 优先解析同名动态 route，未配则复用 `preship`；同样只接受 `SINGLE`。`BatchPreShipConfirmCardSource.java:66-80` |
| `shipped` | 复用 `preship`，且只接受 `SINGLE`。`ShipmentResultCardSource.java:36-45` |

两个容易误判的事实：

- `followup-draft/result` 的缺省类型虽是 `SINGLE`，但 source 只采纳配置中的 `GROUP`。因此缺省 `SINGLE` 环境变量 chat-id 实际会被忽略，默认目标仍是 `designated_reviewer_operator_id → internal_operators.wecom_userid`。
- `WecomBusinessCardRouteProperties` 只验证 route 存在且 chat-id 非空，并把类型和标识符组装为 `Route`；它没有按 domain 统一约束 PII 敏感度。当前 PII 安全依赖具体 source 自己挡住非法 route。证据：`WecomBusinessCardRouteProperties.java:35-70`。

### 1.2 Docker Compose 实际可透传范围比 Spring 配置更窄

`docker-compose.yml` 当前只向 backend 容器透传 5 个 chat-id：

- `review`
- `alert`
- `batch`
- `jd-outbound`
- `preship`

同时只透传 `WECOM_CARD_PRESHIP_ROUTE_TYPE`。前四类的 route type 没有透传，因此在 Compose 路径下落到 Spring 的 `GROUP` 缺省；`followup-draft/result` 的 type/chat-id 都没有透传。证据：`docker-compose.yml:114-125`。

因此必须区分：

- **代码可表达：** 7 个显式 route key；
- **当前 Compose 可配置：** 5 个 chat-id，且只有 `preship` 可配置 type；
- **运行 domain：** 9 个，其中 `preship-batch/shipped` 复用 `preship`。

这只是 Compose 定义事实，不等于本次已经核实生产容器的有效环境变量。

### 1.3 主动消息出口

全仓主动发送最终都经过 `WecomOutboundGateway` / `aibot_send_msg`。`WecomLongConnectionClient` 构造 body 时写入目标 `chatid` 与消息类型：`backend/src/main/java/cn/zimu/fulfillment/connector/wecom/WecomLongConnectionClient.java:327-348`。

| 出口 | 消息 | 当前目标来源 | 未配置/不可达时 |
|---|---|---|---|
| 通用业务卡 | template card，部分卡带 image/file 附件 | source 入队时解析 route，将 `route_type + chat_id` 固化进 `wecom_business_cards`；Runner 使用快照发送 | route 为空时 INFO 跳过，不建 outbox。`WecomBusinessCardEnqueuer.java:38-63`；发送见 `WecomBusinessCardRunner.java:81-112,135-180` |
| 来源回填文件 | file | 按批次 raw row → order line → fulfillment → provider 的 `config.wecomGroupChatId`，跨履约方时 `DISTINCT ... LIMIT 1` | 为空时 INFO 跳过，`source_return_exports` 保持 `NOT_SENT`；成功后才写 `wecom_chat_id`。`SourceReturnWecomRouteResolver.java:9-40`；`SourceReturnWecomScanner.java:47-66`；`SourceReturnWecomDeliveryService.java:107-158` |
| 履约导出首发文件 | file | 首次发送前实时解析履约方 `wecomGroupChatId`；成功 ACK 后固化到 state | 解析不到时进入明确失败/重试收口，不猜目标。`FulfillmentExportWecomDeliveryRunner.java:148-199,240-285` |
| 履约回传提醒 | markdown | 使用首发成功时固化的 state.chat_id | 快照缺失转 UNKNOWN，不另找群。`FulfillmentExportWecomDeliveryRunner.java:288-399` |
| 五分钟业务摘要 | markdown | `responsible_team` 展开 active operators，逐个单聊到 `wecom_userid` | 无成员或成员未绑定时持久记 `BLOCKED`，不静默跳过。`WecomBusinessNotificationRunner.java:48-85,99-145` |
| 订单草稿确认卡 | template card | 从 draft → submission → channel message 反查原会话；单聊用发送者 `userid`，群聊用原 `chat_id` | 原会话不唯一/缺失则拒绝建卡。`JdbcOrderDraftCardStore.java:30-71`；发送见 `OrderDraftCardRunner.java:45-113` |
| #178 会话 Agent 回答 | markdown | 原 `channel_message`；单聊将证据键 `single:userid` 还原为真实 `userid`，群聊保持 `chatid` | Agent/发送失败时回落原 `INTERPRET_MESSAGE` 流水线。`WecomChatAgentRoutingService.java:79-174,227-244,327-391` |
| 卡片交互文字兜底 | markdown | 单聊为点击者 `userid`，群聊为原 chat-id | 无目标或发送失败时记录 fallback 失败。`WecomOrderDraftCardInteractionService.java:292-320`；`WecomMessageDispatchHandler.java:384-440,525-544` |

当前来源回填路由有两个额外风险：

1. 跨履约方批次用无显式排序的 `LIMIT 1` 选目标，结果不表达业务 owner；
2. 来源回填文件不是群聊安全摘要。生成逻辑保留原来源 workbook/CSV 并追加回填列；至少聚福宝回填结构明确含收货人姓名、电话和地址。证据：`TrackingFileService.java:840-846`、`SourceReturnPushService.java:453-456`。当前把这类原文件路由到群存在 PII 风险。

### 1.4 被动回复/回调定址出口

以下出口依赖当前回调的 `req_id`，不是“主动选择某个 chat-id”，不应纳入可配置业务路由：

| 出口 | 定址方式 | 证据 |
|---|---|---|
| 普通消息“已接收”/文件“正在解析”回执 | 透传入站消息 `req_id`，使用 `aibot_respond_msg` | `WecomMessageDispatchHandler.java:620-649`；`WecomLongConnectionClient.java:285-298` |
| 业务卡、订单草稿卡、跟进卡点击后的即时更新 | 透传卡片事件 `req_id`，使用 `aibot_respond_update_msg` | `WecomMessageDispatchHandler.java:195-221,290-352,384-425`；`WecomLongConnectionClient.java:299-315` |

这些被动出口仍应保留现有超时、幂等和 UNKNOWN 围栏，但不需要运营配置目标。

---

## 2. 既有基建盘点：V60、V61、V62 与 #178

### 2.1 V60：`wecom_chat_reply_policies`

V60 建的是会话级策略表，不是卡片路由表：

| 字段 | 含义 |
|---|---|
| `chat_id` | 主键 |
| `reply_mode` | `FULL` / `RECEIPTS_ONLY` |
| `note` | 备注 |
| `updated_by / updated_at` | 更新证据 |

迁移明确规定：无行默认 `FULL`；该策略只约束对话性出口，业务投递不受影响。证据：`backend/src/main/resources/db/migration/V60__wecom_chat_reply_policies.sql:1-14`。

它已经被真实读写：

- `allowsConversational` 查询 reply mode：`WecomChatReplyPolicyService.java:29-38`；
- 即时泛回执和订单草稿追问卡消费该门禁：`WecomMessageDispatchHandler.java:620-633`、`OrderDraftCardRunner.java:62-67`；
- 管理 API upsert：`WecomChatReplyPolicyService.java:73-94`；
- 前端“Agent 中心 → 会话管理”可编辑：`frontend/src/pages/agents/ReplyPolicyPage.tsx:77-91,155-224`。

**承载判断：** 不能原样承接“卡类型 → 投递目标”。它缺少 `delivery_purpose/card_domain`、目标类型、PII 敏感度、来源渠道和 owner。可以复用为会话目录与群聊候选。

### 2.2 V61：不是新表，而是 V60 的会话档案扩展

V61 给 `wecom_chat_reply_policies` 增加：

- `display_name`：人工备注的会话名称；
- `agent_slug`：服务该会话的 Agent；
- `reply_mode` 默认值 `FULL`。

证据：`backend/src/main/resources/db/migration/V61__wecom_chat_profiles.sql:1-15`。

两列均被实际使用：

- 会话目录查询和编辑 `display_name`：`WecomChatDirectoryController.java:56-76,103-150`；
- `assignedAgent` 查询 `agent_slug`：`WecomChatReplyPolicyService.java:41-63`；
- #178 在首次提交时依据它分流：`MessageSubmissionService.java:180-206`。

**承载判断：** 它适合作为“已知会话/备注名/Agent 归属”的目录，不适合把 `agent_slug` 或 `reply_mode` 复用成卡片投递规则。正确做法是新增显式投递关系，引用这个目录中的群会话。

### 2.3 V62：机器人管理台账

V62 字段为：

- `bot_id`
- 名称
- secret
- enabled
- note
- 更新人/更新时间

证据：`backend/src/main/resources/db/migration/V62__wecom_bots.sql:1-21`。

它也有真实管理读写：

- `WecomBotService.java:27-46`；
- `WecomBotController.java:72-114`；
- `frontend/src/pages/system/WecomBotsPage.tsx:1-6,84-105,168-186`。

但运行时仍是单机器人，连接读取 `app.wecom.*` 的 `WecomProperties`，没有从 V62 热切换：

- `WecomBotService.java:7-16`；
- `WecomProperties.java:8-22,69-78`；
- `WecomConnectionManager.java:43-51`。

**承载判断：** V62 不承接业务路由。本需求也不需要新增机器人或密钥。

### 2.4 Issue #178 会话 Agent 分流

#178 当前的数据模型就是：

`wecom_chat_reply_policies.chat_id → agent_slug`

没有单独的 Agent 会话绑定表。入站单聊在证据层用 `single:userid` 避免空 chat-id；策略服务在查绑定和主动发送边界将其还原：

- `WecomChatReplyPolicyService.java:46-70`；
- `MessageSubmissionService.java:180-206`；
- `WecomChatAgentRoutingService.java:227-244`。

文件消息仍固定进入 `WECOM_TRACKING_FILE`，不会被 #178 Agent 截走：`MessageSubmissionService.java:201-206`。

**承载判断：**

- 可复用：会话标识归一、会话档案、已知目标目录、原会话回复范例；
- 不能直接复用：`chat → Agent` 是入站服务归属，卡片需要的是 `purpose/source → destination`，方向不同；
- 推荐：新增投递路由关系，目标侧引用 operator 或 chat profile，而不是给 `agent_slug` 增加双重含义。

### 2.5 会话目录还有一个已确认的实现缺口

页面和 Controller 注释称“把机器人拉进新群并发一条消息，刷新即出现”，但普通 `aibot_msg_callback` 只写 `channel_messages`：

- 入站处理：`WecomMessageDispatchHandler.java:123-164`；
- 证据落库：`ChannelMessageIntakeService.java:18-44`。

`wecom_events` 只由 enter/disconnected/card event 路径写入：

- `WecomMessageDispatchHandler.java:171-178,589-609`；
- 卡片事件另由各 event store 持久化。

而会话目录群候选只查 `wecom_events`：`WecomChatDirectoryController.java:56-76`。

因此普通群消息不会按页面承诺自动进入候选。若要把 chat profile 作为可靠路由目录，查询至少应合并并去重：

`channel_messages(group) UNION wecom_events(group)`

并保留最后活跃时间和来源证据。

---

## 3. “运营人员管理”是什么，以及能否作为 chat-id 归属载体

### 3.1 数据与模块实物

`internal_operators` 当前字段：

| 字段 | 含义 |
|---|---|
| `id` | 人员主键 |
| `display_name` | 显示名 |
| `responsible_team` | 责任团队 |
| `wecom_userid` | 可空、非空时全局唯一 |
| `active` | 是否参与解析 |
| `lock_version` | 乐观锁 |
| `created_at / updated_at` | 时间证据 |

V48 明确边界是“运营人员 ↔ 企微 userid ↔ 责任团队”，不做登录、角色或权限，也不物理删除：`backend/src/main/resources/db/migration/V48__internal_operators.sql:1-40`。

它是完整的管理模块，不只是表：

- Entity/Repository：`InternalOperator.java:12-41`、`InternalOperatorRepository.java:11-31`；
- CRUD 与团队解析 API：`OperatorController.java:20-77`；
- 幂等、审计、乐观锁服务：`OperatorService.java:46-117,187-227`；
- 前端页面 `/system/operators`：`frontend/src/pages/system/OperatorsPage.tsx:54-143,146-181,204-320`；
- 导航入口：`frontend/src/navigation.ts:93-102`。

### 3.2 已有业务复用

运营人员模块已经被至少两类实际投递复用：

1. 五分钟业务摘要按 `responsible_team` 展开 active operators，并逐个单聊：`WecomBusinessNotificationRunner.java:48-85`；
2. 客户跟进 draft/result 卡按 `designated_reviewer_operator_id` 找 active operator 的 `wecom_userid`：`BusinessFollowUpDraftCardSource.java:46-65`、`BusinessFollowUpResultCardSource.java:44-63`。

此外，会话目录把 active、已绑定的运营人员合成为单聊候选：`WecomChatDirectoryController.java:77-96`。

### 3.3 是否已有 chat-id 关联

结论：

- 已有单聊身份：`wecom_userid`；
- 没有群 `chat_id` 字段；
- 没有“运营人员 ↔ 群会话 ↔ 投递用途”的关联表；
- 没有每人/每团队的卡片投递偏好。

因此：

- **适合：** 作为 `SINGLE` 目标的权威 owner。路由只存 `operator_id`，发送时解析 active operator 的 `wecom_userid`；
- **不适合：** 直接在人员行增加一个“万能 chat-id”。一个人可能负责多个用途，多人也可能共享同一个群，群的生命周期不属于人员实体；
- **推荐复用方式：** 在运营人员模块下增加“投递偏好/负责路由”子资源和同页配置区；群目标从 V61 会话档案选择。这样复用现有人员体系和 UI，不另造第二套人员管理。

另有一个类型漂移值得修正：履约方页面已把“机器人群聊”和“运营人员单聊”都放进名为 `wecom_group_chat_id` 的候选框，而后端类仍把该字段定义为群 chat-id，校验也只检查 ASCII/长度，无法区分群和单聊：

- `frontend/src/pages/system/FulfillmentProvidersPage.tsx:87-109,338-353`；
- `FulfillmentProviderWecomConfig.java:9-20,46-99`。

这不能作为 PII 路由类型系统。

---

## 4. 方向 (a)：“谁发文件，就回给谁”的可行性

### 4.1 明确判定：部分能做

| 场景 | 判定 | 原因 |
|---|---|---|
| 企微单聊上传来源订单文件 | 能做，但当前缺一段持久关联 | 发送者 `userid` 已保存，协议可主动发单聊；批次却没有来源消息 FK |
| 企微群上传文件 | 当前不能直接纳入 | 专用 Processor 明确只接受 `chat_type=single`；而来源回填原文件含 PII，也不应回原群 |
| 后台人工上传 | 不能按“企微发送者”回复 | 没有企微来源会话，应走显式 operator route |
| 在线平台拉取 | 不能按“发送者”回复 | 系统行为没有人类发送者，必须走 fallback |

### 4.2 入站证据现在保存在哪里

现有链路：

1. `WecomMessageDispatchHandler` 从回调读取：
   - `chattype`；
   - 群 `chatid`；
   - `from.userid`。
   单聊没有 chat-id 时，证据层写成 `single:<sender_userid>`。  
   证据：`WecomMessageDispatchHandler.java:123-164`。
2. `ChannelMessageIntakeService` 写入 `channel_messages.chat_id/chat_type/sender_user_id/raw_payload`：`ChannelMessageIntakeService.java:18-44`。
3. `message_submissions.source_message_id` 关联该消息，文件任务由 `MessageSubmissionService` 创建：`MessageSubmissionService.java:52-85,180-203`。
4. `WecomTrackingFileProcessor` 下载、解密并识别“来源订单表”或“履约运单表”：`WecomTrackingFileProcessor.java:62-114`。

因此发送者和会话信息没有在入口丢失；它们仍在上游消息证据中。

### 4.3 数据链断在哪里

当文件被识别为来源订单表时，Processor：

- 把 operator 硬编码成 `wecom-file`；
- 构造的 `CommandContext` 不含 sender/chat/submission 的结构化来源；
- 调用 `SourceImportService.upload`。

证据：`WecomTrackingFileProcessor.java:117-136`。

随后 `SourceImportService.upload` 把 `context.operator()` 写入 `import_batches.uploaded_by`：`SourceImportService.java:97-141`。

`import_batches` 本身只有 `uploaded_by VARCHAR`，没有：

- `message_submission_id`；
- `channel_message_id`；
- `origin_chat_type`；
- `origin_sender_userid`。

证据：`backend/src/main/resources/db/migration/V1__baseline.sql:205-251`。

所以当前 `uploaded_by` 的代码语义是“执行上传的 operator 文本”，不是“企微原始发送者”。不能从它解析 userid。

审计里虽然会保存形如 `wecom-source-import-<submissionId>` 的幂等键，并在 response 中带 batch，但这是字符串旁路，不是关系完整性保证：`SourceImportService.java:236-276`。它最多可用于历史一次性核对，不能作为新运行时路由。

### 4.4 出站目前为什么回不到发送者

`SourceReturnWecomDeliveryService` 只接收调用方传入的 chat-id：`SourceReturnWecomDeliveryService.java:107-124`。

调用方 `SourceReturnWecomScanner` 使用 `SourceReturnWecomRouteResolver`，后者只沿批次业务行找到履约方配置群，完全不读取：

- `uploaded_by`；
- `message_submissions`；
- `channel_messages`。

证据：`SourceReturnWecomScanner.java:47-66`、`SourceReturnWecomRouteResolver.java:25-40`。

### 4.5 要成立所需的完整数据链

推荐链路：

`aibot_msg_callback`  
→ `channel_messages`（可信 sender/chat 证据）  
→ `message_submissions`  
→ `WecomTrackingFileProcessor`  
→ 新增结构化 `import_batch_origin` 或批次外键  
→ `import_batches`  
→ `source_return_exports`  
→ origin-first route resolver  
→ active、可达的原发送者单聊  
→ `WecomOutboundGateway`。

最小来源关系至少应包含：

- `import_batch_id`；
- `message_submission_id` 或 `channel_message_id` FK；
- 由 FK 可回查的 `chat_type`、`sender_user_id`、群 `chat_id`。

不建议重复拷贝 sender/chat 明文，优先保存 FK 并从不可变入站证据解析。若为投递围栏需要快照，可在 outbox 中固化最终 `recipient_kind + recipient_id`，并保留来源 FK。

来源回填原文件含 PII，因此即便未来接受群文件，也应把结果单聊回发送者 `userid`，不能回原群。

### 4.6 在线拉取必须有 fallback

系统拉取的 operator 代码上固定为 `system:platform-pull`：

- `AbstractHttpPullConnector.java:78-82`；
- 飞象文件拉取进入同一 `SourceImportService.upload`：`FeixiangConnector.java:69-77`；
- 聚福宝结构化拉取同样写 `context.operator()`：`JufubaoConnector.java:142-145`、`SourceImportService.java:319-343`。

它没有发送者概念。完整方案必须按以下顺序解析：

1. 有可信企微 origin：回原发送者单聊；
2. 没有 origin：按 `delivery_purpose + source_channel` 找运营 owner/会话；
3. 仍无配置：持久 `BLOCKED_NOT_CONFIGURED`，不发送。

---

## 5. 方案建议：按实现代价从低到高

### 5.1 方案一：只补“企微来源文件回原发送者”

**范围：** 仅为企微单聊来源订单批次增加 origin FK；`SourceReturn` 优先发回原发送者。在线拉取、后台上传仍走现有显式 fallback。

**代价：** 最低。它直接解决 Jerry 的方向 (a)，但不解决其他业务卡的自定义 chat-id。

四条硬约束：

1. **PII 边界**
   - `SOURCE_RETURN_FILE` 固定为 `SINGLE_ONLY`；
   - 目标只能从可信入站 `sender_user_id` 解析，不接受请求体手填 `GROUP`；
   - API/服务层拒绝群目标，数据库 outbox `CHECK` 再兜底；
   - Runner 发送前二次检查 `purpose=SINGLE_ONLY && recipient_kind=SINGLE`。
2. **未配置即不发**
   - origin 缺失、FK 断裂、发送者不可达时不猜；
   - 进入显式 fallback；fallback 也没有则持久 `UNROUTED` / `BLOCKED_NOT_CONFIGURED`；
   - 不创建默认群广播，也不只留瞬时 INFO 日志。
3. **官方协议**
   - 第一方 SDK 支持单聊 userid 主动发送；
   - 文件发送者已经与机器人发生过交互，满足“最近对话会话”的最保守范围。
4. **不新增凭据**
   - 继续使用现有 `WecomOutboundGateway` 和 `app.wecom.*`；
   - V62 不参与。

适合先做一个窄 vertical slice，但系统拉取仍需要方案二。

### 5.2 方案二：在运营人员模块下增加显式投递偏好

**范围：** 新增“投递用途 → 目标”的关系与 API，但 UI 归入现有运营人员页面。建议关系表达：

- `delivery_purpose`；
- 可选 `source_channel`；
- `target_kind = OPERATOR_SINGLE | KNOWN_GROUP`；
- `operator_id` 或群 `chat_id`；
- `active / lock_version / updated_by / updated_at`。

单聊只保存 `operator_id`，发送时从 active operator 解析 userid；群聊从 V61 会话档案选择。

四条硬约束：

1. **PII 边界**
   - 中心枚举为每个 purpose 定义 `SINGLE_ONLY` 或 `GROUP_SAFE`，不让 UI 提交敏感度；
   - 至少 `PRESHIP`、`PRESHIP_BATCH`、`SHIPPED`、`SOURCE_RETURN_FILE` 固定 `SINGLE_ONLY`；
   - 写 API 对非法 `purpose + target_kind` 返回 422；
   - DB `CHECK` 明确禁止这些 purpose 使用 `KNOWN_GROUP`；
   - Enqueuer/Runner 二次校验并快照 route type；
   - `GROUP_SAFE` purpose 必须调用类型化的 `renderGroup`，禁止复用全量 renderer。
2. **未配置即不发**
   - resolver 返回结构化 `Resolved / BlockedNotConfigured / Ambiguous`；
   - 后两者持久进入路由评估/outbox 状态，不调用外部发送；
   - 管理页显示“哪些用途未配置”，仍按 INFO 语义，不升级成系统故障；
   - 没有“默认群”。
3. **官方协议**
   - operator single 只在 userid 有可达证据时启用；
   - group 只从已知会话目录选，手填未知值最多保存为“未验证”，不能展示为已可达；
   - 实际 ACK 才是送达证据。
4. **不新增凭据**
   - 路由关系只存标识符和 owner；
   - 继续复用当前 bot 长连接，不新建 secret。

该方案覆盖方向 (b)，但不自动利用文件来源。

### 5.3 方案三：混合路由，origin-first + operator/chat-profile fallback（推荐）

解析顺序：

1. **可信 origin 优先：** 企微上传的来源订单/来源回填业务，回原发送者单聊；
2. **显式业务归属：** 无 origin 的系统卡、在线拉取批次，按 `purpose + source_channel` 找 operator 或 group profile；
3. **拒绝歧义：** 多个 active owner、跨履约方多个不同目标时标记 `AMBIGUOUS_ROUTE`，不再用无序 `LIMIT 1`；
4. **未配置不发：** 持久 `BLOCKED_NOT_CONFIGURED`，管理页可见；
5. **发送时固化证据：** outbox 保存最终 target kind、目标标识、解析来源、版本和 sensitivity。

四条硬约束：

1. **PII 边界**
   - 同方案二的中心枚举、API 拒绝、DB `CHECK`、Runner 二次校验；
   - origin 只决定“给谁”，不能覆盖 purpose 的 `SINGLE_ONLY`；
   - 原来源回填文件没有群聊脱敏版，绝不允许 route 改成 GROUP；
   - 群卡必须是显式 `GROUP_SAFE` renderer 产物。
2. **未配置即不发**
   - 不因 origin 缺失直接丢弃，而是进入显式 fallback；
   - 所有 resolver 结局均持久、可查询；
   - 不发送的配置选择仍按 INFO 展示，不触发错误风暴；
   - 禁止默认广播。
3. **官方协议**
   - 单聊使用 userid、群聊使用对应 chatid；
   - 不依赖当前回调帧，但目标必须在机器人可投递范围内；
   - 原发送者天然有互动证据，其他 operator/group 必须以目录与真实 ACK 验证。
4. **不新增凭据**
   - 复用 `app.wecom.*`、`WecomConnectionManager`、`WecomOutboundGateway`；
   - 不启用新的 bot，不读取 V62 secret。

### 5.4 方案比较

| 方案 | 覆盖 (a) | 覆盖 (b) | 系统拉取 | PII 防线 | 建议 |
|---|---:|---:|---:|---|---|
| 1. 只补 origin | 是 | 否 | 仍依赖旧 fallback | 可做强 | 最小先行 |
| 2. operator 投递偏好 | 否 | 是 | 是 | 可做强 | 必要基建 |
| 3. 混合路由 | 是 | 是 | 是 | 最完整 | **推荐最终态** |

推荐交付顺序是 1 → 2 → 3 的数据模型收口；不是同时改完所有消息出口。无论顺序如何，PII 中心约束应先于开放任何可配置群路由。

---

## 6. 未验证项清单

### 6.1 本次生产只读访问没有建立

本次尝试按指定通道连接生产 SSH 时，在 TCP/SSH 建立前即被当前沙箱拒绝：

`Operation not permitted`

因此：

- SQL **零送达**；
- 没有执行任何生产 SELECT；
- 更没有执行任何写语句；
- 当前生产 `import_batches.uploaded_by` 分布未验证；
- 当前生产业务卡 route 环境变量值未验证；
- 不能把任务中给出的 route 实际值当成本次已核实事实。

### 6.2 过期历史快照只能作背景

`docs/agents/file-io-map-review.md` 记录了 2026-08-25 的一次生产只读快照：当时 16 个批次的 `uploaded_by` 全部归类为 `system:platform-pull`。证据：该文档 `:1-10,147-154`。

该快照比本报告早 3 天，且代码、数据和运营入口都可能已经变化。它只能说明“历史上系统拉取占绝对多数”，不能替代 2026-08-28 当前分布。

### 6.3 企业微信协议仍需补的证据

已核实的第一方结论：

- 企业微信智能机器人官方入口：[企业微信开发者中心 path/101039](https://developer.work.weixin.qq.com/document/path/101039)；
- 长连接消息协议相关页：[企业微信开发者中心 path/101463](https://developer.work.weixin.qq.com/document/path/101463)；
- 企业微信官方 WecomTeam Node SDK 的 `sendMessage(chatid)` 说明主动发送不依赖当前回调帧，单聊填 userid、群聊填对应群 chatid：[WecomTeam/aibot-node-sdk README](https://github.com/WecomTeam/aibot-node-sdk/blob/main/README.md)；
- 同一官方团队的 CLI 将主动消息范围描述为“机器人最近对话过的单聊/群聊”：[WecomTeam/wecom-cli README](https://github.com/WecomTeam/wecom-cli)。

因此本报告采用保守结论：

- “不依赖当前回调 `req_id`”已坐实；
- “可以向任意从未互动过的企业成员发起单聊”**未坐实**；
- 方向 (a) 的文件发送者已经互动，不受这个未知项影响；
- roster 中从未互动过的 operator 是否必达，需要真实 ACK 或官方文档可访问后的明确条款。

本环境无法直接渲染开发者中心页面，以上协议行为由第一方 SDK/CLI 交叉核实。没有把无法读取的网页内容包装成逐字已验证。

### 6.4 其他未验证项

| 未验证项 | 当前能确认什么 | 还需要什么证据 |
|---|---|---|
| 历史企微导入批次能否安全补 origin | 审计幂等键可能带 submission id，但无 FK | 生产只读核对 batch ↔ audit ↔ submission 是否一对一，再决定是否只做历史 backfill |
| 群 chatid 在机器人退群后的可达性 | 目录可证明“曾见过” | 真实发送 ACK 与退群场景测试 |
| 外部群/互联群是否属于支持范围 | 当前代码不区分群种类 | 官方条款或真实群类型回调/ACK |
| `followup` 的 GROUP override 是否产品有意 | 代码明确只有 GROUP 配置覆盖指定审核人 | 产品决策与验收用例 |
| 生产是否通过 Compose 注入环境变量 | Compose 定义可见 | 生产容器只读环境存在性/分类检查，不输出值 |
| 来源回填各渠道的群聊安全版 | 当前只生成保留原表字段的业务文件 | 若要群发，需定义并验证独立脱敏 artifact；当前没有 |

### 6.5 后续生产只读查询建议

下列 SQL 只输出类别与数量/比例，不输出任何姓名、手机号、地址、userid、chatid 或凭据。应继续通过 stdin 传入 psql。

```sql
WITH classified AS (
    SELECT CASE
             WHEN uploaded_by = 'system:platform-pull' THEN 'SYSTEM_PLATFORM_PULL'
             WHEN uploaded_by = 'wecom-file' THEN 'WECOM_FILE_GENERIC'
             WHEN uploaded_by LIKE 'system:%' THEN 'OTHER_SYSTEM'
             ELSE 'HUMAN_OR_OTHER'
           END AS uploader_class,
           count(*) AS row_count
    FROM app.import_batches
    GROUP BY 1
),
totals AS (
    SELECT sum(row_count) AS total_count FROM classified
)
SELECT uploader_class,
       row_count,
       round(100.0 * row_count / NULLIF(total_count, 0), 2) AS pct
FROM classified
CROSS JOIN totals
ORDER BY row_count DESC, uploader_class;
```

还可只统计基建是否已有数据，不读取标识符内容：

```sql
SELECT
  (SELECT count(*) FROM app.internal_operators) AS operator_count,
  (SELECT count(*) FROM app.internal_operators
     WHERE active AND wecom_userid IS NOT NULL) AS active_bound_operator_count,
  (SELECT count(*) FROM app.wecom_chat_reply_policies) AS chat_profile_count,
  (SELECT count(*) FROM app.wecom_bots) AS bot_ledger_count,
  (SELECT count(*) FROM app.source_return_exports
     WHERE wecom_delivery_status = 'NOT_SENT') AS source_return_not_sent_count;
```

route 环境变量应只核实“是否存在、是否相等、route type 分类”，不要打印原始值。

---

本报告的建议边界是：**复用人员与会话档案，新增明确的投递关系；让来源决定优先收件人，让 purpose 决定安全等级，让未配置成为持久可见但不发送的状态。**

---

## 7. 生产事实补录（2026-08-28 17:5x，只读核实）

调研执行时沙箱阻断了生产 SSH，§6.1 列为未验证的两项在此补齐。以下均为只读 `SELECT` 与环境变量读取所得，未执行任何写操作，未读取任何凭据值。

### 7.1 `import_batches.uploaded_by` 实际分布

| 值 | 批次数 | 占比 |
|---|---|---|
| `system:platform-pull` | 33 | 79% |
| `wecom-file` | 6 | 14% |
| `zimu-admin` | 3 | 7% |

**这个分布改变了方案权重。** §4.6 把「在线拉取没有发送者」写成需要 fallback 的边缘情形，实测它是**主导情形**：约八成批次根本不存在「谁发的」这个概念。因此 origin-first 只覆盖约一成半的量，fallback 路径（§5.3 第 2 条的 `purpose + source_channel` 解析）才是日常主路，其设计质量决定整个方案的成败——不能当作兜底草草处理。

同时印证 §4.3 的断点判定：`wecom-file` 这 6 条确实是固定字面量，没有任何指向发送者的信息。

### 7.2 业务卡路由环境变量实际值

```
WECOM_CARD_REVIEW_CHAT_ID       = wrn8VIbwAA…（群）
WECOM_CARD_ALERT_CHAT_ID        = wrn8VIbwAA…（同一个群）
WECOM_CARD_BATCH_CHAT_ID        = wrn8VIbwAA…（同一个群）
WECOM_CARD_JD_OUTBOUND_CHAT_ID  = wrn8VIbwAA…（同一个群）
WECOM_CARD_PRESHIP_CHAT_ID      = jry
WECOM_CARD_PRESHIP_ROUTE_TYPE   = SINGLE
```

两点观察：

1. **四类卡指向同一个群**，只有 PRESHIP 是单聊——这就是用户所说「全部消息都硬编码推送给我」的实物形态。可配置化的收益主要在于把这四类拆开投给各自负责人。
2. **`PRESHIP_BATCH` / `SHIPPED` / `FOLLOWUP_*` 没有任何环境变量落在容器里**，但生产中 `preship-batch` 卡确有 6 张 `SENT`、`shipped` 卡 14 张 `SENT`。它们的实际投递目标来自配置默认值而非显式环境变量——§5.3「发送时固化证据」若要成立，需先查清这条隐式默认链，否则会出现「审计记录说不清这张卡当初为什么发到那里」。**此项已于本次补录追到底,不再是未验证项**——见 §7.3。


### 7.3 `preship-batch` / `shipped` 的定址真相(补查)

不是「隐式默认链」,是**显式借用 + 硬过滤**,设计上是对的:

```java
// ShipmentResultCardSource.route()
Optional<Route> configured = routes.resolve(PreShipConfirmCard.DOMAIN);
return configured.filter(route -> route.type() == RouteType.SINGLE);

// BatchPreShipConfirmCardSource.route()
Optional<Route> configured = routes.resolve(domain())
        .or(() -> routes.resolve(PreShipConfirmCard.DOMAIN));
if (configured.isPresent() && configured.get().type() != RouteType.SINGLE) { /* 拒绝 */ }
```

两类卡都**借用 `preship` 路由**,并且**硬过滤只接受 SINGLE**:

- 配置缺失 → `resolve` 返回空 → 无路由 → **不发卡**(符合 §5 「未配置即不发」)
- 配置成 GROUP → 被 filter 拒掉 → **不发卡**(PII 卡结构上进不了群)

所以 §1.1 说的「7 个显式路由键、9 个运行 domain」,差的两个 domain 不是漏配,是**有意复用 `preship` 这条单聊路由**。这也意味着 §5.5 的 PII 机制性保障在这两类卡上**已经存在**,新方案改造路由模型时必须保留这条 SINGLE 硬过滤,不能退化成可配置项。

一个副作用值得注意:`preship` 路由现在是三类 PII 卡的共同依赖(`preship`/`preship-batch`/`shipped`),改动它会同时影响三处。
