# 订单履约与仓储物流中台 PRD V0.1

> 本文件是地图的**固定输入**（PRD V0.1 主体），所有范围与契约以此为准。
> 范围边界：企业微信、LangBot、Agent 内部实现不在本 PRD 范围内，只定义它们与业务系统之间的数据接口。
> 字段契约：订单部门 → 仓储的核心输入（收货人、联系电话、地址、商品名称、规格、数量、结账方式、结账时间等）；`order_id` 由系统创建，`sku_code` 由系统创建或匹配，不要求来源部门必填。仓储完成履约后返回实际发货数量、发货状态、物流公司、物流单号、异常原因。

## 1. 产品定位

### 1.1 产品名称

**订单履约与仓储物流中台**

英文内部简称：`Fulfillment & Logistics Hub`

### 1.2 产品目标

建设公司订单进入后的统一履约基础设施：

> **订单聚合 → 标准化入库 → 商品/SKU 映射 → 京东仓库存履约 → 缺货采购协同 → Shipment 管理 → 发货结果回传 → 订单追踪 → 履约数据统计**

系统不是电商商城，也不是采购系统。它承担的是：**公司 B 端订单从"进入公司"到"完成发货"的统一业务主线。**

## 2. 系统边界

三个独立环境：

- 智能接入环境（独立维护）：企业微信 → LangBot → Agent / LLM
- 订单履约与仓储物流中台（本系统）：Internal Order API、订单中心、商品/SKU 中心、Fulfillment Engine、Shipment、数据中台
- 外部业务系统：彩食鲜、聚福宝、飞象、京东仓配、采购部门

### 本系统负责

彩食鲜 / 聚福宝 / 飞象订单 Connector；接收 LangBot 环境输出的标准业务数据；Internal Order；商品 / 品类 / SKU；京东仓配；履约状态；Shipment / 运单；缺货采购工单；部门协同接口；发货结果回传；订单追踪；审计日志；履约数据中台。

### 本系统不负责

企业微信机器人本身；LangBot 部署；LLM Prompt；OCR / 图片识别模型；Agent 调度框架；采购部门内部询价、比价、供应商选择、审批；京东 WMS 内部库存分配算法。

## 3. 总体业务流程

订单来源（三平台 Connector + LangBot/Agent 环境）→ 统一订单接口 → 订单业务校验（异常走人工处理/Exception）→ Internal Order → 商品/Internal SKU 映射 → Fulfillment Engine → 查询京东自有库存 →（可履约）京东销售出库 /（库存不足）采购工单 → 采购部门处理结果 → 同步出库状态 → Shipment / Tracking → 发货结果回传（回三平台 / LangBot）→ 订单追踪 → 履约数据中台。

## 4. 核心架构

### 4.1 技术架构

| 层 | 选型 |
|---|---|
| 管理后台 | React + TypeScript + Vite |
| UI | Ant Design |
| 图表 | ECharts |
| 主业务后端 | Java + Spring Boot |
| 架构模式 | Modular Monolith |
| 数据库 | PostgreSQL |
| ORM / Persistence | Spring Data JPA / MyBatis 二选一，建议 JPA 为主 |
| 缓存 | Redis |
| 异步任务 | Spring Scheduler / Worker，一期先不引入 Kafka |
| 对象存储 | S3 Compatible / MinIO |
| 京东接入 | 京东 ISC Java SDK + LOP OpenSDK |
| BI | Metabase |
| API | REST + OpenAPI |
| 部署 | Docker + Nginx |

## 5. 后端模块架构

一期保持**模块化单体**：

```text
backend/
├── order/        (order, order-line, order-version, order-event)
├── customer/
├── product/      (product, category, specification)
├── sku/          (internal-sku, alias, channel-sku, jd-sku)
├── fulfillment/  (fulfillment, routing, state-machine)
├── shipment/     (shipment, tracking, shipment-sync)
├── procurement/  (procurement-ticket)
├── connector/    (jd/, caishixian/, jufubao/, feixiang/)
├── analytics/
├── audit/
├── exception/
└── common/
```

模块之间通过 Service / Domain Interface 调用。**Connector 禁止直接写业务表。**

## 6. 订单接入模块

两条订单输入路径：三个平台（彩食鲜 / 聚福宝 / 飞象）通过 Platform Connector（鉴权、获取订单、获取订单变更、获取取消、字段转换、发货结果回传、限流、重试、外部错误码转换）转成 Canonical Order；LangBot / Agent 环境通过 Internal API。

Connector 不负责：SKU 决策、是否允许发货、京东库存判断、采购判断。

## 7. LangBot / Agent 接口

Agent 系统视为外部可信但**不能直接写业务数据库**的上游。

调用 `POST /internal/v1/orders`：

```json
{
  "source": "WECOM",
  "source_ref": "message_xxx",
  "customer": {},
  "receiver": {},
  "items": [],
  "settlement": {},
  "remark": "",
  "evidence_refs": []
}
```

