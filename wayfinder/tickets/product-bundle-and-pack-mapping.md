---
label: wayfinder:grilling
title: 组合品（礼包）与渠道包装换算
status: open
claimed_by: 
blocked_by: []
parent: wayfinder:map
---

# 组合品（礼包）与渠道包装换算

## Question

真实商品目录（`京东商品编号.xlsx`，2026-08-11 由用户提供）暴露了两个当前 schema 与状态机都没有位置安放的结构。这两个都不是边角情况——礼包是企业微信渠道的主力商品，包装换算影响 PRD §21 的核心口径。

## 决策点

### 1. 组合品 / 礼包 BOM

- Sheet2 有约 25 个礼包（牛羊礼包5800g、牛羊肉超级豪华大礼包10000g、中秋礼包1、清真牛羊肉生鲜礼包2400g …），每个由 **6~12 个 EMG × 数量** 组成；
- `db-schema-design` Q1 定的 19 张表里没有组合品表，`CONTEXT.md` 也没有「组合品」这个词；
- 待定：
  - 建 `product_bundles` / `bundle_items`（bundle_sku_id + component_sku_id + quantity），还是复用 `internal_skus` 自关联？
  - 一行礼包 OrderLine 在履约时炸开成 N 个 Fulfillment 行，还是保留一个 Fulfillment 而在出库单里炸开？（影响状态机的行级聚合：礼包缺一个成分算不算整单缺货）
  - 库存校验：礼包可用量 = 各成分可用量 / 各自需求数量 的**木桶短板**，这个计算放哪层；
  - 数据中台口径：商品分析按礼包统计还是按成分统计？两者都要还是二选一？（原型 D 的热力图目前把礼包当独立商品行，标了「组合品」）

### 2. 渠道包装换算

- 同一京东 SKU 在不同渠道的包装数量不同：羊棒骨 `EMG4418819505546` 彩食鲜 `500g*2`（数量 2）/ 聚福宝 `500g*3`（数量 3）；肩胛烤肉片 `EMG4418904463768` 是 4 vs 6；
- 即渠道映射不是「名称 → 编码」的纯映射，还带一个**换算数量**；Q1 定的 `channel_skus` 与 `aliases` 都没有这一列；
- 待定：
  - 换算数量放 `channel_skus` 一列，还是独立的换算表（可能出现一对多）；
  - PRD §21「实际商品数量」到底是**渠道件数**还是**京东件数**还是**重量**？三个渠道口径不统一时，数据中台的「实发量」按哪个口径汇总——这个不定死，渠道分析的双口径就是错的；
  - 目录里飞象 / 企业微信两列为空，这两个渠道的商品名与包装数量还没有；是走同一张映射表留空，还是这两个渠道本来就不做商品级映射。

## 输入

- `京东商品编号.xlsx`（Sheet1 渠道别名映射 / Sheet2 礼包 BOM / Sheet3 供应商目录「易和天下」/ Sheet4 聚福宝目录）
- 已在原型里做过呈现层验证：[frontend/prototype/dashboard-prototype.html](../../frontend/prototype/dashboard-prototype.html)

## 与其他票的关系

- `db-schema-design`（zed-main 认领中）Q1 表清单需要据此增补——本票结论出来前，商品域 schema 不宜定稿；
- `order-state-machine`（已关闭）的行级推进与最差聚合，在礼包炸开后需要复核；
- B2 后端构建、B5 数据中台都依赖本票。

## Resolution

（未解决）
