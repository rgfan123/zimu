# 04 — 以京东实时库存判定 Shipment 可提交性

**Type:** implementation

**What to build:** 运营人员对已通过预览和 SKU 门禁的 Shipment 执行库存判定，得到可提交或明确阻断的结果；查询失败、数据不完整和库存不足都不会静默放行或擅自改变履约方。

**Blocked by:** 02 — 预览并校验京东出库请求；03 — 建立京东 SKU 映射门禁

**Status:** resolved

**Claimed by:** codex-root

- [x] 库存查询使用请求预览中的同一 goods 标识和精确整数数量，不维护第二套数量换算规则。
- [x] 京东返回库存足够时标记当前预览可提交；库存不足时保持 Shipment 未提交并创建或复用可操作的阻断结果。
- [x] 京东查询失败、响应不可解析、缺少所需仓库行或返回含糊结果时默认阻断并保留诊断码。
- [x] 京东库存不足不自动改变 FulfillmentProvider，也不创建仅适用于我方库存的 ProcurementTicket。
- [x] 判定结果明确说明库存不是预占；真正建单前必须再次校验，JD 最终响应为权威结果。
- [x] 重复判定、失败和恢复均有事件、版本与审计证据，并通过公共应用/API seam 验证。

## Answer

已完成 Shipment 级京东实时库存判定的本地可验收闭环：

- `POST /api/v1/shipments/{shipment_id}/jd-stock-check` 只消费同一 `ShipmentJdOutboundPreviewSnapshot` 的 goodsNo、目标仓和精确正整数京东件数；预览本身不可提交时以 409 `JD_STOCK_PREVIEW_BLOCKED` 拒绝，不调用京东库存。
- 查询在事务外执行；查询后、任何库存快照/事件/版本/审计写入前，重新锁定当前预览并核对包含 ShipmentItem、OrderLine、SKU 活动性/版本、ProviderSku 活动性/版本、goodsNo 与单位换算的 `local_gate_fingerprint`。远端查询期间发生本地变更时以 409 `JD_STOCK_LOCAL_GATE_CHANGED_DURING_CHECK` 失败关闭，不落旧事实。
- 查询失败、响应不可解析、目标仓缺行、重复行、负数/数量关系非法、超出 `NUMERIC(18,3)` 精度和库存不足均默认阻断；缺行保留 `NOT_OBSERVED` 而不伪造零快照，合法显式零则以 `JD_PIECE/JD_ISC_QUERY_STOCK` 只追加落库。
- V14 对新分类列只设置 `UNKNOWN` 默认值，不更新 append-only 历史快照；V16 仅对 `JD_WAREHOUSE + JD_PIECE + JD_ISC_QUERY_STOCK` 开放外部京东只读观测，其他非我方管理的第三方库存仍 fail closed。
- 库存不足只创建/复用 `JD_STOCK_BLOCKED` ReviewCase，不改履约方、不创建 ProcurementTicket、不创建京东出库单；响应、事件和审计均标明 `not_reserved=true`。后续 submit 在 `addSoOrder` 前重跑本库存 seam 并重建当前预览，京东建单响应仍是最终权威结果。
- 旧 `FulfillmentStockDecisionService` 对 `JD_WAREHOUSE` 已在任何库存/采购事实之前以 409 `JD_STOCK_DECISION_RETIRED` 明确 fail closed；托管第三方的原标准库存路径保留。

当前工作树证据：新鲜 Surefire 报告显示 `ShipmentJdStockCheckApiTest` 9/9、`ProviderStockSnapshotMigrationTest` 1/1，均为 0 failure / 0 error；`FulfillmentStockDecisionServiceTest` 中 3 个现行用例全绿，3 个被新 Shipment 语义取代的旧 JD 用例按理由明确跳过。公开 HTTP + 真实 PostgreSQL + 确定性 Mock JD seam 覆盖通过、同 key 重放、查询失败、缺目标仓、非法/超精度数量、资格并发变更、阻断后恢复、显式零和无采购副作用。

最终双轴复核为 Standards P0/P1/P2 = 0、Spec P0/P1/P2 = 0，因此本地 Mock + 真实 PostgreSQL 范围标记为 `resolved`。本票没有执行真实京东读/写请求，不代表生产目标、权限或 `addSoOrder` 已验收。仅保留一项不阻断 P3：旧 `FulfillmentStockDecisionService` 仍保留已不可达的 JD 实现与禁用用例，后续可单独删除这部分死代码。
