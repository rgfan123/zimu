# 履约导出企微发送与周期回传提醒（Issue #84）

> 本地 Resolution：按仓库约定（docs/agents/issue-tracker.md），不改动 GitHub Issue 正文，
> 交付说明记录在本文件。本文件是 #84 的事实源：状态机、交付证据表、时序、群变更策略、
> 失败与未知语义、人工重发/停止契约，以及消费的上传（#82）/路由（#83）seam。

## 0. 一句话

第三方履约导出生成后自动上传并发送 `aibot_send_msg` 文件消息到该履约方登记的企微群，
以**文件消息成功 ack 时刻**为计时起点；到 SLA 未收齐运单回传时按可配置间隔周期提醒，
直到全部收齐或人工停止；发送失败安全重试 1 次（总尝试 2 次）后 RED 告警，不静默。

## 1. 业务边界

- **只自动推送 `export_kind='THIRD_PARTY'` 的履约导出**（普通批次与续发导出）；
  `JD_WAREHOUSE` 不发第三方群、不建出站状态。
- **只对本变更上线后新生成并显式登记出站状态的导出生效**。V46 迁移把历史第三方导出
  登记为 `LEGACY` 终态：不自动入队、不提醒、不支持重发/停止（停止对 LEGACY 同样无意义，
  返回明确错误）。部署后绝无批量补发历史文件的路径。
- **计时起点 = 文件消息 `aibot_send_msg` 的成功 ack 时刻**（`WecomSendResult.SUCCESS`
  的 `acknowledgedAt`），不是文件生成、上传完成或任务领取时刻。`fulfillment_exports
  .tracking_due_at`（旧列，generated_at 派生）对新的第三方导出不再作为公开权威：API/UI
  在未发送时返回 `tracking_due_at=null`，绝不展示假的「已到期时间」；历史/JD 行保持旧语义。
- **快照语义**：`tracking_sla_minutes` 与提醒间隔 `reminder_interval_minutes` 都在导出
  生成时从履约方配置快照；后续改配置不追溯既有导出。
- **自动发送 + 显式人工重发**；安全失败自动重试 **1 次**（总尝试最多 2 次）后告警。
- 到期未收齐周期提醒；收齐或人工停止后不再提醒；重启后从持久化状态恢复，不重复轰炸也不漏。

## 2. 深模块划分

| 模块 | 职责 | 不许做的事 |
|---|---|---|
| `FulfillmentExportWecomStore`（repo） | 唯一读写 `app.fulfillment_export_wecom_states` / `..._deliveries` 两个新表的类；CAS、终态转移、到期扫描、完成判定 SQL | 不调用外部 seam、不拼消息、不写告警 |
| `FulfillmentExportWecomService`（应用层） | `scheduleInitial`（生成事务内）、`resend`、`stop`、`markTrackingReceived`、`scanDueReminders`、`summary`；短事务编排与审计 | 不直接拼协议 JSON；不持有外部调用期间的长事务 |
| `FulfillmentExportWecomRunner` | 单个 delivery 的执行状态机：resolve chat → upload → send → finalize；重试/UNKNOWN/崩溃恢复决策 | 不直接碰表结构（经 Store）；不盲重发 |
| `FulfillmentExportWecomWorker` | `@Scheduled` 轮询领取 `WECOM_EXPORT_DELIVERY` 任务并交给 Runner | 业务规则不在此层 |
| `WecomTrackingReminderScanner` | `@Scheduled` 扫描到期 ACTIVE 导出，原子创建唯一 reminder delivery + task | 不直接发送 |
| `WecomOutboundGateway`（#82/#84 扩展） | `file(chatId, mediaId)` 深模块；审计安全投影 | media_id 不进普通日志/审计明文 |
| `FulfillmentProviderWecomConfig`（#83 扩展） | config 键 `wecomReminderIntervalMinutes` 的唯一解析；`wecomGroupChatId` 沿用 | 规则不散落 |
| `OperationalAlertService`（最小扩展） | `createSystem` + `resolveWecomExportAlerts`（按 shipment + detail.export_id 隔离，同一导出交付终态告警去重/关闭） | 不引入新表 |

消费的既有 seam：`WecomGroupChatResolver.resolve(providerId)`（#83）、
`WecomOutboundGateway.upload(path, filename, FILE)`（#82）、
`ContentAddressedFileStore.openRead(fileRef)`（#84 新增受控 Path 解析）、
`AsyncTaskStore`（`WECOM_EXPORT_DELIVERY` 任务，delivery 外部尝试 maxAttempts=2、async task
总领取上限 3——第 3 次领取只做告警收口）。

