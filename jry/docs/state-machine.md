# 订单履约状态机：业务 Excel 闭环与隔离 Demo

> 初始决策来自「订单状态机精化」；数据库 Schema 设计 Q7、Q25–Q50 对履约、部分发货、Excel 回填和 Demo 隔离作了后续细化。本文件已同步到当前权威模型。

## 1. 原则

1. Order、OrderLine、Fulfillment、Shipment、ProcurementTicket 和 Sync 各自保存所属事实，不压成一个巨型状态。
2. `order_lines.processing_stage` 是 P0 Excel 闭环当前位置的唯一可写权威值；Order 只展示聚合摘要。
3. `fulfillments.shipping_progress` 只表达实际发了多少；`fulfillments.outcome` 只表达最终全部/部分/取消结论。
4. 业务 CanonicalOrder 按真实异步回执推进，不使用 Mock 捷径。只有 `data_scope=DEMO` 的 DemoScenario 可同步跑完演示 Timeline。
5. Timeline 使用只追加 OrderEvent；每次业务变化另追加 OrderVersion 完整快照和 AuditLog。
6. 关键变化必须在一个数据库事务中写业务事实、状态、事件、版本和审计。

## 2. 状态维度

### 2.1 OrderStatus

主线：

`RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED`

异常/等待分支：

`NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED`

OrderStatus 是订单生命周期结论，不替代行级 ProcessingStage。多行订单的操作界面读取 `v_order_progress_summary`，不按 OrderStatus 猜测具体哪行在等待。

### 2.2 ProcessingStage（OrderLine）

主路径：

```text
NEED_REVIEW
  → READY_TO_EXPORT
  → WAITING_PROVIDER
  → TRACKING_RECEIVED
  → RETURN_FILE_READY
  → COMPLETED
```

特殊分支：

- `PROCUREMENT_IN_PROGRESS`：我方库存缺口已创建采购工单；已有部分 Shipment 仍保留真实发货进度。
- `EXCEPTION`：明确失败或无法自动继续，必须进入 ReviewCase。

每条 OrderLine 独立推进。有货行不等待缺货行；订单列表显示最慢未完成阶段和 `completed_count/total_count`。

### 2.3 ShippingProgress（Fulfillment）

按 ShipmentItem 累计实发量计算：

- `NOT_SHIPPED`：累计实发为 0；
- `PARTIALLY_SHIPPED`：累计实发大于 0 且小于请求量；
- `SHIPPED`：累计实发等于请求量。

累计实发不得超过请求量。采购成功后继续由原 FulfillmentProvider 发货，provider 不改变。

### 2.4 FulfillmentOutcome

- `IN_PROGRESS`：尚未形成数量终局；
- `FULLY_FULFILLED`：累计实发等于请求量；
- `PARTIALLY_FULFILLED`：已有实发且其余数量已明确取消；
- `CANCELLED`：未发货且请求量全部取消。

最终数量必须满足：

`requested_quantity = cumulative_shipped_quantity + cancelled_quantity`

### 2.5 ShipmentStatus

P0：

```text
CREATED → SHIPPED
       ↘ FAILED
```

未来物流回调：`SHIPPED → DELIVERED`。

当前未接入京东 SDK 回调，P0 完成不等待签收或 DELIVERED。

### 2.6 SyncStatus

`PENDING → SYNCED`；失败分支：`SYNC_FAILED → SYNCED`（人工/系统重试）。

### 2.7 ProcurementStatus

`PENDING → SUCCESS / PARTIAL / FAILED`；人工取消未完成工单时进入 `CANCELLED`。

一张 ProcurementTicket 可接收多次不可变回执。普通 SKU 有一条工单明细；CustomBundle 按缺货组件建立明细。

## 3. 业务 Excel 主线

| 步骤 | 权威事实 | ProcessingStage | 典型事件 |
|---|---|---|---|
| 来源 Excel 接收并逐行解析 | ImportBatch / RawImportRow / CanonicalOrder | NEED_REVIEW 或 READY_TO_EXPORT | ORDER_RECEIVED |
| 客户与 SKU 显式映射确认 | Customer / SourceChannelSku | READY_TO_EXPORT | CUSTOMER_MATCH_CONFIRMED / SKU_MATCH_CONFIRMED |
| 按 provider 与收货地址生成出库批次 | Shipment(CREATED) / FulfillmentExport | WAITING_PROVIDER | SHIPMENT_CREATED / FULFILLMENT_EXPORT_GENERATED |
| 履约方整批返回结果 | ShipmentItem 实发量 / Tracking / 异常 | TRACKING_RECEIVED、PROCUREMENT_IN_PROGRESS 或 EXCEPTION | TRACKING_RECEIVED / MANUAL_INTERVENTION_REQUIRED |
| 生成阶段性或最终来源回填文件 | SourceReturnExport / Items | RETURN_FILE_READY | — |
| 最终来源回填 Excel 就绪；或多 Shipment 人工完成来源平台后续回传并关闭 ReviewCase | ShipmentSync / 最终文件版本；或人工处理记录 | COMPLETED | SOURCE_SYNCED / MANUAL_SOURCE_FOLLOWUP_COMPLETED |

`COMPLETED` 表示运单与最终回填文件闭环，不表示客户已经签收。

## 4. 默认合箱与部分发货

