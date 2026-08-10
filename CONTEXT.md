# CONTEXT.md — 领域词汇表

订单履约与仓储物流中台（Fulfillment & Logistics Hub）的领域语言。仅收录术语与定义，不含实现细节。

## 核心术语

| 术语 | 定义 |
|---|---|
| **订单 Order** | 公司 B 端业务订单，从来源渠道进入系统后的统一业务实体。订单 ≠ 履约 ≠ 发货。 |
| **订单行 OrderLine** | 订单内的商品明细行（商品名称、规格、数量、单位）。 |
| **来源渠道 Channel** | 订单进入系统的来源：`CAISHIXIAN`（彩食鲜）、`JUFUBAO`（聚福宝）、`FEIXIANG`（飞象）、`WECOM`（企业微信）。 |
| **收货人 Receiver** | 订单的收货信息（姓名、电话、地址），与客户（Customer）分离。 |
| **结账方式 SettlementMethod** | 订单的结账方式（如月结/现结/账期），结账时间 `settlement_time` 一并记录。 |
| **Internal SKU** | 公司内部唯一商品身份，与任何平台解耦；通过 Alias / 渠道 SKU / 京东 SKU 映射到外部编码。 |
| **履约 Fulfillment** | 按订单行拆分出的执行单元（京东仓 / 采购），一个订单可有多个履约。 |
| **发货 Shipment** | 一次实际发货（物流公司、运单号、实际发货数量），一个履约可有多个发货。 |
| **运单 Tracking** | 物流轨迹（物流公司 + 物流单号）。 |
| **采购工单 ProcurementTicket** | 缺货时向采购部门发起的协同工单（SUCCESS / PARTIAL / FAILED）。 |
| **回传 Sync** | 发货结果按来源渠道回传（三平台 Connector / WECOM 模拟回传）。 |
| **订单事件 OrderEvent** | 订单时间线（Timeline）上的业务事件（ORDER_RECEIVED、JD_SHIPPED、SOURCE_SYNCED 等），与审计日志分离。 |
| **审计日志 AuditLog** | 接口调用与人工操作的审计记录（operator、request/response、trace_id），用于排错与追责。 |
| **异常 Exception** | 需要人工介入的订单状态分支（NEED_REVIEW、FULFILLMENT_EXCEPTION、SYNC_FAILED 等）。 |
| **模拟下单 DemoOrder** | 演示入口：前端「模拟下单」页通过 `POST /internal/v1/orders` 创建订单，等价于未来 LangBot 的输入路径。 |
| **采购回执 ProcurementReceipt** | 外部采购部门对采购工单的处理结果回传（SUCCESS / PARTIAL / FAILED + available_quantity / expected_ship_time）；demo 由前端「采购操作台」模拟发送方，业务系统只认真实回执接口（`POST /internal/v1/procurement/tickets/{id}/receipt`）。 |

## 状态维度

状态按维度分离维护，不合并进单一 `order.status`（完整转移矩阵见 `docs/state-machine.md`）：

- **OrderStatus**：主线 RECEIVED → VALIDATED → SKU_MAPPED → FULFILLING → SHIPPED → SYNCED → CLOSED；异常分支 NEED_REVIEW / OUT_OF_STOCK / PROCUREMENT_PENDING / FULFILLMENT_EXCEPTION / SYNC_FAILED / CANCELLED；demo 自动最终态 = SYNCED（CLOSED 不自动进入）
- **FulfillmentStatus**（type ∈ JD_WAREHOUSE / PROCUREMENT）：PENDING → STOCK_CHECKED → JD_SUBMITTED → JD_ACCEPTED → SHIPPED（终）；分支 OUT_OF_STOCK → PROCUREMENT_PENDING → ARRIVED → SHIPPED；EXCEPTION（回执 FAILED / 京东拒收）
- **ShipmentStatus**：CREATED → SHIPPED → DELIVERED（终）
- **SyncStatus**：PENDING → SYNCED；SYNC_FAILED →（重试）→ SYNCED
- **ProcurementStatus**：PENDING → SUCCESS / PARTIAL / FAILED；订单取消 → CANCELLED
- 多行订单：行级独立推进 + 订单级最差聚合

## 边界

- 本系统不负责：企业微信机器人、LangBot 部署、LLM Prompt、OCR 模型、Agent 调度、采购内部流程、京东 WMS 库存算法。
- 外部系统一律通过接口接入，**Connector 禁止直接写业务表**。
