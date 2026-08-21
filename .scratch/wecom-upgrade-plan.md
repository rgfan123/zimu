# 企微能力升级总计划（WeCom Upgrade Plan）

状态：维护中
目的：把散落在多个 effort 里的企微相关内容（已完成 / 当前任务 / 留作后续）合并成一张总图，避免混乱。
来源：`wecom-long-connection`（传输层）、`wecom-message-intake`（业务层）、`wecom-fulfillment-send`（本计划新增）、用户 Channel API 提案（10 接口 + 4 模板）。

## 一句话目标

把企微机器人从「群里只收消息、回『已接收』」升级为**履约闭环的完整业务通道**：
接收证据 → 识别/草稿/复核 → **主动发送履约导出** → **模板卡片确认** → **超时提醒** → 文件/媒体收发。

## 1. 全景总览

```
Phase 0 已落地 ──────────────────────────────────────────────
  传输层（wecom-long-connection ✅）
    WS 长连接（订阅/心跳/重连/被踢/readiness）
    消息接收 aibot_msg_callback → 固定回执「已接收」
    事件接收 aibot_event_callback（enter_chat 留档；template_card_event 暂忽略）
    媒体下载+解密（image/mixed；voice/file/video 落证据不下载）
  业务层（wecom-message-intake ✅ 13 票）
    证据链：ChannelMessage / MessageSubmission / MessageMedia / 意图 / 草稿 / 复核
    意图：CUSTOMER_ORDER / SUPPLIER_TRACKING / ORDER_CHANGE / ORDER_CANCEL / NON_BUSINESS / NEED_REVIEW
    文字运单回传：逐行 ProviderTrackingDraft → 批量确认 → Tracking 落库
    MCP 服务端：fulfillment-hub-mcp（stdio）13 读 + 6 写 + 11 领域读（含 confirm_order_draft）
  履约数据地基 ✅
    履约导出生成：ProviderFileService 第三方 v1-24列 XLSX（发货清单）
    京东：SDK 直连建单
    SLA：fulfillment_providers.tracking_sla_minutes + fulfillment_exports.tracking_due_at 快照（Q43 已定）

Phase 1 当前任务 ────────────────────────────────────────────
  T1 企微侧 MCP 能力跑通（接入当前的）          → 见 mcp-order-fulfillment / 现有工具面核对
  T2 订单确认模板卡片                          → 新票：button_interaction + template_card_event + updateCard
  T3 接受发货信息（第三方回传物流单号）          → 文字✅已有；文件🆕（file 下载+解析，改走单聊直发，见 #85 决议）
  T4 履约导出发送 + 回传提醒                    → wecom-fulfillment-send 01 票（含新增 2h 周期提醒）
  T5 刷新订单 MCP（三平台拉取→展示→确认→发货）  → mcp-order-fulfillment 04 票（复用平台拉取/批次确认/履约导出/回填/回传）

Phase 2 升级计划（原「留作后续」清单 + 用户提案）───────────
  U1 Channel 门面收口（用户 10 接口提案）       → replyText/replyCard/replyFile/sendText/sendCard/
                                                 sendFile/sendImage/downloadFile/updateCard/sendWelcome
  U2 模板卡片库                                → order-confirm / missing-info / human-takeover / shipment-notice
                                                 + task_id 业务化（order:ORD001:v3，Router 零 LLM 解析）
  U3 updateCard 5s 快路径                      → aibot_respond_update_msg（事件帧 req_id，5s 内）
  U4 欢迎语                                    → aibot_respond_welcome_msg（enter_chat 5s 内）
  U5 流式回复                                  → aibot_respond_msg msgtype=stream
  U6 voice 归一化                              → voice.content 自带 ASR 文本，直接转 text 进 Router
  U7 第三方文件回传路径                         → 回传列文件（走单聊直发主路径，见 #85 决议）
  U8 业务事件主动通知                          → 订单创建/发货完成 → 主动通知负责人（aibot_send_msg）
```

## 2. 当前任务（Phase 1）细化

