---
label: wayfinder:issue
title: 履约导出发送与周期性回传提醒
status: open
blocked_by: []
parent: wayfinder:map:wecom-fulfillment-send
---

# 01 履约导出发送与周期性回传提醒

## Goal

1. 通过企微主动把已生成的第三方履约导出（`发货清单` 24 列 XLSX）发送给第三方，触发对方发货；
2. 第三方按现有 `SUPPLIER_TRACKING` 链路回传物流单号（文字路径已通）；
3. 以**企微发送时刻**为计时起点，按履约方 `tracking_sla_minutes` 到期未全部回传时，**周期性**向第三方业务群发送提醒消息，直到全部回传或人工确认；提醒间隔可配置。

## 已确认决策（用户拍板）

| # | 决策 | 结论 |
|---|---|---|
| D1 | 计时起点 | **企微发送时刻**（新增发送记录 `sent_at`），不是导出生成时刻 `generated_at` |
| D2 | 提醒对象 | **第三方业务群**（与发送履约导出同一企微会话） |
| D3 | 提醒频率 | **周期性重复**：每 N 小时提醒一次，直到全部回传或人工确认；N 可配置（建议 `app.wecom.tracking-reminder.repeat-hours`，默认等于 SLA） |
| D4 | 超时参数 | **复用 `fulfillment_providers.tracking_sla_minutes`**（按履约方配置，生成时快照语义不变，改配置不追溯已生成导出） |

## 协议事实（已核对官方 @wecom/aibot-node-sdk 源码）

- 主动发送：`aibot_send_msg`，body = `{chatid, msgtype, ...}`；单聊 chatid=userid，群聊 chatid=chatid；不依赖回调帧。
- 上传：`aibot_upload_media_init`（type/filename/total_size/total_chunks/md5）→ `aibot_upload_media_chunk`（upload_id/chunk_index/base64_data，512KB/片）→ `aibot_upload_media_finish`（返回 media_id）；上限 ≈50MB。
- 发送文件：`aibot_send_msg` msgtype=file，body.file.media_id。
- 帧格式与现有 `WecomLongConnectionClient` 一致（cmd/headers.req_id/body），无需新依赖。
- 企微侧 Java 无官方 SDK；现有自研 WS 客户端即本 effort 的"SDK"。

## 实施拆解

### 1. 传输层新增能力（`WecomLongConnectionClient` 或薄封装）

- `sendCommand(cmd, body)`：现有 `sendFrame` 已通用，补 ack 等待/超时即可；发送类命令走同一连接、同一 req_id 关联。
- 三步上传：`aibot_upload_media_init` → 分片（base64，512KB，并发≤2 避免企微 system error）→ finish 拿 media_id。失败重试（分片 2 次）。
- 注意：现有 `sendRaw` 要求 SUBSCRIBED 状态且同步 get 3s 超时——主动发送与上传属于业务线程，需与心跳/接收线程隔离好发送顺序（复用同一 WebSocket 发送是线程安全的，JDK WebSocket.sendText 排队）。

### 2. Channel 门面（`WecomChannel` 接口，业务层只见业务方法）

```java
interface WecomChannel {
    boolean sendText(String chatId, String content);       // aibot_send_msg markdown/text
    String uploadFile(byte[] bytes, String filename);      // 三步上传 → media_id
    boolean sendFile(String chatId, String mediaId, String filename);
}
```

- 业务方法（`send_fulfillment_export()`、`send_tracking_reminder()`）在此层之上，不暴露协议。

### 3. 履约方 → 企微会话映射（新增配置，必需）

- 发送目标和提醒目标都要求知道"第三方在哪个企微群"：新增 `fulfillment_providers` 扩展列或 `app.wecom.provider-chats` 配置（provider_code → chatid）。
- 缺映射时发送/提醒失败只告警落审计，不静默。

### 4. 发送记录（D1 落地）

- 新增发送记录（建议 `wecom_channel_sends`：business_ref=`fulfillment_export:{id}`、chatid、msgtype、media_id、filename、sent_at、status、req_id），发送成功写 `sent_at`。
- 重发语义：同一导出可重发（新记录）；提醒是消息不是重发文件。

### 5. 提醒任务（复用 async_tasks 延迟任务设施）

- 发送成功后在 `async_tasks` 落一条延迟任务：`task_type=TRACKING_REMINDER`、`payload_ref=export:{id}`、`next_run_at=sent_at + tracking_sla_minutes`、稳定 `idempotency_key`（`export:{id}:reminder`）。
- Worker 到期执行：
  1. 检查该导出是否已全部回传：`fulfillment_export_items` 关联的订单行是否全部离开 `WAITING_PROVIDER`（或相关 Shipment 均已取得 Tracking）；已齐 → 任务 SUCCEEDED 结束。
  2. 未齐 → 幂等创建 `operational_alerts(type=TRACKING_OVERDUE, severity=RED)`（Q43 设计）+ 企微发提醒消息到映射会话 + 重排下一轮 `next_run_at = now + repeat-hours`（D3）。
  3. 人工确认（acknowledge 或复核解决）后任务不再重排。
- 不改变任何订单状态/ShippingProgress（Q43：提醒不是订单推进机制）。

### 6. 提醒文案（建议，可调）

```
【回传提醒】履约导出 {batchNo} 已于 {sentAt} 发送，SLA 截止 {dueAt}，
当前仍有 {n} 个发货任务未回传物流单号。请尽快按回传列返回。
```

## 待确认/待实测

- [ ] 触发方式：履约导出生成后**自动**发送，还是管理后台人工点「发送」？（默认建议：批次确认生成后自动发送 + 后台可重发）
- [ ] 履约方 → 企微会话映射的存放位置（provider 扩展列 vs 配置项）。
- [ ] 群聊能否收到 file 消息（决定第三方回传是否可走文件路径；文字路径不受影响）。
- [ ] 提醒消息发送失败的重试策略（建议沿用回执的"重试 1 次只告警"）。

## 验收建议

- 本地：协议层单测（帧构造/上传分片/ack 解析）+ 发送记录落库 + 提醒任务到期触发/幂等/重排（用测试替身替换 WS 发送）。
- 真实企微验收（门槛，缺配置不声称通过）：向真实第三方业务群发送履约导出文件 → 第三方回传文字物流单号 → 正常路径不提醒；另造一条不回传的导出，SLA 调短到分钟级，验证到期收到提醒且后续周期重复，回传后停止。
