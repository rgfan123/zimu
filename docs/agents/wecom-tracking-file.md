# 企微单聊运单文件回传（Issue #86 Resolution）

## 1. 路径决策

#86 采用「第三方把原始 XLSX **单聊直发机器人**」的主路径。原因来自
[#85](https://github.com/rgfan123/zimu/issues/85) 的真实企微实测：当前业务群内发送 XLSX 后，
生产证据中没有成功持久化的有效 `group/file` 回调，因此群聊 file 在当前运营流程中
不可用。这是窄口径的环境结论，**不声明企微协议永久或普遍不支持群聊 file**。

鉴权后台的原有履约返回上传仍是人工兜底；不以群里发图片为主路径，因为图片会丢失
24 列表格的可机器校验结构。

## 2. 下载、解密与大小门禁

- 回调只保存原始 `file.url` / `file.aeskey` / `file.filename` 证据，不在回调线程下载；
- `WECOM_TRACKING_FILE` 专用租约任务即时下载短效 URL；只接受 HTTP 200，整个
  connect + headers + body 默认必须在 15 秒内完成；
- body subscriber 最多接收 **20 MiB** 密文；`Content-Length` 超限时在读 body 前拒绝，
  未声明或不可信长度仍由 subscriber 硬上限取消；
- 媒体解密复用企微长连接媒体规格：AES-256-CBC，IV 取 aeskey 前 16 字节，
  PKCS#7 按 32 字节块去填充；明文仍不得超过 20 MiB；
- URL、aeskey、原始异常和文件字节不进入模型、公开 DTO 或普通日志。

## 3. 严格 24 列只读解析

`TrackingFileService.parseForDraft` 是 #86 的只读 seam，**不调用**
`TrackingFileService.upload`，不创建 `PROVIDER_TRACKING` 导入批次，不写 Shipment、Tracking
或来源回填文件。它复用正式履约返回的确定性校验：

1. 必须是真实 XLSX，且只保留一个工作表；
2. 表头必须与第三方发货清单**精确 24 列、同名且同顺序**；
3. 首行「导出批次号」必须唯一定位已登记的 `THIRD_PARTY` 履约导出；
4. 行数必须与导出明细一致，前 18 个指令列不可改写；
5. `SHIPPED` / `PARTIAL` / `FAILED` 、数量、物流公司、运单号和异常原因按既有
   业务规则校验；同一 Shipment 的多明细只允许全部 `SHIPPED` 并共用同一运单。

## 4. 草稿、复核与正式事实

- 每个已验证 Shipment 生成一个 `ProviderTrackingDraft` 和一个
  `WECOM_TRACKING_DRAFT` ReviewCase；文件来源在 DTO 中明示为
  `source=WECOM_TRACKING_FILE`；
- `confirmation_scope=SINGLE_TASK` 表示单任务；`ATOMIC_SHIPMENT` 表示 DTO 中列出的
  所有明细都是必选范围，人工确认时一次写入整个 Shipment 且只生成一个 Tracking；
- 物流候选 `source=FILE` 表示回传文件明确给出，不得展示成运单前缀推断；
- 文件中的 `PARTIAL` 保留已校验实发数量，页面必须展示并由操作员明确确认；
  它不进入一键批量确认，只能在单条表单逐项核对；`FAILED` 不会被偷换为发货，
  只能保留复核或驳回；
- **只有授权人工确认**才调用 `ShipmentTrackingService`。企微路径产生的正式
  Tracking 不伪造导入批次，`provider_tracking_batch_id` 为空；它仍是已收齐运单证据，
  会在同一事务内停止 #84 周期提醒。

## 5. 幂等、留证与失败可见

- 企微 `(corp_id, connection_id, message_id)` 去重、`async_tasks.idempotency_key` 和
  `message_media(channel_message_id, channel_media_id)` 共同收敛重复回调与重试；
- 租约丢失或 reinterpret 代际通过 application fence 防止旧任务应用业务结果；
  已成功的 `MessageMedia=AVAILABLE` 是单调终态，并发的迟到失败不得降级它；
- 解密明文以 SHA-256 内容寻址长期留存，并通过 submission / channel message / media id
  追溯原始消息；不在复核 DTO 暴露下载凭据或本地路径；
- 下载、解密、大小、模板或处理失败最多重试三次；终态生成
  `WECOM_TRACKING_FILE_REVIEW`，只展示稳定错误码和服务端中文文案。失败事项编号包含
  task 代际：同一任务恢复幂等，reinterpret 后再失败仍会产生新 OPEN 事项。

## 6. 验收边界

本实现已用本地 HTTP 服务器、真实 AES-CBC/PKCS#7 密文、PostgreSQL/Testcontainers、
真实 XLSX 与公开 REST 确认链覆盖重复、超限、下载失败、24 列非法、PARTIAL、
原子多明细与确认后停止提醒。

**尚未在真实企微租户完成外部验收**：未用真实单聊 file 回调现场验证短效 URL、aeskey、
大文件下载速度与运营人员端到端确认。因此本地测试通过不等于真实企微已验收。
