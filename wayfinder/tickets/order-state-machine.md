---
label: wayfinder:grilling
title: 订单状态机精化
status: open
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

（未解决）
