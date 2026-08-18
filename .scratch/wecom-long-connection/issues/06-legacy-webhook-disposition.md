# 06 — 旧 Webhook 代码与旧票处置

Type: grilling
Status: resolved
Blocked by: 04 — 接收链路适配与固定回执, 05 — 多媒体证据接收适配

Label: wayfinder:grilling

## Answer

**删除**旧 Webhook 实现：`WecomCallbackController`、`WecomCallbackCrypto`、`WecomMessageCallbackApiTest` 及 `ConnectorApiTest` 中 wecom 回调断言。理由：企微后台已切换长连接、旧回调地址失效；02 决策已移除 token/encodingAesKey 字段；保留死代码会形成双维护面且旧测试恒红。旧票处置：`wecom-message-intake` 的 02/07/13 传输层交付物在新渠道重建（07 票 checkbox 作为媒体链路验收基线），旧票保留原记录不再回改；长连接事件留档需新表（归接收链路实现）。

被替换的 Webhook 实现与旧票怎么处置？

- 企微后台已切长连接模式，旧回调地址失效——`WecomCallbackController`、`WecomCallbackCrypto`、相关 readiness/连接测试是**删除**还是**冻结保留**（如关闭路由、留待回滚）？
- `wecom-message-intake` 传输层旧票重定向：`02`（接收并查看企业微信文字消息）、`07`（接收图片并形成可复核订单草稿）、`13`（完成一期整链验收与真实企微门禁）——标 wontfix（被替换）还是保留 resolved 记录 + 在本 effort 开新验收票？旧票的 `## Answer` 记录与测试代码（HTTP 回调协议测试）如何迁移？
- 旧测试矩阵：`WecomCallbackController` 相关测试替换为 WS 帧级测试的范围与边界（哪些协议细节值得帧级断言，哪些留给真实验收）。