## 3. 持久化状态（V46）

### 3.1 `app.fulfillment_export_wecom_states`（导出唯一状态行）

| 列 | 语义 |
|---|---|
| `export_id` PK→fulfillment_exports | 每导出唯一一行 |
| `provider_id` | 路由证据（生成时履约方快照） |
| `status` | `PENDING`（initial 已入队未发送）→ `ACTIVE`（initial 已 ack）→ `COMPLETED` / `MANUALLY_STOPPED`；失败终态 `FAILED`（确定性失败，可安全重试但已耗尽）与 `UNKNOWN`（结局未知，必须人工对账）；`LEGACY`（迁移历史行，不可入队） |
| `chat_id` | **快照**：initial 实际解析并发送成功的群；提醒永远发到该快照群，不重新解析（见 §6 群变更策略） |
| `tracking_sla_minutes` / `reminder_interval_minutes` | 生成时快照，>0 |
| `initial_sent_at` / `tracking_due_at` / `next_reminder_at` / `last_reminded_at` / `reminder_count` | 提醒时间线；`tracking_due_at = initial_sent_at + sla`；`next_reminder_at` 初始同 due，每次提醒 ack 后 = ack + interval；失败暂停/停止/收齐时置 NULL（NULL=不再自动提醒） |
| `last_error` | 稳定错误码/可操作消息（无 media_id/config/secret） |
| `stopped_by` / `stopped_reason` / `stopped_at` | 人工停止证据（审计可追溯） |
| `lock_version` + 时间戳 | 版本并发 |

CHECK 不变式：`ACTIVE` ⇒ `initial_sent_at`/`tracking_due_at`/`chat_id` 非空；
`COMPLETED` ⇒ `next_reminder_at IS NULL`；`MANUALLY_STOPPED` ⇒ 停止证据完整；
`LEGACY` ⇒ `initial_sent_at IS NULL`。索引：`(status, next_reminder_at, export_id)
WHERE status='ACTIVE'` 支撑到期扫描。

### 3.2 `app.fulfillment_export_wecom_deliveries`（每次 delivery 的证据）

| 列 | 语义 |
|---|---|
| `id` / `export_id` FK→states | 证据行 |
| `kind` | `INITIAL`（自动发送/人工重发都是 INITIAL 的新 sequence）或 `REMINDER` |
| `sequence` | kind 内单调递增（INITIAL 从 1；REMINDER 从 1）；**UNIQUE (export_id, kind, sequence) 是「同一 initial 或同一 reminder sequence 不重复入队/并发发送」的数据库级保证** |
| `status` | `PENDING` →（CAS）`SENDING` → `SENT` / `FAILED` / `UNKNOWN`；worker 崩溃落在 SENDING，重启转 UNKNOWN 告警，不盲重发 |
| `attempts` / `max_attempts=2` | delivery 的外部尝试计数与上限（与 async task 的 claim 计数同步；task 总领取上限 3，第 3 次只做告警收口） |
| `stage` | `SCHEDULED`/`RESOLVE_CHAT`/`UPLOAD`/`SEND`/`FINALIZED`（稳定安全元数据） |
| `chat_id` / `request_id` / `ack_sent_at` | 路由证据 + 服务端 ack 请求 id + ack 接收时刻（= 计时起点） |
| `media_id_sha256` | media_id 的 SHA-256 摘要（可追溯、不存 3 天临时引用明文） |
| `error_code` / `error_message` | 稳定错误；**不持久化 media_id 明文或文件内容** |

### 3.3 迁移安全默认

V46：`ALTER TABLE fulfillment_exports ALTER COLUMN tracking_due_at DROP NOT NULL`（新第三方
导出发送前为 NULL）；建两张表与索引；`INSERT ... SELECT ... WHERE export_kind='THIRD_PARTY'
ON CONFLICT DO NOTHING` 把历史导出登记为 `LEGACY`（sla/interval 取履约方当前值做快照）。
**迁移不创建任何 async_tasks、不发任何消息。**

