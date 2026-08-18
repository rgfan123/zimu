# 04 — 接收链路适配与固定回执

Type: grilling
Status: resolved
Blocked by: 01 — WS 长连接客户端选型与协议基线, 02 — 配置与凭据模型

Label: wayfinder:grilling

## Answer

`aibot_msg_callback` 复用 `MessageSubmissionService` / `ChannelMessageCommand` 链路：`chattype` 区分单聊（无 `chatid`）与群聊，`from.userid` 明文直用，`msgid` 幂等（沿用「重复回调只落库和回执一次」）。回执 `aibot_respond_msg` 透传回调 `req_id`，回复「已接收」；发送失败重试 1 次、仍失败只告警不重推。事件：`enter_chat` 与 `disconnected_event` 留档审计（不回复欢迎语），`template_card_event`、`feedback_event` 忽略不落库。**不设白名单**——单聊与群聊任何可触达消息都进识别流程，推翻旧 spec「业务群 allowlist」决策（wecom-message-intake spec User Story 4 与决策 84 行）。

长连接消息/事件回调如何映射进现有 `MessageSubmissionService` 证据链路，固定回执「已接收」怎么回？

- `aibot_msg_callback` → 内部规范化：`chattype` single/group 区分（单聊无 `chatid`）；`from.userid` 明文；`msgid` 幂等（沿用现 spec"重复回调只落库和回执一次"）；`msgtype` text / image / mixed 范围（voice/file/video 出界）。
- 回执：`aibot_respond_msg` 透传回调 `req_id`，回复「已接收」；回执失败（连接中断、频率限制 30 条/分）时的重试或告警策略。
- `aibot_event_callback`：`enter_chat`（不做欢迎语，是否留档？）、`feedback_event`、`template_card_event`（不做卡片，忽略？）、`disconnected_event`（接 03 票策略）。
- 单聊场景：不设白名单（用户已确认），任何可触达机器人的单聊/群聊消息都进入识别流程——确认这是否推翻旧 spec「业务群 allowlist」决策（`wecom-message-intake` spec User Story 4 与「企业微信 Adapter 只处理…业务群 allowlist」）。
