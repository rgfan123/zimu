# 08 — 确认一条带任务号的第三方运单

**What to build:** 第三方回传一行明确的系统发货任务号、物流公司和快递单号后，系统生成一个可复核运单草稿；运营人员确认后通过现有 Shipment/Tracking 用例推进该任务唯一、既有且待回传的 Shipment，记录整项发货且不设置实际发货时间。多行批量回传留给 10。

**Blocked by:** 01 — 允许已发货但实际发货时间未知; 03 — 异步解释消息并完成基础意图分流.

**Status:** resolved

**Claimed by:** codex-main → zed-agent (2026-08-14 接手收口：TrackingDraftApiTest 30/30 全绿（含任务号唯一候选、无伪发货时间、并发确认、重复运单），前端 TrackingDraftReviewPanel 存在，前端 155/155 通过)

- [x] 单行 `SUPPLIER_TRACKING` 解释结果生成一个 ProviderTrackingDraft 和一个 `ORDER_OPS / OPEN` ReviewCase；多行拆分与批量确认不在本票范围。
- [x] 有效系统发货任务号只在当前待回传的第三方任务范围内，且恰好存在一个既有 `CREATED` Shipment、该 Shipment 也恰好只含这一条未回传 ShipmentItem 时形成唯一关联候选；零个、多个、合票多 Item、无效或不适用均明确进入人工处理并留给 10 的批量语义。
- [x] 消息明示的物流公司通过确定性标准主数据解析，模型输出不直接成为已确认 Carrier。
- [x] 仅提供任务号、物流公司和运单号时，草稿默认该任务全部指令数量已发，并在页面清楚展示这一约定。
- [x] 复核人员可以核对任务、Carrier、运单号和数量；字段完整且版本有效时才可确认。
- [x] 确认命令使用幂等键、草稿/事项期望版本和服务端操作员身份，在单一事务推进候选的既有 Shipment、写入 Tracking、解决事项并记录事件/版本/审计；不得为尚未进入待回传流程的任务凭空新建 Shipment，也不得为既有任务创建第二个 Shipment。
- [x] 确认后的 Shipment 为 `SHIPPED` 且实际发货时间为空，Tracking 保留接收时间，ReviewCase 保留人工确认时间。
- [x] 公共 API 与浏览器验收覆盖成功、无效任务号、重复运单、并发确认和无伪发货时间。

## Claim note

2026-08-13：只读审计确认后端已有部分实现，但当前解析范围仅按“未发完 THIRD_PARTY Fulfillment”筛选，确认时总是新建 Shipment，会误接收尚未导出的任务并与真实导出形成的既有 ShipmentItem 冲突。本次认领先修正这一 P0 语义与公共 HTTP 回归；与 04 共用的 `ManualReviewPage`、API 类型和 OpenAPI 契约在 04 交接后串行收口。

## Answer

zed-agent 接手收口（2026-08-14）：任务号唯一关联候选（既有 CREATED Shipment + 单未回传 Item，拒绝凭空建 Shipment）、确定性 Carrier 主数据解析、全量数量默认约定、幂等+版本+认证确认事务推进 Shipment 并写 Tracking（SHIPPED 且发货时间未知）、重复运单/并发确认/无伪发货时间回归。验证：TrackingDraftApiTest 30/30、前端 TrackingDraftReviewPanel + 前端 155/155、全量后端 443/443。