| 任务 | 现状 | 缺口 | 依赖 |
|---|---|---|---|
| T1 企微侧 MCP 能力 | MCP 服务端已接入（.mcp.json，MCP_ENABLED=true） | 跑通验证；发送类工具是否新增待议 | 无 |
| T2 订单确认模板 | 协议全支持，Java 未实现；`template_card_event` 被忽略 | 翻转忽略决策 + 卡片发送 + 事件落库 + updateCard | 传输层主动回复/发送 |
| T3 接受发货信息 | 文字回传链路已通（验收过） | file msgtype 纳入媒体下载 + Excel 解析 → 运单草稿 | 已决议：改走单聊直发（见 #85） |
| T4 履约导出发送+提醒 | 导出生成已有；发送/上传/提醒均无 | aibot_send_msg + 三步上传 + 发送记录 sent_at + TRACKING_REMINDER 周期任务 | 履约方→企微群映射 |
| T5 刷新订单 MCP | 平台拉取编排（`PlatformOrderRefreshService`）、批次确认、履约导出、回填/回传均已有；MCP 无拉取/批次/确认工具 | refresh_platform_orders + 批次查询 + confirm_import_batch 三个 MCP 工具（复用应用用例） | 07/08/09 在线拉取落地后可替换内部实现，MCP 面不变 |

**T4 已确认决策**：计时=企微发送时刻；提醒对象=第三方业务群；周期重复（间隔可配置）；SLA 复用 `tracking_sla_minutes`。
（详见 `wecom-fulfillment-send/issues/01-export-send-and-reminder.md`）

**T5 流程**：`refresh_platform_orders`（复用现有拉取编排，建 NEW 导入批次）→ `list/get_import_batch`（展示订单给人确认，复用批次查询）→ `confirm_import_batch`（复用批次确认应用用例，触发履约导出=发货）→ 回填/回传复用既有链路（SourceReturnExport / Sync）。
约束（platform-online-integration 裁决）：确认闸门保留——confirm 由用户在企微对话发起（人工主体），不自动确认；拉取与建批次为 SYSTEM 主体。

## 3. 协议能力对照（升级计划的可行性基础，已核对官方 @wecom/aibot-node-sdk 源码）

| 能力 | 协议命令 | Java 现状 |
|---|---|---|
| 被动回复（含卡片/流式/媒体） | aibot_respond_msg | ✅ 已有（仅回执文本） |
| 主动发送（text/markdown/card/file） | aibot_send_msg | ❌ |
| 素材上传（分片，≈50MB） | aibot_upload_media_init/chunk/finish | ❌ |
| 更新卡片（5s，task_id 一致） | aibot_respond_update_msg | ❌ |
| 欢迎语（5s） | aibot_respond_welcome_msg | ❌ |
| 媒体下载+解密 | — | ✅ 已有（下载器+解密器，扩展 file 即可） |
| 五类模板卡片 | template_card（text_notice/news_notice/button_interaction/vote_interaction/multiple_interaction） | 协议支持，Java 未用 |

## 4. 全局待确认（跨任务）

1. T4 发送触发方式：导出生成后自动发 vs 后台人工点发送（默认：自动 + 可重发）
2. 履约方 → 企微群映射存放位置（provider 扩展列 vs 配置项）
3. ✅ 群聊能否收到 file 消息 —— **已决议（2026-08-21 真实环境实测，见 §7 与
   `docs/agents/wecom-group-file-spike.md`）**：当前智能机器人群聊流程对群聊 file 消息在
   操作上不可用（用户发 XLSX 后立即 + 延迟两次复查生产库，`app.channel_messages` 无成功
   持久化的有效 group/file 回调、`app.message_media` 无群聊 file 媒体证据行（后者仅一致的
   支持性观察），同一群同一机器人文字回调正常到达）；**不声明协议普遍不支持群聊 file**（应用
   日志/DB 只证明没有成功持久化的有效回调，不能证明没有任何 file 帧到达进程、也不能证明企微
   从未发出）；T3/U7 改走单聊直发主路径。
