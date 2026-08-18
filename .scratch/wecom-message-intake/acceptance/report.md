# 13 — 一期整链验收报告

- 验收时间：2026-08-15
- 执行：opencode（含并行 subagent 与主会话直接执行）
- 环境：本地验收栈 `zimu-accept-pg`(15432) / `zimu-accept-redis`(16379) + `backend/.env.acceptance.local`（SERVER_PORT=8081）；前端 vite :5195（浏览器验收 mock）；MCP stdio 子进程 :8082
- 结论分层：**本地实现验收全部通过**；**真实企微长连接已连接（SUBSCRIBED）**，真实消息流转待用户在真群配合一次转发（见「待用户动作」）。

## 1. 本地整链验收（可重复）

- 后端全量：`mvn test` **501/501 通过**（7 skipped 为既有）。
- 核心整链：`WecomEndToEndAcceptanceTest` 7/7 —— 长连接帧接收接缝（`WecomMessageDispatchHandler.onFrame`，原加密回调已由长连接替换）、固定回执、msgid 幂等、Worker 异步解释（版本化追加）、ReviewCase 单主体约束、订单草稿→确认→CanonicalOrder→审计、运单草稿→确认、租约过期重启恢复不重复落证据。
- 本次修复 1 处验收测试断言笔误（`WecomEndToEndAcceptanceTest` 619 行：按 `message_submission_id` 查订单草稿 case，与「恰好一个主体」约束语义不符，改为 JOIN `order_drafts`；运单侧 350 行已有正确先例）。生产代码无缺陷。
- 重复运行方式：`cd backend && mvn test -Dtest=WecomEndToEndAcceptanceTest`；MCP：`python3 .scratch/wecom-message-intake/acceptance/mcp-runtime-acceptance.py`；浏览器：`python3 .scratch/wecom-message-intake/acceptance/browser-acceptance.py`（需 `cd frontend && npm run dev -- --port 5195`）。

## 2. 验收接缝合规

- HTTP：全部走公共管理 API（`/api/v1/*`），认证=网关注入 Basic Auth + `X-Operator` 复验；未直写业务表。
- Worker：经 `AsyncTaskStore` 租约/幂等键，未调用私有方法。
- 浏览器：vite + playwright route mock，数据形状与契约 DTO 一致；断言以渲染文本/选择器为准。
- MCP：JSON-RPC 2.0 over stdio 真实 jar 进程。

## 3. Schema / 契约 / 重启恢复 / Compose

- Schema smoke：`message_media`、`review_cases` 单主体约束、`async_tasks` 租约等由 V8/V19 迁移与既有测试覆盖（501 全过含约束冲突测试）。
- OpenAPI 契约：`JacksonConfig` SNAKE_CASE 全契约；既有契约测试（含新 `media_refs` 投影断言 1 处新增）全过。
- 重启恢复：`leaseExpiredTaskResumesAndNeverDuplicatesEvidence` 明确覆盖「崩溃前已领取+租约超时 → 新 Worker 重新领取 → 幂等收敛」；8081 验收进程重启后 `SUBSCRIBED` 恢复。
- Compose 公共入口：docker 验收栈 `acceptance-current-*`（nginx :18095、backend/redis/pg healthy）与本机 8081 均正常。

## 4. 浏览器证据（output/playwright/wecom-intake-13/，1440×900）

| 截图 | 覆盖 |
|---|---|
| review-queue-1440.png | 复核队列：WECOM_ORDER_DRAFT / WECOM_TRACKING_DRAFT 事项、语义 Tag |
| order-draft-panel-1440.png | 订单草稿复核：原始企微消息、缺字段、客户/线项确定性候选、确认/驳回（danger） |
| order-draft-panel-image-preview-1440.png | 原图预览：`/api/v1/message-media/88/content` 受权原图，可点击放大 |
| tracking-draft-panel-1440.png | 运单草稿复核 + 批量确认区（任务/姓名/Carrier/单号/数量 列） |
| tracking-batch-confirm-checked-1440.png | 3 行同批回传，2 行（通过校验）默认勾选，1 行（TRACKING_NO_MISSING）禁选 |
| tracking-batch-confirm-result-1440.png | 批量确认按钮已接线（mock 场景 404 → 失败行 Alert 渲染） |

敏感词扫描：`raw_payload` / `decrypt_info` / `aeskey` / `secret` / `EncodingAESKey` / `content_ref` / `shipped_at` / `WECOM_SECRET` / `storage_ref` / `agent_identity` 均未出现在复核页面 HTML；页面仅渲染白名单视图模型。伪发货时间：运单确认文案明确「实际发货时间保持为空」。

## 5. MCP 验收（运行时，5/5 PASS）

