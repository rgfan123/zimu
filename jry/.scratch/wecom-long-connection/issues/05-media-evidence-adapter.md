# 05 — 多媒体证据接收适配

Type: research
Status: resolved
Blocked by: 04 — 接收链路适配与固定回执

Label: wayfinder:research

## Question

长连接模式下的图片/图文混排证据接收，如何适配 `aeskey` 下载解密并合并进现有图片证据链路？

- 长连接 `image`/`mixed` 结构体带 `url`（5 分钟有效）+ 每 URL 独立 `aeskey`；解密为 AES-256-CBC、PKCS#7 填充至 32 字节倍数、IV 取 aeskey 前 16 字节——与旧回调模式统一 EncodingAESKey 不同。
- 调研现状：`wecom-message-intake` 07 票（接收图片并形成可复核订单草稿）的既有下载/解密/落库实现与测试，确定哪些可复用、哪些必须重写。
- 输出：适配方案（下载失败重试次数与最终人工待办——对齐 spec User Story 14/15）、与 `ChannelMessage`/`MessageMedia` 模型的合并点、测试策略（本地构造 aeskey 密文样本）。

## Answer

关键修正：`wecom-message-intake` 07 票**并未实现媒体下载/解密/落库**（状态 ready-for-agent、无 Answer），现有实现只到「接收 + 落原始载荷 + 排解释任务」——`WecomCallbackController.mixedText()` 把含 url + aeskey 明文的整包 rawPayload 存进 `channel_messages.raw_payload` jsonb，全仓无 HTTP 下载能力、无 `MessageMedia` 类或表。因此本票交付物是**新建媒体链路**而非合并：WS 帧解析、媒体下载（全仓无 HTTP 客户端）、纯 CBC aeskey 解密（AES-256-CBC、PKCS#7 32 字节块、IV=aeskey 前 16 字节）、受控存储 + `MessageMedia` 表、复核页原图受权接口、媒体级密文测试样本。

可复用：`WecomCallbackCrypto` 的 AES 原语（去掉信封即新规范）、幂等落库骨架、`AsyncTaskStore` 3 次重试与终态 NEED_REVIEW、sanitizer 白名单模式、`InterpretationInput.mediaContentRefs`（已存在但全仓无人填充——现成媒体接入点）。验收基线：07 票 checkbox。详见 `## Findings` 段。

### 前提修正：07 票并未实现媒体下载/解密/落库

`wecom-message-intake/issues/07-receive-image-order-evidence.md` 状态仍为 `ready-for-agent`、全部 checkbox 未勾选、**没有 Answer 段**；`wecom-long-connection/map.md` 明确其传输层 02/07/13 已重定向到本 effort。当前实际实现仅到「接收 + 落原始载荷 + 排解释任务」为止：

- `WecomCallbackController.receive()`（HTTP 回调）用统一 `EncodingAESKey` 解密信封后，`mixedText()` 只做校验（text 内容拼接 + 要求 image 项含 `url`），然后把**整包 rawPayload JSON（含 image.url 与 aeskey 明文）**原样传给 `ChannelMessageCommand` 落库；没有任何下载、解密或媒体存储代码。
- `wecom` 与 `message` 两个包内**不存在任何 HTTP 下载能力**（无 WebClient/RestClient/RestTemplate），也没有 `WecomMediaService`、`MessageMedia` 类或表。
- 07 票 spec 规划的 `MessageMedia` 持久化、内容寻址存储、复核页原图、下载失败重试等全部未建；已建的只有 `channel_messages.raw_payload` jsonb 列与 `INTERPRET_MESSAGE` 任务。

### 现有媒体链路代码位置与职责

| 文件 | 职责 | 与媒体链路的关系 |
|---|---|---|
| `connector/wecom/WecomCallbackController.java` | HTTP 回调入口：验签→统一密钥解密→过滤→`mixedText()` 解析→`MessageSubmissionService.submit()`→加密 stream 回执 | mixed 解析（L164-189）只校验 image.url 存在，不取 aeskey 不下载；rawPayload 整包透传 |
| `connector/wecom/WecomCallbackCrypto.java` | 统一密钥信封算法：AES-256-CBC、IV=key 前 16 字节、PKCS#7 填充至 32 字节块，外加 16B 随机前缀 + 4B 长度 + 空 receiveid | `crypt()`/`pad()`/`unpad()`/`decodeKey()` 与新 aeskey 解密同构（见可复用清单） |
| `message/ChannelMessageIntakeService.java` | `store()`：幂等 INSERT `app.channel_messages`（ON CONFLICT `(corp_id, connection_id, message_id)` DO NOTHING），`raw_payload` 存 jsonb | 媒体引用当前只活在这个 jsonb 里 |
| `message/MessageSubmissionService.java` | `submit()`：同一事务创建 `message_submissions` + `AsyncTaskStore.enqueue("INTERPRET_MESSAGE", maxAttempts=3)` | 07 票规划里媒体下载任务应挂在这里，目前只有解释任务 |
| `message/ChannelMessageQueryService.java` | list/detail 投影；detail 只暴露 `raw_payload_ref = "channel-message-payload:{id}"` 引用，不投影 raw_payload 内容 | 复核页取原图需另建受权接口，不能走此引用 |
| `message/MessagePublicProjectionSanitizer.java` | ReviewCase/解释结果的公共投影白名单（intent/error_code/order_no/配对证据/模型元数据） | 媒体下载凭据与受控引用必须沿用同样的 fail-closed 思路 |
| `message/InterpretationWorker.java` + `message/AsyncTaskStore.java` | 租约式领取（SKIP LOCKED）、attempts/max_attempts=3、退避重试、终态 FINALIZING→NEED_REVIEW 人工待办 | spec US 14/15 的「重试三次 + 最终人工待办」机制已在解释链路实现，媒体下载可直接复用同一任务表 |
| `message/InterpretationInput.java` | `mediaContentRefs` 字段已存在但**全仓无调用方填充**（grep 验证） | 预留的受控媒体接入点：下载完成后把受控引用填进这里再喂解释器 |

