---
label: wayfinder:task
title: 履约发货与采购模块构建
status: closed
claimed_by: codex-backend
blocked_by: [后端骨架与订单域实现, 数据库 Schema 设计, API 契约设计, 订单状态机精化]
parent: wayfinder:map
---

# 履约发货与采购模块构建

## Question

落地 fulfillment / shipment / procurement 模块：真实状态机、异步业务履约流水线、隔离 DemoScenario 同步演示、Shipment 与采购工单。

## 范围

- 状态机实现（按订单状态机精化票）：五维状态、转移、事件（Timeline）；
- 履约流水线：接收核心域已创建的初始 Fulfillment → SKU → 库存判断（走 JDWarehouseService 接口）→ 可导出/缺货采购工单 → 接收履约结果后落 Shipment/Tracking；
- BUSINESS 订单按真实文件/采购/运单回执逐阶段推进，不同步跑到最终态；只有隔离 DemoScenario 使用 Mock Adapter 同步跑完全程；
- Fulfillment / Shipment / Tracking / Procurement 的共享领域模型、状态转移、应用服务与 JSON 查询/命令 API（PRD §14）；P0 Excel 通过这些应用服务落业务事实；
- procurement-ticket：创建、结果回填（SUCCESS/PARTIAL/FAILED）；
- 履约中心 / 采购工单的查询 API。

## 不包含

- 三平台文件指纹、Excel/CSV 解析与序列化、ImportBatch/FulfillmentExport/SourceReturnExport 文件编排；
- 履约方回传文件的整批校验与原子导入（归「P0 Excel 接入与履约回填闭环构建」）。

## 验收

- DemoScenario 到达隔离最终态且 Timeline 完整；BUSINESS 订单停在其真实等待阶段，不伪造回执；
- 缺货订单生成采购工单，状态可查；
- 履约/发货/采购的查询 API 数据正确。

## Blocked by

后端骨架与订单域实现、数据库 Schema 设计、API 契约设计、订单状态机精化。

## Validation

- `cd backend && mvn test -q`：27/27 通过（0 failures，0 errors）；
- `cd backend && mvn -Dtest=FulfillmentProcurementApiTest test -q`：1/1 通过，覆盖履约/发货/物流/采购查询、采购回执幂等、状态、Timeline 与审计；
- `cd backend && mvn -Dtest=FulfillmentStockDecisionServiceTest test -q`：1/1 通过，覆盖默认 Mock 不自动推进 BUSINESS、显式 AVAILABLE 保持待导出、OUT_OF_STOCK 幂等生成唯一采购工单/提醒/事件/版本/审计；
- 采购超额回执在写入前锁定明细并返回 `422 RECEIPT_QUANTITY_EXCEEDS_REMAINING`，定向测试通过。

## Resolution

已补齐 Fulfillment、Shipment/Tracking、Order Shipments、ProcurementTicket/Receipt 的 JSON 查询与幂等命令应用服务，包含 BUSINESS 数据隔离、分页计数、采购 SUCCESS/PARTIAL/FAILED、FAILED 重试与取消剩余量。新增由 JD Adapter/P0 文件流程明确调用的规范化库存决策 seam：不猜测 SDK data shape，也不由默认 Mock 自动推进 BUSINESS；库存足够只记录快照并保持 `READY_TO_EXPORT`，缺货则原子创建采购工单、黄色提醒并转 `PROCUREMENT_IN_PROGRESS`。DemoScenario 继续使用隔离 DEMO 数据同步完成 Timeline。
