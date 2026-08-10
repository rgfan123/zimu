---
label: wayfinder:grilling
title: 数据库 Schema 设计
status: open
claimed_by: 
blocked_by: []
parent: wayfinder:map
---

# 数据库 Schema 设计

## Question

基于 PRD §8 字段契约 + §23 ER + §17 状态机，定出 PostgreSQL **全部表/列/类型/枚举/索引/约束**，产出可执行的 DDL。这是所有构建票的地基。

## 决策点

- 表清单：order / order_line / order_version / order_event / customer / product / category / specification / internal_sku / alias / channel_sku / jd_sku / fulfillment / shipment / tracking / shipment_sync / procurement_ticket / audit_log + analytics 视图/物化视图（§20/§21 指标）；
- 状态维度：OrderStatus / FulfillmentStatus / ShipmentStatus / SyncStatus / ProcurementStatus 用枚举还是查找表（倾向枚举 + PG enum 或 CHECK）；
- 幂等键：放哪张表（idempotency_key 唯一约束）；
- order_version 快照方案（§5 模块里有 order-version——每次更新存快照？）；
- 金额/数量类型（decimal 精度）、unit 字段（§8 新增建议）；
- 时间戳与时区（timestamptz）；
- analytics 视图/MV 清单（渠道×商品×日期 等）；
- Flyway 迁移 vs JPA ddl-auto（默认 Flyway，见地图 Notes）。

## 产出

- `docs/schema.md`（表设计说明 + ER）
- `docs/schema.sql`（可执行 DDL）

## Blocked by

无（前沿票）。

## Resolution

（未解决）