V47（#84 第二轮）：企微导出告警隔离——`uq_operational_alert_active_subject` 对
`FULFILLMENT_EXPORT_WECOM` 类型不再生效，新增 `uq_operational_alert_active_wecom_export`
按 `(alert_type, shipment_id, detail->>'export_id')` 唯一（同一导出同一 delivery 多次
ensure 只一条活动告警，续发导出共享 shipment 也互不影响）；非企微导出告警语义不变。

`docs/schema.sql`（空库快照）同步追加，由 `SchemaSnapshotMigrationEquivalenceTest` 保证与
Flyway 全链等价。

## 4. 时序：initial 发送（生成事务 + Worker）

```
[生成事务] generateThirdParty / generateContinuation
  INSERT fulfillment_exports（tracking_due_at = NULL）
  INSERT state (PENDING, sla/interval 快照)
  INSERT delivery (INITIAL, seq=1, PENDING)
  enqueue async_tasks WECOM_EXPORT_DELIVERY, key=wecom-export-initial:{exportId}, max=2
    （同一事务提交；JD 路径一律不执行以上三步）

[Worker 领取] claim(WECOM_EXPORT_DELIVERY) → payload_ref=export:{id}:INITIAL:{seq}
  ── 短事务：读 delivery + state
     · delivery 终态（SENT/FAILED/UNKNOWN）→ 幂等 no-op，succeed
     · delivery=SENDING（崩溃遗留）→ 转 UNKNOWN + RED 告警 + succeed（绝不重发）
     · delivery=PENDING → CAS PENDING→SENDING（attempts=task.attempts，stage=RESOLVE_CHAT）
        且 state.status IN ('PENDING','ACTIVE','UNKNOWN','FAILED')（停止/收齐后 no-op）
  ── 外部（无事务）：resolve chat（每次实时解析；未登记 → 可操作错误，安全重试路径）
  ── 外部（无事务）：upload(fileStore.openRead(file_ref), batchNo+".xlsx", FILE)
  ── 外部（无事务）：gateway.send(WecomOutboundMessage.file(chatId, mediaId))
  ── 短事务 finalize（按结局）：
     · SUCCESS → delivery SENT(ack, req_id, chat_id, media_id_sha256, stage=FINALIZED)
                  state ACTIVE：initial_sent_at=ack, tracking_due_at=ack+sla,
                  next_reminder_at=同 due, chat_id=快照, last_error=NULL
                  （仅当 state 未被人工停止/收齐，且该 delivery 是 latest INITIAL）
     · 可安全重试失败（CONNECTION_NOT_READY/BACKPRESSURE/上传 retryable/群未登记）
         → attempts < max：delivery 回 PENDING（记 error）→ taskStore.fail(backoff)
         → attempts >= max（总尝试 2 次）：delivery FAILED + state FAILED + RED 告警 + succeed
     · 结局未知（send TIMEOUT/LOST/非 retryable FAILED、upload UNKNOWN/非 retryable）
         → delivery UNKNOWN + state UNKNOWN + RED 告警 + succeed（人工对账，绝不盲重发）
```

「外部已发成功但本地未落库」由 SENDING 崩溃恢复与 UNKNOWN 分支共同兜底：发送提交后的任何
不确定结局都不重发；重试只发生在帧未提交的确定性安全窗口（#81 未知态纪律不因 #84 破坏）。

## 5. 周期提醒（扫描器 + Worker，无内存延迟链）

```
[Scanner 每 N 秒] 短事务：
  SELECT ... FROM states WHERE status='ACTIVE' AND next_reminder_at<=now
    AND NOT EXISTS (进行中的 REMINDER PENDING/SENDING)   -- 第一条 SENT 前绝不生成 sequence2
  ORDER BY next_reminder_at LIMIT 20 FOR UPDATE SKIP LOCKED
  对每个候选：sequence = MAX(REMINDER.sequence)+1
    INSERT delivery (REMINDER, sequence) ON CONFLICT (export_id, kind, sequence) DO NOTHING
    插入成功才 enqueue key=wecom-export-reminder:{exportId}:{sequence}
  （多实例并发/重复轮询：唯一键 + NOT EXISTS + 幂等键三重保险，只创建一个 sequence）

[Worker 执行 REMINDER] 单短事务线性化准备（prepareReminder）：
  · 锁 state 行 FOR UPDATE，同事务复查 ACTIVE + next_reminder_at<=now + 全量收齐判定
  · 已收齐 → 同事务标 COMPLETED + 清 next_reminder → 任务幂等 no-op
  · 全部通过才 CAS delivery PENDING→SENDING（与 tracking import 的 markTrackingReceived
    在同一 state 行锁上互斥：import 先提交则本 CAS 必失败绝不发送；本 prepare 先提交则
    发送决策线性化在收齐之前，合法）
  ── 外部（事务外）：send(markdown) 到 **state.chat_id 快照群**（不重新解析、不悄悄换群）
  ── 短事务 finalize：
     · SUCCESS → delivery SENT(ack)；state last_reminded_at=ack, reminder_count+1,
                  next_reminder_at=ack+interval_snapshot（仅当仍 ACTIVE）
     · 可安全重试（CONNECTION_NOT_READY/BACKPRESSURE）→ 同 initial 的 2 次预算
     · 终态失败/UNKNOWN → delivery FAILED/UNKNOWN + RED 告警 + next_reminder_at=NULL
       （暂停自动提醒，避免重复轰炸）；人工重发成功后可自动 resolve 该导出告警
```

