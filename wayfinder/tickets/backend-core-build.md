---
label: wayfinder:grilling
title: 后端核心实现边界
status: closed
claimed_by: zed-main
blocked_by: [数据库 Schema 设计, API 契约设计]
parent: wayfinder:map
---

# 后端核心实现边界

## Question

在进入实现前，锁定后端核心域、真实内部订单、Demo、初始 Fulfillment、主数据、Redis 与 P0 Excel 的责任边界。

## Grilling decisions

- `POST /internal/v1/orders` 只接收真实 `BUSINESS + WECOM` 结构化订单，不再供 Mock 演示页复用；Mock 使用独立 `/demo/v1`。
- 企业微信消息解析、LLM Prompt 与 AI 结构化提取不属于本系统；后端只接收结构化结果。
- 客户与 SKU 校验全部通过后，每条已映射 OrderLine 在同一事务创建一个初始 Fulfillment；库存判断、采购、Shipment、履约文件和运单回传由后续履约/P0 Excel 构建票负责。
- Customer 是公司内部统一客户单位档案，与订单上的 Receiver 收货快照分离；不得按收货人电话自动合并客户。
- 后端核心实现票负责 Order、Customer、Category、Product、SKU、来源/履约方 SKU 映射在既有 API 契约中的端点，以及共享的幂等、事件/版本、AuditLog 写入与查询基础设施；不负责履约、Shipment、采购或 Excel 端点。
- 核心实现只播最小主数据种子以验收客户、京东 SKU、第三方 SKU 和订单创建；30 天完整演示数据仍归种子与一键启动票。
- PostgreSQL `idempotency_registry` 是业务幂等与事务正确性的权威；Redis 只作可丢失缓存/连接骨架，缓存失效不得改变业务结果。
- 履约实现票拥有 Fulfillment / Shipment / Tracking / Procurement 的共享领域模型、状态转移与 JSON API；P0 Excel 实现票拥有文件指纹、解析/序列化、导入导出批次与整批事务编排，通过履约应用服务落 Shipment/Tracking，不复制状态机。
- P0 Excel 已有完整设计，并已卒业为独立的「P0 Excel 接入与履约回填闭环构建」票；不把文件解析与转换塞入核心域。

## Review checklist

- 与已关闭数据库 Schema/API 契约一致；
- Mock Demo 与 BUSINESS 内部订单不混用；
- 后端核心、履约和 P0 Excel 三张实现票没有重复所有权；
- 没有把 Redis 变成第二份事务真相源。

## Blocked by

数据库 Schema 设计、API 契约设计（均已关闭）。

## Resolution

后端核心域的实现边界已确认：真实 WECOM 入口只接收结构化 BUSINESS 订单；成功映射的订单行创建初始 Fulfillment，但不在核心票内推进库存、采购、Shipment 或文件闭环；核心票拥有 Order/Customer/Product/SKU 与映射 API，使用 PostgreSQL 权威幂等、Redis 非权威缓存和最小主数据种子。Mock Demo 与 P0 Excel 均拆到独立实现票。

## Review evidence

- 对照 `docs/api-contract.md` 与 `docs/openapi.yaml`：`/internal/v1/orders` 仅接受 WECOM 结构化输入，Demo 使用 `/demo/v1`；缺映射时保存 ReviewCase 而不进入履约，与本票一致。
- 对照 `docs/schema.md` / `docs/schema.sql`：Customer 与 Receiver 快照分离，`idempotency_registry` 在 PostgreSQL 中作权威事实，Demo 以 `data_scope` 隔离；本票没有引入第二份真相源。
- 定点复审发现并消除两处重叠：AuditLog 基础设施/查询归核心实现，Connector 只调用共享审计服务；Shipment/Tracking 领域服务归履约实现，P0 Excel 只拥有文件适配与整批编排。
- 新增「后端骨架与订单域实现」和「P0 Excel 接入与履约回填闭环构建」两张实现票，并将后续 blocker 按新票名重新连线。
