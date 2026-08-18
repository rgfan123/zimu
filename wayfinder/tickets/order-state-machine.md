---
label: wayfinder:grilling
title: 订单状态机精化
status: closed
claimed_by: zed-main
blocked_by: []
parent: wayfinder:map
---

# 订单状态机精化

## Question

把 PRD §17 的五个状态维度（OrderStatus / FulfillmentStatus / ShipmentStatus / SyncStatus / ProcurementStatus）精化为**可实现的转移矩阵**：谁触发、什么条件、异常分支怎么走、谁有权人工干预。

## 决策点

- 每个维度的状态集 + 合法转移边 + 触发者（系统/人工）；
- 异常分支（NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED）的进入与退出条件；
- 「创建即跑完全程」模式下：哪条主线路径同步执行、各步骤产生哪些 OrderEvent（§18）、最终态是什么；
- 缺货路径：OUT_OF_STOCK → 采购工单创建 → 结果回填（SUCCESS/PARTIAL/FAILED）→ 各自转移；
- 人工干预点：NEED_REVIEW 谁能改成什么；
- 状态变更的持久化：状态变更是否走 OrderEvent（Timeline 即状态历史）。

## 产出

- `docs/state-machine.md`（五维转移矩阵 + 事件清单 + 主线/异常路径图）

## Blocked by

无（前沿票）。

## Resolution

### 决策日志（grilling 完成，2026-08-10，zed-main）

**Q1 持久化模型（已定）**：双轨 — `order_event` 语义事件流（§18 的 12 种事件 + payload + operator + created_at，Timeline 数据源）+ `order_version` 每次状态/数据变更追加完整快照（五维状态 + 订单头 + 行摘要 + 变更原因，支撑 Version Validation 与 §19 数据修改追责）；所有写操作单事务。

**Q2 主线「创建即跑完全程」（已定）**：创建请求内同步跑完（无定时器/手动推进）；事件只记 §18 语义事件（ORDER_RECEIVED → SKU_MAPPED → JD_STOCK_CHECKED → JD_OUTBOUND_SUBMITTED → JD_OUTBOUND_ACCEPTED → JD_SHIPPED → SHIPMENT_CREATED → TRACKING_RECEIVED → SOURCE_SYNCED），VALIDATED 等中间态由 status 列 + order_version 承载，不为每个转移硬造事件；最终态 = SYNCED；CLOSED 不自动进入（保留合法状态，种子数据历史单用）；ORDER_UPDATED 保留备用（demo 无编辑订单入口）。

> **后续范围澄清（数据库 Schema 设计 Q44）**：本条同步全流程仅适用于隔离的 DemoScenario。Excel 导入的业务 CanonicalOrder 不走 Mock 捷径，必须按真实异步履约、采购、运单与来源回填阶段推进；Mock 演示不能作为内部闭环验收。

**Q3 人工介入与采购回执（已定）**：demo 只实现一个 H 动作——采购回执；缺货单停 PROCUREMENT_PENDING 等待外部消息；外部 mock = 前端「采购操作台」页面扮演（调真实回执接口 `POST /internal/v1/procurement/tickets/{id}/receipt`，body 对齐 PRD §13：result / available_quantity / expected_ship_time / remark / idempotency_key）；业务系统内部零 mock 捷径，未来真实采购系统调同一接口，只换发送方。回执校验：工单必须 PENDING；PARTIAL 需 available_quantity < required_quantity。PARTIAL → 按 available 部分发货，剩余回 OUT_OF_STOCK 可再建工单；FAILED → FULFILLMENT_EXCEPTION（可再采购或取消）；回执接口幂等（idempotency_key，重复拒绝）。

**Q4 五维状态集（已定）**：
- OrderStatus：主线 RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED；异常分支 NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED；异常不占主线位，处理完回主线继续。
- FulfillmentStatus（type=JD_WAREHOUSE / PROCUREMENT）：PENDING → STOCK_CHECKED →（JD）JD_SUBMITTED → JD_ACCEPTED → SHIPPED（终）；缺货分支 OUT_OF_STOCK → PROCUREMENT_PENDING →（回执成功）ARRIVED → SHIPPED；回执 FAILED → EXCEPTION；京东拒收/出库失败 → EXCEPTION（H 重试 → STOCK_CHECKED / H 取消）。
- ShipmentStatus：CREATED → SHIPPED → DELIVERED（终；新单 mock 直达 SHIPPED，DELIVERED 种子历史单）。
- SyncStatus：PENDING → SYNCED；失败分支 SYNC_FAILED →（H 重试）→ SYNCED。
- ProcurementStatus：PENDING → SUCCESS / PARTIAL / FAILED；订单取消时工单 → CANCELLED。
- 人工干预规则：demo 统一演示账号 demo-ops；H 动作只作用于对应维度可操作态（回执只对 PENDING 工单、重试只对 SYNC_FAILED / EXCEPTION / NEED_REVIEW、取消只对未终态订单）；每个人工动作产生 Audit Log + OrderEvent；除采购回执外，其余 H 动作 demo 不做按钮，规则留口（种子数据展示）。

> **后续模型细化（数据库 Schema 设计 Q7、Q25–Q50）**：原 FulfillmentStatus/type 二合一模型已拆为 `order_lines.processing_stage`、`fulfillments.shipping_progress` 和 `fulfillments.outcome`；采购是我方库存不足时的补货分支，不是 Fulfillment 类型。Shipment 通过 ShipmentItem 支持多订单行同箱与一行多批；P0 完成不等待 DELIVERED。当前完整说明以已同步的 `docs/state-machine.md` 与 `docs/schema.md` 为准。

**Q5 多行订单聚合（已定）**：A 行级独立推进 + 订单级最差聚合——每行独立拆 Fulfillment 独立跑，有货行立即出库发货不等缺货行；订单级 OrderStatus 取所有行最差/最晚进度（任一待采购 → PROCUREMENT_PENDING；任一异常 → FULFILLMENT_EXCEPTION；全部 SHIPPED → SHIPPED；全部回传成功 → SYNCED）；回传按 shipment 独立；order_event 带可选关联 id（order_line_id / fulfillment_id / shipment_id / procurement_ticket_id）。

**2026-08-10 已关闭**：产出见 `docs/state-machine.md`。无新票浮现，未从 Not yet specified 毕业新雾区；回执接口细节归入 API 契约票。