提醒内容（markdown，经 `WecomTrackingReminderMessage` 构建）：导出批次号、履约方名称、
已等待时长、未回传 shipment 数（= 该导出 item 的 distinct shipment 中无已收齐 tracking
的数量）与可操作指引；不含 media_id/config/secret。

## 6. 群变更策略（明确 + 测试）

- **initial 发送前**：每次实际尝试都实时解析（#83 resolver），登记/修改/清除即时生效。
- **initial 发送成功后**：`chat_id` 快照到 state；**提醒与人工重发永远使用快照群**，
  不重新解析——同一导出的提醒不会悄悄换群；履约方改群只影响之后新生成的导出。
- 群被清除/未登记时 initial 报「请在履约方配置登记企微群」可操作错误并安全重试一次。

## 7. 收齐判定（真实 tracking 关系）

```
completed = NOT EXISTS (
  SELECT 1 FROM fulfillment_export_items fei
  WHERE fei.fulfillment_export_id=:exportId
    AND NOT EXISTS (
      SELECT 1 FROM trackings t
      JOIN import_batches ib ON ib.id=t.provider_tracking_batch_id AND ib.status='COMPLETED'
      WHERE t.shipment_id=fei.shipment_id
        AND t.tracking_number IS NOT NULL AND btrim(t.tracking_number) <> ''))
```

- 任一 export_item 的 shipment 无已收齐 tracking 即未完成（部分发货的续发批次各自独立判定）。
- **导入成功后主动标 COMPLETED**（TrackingFileService 接收事务内 UPDATE，无外部调用）；
  **scanner/Worker 发送前复查**保证「已收齐后不再催」，import 与 scanner 并发也不会多发。
- COMPLETED 由 PENDING/ACTIVE/FAILED/UNKNOWN 进入（收齐是事实，终态一律不再提醒/不再发送）；
  MANUALLY_STOPPED 保持人工选择，不自动覆盖；LEGACY 不动。

## 8. 人工停止与重发（REST 写端点）

- `POST /api/v1/fulfillment-exports/{export_id}/wecom-stop`
  请求：`{expected_version, reason}`；头：Idempotency-Key / X-Operator / X-Request-Id（认证沿用
  既有写接口 Basic Auth + X-Operator 复核）。语义：版本冲突 409；已 COMPLETED/STOPPED 再停
  幂等 no-op 返回现状；PENDING（initial 未发送）也允许停止（行为明确：pending 任务下次领取时
  no-op，不发送）；停止后持久化 operator/reason/time、清 next_reminder、不删除历史 delivery。
- `POST /api/v1/fulfillment-exports/{export_id}/wecom-resend`
  请求：`{expected_version, reason?}`（expected_version 必填，缺失 400 VALIDATION_ERROR）。
  语义：只生成**新的 INITIAL delivery（sequence=max+1）+ 新 async task**（key=
  `wecom-export-initial:{exportId}:{deliveryId}`），HTTP 线程不直接发送；对 tracking 已收齐
  （COMPLETED）或 LEGACY 拒绝（422 明确错误）；version 冲突 409；**存在进行中的 INITIAL
  （PENDING/SENDING，含旧自动发送未完成）→ 409 WECOM_RESEND_IN_FLIGHT**（重发先锁 state 行，
  并发重发串行化，只允许一个成功）；成功后 sent_at/due/reminder schedule 以新 ack 重置；
  旧任务与新任务各行其道（delivery 行隔离 + finalize 只认 latest INITIAL），不并发重复。
