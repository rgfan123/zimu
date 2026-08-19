# 02 — 静态礼包主数据 schema 设计

Type: research
Status: closed
Blocked by: None — can start immediately

Label: wayfinder:research

## Question

静态礼包主数据在现有 PostgreSQL schema（`docs/schema.sql` / `docs/schema.md`，当前约 38 表）里怎么落？

已定方向（grilling）：礼包 = **Product + BOM**——礼包作为商品族（复用现有 `products` 表还是新建 `product_bundles`），组件清单新建（`bundle_items` 或类似），组件引用现有 `internal_skus` + 数量；礼包本身**不创建 internal_sku**。

需要调研并给出建表方案：
- 现有 `products` / `internal_skus` / `source_channel_skus` 的结构与约束（`docs/schema.md` §3.1 商品域），礼包字段（条码、税率、大者结算成本、名称/别名、上架状态）放哪张表；
- 组件表结构（bundle_id、sku_id、quantity_per_bundle、排序、EMG 编码快照还是引用）与约束（同 provider、数量正整数、缺 EMG 的表示）；
- 与现有 `order_line_components`（当单礼包快照）的关系：静态礼包命中 → 下单时如何从主数据快照进 `order_line_components`，需要哪些新字段（如 source_bundle_id）还是直接复用；
- 触发器/防御约束清单（参照现有 67 个触发器风格）：礼包删除保护（已有订单引用）、BOM 修改是否留版本（下单快照后主数据可改）；
- 分析视图（`analytics.v_product_daily` 等）是否需要为静态礼包加列（如按礼包聚合 vs 组件展开——已定按组件展开，需确认视图是否要新增礼包维度）；
- 输出：**DDL 草案**（含约束说明）+ 与现有 schema 的差异清单。

## Assets

（research 子代理产出：`.scratch/static-bundle-bom/research/02-bundle-schema.md`）

## Resolution

7 个决策点全部定稿，净增量相对活库 53 表：**+3 表、+1 列（`order_lines.bundle_id`）、+3~4 触发器函数、+4 索引、（可选）+1 分析视图**：

1. **新建 `app.product_bundles`**（不复用 `products`）：礼包商品族 + 条码/税率/大者结算成本/上架状态（DRAFT→ACTIVE→INACTIVE，组件未齐不可 ACTIVE），独立生命周期；
2. **新建 `app.bundle_items`**：`bundle_id + sku_id(→skus, NOT NULL) + quantity_per_bundle(正整数) + sort_no + emg_code_snapshot(缺=NULL) + source_text_snapshot`；`UNIQUE(bundle_id,sort_no)`、`UNIQUE(bundle_id,sku_id)`；同 provider 由触发器维护并自动填充礼包级 provider；
3. **新建 `app.bundle_aliases`**（镜像 `sku_aliases`）：名称/条码别名供识别命中；
4. `order_lines` 加 `bundle_id`（静态命中非空、当单定制 NULL）；`order_line_components` 不加列，快照一致性复用现有 `trg_component_validation`；
5. **不引入 `bundle_versions`**：直接允许改主数据 + 下单快照隔离；删除保护用 FK RESTRICT + 触发器；
6. 分析视图：`v_product_daily` 加 `bundle_id/bundle_name` 列（组件口径不变），可选 `analytics.v_bundle_daily`；
7. 差异清单与需同步文档（schema.md/schema.sql/schema-smoke.sql/api-contract.md/CONTEXT.md）已列出；落地为 Flyway V36 增量迁移。

上游注意：源文件 165 组件行仅 70 带 EMG，但 **EMG 缺失 ≠ SKU 缺失**——主方案 `sku_id NOT NULL`，备选（sku_id 可空 + ACTIVE 前置「全部组件已映射」）已在 DDL 注释给出，最终取舍待 01 票合并定稿（已定稿：16 个组件缺 EMG 导入待补，其余 319 个有 EMG）。