后端收到之后仍必须执行：Schema Validation → Business Validation → SKU Validation → Duplicate / Version Validation → Internal Order。

> Agent 输出的是业务输入，不是数据库事实。

## 8. 订单部门 → 仓储字段契约

| 业务字段 | Internal 字段 | 必填 |
|---|---|---:|
| 订单号 | `order_id` | 否 |
| 收货人 | `receiver_name` | 是 |
| 联系电话 | `receiver_phone` | 是 |
| 收货地址 | `address` | 是 |
| 商品名称 | `product_name` | 是 |
| 商品编码 | `sku_code` | 否 |
| 商品规格 | `specification` | 是 |
| 下单数量 | `quantity` | 是 |
| 结账方式 | `settlement_method` | 是 |
| 结账时间 | `settlement_time` | 是 |
| 订单备注 | `remark` | 否 |

业务模型拆成：

```text
Order
├── order_id
├── source_channel
├── source_order_id
├── customer_id
├── settlement_method
├── settlement_time
├── remark
│
├── Receiver
│   ├── receiver_name
│   ├── receiver_phone
│   └── address
│
└── OrderLines[]
    ├── product_name
    ├── internal_sku
    ├── specification
    ├── quantity
    └── unit        ← 新增建议：B 端场景 20 件/20 箱/20 kg/20 盒 不能只靠 quantity
```

## 9. 商品与 SKU 中心

商品主数据与平台解耦：

```text
Product → Internal SKU → Alias / 彩食鲜 SKU / 聚福宝 SKU / 飞象 SKU / JD Warehouse SKU
```

例：Internal SKU SKU-000128 澳洲牛腩块 500g；Mappings：彩食鲜 CSX-28372、飞象 FX-92821、京东 JD-8372718。Internal SKU 是公司内部唯一商品身份。

## 10. Fulfillment Engine

核心领域模块：接收已确认订单 → 按 Order Line 处理 → 获取 Internal SKU → 查询京东仓自有库存 → 创建京东销售出库 → 处理库存不足 → 创建采购工单 → 接收采购结果 → 推进履约状态。

核心原则：**Order ≠ Fulfillment ≠ Shipment**。一个订单可拆成多个 Fulfillment（京东仓 / 采购），之后产生多个 Shipment。

## 11. 京东仓配模块

使用已有：`lop-opensdk-support-1.0.30.jar`、`IntegratedSupplyChain_ISC_JAVA_6.1_20260707185402.jar`（已放入 `backend/libs/`）。

Spring Boot 内 `connector/jd/` 统一封装：

```java
JDWarehouseService

queryWarehouses()
queryProducts()
queryStock()

createOutboundOrder()
queryOutboundOrder()
cancelOutboundOrder()

queryTracking()
```

核心流程：Fulfillment → queryStock() → createOutboundOrder() → 京东 ISC → queryOutboundOrder() → status → carrier / waybillNo → Shipment。

## 12. 库存处理原则

系统内部可保存 `last_stock` / `stock_sync_time` / `warehouse` / `sku` 用于查询、看板、履约预判断，但不要建立试图替代京东 WMS 的库存系统。**京东销售出库单是否被受理，才是最终履约依据。**

## 13. 缺货采购工单

只负责跨部门接口。Fulfillment → OUT_OF_STOCK → Procurement Ticket → 采购部门。

工单字段：ticket_id, order_id, order_line_id, sku_code, product_name, specification, required_quantity, unit, delivery_address, required_delivery_time, priority。

采购模块返回：SUCCESS / PARTIAL / FAILED 及 available_quantity, expected_ship_time, shipment/fulfillment reference, remark。采购内部流程 TBD。

## 14. Shipment 模型

```text
Order → Fulfillment → Shipment → Tracking
```

1 Order → N Fulfillment → N Shipment → N Tracking Number。

字段：shipment_id, order_id, fulfillment_id, shipped_quantity, logistics_company, tracking_number, shipped_at, shipment_status。

## 15. 仓储 → 订单部门回传契约

| 业务信息 | 字段 |
|---|---|
| 实际发货数量 | `shipped_quantity` |
| 发货状态 | `fulfillment_status` |
| 物流公司 | `logistics_company` |
| 物流单号 | `tracking_number` |
| 异常原因 | `exception_reason` |

标准响应：

```json
{
  "order_id": "ORD-xxx",
  "fulfillment_status": "SHIPPED",
  "shipments": [
    { "shipped_quantity": 20, "logistics_company": "JD", "tracking_number": "JDxxxxx" }
  ],
  "exception_reason": null
}
```

## 16. 发货结果回传

Shipment 创建后由 Shipment Sync 按 source_channel 分发：CAISHIXIAN → CaishixianConnector；JUFUBAO → JufubaoConnector；FEIXIANG → FeixiangConnector；WECOM → Internal Callback → LangBot。业务层不关心各平台具体字段格式。

## 17. 订单状态机

一级状态：RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED。