### 落库形态

- **现状**：没有本地文件、没有对象存储、没有 URL 落库。唯一落库是 `app.channel_messages.raw_payload`（jsonb，`V7__add_channel_messages.sql`），原样保存整包回调 JSON——image 的 `url` 与 `aeskey` 以明文躺在里面，仅靠 API 不投影 + 测试断言防泄露。
- **07 票 spec 规划（未实现）**：`MessageMedia` 表保存渠道媒体标识、下载状态、受控文件引用、哈希、内容类型、大小、解密信息、失败原因与保留策略；原件以内容哈希写入受控存储、不可变；相同内容复用存储但保留各自消息证据关系（`spec.md` L103）。

### 测试方式（07 票交付的现状）

- `backend/src/test/.../message/WecomMessageCallbackApiTest.java` 的 `encryptedMixedGroupCallbackPersistsTextAndImageEvidenceAndQueuesDownstreamWork`（L264-298）是唯一图片相关测试。
- **样本构造**：`mixedMessage()`（L417-430）直接拼 JSON——image 项 `{url: "https://ww-aibot-img.example/evidence?signature=temporary", aeskey: "per-message-media-aes-key"}`，字段形状与长连接 `aeskey` 一致；整条消息用**回调信封**加密（`encrypt()` L446-458：16B 随机前缀 + 4B 长度 + PKCS#7 32 字节块，统一 ENCODING_AES_KEY）。
- **断言重点**：消息落库（total_elements +1）、`INTERPRET_MESSAGE` 任务 PENDING、管理 API 输出不含 image url 与 aeskey、回执为加密 stream「已接收」。
- **没有**媒体文件级密文样本、没有下载替身（spec L166 要求媒体下载只在 Adapter 边界用测试替身，尚未建）。

### 可复用清单 vs 必须重写清单

**可直接复用（或小改）**：

- `WecomCallbackCrypto` 的 AES 原语：`crypt()`（AES/CBC/NoPadding + IV=key 前 16 字节）、`pad()`/`unpad()`（PKCS#7 至 32 字节块）、`decodeKey()`（43 字符 base64 → 32 字节）——与长连接媒体解密规范逐项一致；**仅需去掉信封层**（16B 随机前缀 + 4B 长度 + receiveid 校验），媒体密文是纯文件字节填充。
- 幂等落库骨架：`ChannelMessageIntakeService.store()` 的 ON CONFLICT 模式可扩展到 message_media（`(message_id, media_key)` 幂等）。
- `MessageSubmissionService.submit()` 的「提交 + 任务原子创建」模式；媒体下载任务可注册新 task_type（如 `DOWNLOAD_MEDIA`），复用 `AsyncTaskStore` 的 3 次重试、租约、退避与终态失败→NEED_REVIEW 机制（spec US 14/15 已对齐）。
- `MessagePublicProjectionSanitizer` 的 fail-closed 白名单模式；`InterpretationInput.mediaContentRefs` 槽位。
- 测试基建：`WecomMessageCallbackApiTest` 的信封加解密 helper 可继续用；mixed 样本的 image 项结构可直接搬。

**必须重写/新建**：

- 消息来源解析：HTTP 回调 JSON + 统一密钥信封解密 → WS 帧（`aibot_msg_callback`）明文 JSON 解析，image/mixed 项的 `url`+`aeskey` 提取（现 `mixedText()` 丢弃了 aeskey）。
- 媒体下载：全仓无 HTTP 客户端可用，需新建（`java.net.http` / RestClient）；url 5 分钟有效，须在任务内即时下载并处理过期。
- 媒体解密：新写纯 CBC 解密（无信封），按每条消息的 `aeskey` 解密下载字节。
- 受控存储 + `MessageMedia` 表/模型：当前只有 raw_payload jsonb，spec 规划的内容寻址存储、哈希、状态机（PENDING/SUCCEEDED/FAILED）与保留策略全缺。
- 复核页原图受权接口：`ChannelMessageQueryService.detail()` 只给 payload 引用，不含图片字节。
- 测试策略：需新增「媒体文件级 aeskey 密文样本」（固定 aeskey + 已知字节 + PKCS#7 32B 填充）与本地 mock 下载端点，替代/补充现有信封级样本。

### 与 ChannelMessage/MessageMedia 的合并点

- **表结构**：`app.channel_messages` 已存在且本 effort 不迁移（05 票只做媒体适配）；新增 `app.message_media`（FK → channel_messages.id 或 submission_id），按 spec L103 字段建。
- **解析层**：WS 帧解析后产出与 `ChannelMessageCommand` 同构的规范化输入（content 文本 + image 项列表），`raw_payload` 照旧落 jsonb 保原始证据；媒体下载任务在 `submit()` 同事务内创建。
- **解释链路**：下载/解密成功的受控媒体引用填入 `InterpretationInput.mediaContentRefs`（当前无人使用，是现成的合并点），文本继续走现有 content 路径。
- **人工待办**：媒体下载最终失败与解释最终失败走同一 NEED_REVIEW 机制（`review_cases` 已支持 `message_submission_id` 关联）。
- **07 票 checkbox 即验收基线**：内容去重（同哈希复用存储）、重复回调幂等、下载失败 3 次重试、复核页原图可对照、确认草稿成单——05 适配方案应对齐这些验收点。
