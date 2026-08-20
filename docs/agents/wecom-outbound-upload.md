# 企微长连接临时素材三步分片上传（Issue #82）

> 官方协议权威源：[智能机器人长连接](https://developer.work.weixin.qq.com/document/path/101463)
> （2026-08-21 核对）。本文件是 #84（发送文件消息）消费**上传 seam** 的事实源：调什么接口、
> 传什么参数、拿什么结果、哪些失败可重试、media_id 能用多久、绝不能做什么。

## 1. 官方限制（逐类型）

| 类型 | body.type | 大小上限 | 允许扩展名 | 本票支持 |
|---|---|---|---|---|
| 普通文件 | `file` | 20 MiB | xlsx / xls（本票范围） | ✅ |
| 图片 | `image` | 10 MiB | png / jpg / jpeg / gif | ✅ |
| 语音 | `voice` | 2 MiB | amr（一期不支持上传） | ❌ |
| 视频 | `video` | 10 MiB | mp4（一期不支持上传） | ❌ |

协议级硬限制（对任何类型都成立）：

- 单分片 ≤ **512 KiB**（Base64 编码前）；最多 **100** 片；分片序号 **从 0 开始**；
- `total_size` 最少 **5 字节**；`filename` 与 `headers.req_id` 均 ≤ **256 字节**
  （按 UTF-8 字节数，不是 Java char 数）；
- 重复上传同一分片**幂等**（服务端自动忽略），分片可**乱序**上传；
- 上传会话（upload_id）有效期 **30 分钟**，超时未 finish 自动清理；
- 合并时按 init 携带的 **MD5** 校验完整性；
- 临时 media_id **3 天有效**；
- 频率限制：单机器人上传 **30 次/分钟、1000 次/小时**（服务端计数，客户端不节流，
  被拒后按 errcode fail closed）。

## 2. 三步协议

1. `aibot_upload_media_init`：body `{type, filename, total_size, total_chunks, md5?}`，
   成功应答 `body.upload_id`；
2. `aibot_upload_media_chunk`：body `{upload_id, chunk_index, base64_data}`，
   成功应答仅 `errcode=0`；
3. `aibot_upload_media_finish`：body `{upload_id}`，成功应答
   `body{type, media_id, created_at}`。

所有应答都按 `headers.req_id` 关联；`errcode=0` 才算成功，字段缺失一律 fail closed。
finish 成功应答的 `body.type` 必须等于请求类型、`body.created_at` 必须是正的 Unix 秒，
否则视为证据矛盾（见 §4）。

## 3. #84 使用的 seam（深模块）

```java
// backend/.../connector/wecom/WecomOutboundGateway.java
@Autowired WecomOutboundGateway wecom;

WecomUploadResult result = wecom.upload(path, filename, WecomMediaType.FILE);
```

- 参数：本地 `Path` + 发送给企微的 `filename` + 明确 `WecomMediaType`（FILE/IMAGE）；
- **不要**在 #84 拼协议 JSON、不直接触碰 `aibot_upload_media_*` 帧；
- 前置校验失败抛 `WecomUploadValidationException`（`code()` 稳定码 + 中文可读消息），
  该异常**不创建 upload_id、不写审计**，无需重试语义；
- 其余结局统一返回 `WecomUploadResult`（见 §4），业务侧按 `status()` 分支。

`WecomUploadResult` 字段：

| 字段 | 说明 |
|---|---|
| `status` | `SUCCESS` / `FAILED` / `UNKNOWN`（finish 结局未知，必须人工对账） |
| `mediaId` | 仅 `SUCCESS` 携带；`mediaType`/`createdAt`/`acknowledgedAt`/`requestId`/`uploadId` 同为其 ack 证据 |
| `uploadId` | init 成功后即有；断线续传与人工对账的依据 |
| `step` | `INIT` / `CHUNK` / `FINISH`（稳定安全元数据） |
| `errorCode` / `errorMessage` | 服务端 errcode / 稳定错误码（如 `UPLOAD_CHUNK_REJECTED`、`FINISH_ACK_UNKNOWN`） |
| `retryable` | **仅**表示「可安全从头重试」（重新 init；旧 upload_id 成为 30 分钟后自动清理的孤儿会话） |

## 4. 断线续传与有界重试语义

上传器（`WecomMediaUploader`）内维护：`upload_id`、下一个待确认 `chunk_index`、
30 分钟会话期限。全部等待/重试都有界：

- **断线（LOST）**：分片已提交但未获 ack → 等待重连 `SUBSCRIBED`（每次有界等待
  60s）→ 以**相同 chunk_index** 重发（服务端幂等）→ 继续；
- **ack 超时（TIMEOUT，连接存活）**：以相同 chunk_index 重发，同样消耗预算；
- **NOT_READY（提交瞬间连接不可用）**：帧**未入队、未提交**（连接在就绪检查与入队之间
  断线），可安全重试——预算内等待重连 `SUBSCRIBED` 后重做该步：INIT 以**新 req_id** 重做
  （旧帧没有任何协议状态，无孤儿会话风险），CHUNK 以**相同 upload_id + 相同 chunk_index**
  重发（服务端幂等），FINISH 安全**重提交一次**（该次 finish 帧未提交，不存在重复提交）；
  每次 NOT_READY 恢复消耗同一重试预算，且 CHUNK/FINISH 在等待后**重新检查 30 分钟会话
  期限**，不得越过截止时间发送；
- **BACKPRESSURE（发送队列满）**：帧同样**未入队、未提交**，保持快速失败 → `FAILED`
  `OUTBOUND_BACKPRESSURE`，`retryable=true`（可安全从头重试），不消耗重试预算；
- **重试预算**：默认 5 次「未获 ack 重发/断线恢复/NOT_READY 恢复」，耗尽 → `FAILED`
  `UPLOAD_RETRY_BUDGET_EXHAUSTED`（或按最后一次失败形态
  `UPLOAD_ACK_TIMEOUT` / `UPLOAD_SEND_FAILED`），`retryable=true`；
- **会话超期**：init ack 后 30 分钟未完成 → `FAILED` `UPLOAD_SESSION_EXPIRED`，
  `retryable=true`；
- **服务端拒绝**（errcode 非 0）：init/chunk/finish 各自 fail closed
  （`UPLOAD_INIT_REJECTED` / `UPLOAD_CHUNK_REJECTED` / `UPLOAD_FINISH_REJECTED`），
  携带 errcode，`retryable=true`（从头重试安全）；
- **finish 结局未知**（提交后未获 ack TIMEOUT/LOST/SEND_FAILED，或应答 errcode=0 但证据
  矛盾：缺 media_id/type/created_at、`body.type` 与请求类型不一致、`created_at` 非正的
  Unix 秒（0/负数/不可转换））：服务端**可能已生成 media_id**，**禁止盲目重发 finish、
  禁止标记可重试** → 一律返回 `UNKNOWN`（`FINISH_ACK_UNKNOWN` / `FINISH_MISSING_MEDIA_ID`
  / `FINISH_MEDIA_TYPE_MISMATCH` / `FINISH_RESPONSE_INVALID`，携带 upload_id + finish
  req_id），由人工对账决定是否重传；#84 只按 `status()==UNKNOWN` 一个分支进入对账。

绝不把半截上传当成功：任何未获确认的分片或 finish 都不会产生 `SUCCESS`。

其余稳定错误码（docs 只列部分时的全集，均与 `WecomUploadResult.errorMessage` 一致）：
`INIT_MISSING_UPLOAD_ID`（init 应答缺 upload_id）、`UPLOAD_FILE_READ_FAILED`（本地文件
读取失败）、`UPLOAD_RECONNECT_TIMEOUT`（等待重连超时）、`CONNECTION_NOT_READY`（入口
预检未连接，快速失败、retryable）、`UPLOAD_FAILED`（兜底）；前置校验码（抛
`WecomUploadValidationException`，不写审计、不创建 upload_id）：
`UPLOAD_TYPE_REQUIRED` / `UPLOAD_FILENAME_REQUIRED` / `UPLOAD_FILENAME_TOO_LONG` /
`UPLOAD_FILE_REQUIRED` / `UPLOAD_FILE_NOT_FOUND` / `UPLOAD_FILE_NOT_REGULAR` /
`UPLOAD_FILE_NOT_READABLE` / `UPLOAD_FILE_SIZE_UNREADABLE` / `UPLOAD_FILE_TOO_SMALL` /
`UPLOAD_FILE_TOO_LARGE` / `UPLOAD_EXTENSION_MISSING` / `UPLOAD_EXTENSION_NOT_ALLOWED` /
`UPLOAD_TYPE_UNSUPPORTED` / `UPLOAD_TOO_MANY_CHUNKS`。

## 5. 心跳、队列与内存纪律

- 上传帧（init/chunk/finish）走客户端**同一个有界发送队列**；心跳保留容量与高优先级
  不变，多片上传期间 ping 可越过后续业务片；
- 单个大分片正在 socket send 时由发送线程串行化，**不并发乱写**；
- 文件读取 / MD5 / Base64 只发生在**调用方线程**（接收线程只解析 JSON 与完成 ack）；
- MD5 与分片都流式读取（≤ 512 KiB 缓冲），**从不整文件载入内存**；
- 文件内容、base64、`media_id` **绝不进普通日志或整包审计**；错误只保留
  稳定 code / step / req_id / upload_id / errcode；审计只记 media_type、文件大小、
  状态与稳定错误码（`WecomOutboundGateway.auditUpload*`）。

## 6. media_id 有效期与禁止长期缓存

- 临时 media_id **3 天有效**，业务侧**不得**把它当永久引用缓存；
- **不引入数据库持久缓存**：每次需要发送文件消息时现上传（或仅在内存中短时持有并
  明确过期语义）；`upload_id` 30 分钟后失效，同样不可复用；
- #84 拿到 `SUCCESS` 的 media_id 后应立即用于文件消息发送，不要落库长期引用。

## 7. 成功结果不可伪造

`WecomUploadResult` 的规范构造器强制不变式：`SUCCESS` 必须同时携带 media_id、mediaType、
createdAt、acknowledgedAt、uploadId 与 requestId，且不允许携带错误或 retryable；
`UNKNOWN` 必须携带 uploadId + requestId 且不可重试；`FAILED` 不得携带任何成功证据。
传输层只通过包内工厂产出结果，业务侧无法拼装出一个缺 ack 证据的 SUCCESS。
finish 应答即使 `errcode=0`，只要 `body.type` 与请求类型不一致、或 `created_at` 不是正的
Unix 秒（0/负数/不可转换），一律按证据矛盾返回 `UNKNOWN`，绝不构造假成功。
（与 #81 `WecomSendResult` 同一模式。）

## 8. 测试门禁

- `WecomMediaUploaderTest`（帧级，手写 Rfc6455 服务器自动应答 init/chunk/finish，
  可注入 errcode / 丢 ack / 指定分片断线 / finish 缺 media_id / finish type 与 created_at
  覆盖 / ack 延迟）：成功重组与 MD5、逐类型校验 fail-fast、errcode/超时/缺字段 fail
  closed、NOT_READY 三阶段确定性恢复（同 index 重发、finish 只提交一次、预算消耗）、
  断线同 upload_id 同 index 幂等续传、30 分钟超期、预算耗尽、心跳优先级与发送串行化
  （事件驱动、无固定睡眠）、finish 类型/时间证据矛盾 → UNKNOWN、日志不泄漏；
- `WecomOutboundGatewayTest`：上传深模块 seam、审计安全投影、未连接 fail-fast；
- 既有 `WecomLongConnectionClientTest`（send ack、fragmented ack、timeout/reconnect）
  不得退化。