异常分支：NEED_REVIEW, OUT_OF_STOCK, PROCUREMENT_PENDING, FULFILLMENT_EXCEPTION, SYNC_FAILED, CANCELLED。

分别维护：OrderStatus, FulfillmentStatus, ShipmentStatus, SyncStatus, ProcurementStatus。

## 18. Order Event / Timeline

事件：ORDER_RECEIVED, ORDER_UPDATED, SKU_MAPPED, JD_STOCK_CHECKED, JD_OUTBOUND_SUBMITTED, JD_OUTBOUND_ACCEPTED, JD_SHIPPED, PROCUREMENT_REQUESTED, PROCUREMENT_COMPLETED, SHIPMENT_CREATED, TRACKING_RECEIVED, SOURCE_SYNCED。后台按时间线展示。

## 19. Audit Log

与 Order Event 分开。记录：request_id, trace_id, operator, service, operation, request, response, http_status, business_code, latency, created_at。用于京东/三平台接口排错、人工操作审计、数据修改追责。

## 20. 数据中台

一期建立在 PostgreSQL 上：业务表 → analytics schema → View / Materialized View → Analytics API → Metabase / React+ECharts。首版不引入 Kafka、Flink、ClickHouse、Data Lake。

## 21. 数据中台核心指标

- 渠道（彩食鲜/聚福宝/飞象/企业微信）：订单数、Order Line 数、实际发货数量、Shipment 数、异常数、缺货数、回传失败数。**"每个渠道发了多少货"必须同时提供订单数和实际商品数量两个口径。**
- 商品：渠道 × 商品、渠道 × SKU、渠道 × 品类、商品 × 日期。
- 履约：京东仓履约量、缺货订单、采购工单数、京东提交失败数、待出库、已出库、待取得运单、待回传、回传失败。

## 22. 管理后台导航

```text
工作台
订单管理：全部订单 / 待处理 / 异常订单 / 订单追踪
商品中心：商品 / 品类 / Internal SKU / SKU Mapping
履约中心：履约任务 / 京东仓 / 销售出库 / Shipment
部门协同：采购工单
数据中台：履约总览 / 渠道分析 / 商品分析 / 履约分析
系统：Connector / Audit Log / 系统配置
```

## 23. 核心数据库关系（ER）

CHANNEL 1—N ORDER；CUSTOMER 1—N ORDER；ORDER 1—N ORDER_LINE；PRODUCT 1—N INTERNAL_SKU；INTERNAL_SKU 1—N ORDER_LINE；ORDER_LINE 1—N FULFILLMENT；FULFILLMENT 1—N SHIPMENT；SHIPMENT 1—N TRACKING；ORDER_LINE 1—N PROCUREMENT_TICKET；ORDER 1—N ORDER_EVENT；ORDER 1—N AUDIT_LOG。

CHANNEL {channel_id, code}；ORDER {order_id, source_order_id, status}；ORDER_LINE {order_line_id, product_name, specification, quantity}；INTERNAL_SKU {sku_id, sku_code}；FULFILLMENT {fulfillment_id, type, status}；SHIPMENT {shipment_id, shipped_quantity, status}；TRACKING {logistics_company, tracking_number}。

## 24. 部署架构

业务人员 → Nginx → React Admin / Spring Boot；API → PostgreSQL / Redis / S3-MinIO / 京东 ISC SDK → 京东物流 / 三平台 Connector / Spring Worker-Scheduler；Metabase → PG；独立 LangBot 环境 → Internal API。一期全部 Docker + Docker Compose + Nginx，不需要 Kubernetes。

## 25. 非功能要求

- **数据一致性**：Order + OrderLines + OrderEvent、Shipment + Tracking + OrderEvent 等关键写操作事务化。
- **幂等**：LangBot 创建订单、平台订单同步、京东销售出库、Shipment 创建、平台发货回传、采购工单创建，建议 `idempotency_key`。
- **可追溯性**：来源 → 原始订单/消息 → Internal Order → SKU → Fulfillment → 京东/采购 → Shipment → Tracking → 发货回传，全程可反查。
- **权限**：管理员、订单人员、仓储人员、采购人员、运营/管理人员、只读人员；重要操作记录 operator。

## 26. 一期验收标准

主线：LangBot / 平台订单 → 统一订单接口 → Internal Order → SKU Mapping → 京东库存 →（有货）京东销售出库 /（缺货）采购工单 → Shipment → 运单 → 平台/LangBot 回传 → 订单 Timeline → 渠道履约数据统计。

验收十问：

1. 这张订单从哪里来的？
2. 现在处于什么状态？
3. 发了哪些商品、实际发了多少？
4. 是否由京东自有库存履约？
5. 如果缺货，采购工单在哪里？
6. 物流公司和运单是什么？
7. 发货结果有没有成功回传来源渠道？
8. 今天彩食鲜、聚福宝、飞象分别发了多少单、多少货？
9. 哪个商品今天发货最多？
10. 某笔订单发生异常时，能否找到完整业务轨迹和接口日志？
