---
label: wayfinder:grilling
title: 数据库 Schema 设计
status: open
claimed_by: zed-main
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

### 决策日志（grilling 进行中，2026-08-10，zed-main）

**Q1 表清单（已定）**：19 张业务表 + 3 个分析视图。

- 订单域：`orders` / `order_lines` / `order_versions` / `order_events`
- 客户域：`customers`（建表；`orders.customer_id` FK 必填；`phone` 唯一索引）
- 商品域：`products` / `categories`（**specifications 不建表**——`products.specification` + `products.unit` 两列；判据：无 UI 页、demo 内举不出「共享规格统一改」场景；验收 5 要的是模块非空壳，不是每实体一张表）
- SKU 域：`internal_skus` / `aliases` / `channel_skus` / `jd_skus` / `jd_stock_snapshots`（jd_skus 纯映射：internal_sku_id + jd_goods_no + erp_goods_no；jd_stock_snapshots 承载 §12 四件套：唯一键 (warehouse_no, jd_sku_id)，stock_num / usable_num / synced_at）
- 履约域：`fulfillments`；发货域：`shipments` / `trackings` / `shipment_syncs`；采购域：`procurement_tickets`；审计域：`audit_logs`
- 连接器域：**`connector_configs`（新增）**——§22「系统→Connector」页数据源：按渠道一行，enabled / mode(mock\|real) / last_pull_at / last_error
- channel 不建表：四渠道 PRD 定死的常量，`orders.source_channel` 枚举列，视图 GROUP BY 不吃亏
- 分析视图：`v_analytics_channel_daily`（渠道×日期：订单数/行数/实发量/shipment 数/异常/缺货/回传失败）、`v_analytics_product_daily`（渠道×商品×日期：实发量/订单数）、`v_analytics_fulfillment_daily`（履约状态计数：京东履约量/缺货/采购/待出库/已出库/待运单/回传失败）
- customer 接收语义：§7 payload 的 customer 自由文本，**按 phone upsert**（匹配不到即建），与 SKU 映射 create-or-match 对称；receiver 三字段内嵌 orders
- 待 B3 回看：jd_skus 两列（jd_goods_no / erp_goods_no）哪列是主路径（京东认我方编码 vs 我方存京东编码），真实封装时定

**Q2 枚举存储形态（已定）**：PG 原生 enum，**统一应用于全部枚举列**——五维状态（order_status / fulfillment_status / shipment_status / sync_status / procurement_status）+ source_channel + settlement_method（MONTHLY / CASH / CREDIT）+ fulfillment.type（JD_WAREHOUSE / PROCUREMENT）+ order_event.type（§18 十二事件）。实现成本已知：Hibernate 6 需 @JdbcTypeCode(SqlTypes.NAMED_ENUM) 映射；ALTER TYPE ADD VALUE 在 Flyway 事务脚本受限（状态集已定死，风险可控）。
