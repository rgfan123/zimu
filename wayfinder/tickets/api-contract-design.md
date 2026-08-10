---
label: wayfinder:grilling
title: API 契约设计
status: open
claimed_by: 
blocked_by: []
parent: wayfinder:map
---

# API 契约设计

## Question

定出系统全部 REST API 契约，产出 OpenAPI 3.0 文档。覆盖所有前端页面所需数据 + 外部接入面。

## 决策点

- `POST /internal/v1/orders`（PRD §7 结构，模拟下单页与未来 LangBot 共用）；
- 业务管理 API：订单（列表/详情/Timeline/状态筛选）、商品中心（商品/品类/SKU/Mapping CRUD）、履约中心（履约任务/京东仓/销售出库/Shipment）、采购工单（列表/详情/结果回填）、Audit Log 查询、数据中台查询（渠道/商品/履约指标，§21）；
- 外部对接面：京东 `JDWarehouseService` 接口签名（对齐 R1 提取结果）、三平台 Connector 接口（拉单/转换/回传）、WECOM 渠道回传模拟；
- 幂等机制：`idempotency_key` 放 header 还是 body，写接口清单；
- 错误模型：错误码 + 消息结构（business_code / http_status）；
- 分页/筛选/排序约定；鉴权（demo 无登录，但预留 token header 位）。

## 产出

- `docs/api-contract.md`（OpenAPI 3.0 + 说明）

## Blocked by

无（前沿票）。

## Resolution

（未解决）