4. MCP 是否新增 wecom_send_* 工具（影响 T1 边界）
5. 提醒消息发送失败重试策略（默认：重试 1 次只告警）

## 5. 全局问题与风险清单（2026-08-19 排查）

### T1 MCP 跑通
- **jar 部署正式化**：mcp-order-fulfillment 02 票遗留——容器内 jar 靠 `docker cp` 替换，重启会被镜像原 jar 覆盖；正式化需 `docker compose build backend` 或持久化挂载。
- **MCP 边界文档过期**：CONTEXT.md「企业微信一期实现边界」仍写"不提供确认订单工具"，但 `confirm_order_draft` 已开闸、T5 再加 `confirm_import_batch`——文档需同步，避免新旧表述打架。
- 身份门禁：MCP_AGENT_IDENTITY=mcp-gateway 单一身份，多 Agent 时是否需要按人区分待议。

### T2 订单确认模板
- **确认人可溯性**：群聊 button_interaction 事件载荷能否拿到操作人（`from.userid`）待实测——决定卡片"操作人：张三"与确认审计主体能否成立。
- **卡片确认与 MCP confirm_order_draft 的关系**：点「确认订单」是直接走人工确认应用用例（点卡人=人工主体），还是转 MCP 动作？草稿确认要求 expected_revision + 客户/收货/行级 SKU 参数，卡片只有 event_key/task_id——**缺参时卡片点确认应进入"补充信息"分支而不是报错**（正好接 missing-info 模板）。
- 触发点：订单确认卡在什么时机发给谁（草稿生成后发给提交人？群里？）待定。

### T3 接受发货信息
- **群聊收 file 已实测决议（2026-08-21，见 §7）**：当前智能机器人群聊流程对群聊 file 在操作上
  不可用（用户发 XLSX 后立即 + 延迟复查生产库，`app.channel_messages` 无成功持久化的有效
  group/file 回调、`app.message_media` 无群聊 file 媒体证据行（后者仅一致的支持性观察），同一群
  同一机器人文字回调正常到达；不声明协议普遍不支持，见 §7 边界）。
- ✅ 好消息：`TrackingFileService` 已有履约方回传文件解析（CSV/XLSX 整批校验、单事务接收 → ShipmentTrackingService）——T3 文件路径直接复用其解析/接收逻辑，不是从零写解析器；缺口只剩"file msgtype 纳入媒体下载 + 解析结果接入意图链路"。
- **主路径（#86 决策）**：单聊直发文件给机器人 → 下载解密（`file.url` + `file.aeskey`）→ 喂入
  `TrackingFileService` 解析/接收 seam。代价：第三方改一对一发送；实现新增 file msgtype
  下载/解密与提交关联。回退保留后台上传；不推荐群发图片为主路径（丢机器可读结构）。

### T4 履约导出发送+提醒
- **提醒实现形态**：Q43 原文是"定时任务扫描 tracking_due_at 索引"，你拍板的是"发送时刻计时 + 周期重复"——实现建议用**扫描器**（每 N 分钟扫 `next_reminder_at`，天然幂等、重启恢复友好），而不是每导出一条延迟任务链；已确认的 async_tasks 设施仍可承载（或 @Scheduled + 查询）。
- **主动发送 ack 语义**：现在 `sendRaw` 只提交不等待业务 ack；`aibot_send_msg` 发送成功与否需要 req_id 关联 ack 才能可靠记录 `sent_at`（提醒计时依赖它）——传输层要补。
- 触发方式 / 群映射 / 重试策略：见全局待确认 1/2/5。

### T5 刷新订单 MCP
- **在线拉取替换 blocked by** 07/08/09（已挂，一期脚本通道不阻塞）。
- **聚福宝缺收货人**：现有脚本通道只报告拉取数量（票 15 blocker），refresh 输出必须如实呈现。
- **refresh 耗时**：拉取脚本超时上限 10 分钟，MCP tools/call 同步等待体验差——建议 refresh 入 async_tasks（MCP 返回任务 id，完成后 agent 再查结果），与 T4 提醒共用延迟任务设施。
- ✅ 好消息：批次确认接口无版本参数、幂等键已收敛重放；京东路径确认后自动触发 SDK 建单（jd-real-sdk-switch 05）已有——"确认→发货"对京东全自动、对第三方生成履约导出文件，MCP 只透传 `outbound_routing`。

