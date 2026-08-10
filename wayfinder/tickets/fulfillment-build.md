---
label: wayfinder:task
title: 履约发货与采购模块构建
status: open
claimed_by: 
blocked_by: [数据库 Schema 设计, API 契约设计, 订单状态机精化]
parent: wayfinder:map
---

# 履约发货与采购模块构建

## Question

落地 fulfillment / shipment / procurement 模块：真实状态机、履约流水线（创建即跑完全程）、Shipment 与采购工单。

## 范围

- 状态机实现（按订单状态机精化票）：五维状态、转移、事件（Timeline）；
- 履约流水线：Order → 按行拆 Fulfillment → SKU → 库存判断（走 JDWarehouseService 接口）→ 有货出库 / 缺货采购工单 → Shipment → 回传记录；
- **创建即跑完全程**：新订单在创建路径上真实执行整条流水线到最终态（不引入推进机制/定时器）；
- shipment + tracking 模型（PRD §14）；
- procurement-ticket：创建、结果回填（SUCCESS/PARTIAL/FAILED）；
- 履约中心 / 采购工单的查询 API。

## 验收

- 模拟下单后订单到达最终态，Timeline 事件完整（ORDER_RECEIVED → … → SOURCE_SYNCED）；
- 缺货订单生成采购工单，状态可查；
- 履约/发货/采购的查询 API 数据正确。

## Blocked by

数据库 Schema 设计、API 契约设计、订单状态机精化。

## Resolution

（未解决）