默认按以下范围生成一个 Shipment：

`同一 CanonicalOrder + 同一 FulfillmentProvider + 同一 Receiver 地址 + 同一发货批次`

该 Shipment 内可有多个 ShipmentItem，因此普通多商品或礼包组件可以共享一个出库单号和运单号。不同 provider 必须拆开。

我方库存请求 100、可用 80 时：

1. 第一批 Shipment/出库单发 80；
2. 自动为缺口 20 创建 ProcurementTicket，并生成黄色提醒；
3. 采购回执取得可用量后，重新检查当批库存；
4. 第二批使用新的 Shipment、出库单号和运单；
5. 所有真实 Shipment 均有 Tracking 且数量达到终局后，才允许最终来源回填。

系统不预占库存、不锁单。每个批次是独立原子操作，下一批必须重新判断我方库存。

## 5. CustomBundle

CustomBundle 只是若干普通商品按清单完整配齐并同盒发货，不涉及加工或额外组装。

- 请求量、ShipmentItem 实发量和 Fulfillment 进度使用“完整礼包份数”；
- 我方库存可发份数为所有组件可组成的最小完整份数；
- FulfillmentExport 再按礼包份数×组件单份用量展开组件行；
- 散件、缺件或拆开的组件不得计作已发礼包；
- 第三方库存不由系统预判，但第三方回传仍必须以完整礼包份数表达实际结果。

## 6. 第三方结果

第三方库存不归本系统管理。系统按待履约请求生成第三方 Excel，只接收实发量、物流公司、运单号和异常。

- 第三方 SHIPPED：创建/完成 Shipment 与 Tracking；
- 第三方 PARTIAL：保存真实实发量和剩余量，进入人工复核；
- 第三方 FAILED：保存失败结果，进入人工复核；
- PARTIAL/FAILED 不创建我方采购工单，不修改我方库存。

同一 ProviderTrackingBatch 可以混合 SHIPPED/PARTIAL/FAILED。整批先校验模板、provider、关联和数量；结构合法后全部结果在一个事务中提交。合法的业务失败不是文件校验失败。

## 7. 来源回填

- 京东和每个第三方独立返回，不混文件；
- 某 provider 的一批结果接收完成后可生成一版阶段性来源回填；
- 未取得运单的原始行保持空白，不写占位符；
- 首个 Shipment 不能覆盖来源行全部请求量或后来出现第二个 Shipment 时，只自动回填该 OrderLine/Fulfillment 关联的最早 Shipment；禁止复制来源行、拼接或覆盖运单，并创建 `MULTI_SHIPMENT_SOURCE_FOLLOWUP` ReviewCase；
- 采购仍进行时保持 `PROCUREMENT_IN_PROGRESS`，ReviewCase 单独表达人工责任；全部真实 Shipment 有 Tracking 且履约终局后转 NEED_REVIEW，人工完成来源平台后续处理后写 `MANUAL_SOURCE_FOLLOWUP_COMPLETED` 并进入 COMPLETED；
- 最终少发只有在来源格式能明确表达未发量/取消原因时自动生成，否则创建 `SOURCE_FORMAT_CANNOT_EXPRESS_PARTIAL` ReviewCase。

## 8. OrderEvent

事件类型由 `order_event_types` 目录表管理，初始包含：

`ORDER_RECEIVED`、`ORDER_UPDATED`、`SKU_MAPPED`、`JD_STOCK_CHECKED`、`JD_SKU_MAPPING_CHECKED`、`JD_OUTBOUND_SUBMITTED`、`JD_OUTBOUND_FAILED`、`JD_OUTBOUND_ACCEPTED`、`JD_SHIPPED`、`PROCUREMENT_REQUESTED`、`PROCUREMENT_RECEIPT_RECORDED`、`PROCUREMENT_COMPLETED`、`SHIPMENT_CREATED`、`TRACKING_RECEIVED`、`SOURCE_SYNCED`、`CUSTOMER_MATCH_CONFIRMED`、`SKU_MATCH_CONFIRMED`、`MANUAL_INTERVENTION_REQUIRED`、`FULFILLMENT_EXPORT_GENERATED`、`MANUAL_SOURCE_FOLLOWUP_COMPLETED`。

Timeline 按订单内 sequence/created_at 排序。事件可关联 OrderLine、Fulfillment、Shipment 或 ProcurementTicket，但关联对象必须属于同一 Order。

## 9. DemoScenario 隔离

- Demo 订单使用 `data_scope=DEMO`，只从 `/demo/v1/scenarios` 创建；
- 业务 CanonicalOrder 使用 `data_scope=BUSINESS`，按真实文件与回执推进；
- Demo 不进入业务查询、ReviewCase、OperationalAlert、履约/来源 Excel、analytics 或 Metabase；
- Demo 可以复用领域服务和事件代码，但不能调用真实履约方或证明 P0 Excel 闭环完成。

## 10. 处理健康度

- BLUE：系统内部自动处理中；
- YELLOW：等待履约方或人工动作，包括 WAITING_PROVIDER、NEED_REVIEW、PROCUREMENT_IN_PROGRESS；
- RED：明确异常、失败或超时；
- GREEN：全部 OrderLine COMPLETED。

颜色是查询/UI 投影，不是业务状态，不能被人工写入或作为状态转移条件。
