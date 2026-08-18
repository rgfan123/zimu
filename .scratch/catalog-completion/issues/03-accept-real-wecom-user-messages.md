# 03 — 验证企微消息网关接收用户消息

**What to build:** 配置完成后，真实企微用户在指定业务群发送或转发消息，系统能够验签、解密、幂等保存并及时返回固定“已接收”；后台可查看该条消息证据。

**Blocked by:** None — can start immediately

**Status:** wontfix

**Claimed by:** codex-root

- [x] URL 验证和加密消息回调通过公开 HTTP seam，错误签名、非白名单群和重复消息均按既有边界处理。
- [x] 普通自动化使用固定协议样本和真实 PostgreSQL，证明文字/图文消息持久化、固定回执、后台查询和下游任务入队。
- [x] 启动配置提供 fail-fast 的 readiness 诊断，缺 corp/bot/token/AES key/allowed group/domain 时明确不可验收，不输出密钥。
- [ ] 若当前环境具备真实企微凭据和可达 HTTPS 回调，执行一次指定业务群的真实用户消息验收并记录 request id、message id、接收时间与后台可见性。
- [x] 若外部条件不齐，保留明确外部门禁，不把本地样本或 Mock 成功描述为真实企微已可用。

## Comments

- 2026-08-14 独立复核：Standards 无 P0–P2；唯一 P3 已修复，callback POST 200 现引用 `WecomEncryptedResponse`。Spec 无 P0–P3；本地源码纵切可收口，真实平台验收仍受外部条件阻塞。本轮没有运行浏览器或企微外部请求。
- 2026-08-14 wontfix：企微接入已从「设置接收消息回调地址」（Webhook）整体替换为智能机器人长连接（WebSocket，见 `.scratch/wecom-long-connection/`）；本票的 URL 验证/加密回调/公网可达性语义已不存在，真实企微验收由 `wecom-long-connection/07` 与 `wecom-message-intake/13` 覆盖，本票关闭不再作为待办。

## Answer

当前源码已具备本地自动化验收所需的纵向切片：

- 公开 `GET/POST /wecom/callbacks/{connection_id}` 完成 URL 验证、验签、解密、bot/业务群白名单、文字与 mixed 图文回调解析、事务幂等保存与加密固定回执。接收事务原子写入 `ChannelMessage`、`MessageSubmission` 与唯一 `INTERPRET_MESSAGE` 任务；重复 message id 不会重复建提交或任务。
- 管理查询需要 Operator，只返回白名单消息证据与受控原始载荷引用；Token、EncodingAESKey、`response_url` 和图片临时解密信息不进入管理响应。Nginx 只对签名 callback 关闭 Basic Auth，`/api/` 管理面仍由 Basic Auth 注入 Operator。
- 受权 `GET /api/v1/wecom/connections/{connection_id}/readiness` 只返回非秘密门禁：连接开关、corp/bot/token/AES 配置是否完整、白名单数量和由显式 HTTPS 基址组成的 callback URL。`NOT_VERIFIED` 只表示配置可以尝试真实验收，不表示公网可达或真实消息已通过。

当前快照测试证据：`mvn -Dtest=WecomMessageCallbackApiTest test` 使用 Testcontainers PostgreSQL 16、Flyway V1→V20，4/4，0 failures / 0 errors / 0 skipped，exit 0，完成于 2026-08-14 12:06 +08:00；覆盖 readiness 与 HTTPS 负例、URL 验证/错签名、白名单/重复回调、文字/mixed 持久化、固定回执、管理查询与任务入队。OpenAPI YAML 当前可解析，readiness path/schema 均存在。

三层状态必须分开：

1. **本地源码实现：可 resolve。** 没有已知 P0–P2；上述 OpenAPI P3 不影响 callback 运行或本地验收。
2. **当前 Compose 运行态：本地实现已生效。** 2026-08-14 复核健康 backend 的受权 readiness：`local_callback_implemented=true`、`configuration_ready=false`、`real_acceptance_executable=false`、`external_acceptance_status=BLOCKED_BY_CONFIGURATION`，且响应不返回任何凭据值。显式 HTTPS callback 基址已经配置并形成稳定 URL，但这本身不证明公网可达。
3. **真实企微平台：blocked。** 当前容器中 relay 仍关闭，corp id、bot id、callback token、有效 EncodingAESKey 与 allowed group 均未配置；readiness 稳定列出 `CONNECTION_ENABLED`、`CORP_ID`、`BOT_ID`、`CALLBACK_TOKEN`、`ENCODING_AES_KEY`、`ALLOWED_GROUP_ID` 六项缺口。未执行企微后台 URL 绑定、公网可达性或真实群消息验收，因此尚无可记录的 request id、message id、接收时间或后台可见性证据，不能声称真实企微已可用。