- 17 个允许工具全在；19 个终局工具（confirm_order/confirm_tracking_draft/batch_confirm/create_customer/close_review_case 等）全部缺席。
- 只读工具实际调用：`get_message_submission` 对不存在提交返回业务 `NOT_FOUND`（协议可用性证明）。
- 工具发现与描述不泄漏 `MCP_AGENT_IDENTITY`/`MCP_ENABLED`/`SECRET`/`TOKEN`/`PASSWORD`。
- agent-identity 由环境注入（`MCP_AGENT_IDENTITY=acceptance-agent`），协议帧无 operator 参数（伪造面为零）。
- 既有 `McpProtocolAcceptanceTest` 同步覆盖认证失败/版本冲突/幂等/审计。

## 6. 真实企微门禁

- `GET /api/v1/wecom/readiness`：**`SUBSCRIBED`**，`heartbeat_count` 持续增长，`secret_configured/ws_url_configured/bot_id_configured` 全 true（8081 与重启后均验证）。
- 真实消息流转（checkbox 6）**已在主栈（:8088）完成真实单聊消息流入验证（2026-08-17）**：
  - 用户与机器人单聊发送真实消息 `g` → 长连接收到 → `app.channel_messages` 落库 `id=1`，`message_id=f10190145c13d40ec33f1d42c786e78b`，`chat_type=single`，`received_at=14:56:26`。
  - 提交自动创建并解释：`message_submissions id=1` 状态 `INTERPRETED`，`INTERPRET_MESSAGE` 任务首次尝试 `SUCCEEDED`；DeepSeek `deepseek-chat / wecom-interpret-v1` 判 `NON_BUSINESS`（"消息内容为单个字母，无法构成业务信息"），未误建 ReviewCase —— 判定符合预期。
  - 列表/详情 API 可见：`GET /api/v1/channel-messages?page=0` 返回该消息；`GET /api/v1/channel-messages/1` 与 `/message-submissions/1` 白名单投影正常（注：分页为 0 起始，page=1 是第二页）。前端 `useState(0)` 与之一致，`http://127.0.0.1:8088/workbench/channel-messages` 第一页可见该消息。
- **业务群真实文字订单验收完成（2026-08-17）**：
  - 群消息 `@孔小弟 子牧要一份A5和牛送到北京国贸`（`chat_id=wrn8VIbwAA6gQWyB_bxqHvkflW2ivdOQ`，`chat_type=group`，`received_at=15:01:54`）→ `channel_messages id=3` 落库。
  - DeepSeek 判 **CUSTOMER_ORDER**（v3）：A5和牛 ×1、收货人「子牧」、地址「北京国贸」；提交 `SUB-3` 状态 **DRAFTED**。
  - 草稿 **OD-3-3 OPEN** + 复核事项 `RC-WECOM-30b756f37393413f OPEN`（`/api/v1/review-cases` 可见）；缺字段 `customer / receiver_phone / settlement_method / line_1_sku`。
  - 重解释版本化收敛正确：7 个 `INTERPRET_MESSAGE` 任务（1 初始 + 6 次用户重解释）经 `ApplicationFence.SUPERSEDED` 收敛为 3 个解释版本、3 个草稿（前 2 个 REJECTED + case DISMISSED），无重复 OPEN 事项。
- **图文/图片证据验收仍待用户动作**：请在业务群转发一条**图文（含订单图片）**并 `@` 机器人，确认群内仅收到一次「已接收」。完成后我验证 `message_media` 落库（AVAIABLE、媒体受控存储）与复核面板原图 `media_refs` 可见，并可用真实数据完成一次订单/运单确认的 HTTP 证据。
- 未证明项将如实标记，不会以本地替身宣称生产可用。

## 7. 本轮新增交付（验收中发现并补齐的缺口）

1. `ChannelMessageDetailDto` 增加 `media_refs` 白名单投影（`ChannelMediaEvidenceDto`：id/media_type/content_type/size_bytes；不含 content_ref/decrypt_info/URL），`ChannelMessageQueryService.availableMedia` 查询 AVAILABLE 媒体。
2. 前端复核面板渲染原图（antd Image + PreviewGroup，`/api/v1/message-media/{id}/content` 受权接口）。
3. 前端运单批量确认 UI（`trackingDraftReviewApi.listBySubmission/batchConfirm` + `TrackingDraftReviewPanel` 批量区：同提交 OPEN 草稿列表、仅勾选已通过校验行、逐行独立事务结果展示）。
4. 新增后端测试 `channelMessageDetailExposesWhitelistedMediaRefs`（5/5 通过）。

## 8. 边界

- 未 commit、未推送、未部署（checkbox 8 语义）。
- 未触碰 wecom 之外文件域；`saasTheme.ts`、03/04/05 视觉基线不受影响。
- 遗留（记录于 10 票与前端）：`/api/v1/message-media/{id}/content` 原图受权接口既有测试在 `MessageMediaContentApiTest`；真实消息流入后建议补一条真实原图复核的浏览器证据（可选）。
