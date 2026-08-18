# 02 — 京东出库单回填运单并推进履约状态

**What to build:** 已建京东出库单的订单，系统定时/按需调 `querySoOrder` 查京东侧出库进度，把运单号回填到本地 trackings 并调 ShipmentTrackingService.accept() 推进订单状态——替代人工回传 Excel。支持拆单（一单多运单）与部分发货：按 erpDeliveryNo 先查后写，多运单建多条 shipment，部分发货不误判完结。

**Blocked by:** 01 — 京东云仓建出库单并落地本地 shipment（回填的对象是 01 建的出库单与 shipment）

**Status:** wontfix

- [ ] 新建 querySoOrder 防腐层方法（当前订单查询服务无此方法），返回京东出库单状态与运单信息。
- [ ] 幂等先查后写：以 erpDeliveryNo 为键查本地是否已回填，重复轮询不产生重复 trackings（现有 trackings UNIQUE(shipment_id) / UNIQUE(logistics_company_code, tracking_number) 约束下成立）。
- [ ] 拆单处理：京东返回多运单（isSplit）时建多条 shipment；部分发货复用部分发货语义（MULTI_SHIPMENT_SOURCE_FOLLOWUP 模式），不提前完结履约。
- [ ] 回填后调用 ShipmentTrackingService.accept() 推进订单状态；失败可重试，审计留痕。
- [ ] Mock 模式可演示全链路（建单 → 回填 → 状态推进）；轮询间隔与手动触发入口明确。

## Comments

- 2026-08-13：此票已被 `jd-fulfillment-loop/06 — 幂等回填京东运单与履约进度` 取代。新票遵循既有 Shipment 单运单边界，并将冲突或多运单交给 ReviewCase；保留文件仅用于追溯，不得继续领取或实现。