- 审计：`wecom_export.stop` / `wecom_export.resend`（operator/request/trace + 结果投影）。

## 9. 告警

- 终态 FAILED / UNKNOWN（initial 或 reminder）创建 `RED` `operational_alerts`
  （`alert_type='FULFILLMENT_EXPORT_WECOM'`），subject 用该导出第一个真实 shipment（业务主体，
  另附 fulfillment 便于 UI 关联），detail：`export_id/delivery_id/batch_no/provider_code/stage/
  stable_error/error/attempts/kind`（无 media_id/config/secret）。
- **不跨导出误关**：告警创建与关闭都按 `(alert_type, shipment_id, detail.export_id)` 隔离
  （V47 唯一索引 `uq_operational_alert_active_wecom_export` 兜底并发）——续发导出可与原导出
  共享 fulfillment（shipment 各自新开），各自持有一条 OPEN 告警，互不影响；同一导出同一
  delivery 多次 ensure 只一条活动告警。
- 告警收口持久可恢复：`createSystem` 失败 → 任务退避（`WECOM_ALERT_CREATE_FAILED`），第 3 次
  领取重进终态 delivery 幂等 ensure；持续失败 → 任务 FAILED 可见（可人工补建），绝不静默。
- 人工重发成功（新 initial ack）后自动 RESOLVE **仅该导出**（shipment + detail.export_id）的
  OPEN 告警并在 detail 留关闭证据（`auto_resolved_reason=wecom_initial_ack`）。

## 10. 配置契约（#83 扩展）

`fulfillment_providers.config` 新增键 `wecomReminderIntervalMinutes`（1..10080 整数；
null/缺失 = 默认等于 `tracking_sla_minutes`）。解析唯一归属
`FulfillmentProviderWecomConfig`；PATCH 合并写入保留其他 config 键、版本并发与审计沿用；
Provider 投影新增 `wecom_reminder_interval_minutes`（number|null）。前端配置页同规则校验。

## 10.5 Worker 租约（#82 上传上界）

`app.wecom-export-worker.lease-seconds` 默认 **1800 秒（30 分钟）且为代码下限**（不可调小，
只能调大）：#82 同步分片上传最坏时长有界可达十余分钟，租约必须覆盖该上界——真实执行仍活跃
时任务绝不被第二实例重新领取误判为 crash。SENDING 只在租约确实过期后的重新领取时转 UNKNOWN
人工对账（崩溃恢复前提，见 §4）。

## 11. 测试门禁

- `WecomOutboundGatewayTest` 扩展：文件帧精确 shape（`msgtype=file` + `file.media_id`）、
  text/markdown 帧不退化、ACK sent_at = 服务端 ack 接收时刻、审计只存 media_id 摘要。
- `FulfillmentExportWecomPipelineApiTest`：生成建状态+task（普通/续发）、JD/历史不入队、
  群未配置不上传、上传→发送成功才落 sent_at/due、upload UNKNOWN / send TIMEOUT / 崩溃
  SENDING 恢复不盲重发且告警、pre-submit 失败总尝试 2 次后告警、成功重试无重复 delivery。
- `FulfillmentExportWecomReminderScannerTest`：多实例/重复轮询只创建一个 sequence、
  重启可恢复、ack 后按 snapshot interval 再排、收齐与停止阻止提醒、失败暂停不轰炸。
- `FulfillmentExportWecomResendStopApiTest`：认证/幂等/版本/审计、expected_version 必填
  （400）、null body 422、reason 长度、in-flight 阻止重发（409）、并发重发唯一成功、
  COMPLETED 拒绝重发、停止幂等、重发重置时间线。
- `FulfillmentExportWecomAlertScopingTest`：续发导出共享 fulfillment/shipment 时各自独立告警、
  重发 ack 只关闭同导出告警、告警创建失败退避并在第 3 次领取收口、持续失败任务 FAILED 可见。
- `FulfillmentExportWecomReminderScannerTest` 并发用例：reminder prepare 与 tracking import
  在同一 state 行锁上互斥（latch/两事务确定性 seam），import 先提交绝不发送、
  prepare 先提交发送证据保留。
- 前端：`SalesOutboundPage` 按钮状态/确认弹窗/生产路由载荷；`FulfillmentProvidersPage`
  提醒间隔输入与 config 键共存。
- `SchemaSnapshotMigrationEquivalenceTest`：V46 与 docs/schema.sql 等价。
