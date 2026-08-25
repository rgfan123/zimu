# 07 — 真实验收与切换门禁

Type: task
Status: claimed
GitHub: https://github.com/rgfan123/zimu/issues/34
Blocked by: 03 — 连接生命周期与运维可见性, 04 — 接收链路适配与固定回执, 05 — 多媒体证据接收适配
Claimed by: zed-agent

Label: wayfinder:task

## Question

真实企微环境的验收清单与完成标准是什么？

- 前置已满足：长连接 API 模式已开启，BotID/Secret 已拿到（外部 gate 解除）。凭据按 02 票配置模型放入 git-ignored env 文件。
- 验收清单（需用户在企微后台/真群配合）：单聊文字消息 → 证据落库 → 回「已接收」；群聊 @机器人文字消息；图片/图文混排消息 → 下载解密 → 证据可复核；断网/重启 → 心跳与自动重连；双连接踢线行为与 `disconnected_event` 告警。
- 完成标准：上述全部通过、旧 Webhook 链路确认失效、`wecom-message-intake` 业务层票在新渠道下回归通过。
- 输出：验收执行记录 + 一次性的凭据与连接诊断（不落盘、不打印 Secret）。