### 横切
- 真实企微验收门槛：T2/T4 发送提醒依赖真实企微群验证（卡片事件、主动发送），本地单测只能算实现证据；T3 文件路径的「群聊可达性」已由 #85 实测决议关闭（走单聊直发），单聊 file 下载解密仍须真实单聊验收（已有 single/file=1 历史真实回调作旁证）。
- 文档同步：CONTEXT.md MCP 边界、wecom-message-intake spec 的"一期禁止终局工具"表述、wecom-long-connection map 的 Out of scope（主动推送/模板卡片已进入 Phase 1）。

## 6. 落地顺序建议

1. ~~**实测群聊 file**（半天）~~ ✅ 已完成（2026-08-21，见 §7）→ T3 文件路径改走单聊直发主路径；#86 不再等待群聊 file 能力，可与后续步骤并行推进
2. 传输层补主动发送 + 上传（T4 前置，T2 也依赖）
3. 履约方→群映射 + 发送记录 + T4 发送动作
4. TRACKING_REMINDER 周期提醒（复用 async_tasks，含 Q43 扫描器落地）
5. T2 订单确认模板（翻转 template_card_event + 卡片 + 更新）
6. T1 MCP 跑通验证（工具面核对）
7. T5 刷新订单 MCP（refresh + 批次查询 + confirm_import_batch，复用现有应用用例）
8. U1 Channel 门面收口（把 1-7 的散点收进 10 接口）
9. U2-U8 按序推进

## 7. 群聊 file 实测决议（2026-08-21 真实环境）

对应 GitHub Issue #85（05 — 群聊 file 消息接收实测）。完整证据与边界见
`docs/agents/wecom-group-file-spike.md`。

**操作结论（窄口径）**：当前真实智能机器人群聊流程对群聊 file 消息**操作上不可用**——用户在
业务群发送 XLSX 后，立即与延迟两次复查生产库，`app.channel_messages` 无成功持久化的有效
group/file 回调、`app.message_media` 无群聊 file 媒体证据行（后者仅作一致的支持性观察，非独立
证明），而同一群同一机器人文字回调正常落库（group/text count=2，最新 2026-08-21
21:59:49.502972+08）。**不声明协议普遍不支持群聊 file**：应用日志/DB 只证明没有成功持久化的
有效回调，不能证明没有任何 file 帧到达进程、也不能证明企微从未发出。

**关键对照证据（均为 2026-08-21 与 2026-08-20 真实生产数据，不含任何原始 URL/aeskey/ID/密钥）**：

- 连接在线：`WECOM_CONNECTION_ESTABLISHED`（接收链路处于可接收状态）。
- 同群文字到达（group/text count=2，内容为一条 @成员的文本）：证明同一群/机器人文字回调路径是活的。
- 同群 file 未成功持久化（用户发 XLSX，UI 不允许在文件消息上 @ 机器人；立即 + 延迟复查
  `app.channel_messages` group/file=0、`app.message_media`=0）：只证明无成功持久化的有效群聊
  file 回调、无群聊 file 媒体证据行；`message_media`=0 仅作一致的支持性观察（
  `WecomMediaEvidenceService` 本就不提取 file），不能证明没有任何 file 帧进入进程。
- 历史真实单聊 file 回调存在（single/file count=1，2026-08-20 17:07:40.682914+08，帧内含
  `file.url` 与 `file.aeskey`）：证明单聊 file 帧路径真实可达。

**#86（T3 文件路径）主路径**：单聊直发文件给机器人 → 下载解密（`file.url` + `file.aeskey`）→
喂入既有 `TrackingFileService` 解析/接收 seam。代价：第三方改一对一发送；实现新增 file msgtype
下载/解密与提交关联。回退保留鉴权后台上传；不推荐群发图片为主路径（电子表格转图片丢机器可读结构）。
