# 01 — 京东云仓建出库单并落地本地 shipment

**What to build:** 京东云仓履约的订单在业务确认发货时自动调京东 `addSoOrder` 建出库单，替代 Excel 发货表：建单成功后系统同步创建本地 shipment + shipment_items（京东运单/出库单号作为跟踪锚点），并把履约推进到「已建京东出库单」阶段，防止 Excel 通道与 API 通道对同一订单重复建单。

**Blocked by:** 00 — 履约记录京东同步字段扩展（需要 erpDeliveryNo 落点与阶段推进列）

**Status:** wontfix

- [ ] 新建 addSoOrder 防腐层方法（当前写服务无此方法），请求字段完备：planQuantity 整数语义（系统千克数值 → 整数数量）、ReceiverInfo 结构化 province/city/county/town/detailAddress（系统自由文本地址需拆分规则）、warehouseNo/erpShopNo/customerCode 从履约方配置读取。
- [ ] erpDeliveryNo 生成规则确定且防重：同一履约订单重复触发不产生第二张京东出库单（先查后建或唯一约束）。
- [ ] 建单成功 → 事务内创建本地 shipment + shipment_items，并推进 processing_stage（如 JD_ORDER_CREATED）；建单失败 → 可诊断的失败结果 + 审计 + 告警，不留下半截 shipment。
- [ ] Mock 模式全链路可演示（建单 → 本地 shipment 可见）；写模式门闩（OFF 时拒绝）沿用现有门闩语义，不触网。
- [ ] 跟踪链路前置条件满足：ShipmentTrackingService.accept() 的 shipment 存在性假设由本票建单路径满足（不再只有 Excel/企微两个创建点）。

## Comments

- 2026-08-13：此票已被 `jd-fulfillment-loop/02、04、05` 取代。旧方案按 Fulfillment 建单，并允许地址猜测、数量取整和隐式配置；保留文件仅用于追溯，不得继续领取或实现。
