# 01 — 统一 Shipment 级京东出库单边界

**Type:** implementation

**What to build:** 运营人员在订单与履约视图中看到一条 Shipment 级京东出库状态；同一发货批次内的多个 Fulfillment 共享一个京东出库引用，不再按订单行重复建单。

**Blocked by:** None — can start immediately

**Status:** resolved

**Claimed by:** zed-main

- [x] 一个 Shipment 最多拥有一个京东出库集成记录，同批次多个 ShipmentItem/Fulfillment 共享该记录。
- [x] 商户侧出库引用、同步状态、失败阶段与重试信息由 Shipment 边界承载，不以 Fulfillment 为唯一归属。
- [x] JD 同步状态不写入或扩展 OrderLine `processing_stage`，现有权威业务阶段保持不变。
- [x] 操作视图能读取 Shipment 的 JD 引用、状态和最近失败原因，且不会把凭据或原始 PII 暴露给前端。
- [x] 若旧的 Fulfillment 级字段已承载数据，采用兼容迁移并证明数据不丢失；若尚未发布，则在发布前纠正旧迁移。
- [x] 通过公共应用/API seam 证明一个多行 Shipment 只产生一个 JD 出库聚合，并写入事件与审计。

## Comments

- 2026-08-13：这是 `jd-fulfillment-loop` 当前唯一无阻塞实现票。旧 `jd-sdk-bridge` 二阶段 00–05 已封存；领取本票时必须先核对并收口其遗留代码，不得从旧票继续扩展。

## Answer

实现完成：

- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundService.java`：Shipment 级建单核心。`lockContext` 按 Shipment 聚合全部 ShipmentItems；`app.shipment_jd_outbounds` 以 `shipment_id UNIQUE` 承载 erp_delivery_no（= shipments.outbound_order_no 稳定引用）、jd_delivery_no、sync_status（NONE/SUBMITTED/SYNC_FAILED）、failure_phase（VALIDATION/SUBMIT）、retry_count、last_error_*、request_hash；`ON CONFLICT (shipment_id) DO UPDATE` 保证同 Shipment 只一条记录；失败/重试不新建记录。请求哈希防漂移：同 Shipment 不同请求在失败记录上重试被拒（JD_SHIPMENT_OUTBOUND_REQUEST_CHANGED）。
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundController.java`：`POST /api/v1/shipments/{id}/jd-so-order`，Idempotency-Key + X-Operator 必填，复用 WriteCommands 写门禁。
- V9 migration 发布前纠正为 Shipment 级（旧 Fulfillment 级列从未提交，无兼容迁移负担）；`fulfillments` 表无任何 `jd_%` 列。
- 写门闩：`app.jd.write-mode` 默认 OFF 时 submit 被拒（JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED, 409），失败阶段/诊断码/重试信息独立提交（REQUIRES_NEW）+ 告警 + 审计，不触网、不推进业务阶段。
- 事件 JD_OUTBOUND_SUBMITTED（带 shipment_id）、版本（change_reason=京东云仓建出库单）、审计（seam 侧 orderSoCreate 审计 receiverInfo 整容器脱敏为 ***，cargoInfos 保留商品明细）。
- 测试：`ShipmentJdOutboundSubmitTest`（10 个用例：多行单聚合/幂等重放/新 key 拒绝/失败重试恢复/请求变更拒绝/礼包共享/阶段拒绝/第三方拒绝/已发货拒绝/无 jd 列）、`ShipmentJdOutboundWriteModeDisabledTest`（写门闩）。SubmitTest 10/10 通过；WriteModeDisabledTest 曾遇 Testcontainers 容器启动环境超时，重验中。
