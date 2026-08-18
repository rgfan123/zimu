# 企业微信智能机器人长连接替换

Label: wayfinder:map

## Destination

将企业微信智能机器人接入从「设置接收消息回调地址」（Webhook 短连接）替换为「长连接」（WebSocket，`wss://openws.work.weixin.qq.com`）API 模式：应用内常驻一条 WS 连接，接收单聊与群聊消息及事件，落证据后被动回复「已接收」，替换现有 `WecomCallbackController` 传输链路。**不做**欢迎语、流式回复、主动推送与模板卡片（用户已确认留作后续）。不设准入白名单（单聊与群聊均无过滤）。`from.userid` 为明文（机器人创建者为超管），无需加密转换。单实例部署，BotID 与长连接 Secret 已在企微后台开通并拿到。

## Notes

- 域：Spring Boot (Java 21) 单体的企微接入替换；前端与业务层（意图/草稿/复核/运单/MCP）链路不变，只换消息来源。
- 企微官方仅提供 Node.js / Python SDK，**无 Java SDK**——长连接客户端需在 Java 侧自研协议实现（候选：JDK 内置 `java.net.http.WebSocket` / Spring WebSocket / 第三方库）。
- 协议要点（官方文档 path/101463）：`aibot_subscribe` 订阅（携带 BotID/Secret，成功后避免反复请求）；心跳为**业务 JSON 帧** `ping`（建议 30s，非 WS 控制帧）；消息回调 `aibot_msg_callback`、事件回调 `aibot_event_callback`（`enter_chat`/`template_card_event`/`feedback_event`/`disconnected_event`）；被动回复 `aibot_respond_msg` 需透传回调的 `req_id`；单机器人**同一时间只能一条长连接**（新连接踢旧连接）；多媒体 `image`/`mixed` 下载需用每 URL 独立 `aeskey`（AES-256-CBC、PKCS#7 填充至 32 字节倍数、IV 取 aeskey 前 16 字节）。
- 企微后台 API 模式只能二选一：切到长连接后旧回调地址已失效——现有 Webhook 代码与真实链路已不可用，替换是迁移而非并行。
- 相关既有 effort：`wecom-message-intake`（业务层 13 张票，传输层 02/07/13 重定向到本 effort）。
- 每次会话先读本 map，再取 frontier 票。

## Decisions so far

- [01 — WS 长连接客户端选型与协议基线](issues/01-ws-client-selection.md) — 选 JDK 内置 `java.net.http.WebSocket`（零新依赖、Java 21 内置、纯 JSON 文本帧协议、心跳与重连任何方案都需自建）；协议基线：订阅一次成功不重复、req_id 透传/自生成、30s 业务 ping + 60–75s 入站看门狗、指数退避重连、单 bot 单连接。
- [05 — 多媒体证据接收适配](issues/05-media-evidence-adapter.md) — 07 票原无媒体链路（仅 rawPayload 落库）；需新建下载 + 纯 CBC aeskey 解密 + `MessageMedia` 存储 + 复核页原图接口；可复用 `WecomCallbackCrypto` AES 原语、幂等落库骨架、AsyncTaskStore 重试、`InterpretationInput.mediaContentRefs` 现成接入点。
- [02 — 配置与凭据模型](issues/02-config-and-credentials.md) — 收敛为单机器人配置（botId/secret/wsUrl/心跳/enabled），git-ignored `backend/.env.wecom.local`；移除 token/encodingAesKey/allowlist/多连接；缺配置不拒启动、readiness 标不可用。
- [03 — 连接生命周期与运维可见性](issues/03-connection-lifecycle.md) — 指数退避重连（1s→30s+抖动）；被踢停止重连标 KICKED 告警（防互踢）；订阅失败 3 次封顶；readiness 增连接状态维度（DISCONNECTED/CONNECTING/SUBSCRIBED/KICKED/FAILED）。
- [04 — 接收链路适配与固定回执](issues/04-receive-and-receipt.md) — 复用 ChannelMessageCommand 链路（chattype 区分、明文 userid、msgid 幂等）；回执透传 req_id 回「已接收」失败重试 1 次只告警；enter_chat/disconnected 留档、template_card/feedback 忽略；无白名单（推翻旧 spec allowlist 决策）。
- [06 — 旧 Webhook 代码与旧票处置](issues/06-legacy-webhook-disposition.md) — 删除 WecomCallbackController/Crypto/旧回调测试；旧票保留记录，07 checkbox 作媒体验收基线。

## Not yet specified

- 单聊场景的提交语义细化（员工直接与机器人对话的审计维度、与群聊转发的差异）——随接收链路实现推进。

## Out of scope

- 欢迎语（`aibot_respond_welcome_msg`）、流式回复、主动推送（`aibot_send_msg`）、模板卡片与更新卡片——用户确认留作后续，本 effort 不建。
- `voice` / `file` / `video` 消息类型（仅单聊支持，一期不纳入）。
- 上传素材（`aibot_upload_media_*`）——本 effort 不发送图片/文件，无需。
- 加密 userid 转明文（自建应用对接）——当前为明文 userid。
- 多实例 / 主备高可用——单实例部署。
