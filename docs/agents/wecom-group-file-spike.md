# 群聊 file 消息接收实测结论（Issue #85）

> 本文件是「群聊 file 消息机器人到底能不能收到」的真实环境实测结论的**持久化支持证据**
> （durable supporting evidence），供 #86（接受第三方发货文件回传）选择实现路径，并同步
> `.scratch/wecom-upgrade-plan.md` 的 T3 / U7 / 全局待确认 3 与落地顺序。
>
> 按仓库约定（docs/agents/issue-tracker.md），当前工作须通过 GitHub Issues 更新，本文件**不是
> Resolution 的终态载体**。权威 Resolution 在关闭前还须以**脱敏后的 GitHub Issue #85 评论**
> 形式发布（去掉原始 ID/密钥/PII，仅保留结论与边界）；本文件作为其支撑证据保留在仓库内。

## 0. 一句话（操作结论）

真实企微智能机器人环境下实测（2026-08-21 Asia/Shanghai）：**当前智能机器人群聊流程对群聊
file 消息在操作上不可用**——用户在业务群发送 XLSX 后，立即与延迟两次复查生产库，
`app.channel_messages` 无成功持久化的有效 group/file 回调、`app.message_media` 无群聊 file
媒体证据行（后者仅作一致的支持性观察，非独立证明），而同一群同一机器人的文字消息回调正常落库。
因此 #86 **不等待群聊 file 能力**，改走「单聊直发文件给机器人」为主路径。

## 1. 实测证据（2026-08-21 / 2026-08-20 真实生产数据）

| # | 证据 | 说明 |
|---|---|---|
| 1 | 真实企微连接测试成功：`WECOM_CONNECTION_ESTABLISHED` | 长连接在线，接收链路处于可接收状态 |
| 2 | 生产库 `app.channel_messages`：group/text count=2，最新 2026-08-21 21:59:49.502972+08，内容为一条 @成员的文本 | 同一群/机器人的文字回调路径**是活的** |
| 3 | 用户在该群发送 XLSX 文件；UI **不允许**在文件消息上 @ 机器人 | 群聊 file 无法触发 @ 机器人回调 |
| 4 | 立即 + 延迟两次复查生产库：`app.channel_messages` group/file count=0，`app.message_media` count=0 | **无成功持久化的有效群聊 file 回调、无群聊 file 媒体证据行**（`message_media`=0 仅作一致的支持性观察） |
| 5 | 历史真实单聊 file 回调存在：single/file count=1，2026-08-20 17:07:40.682914+08，帧内含 `file.url` 与 `file.aeskey` | 单聊 file 帧可到达应用，且携带可下载/解密凭据 |

证据 2 与 4 的对照是关键：同一时间窗、同一群、同一机器人，文字成功落库、file 未成功持久化。

当前接收代码（`WecomMessageDispatchHandler` → `ChannelMessageIntakeService`）对任何
`aibot_msg_callback` 的 msgtype 都统一落 `app.channel_messages`（无文本则 content 空串），
**不按 msgtype 过滤**。但 group/file count=0 只能证明「没有**有效**（带 msgid）且**成功持久化**
的群聊 file 回调出现在 `app.channel_messages`」——`WecomMessageDispatchHandler` 会丢弃缺少
msgid 的帧，`ChannelMessageIntakeService` 的落库也可能失败（异常时只记日志、等待通道重试）。
因此 group/file count=0 **不能证明没有任何 file 帧进入进程**，也不能证明企微从未发出。

## 2. 结论的精确边界（不得过度声明）

- **只声明**：当前真实智能机器人群聊流程对群聊 file 消息在操作上不可用——用户发 XLSX 后
  延迟轮询，`app.channel_messages` 无成功持久化的有效 group/file 回调、`app.message_media`
  无群聊 file 媒体证据行（后者仅作一致的支持性观察），而同一群文字回调落库。这足以将当前
  操作流视为不可用。
- **不声明**：企微协议「普遍/永远」不支持群聊 file；也**不能证明**没有任何 file 帧到达进程、
  或企微从未发出。应用日志与 DB 只能证明「没有成功持久化的有效回调」，不能证明帧未到达进程；
  可能原因（智能机器人不支持群 file、或 UI 无法 @ 导致未触发回调、或协议层面不投递）未被
  本次实测区分。
- 单聊 file 的历史真实回调（证据 5）证明「单聊 file」这条帧路径在真实环境可达。

## 3. #86 主路径与代价

- **主路径（推荐）**：第三方将回传文件**单聊直发**给机器人 → 应用按既有媒体链路下载并解密
  （复用 `file.url` + `file.aeskey`，扩展 `WecomMediaCrypto` / `WecomMediaEvidenceService` 的
  file msgtype）→ 喂入既有 `TrackingFileService` 解析/接收 seam，接上既有批量确认链路。
- **代价**：① 第三方需改为一对一发送，而不是在业务群发；② 实现须新增 file msgtype 的
  下载/解密与消息、提交、媒体证据关联；XLSX 由专用文件任务确定性解析，不进入模型解释队列。
- **回退（保留）**：既有鉴权后台上传/手工上传路径保持不变，作为不受企微限制的兜底。
- **不作为主路径**：不推荐「群里发图片」作为主路径——电子表格转图片会丢失机器可读结构，
  无法走 `TrackingFileService` 的 CSV/XLSX 解析。

## 4. 对升级计划的落点

- `.scratch/wecom-upgrade-plan.md` 全局待确认 3「群聊能否收到 file 消息」→ 已决议；
- T3 / U7 不再依赖「群聊 file 实测」；#86（T3 文件路径）改走单聊直发主路径；
- 落地顺序第一步「实测群聊 file」→ 已完成，改为按 #86 单聊直发主路径推进。

## 5. 隐私与证据纪律

本文件不记录任何原始 URL、aeskey、chatid、userid、消息 ID、密钥或人员姓名等 PII；
时间戳仅保留到业务日期与证据所需的时刻，用于证明「同群文字成功落库、同群 file 未成功持久化」的对照。
