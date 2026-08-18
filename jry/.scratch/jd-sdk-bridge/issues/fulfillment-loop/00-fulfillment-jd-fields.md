# 00 — 履约记录京东同步字段扩展

**What to build:** 履约记录（fulfillment）承载京东履约闭环所需的同步状态：京东出库单号（erpDeliveryNo）、建单状态推进标记（如 JD_ORDER_CREATED 的 processing_stage）、以及可选的京东侧扩展信息（仓库/店铺/客户编码等配置引用）。这是 01-04 的数据库前置，单独成票让字段形状一次定对，避免各票各自加列互相踩。

**Blocked by:** None — can start immediately

**Status:** wontfix

- [ ] fulfillment 增加京东同步所需的列（erpDeliveryNo 或等效标识 + processing_stage 推进标记），通过增量 migration 落库，不破坏现有数据。
- [ ] 实体与 repository 同步新字段，既有查询/更新路径行为不变（向后兼容）。
- [ ] 若采用 JSONB 单列（如 jd_sync_info），确认脱敏与审计链路对该列的默认处理（不渲染自由 JSON、不泄漏凭据）。
- [ ] 领域词汇与 CONTEXT.md 一致（履约/出库单/运单不混用）；与 01（建单）、02（回填）的字段消费方对齐后完成。

## Comments

- 2026-08-13：此票已被 `jd-fulfillment-loop/01 — 统一 Shipment 级京东出库单边界` 取代。旧方案把 JD 出库状态放在逐行 Fulfillment，违反当前 Shipment 批次模型；保留文件仅用于追溯，不得继续领取或实现。
