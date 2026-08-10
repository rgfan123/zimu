---
label: wayfinder:task
title: 后端骨架与订单域构建
status: open
claimed_by: 
blocked_by: [数据库 Schema 设计, API 契约设计]
parent: wayfinder:map
---

# 后端骨架与订单域构建

## Question

落地后端工程骨架与核心域：order / customer / product / sku 模块，schema 落库，可调通订单创建（模拟下单的后端路径）。

## 范围

- Spring Boot 工程（Java 21 / Maven / Spring Data JPA），单工程包分层（Modular Monolith）；
- `common` 模块：幂等键、OrderEvent 记录、Audit 切面、统一错误模型（按 API 契约票）；
- schema 落库（按数据库 Schema 票，默认 Flyway）；
- order / customer / product / sku 模块：实体、仓库、服务、控制器；
- SKU 映射逻辑（按商品信息创建或匹配 Internal SKU，PRD §9）；
- 订单创建管线：Schema/Business/SKU/Duplicate 校验（PRD §7）；
- Redis 接入（幂等键/缓存）。

## 验收（对齐地图 7 条验收）

- `mvn compile` 通过、应用可启动；
- 订单创建 API 可调通（`POST /internal/v1/orders` 路径）；
- 创建订单产生 OrderEvent + Audit Log 记录；
- 商品/SKU 种子可查（商品中心 API 有数据）。

## Blocked by

数据库 Schema 设计、API 契约设计。

## Resolution

（未解决）
