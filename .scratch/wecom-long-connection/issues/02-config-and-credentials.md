# 02 — 配置与凭据模型

Type: grilling
Status: resolved
Blocked by: None — can start immediately

Label: wayfinder:grilling

## Answer

配置收敛为**单一机器人**：`botId` / `secret` / `wsUrl`（默认 `wss://openws.work.weixin.qq.com`）/ 心跳间隔（默认 30s）/ `enabled`（默认 false）；移除 `token`、`encodingAesKey`、allowlist 与多连接 `Map<connectionId, Connection>` 结构。凭据走 git-ignored `backend/.env.wecom.local`（0600，沿用 `.env.jd.uat.local` 模式）。缺配置时应用正常启动、连接不建立、readiness 标记不可用（不拒绝启动）。

长连接替换后，`WecomProperties` 的配置与凭据模型怎么定？

- 现状：`WecomProperties.Connection`（token / encodingAesKey / enabled / 群 allowlist），git-ignored env 加载（参照 `backend/.env.jd.uat.local` 的 0600 文件模式与 `JD_ENV_FILE` 显式指向）。
- 新模型需要覆盖：BotID、长连接 Secret、WS 地址（`wss://openws.work.weixin.qq.com`）、心跳间隔（可配置默认 30s）；是否保留多 connection 结构（现为 `{connection_id}` 路由）还是收敛为单机器人配置。
- 不设置准入白名单（用户已确认）——现有 allowlist 字段应移除还是保留为空壳？
- 缺失配置时的启动/运行行为：拒绝启动、还是启动但 readiness 标记不可用（对齐现有 `WecomConnectionReadiness` 的"只读投影 + 一次性列出全部缺失键"风格）。
