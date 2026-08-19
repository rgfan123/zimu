# 04 — 静态礼包命中后的履约展开边界

Type: grilling
Status: closed
Claimed by: zed-main (2026-08-19)
Blocked by: [02 — 静态礼包主数据 schema 设计]（02 已 closed，阻塞解除）

Label: wayfinder:grilling

## Question

订单识别命中静态礼包后，从 OrderLine 到履约导出的改造边界是什么？哪些现有 CustomBundle 机制直接复用，哪些要改？

已定方向（grilling）：
- 订单行仍用 `CUSTOM_BUNDLE` 类型但引用主数据礼包；**下单时快照 BOM** 到 `order_line_components`；
- 当单定制礼包保留不动（并存）。

需要决策：
- 命中静态礼包与当单定制礼包在 OrderLine 上的区分（加类型/来源字段，还是同一 CUSTOM_BUNDLE 不加区分）；
- 快照时机：识别命中时即快照（订单草稿阶段）还是订单确认时快照；BOM 主数据在识别后、确认前被改怎么办；
- 履约导出、完整份数校验、同盒发出、采购工单组件展开——是否全部复用现有机制（`docs/excel-closed-loop-spec.md §6.2`、`docs/schema.md §3.3`），还是静态礼包需要新分支；
- 组件缺 EMG/未映射 internal_sku 时礼包订单行的处置（整礼包 NEED_REVIEW 还是按组件）；
- 分析口径：礼包按组件展开（沿用旧决策），是否需要为静态礼包新增「按礼包维度」的视图或筛选。

## Assets

（HITL grilling——结论记入本票 Resolution；依赖 02 的 DDL 草案）

## Resolution

五个决策点全部定稿（2026-08-19，grilling 与用户逐题确认）：

1. **订单行区分：`order_lines.bundle_id`**——静态礼包命中时非空（指向 `product_bundles`），当单定制礼包为 NULL，SINGLE 行强制 NULL；`line_type` 不加值（仍统一 `CUSTOM_BUNDLE`），履约下游零改动，bundle_id 只作溯源字段（复核页可展示「来自静态礼包」并跳主数据）。
2. **快照时机：订单确认时快照**——识别命中（Excel 解析/企微草稿）只记录 bundle_id 引用；订单确认（ImportBatch 确认/草稿人工确认成单）时从主数据取最新 BOM 快照到 `order_line_components`；识别后、确认前主数据被改不影响快照正确性；`validate_static_bundle_snapshot` 触发器兜底确认事务内并发修改。
3. **履约复用边界：全部复用，零新分支**——导出展开（组件带礼包分组标识同盒）、完整份数校验（回传按 quantity_per_bundle 整除）、采购工单组件明细、来源回填全部走现有 CUSTOM_BUNDLE 机制；订单行层面静态礼包与当单定制结构同构，下游不区分来源。
4. **缺 EMG/未映射组件：行级 NEED_REVIEW**——沿用 `createBundleLine` 现状（组件缺映射 → 该礼包订单行停 NEED_REVIEW + 原因码，同单其他行不阻塞）；补录 provider_skus 后重试（沿用 `ShipmentJdSkuMappingGateService`）；主数据侧用「组件未全映射不能置 ACTIVE」防上游（02 status 门禁）。
5. **分析口径：最小方案**——`v_product_daily` 加 `bundle_id/bundle_name` 两列（组件展开口径不变，可溯源到礼包）；暂不建 `v_bundle_daily`（按礼包聚合的独立分析留待实际需求）。

与 02 的关系：本票确认 02 的 `order_lines.bundle_id` 与 `validate_static_bundle_snapshot` 设计成立；`sku_id NOT NULL` 主方案成立（01 已定稿：319/336 组件有 EMG，16 个缺 EMG 导入待补由主数据侧管理，不阻断订单识别）。
