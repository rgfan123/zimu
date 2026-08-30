# 10 — 一个缺货品换成多个品（1:N 换品）

Type: **design-first**（先出设计，不要直接开写）
Status: needs-design
Priority: P1
Requested: Jerry 2026-08-28「换品不止有一个 可以是多个」，明确选定语义为
**一个缺货品换成多个品**（如 牛肉饼1.2kg 缺货 → 换成 2×600g，或换成「600g + 牛肉馅500g」）

## 为什么这张票不能直接丢给实现

现有换品是**严格 1:1**，而且不是"接口没做"，是**库层面钉死的**：

```
fulfillments_order_line_id_key  UNIQUE (order_line_id)
```

一条订单行只能有一个履约单元，实测 0 行例外。所以「一换多」加不进现有模型，
必须先决定往哪走。

## 已勘明的现状（不要重新论证）

**1:1 换品做了什么**（`order/OrderLineSkuSubstitutionService.java:76-140`）：

1. 校验订单未发出（`SHIPPED/SYNCED/CLOSED/CANCELLED` 一律拒绝）+ 乐观锁 `expected_order_version`
2. 删旧 `shipment_items` → 删旧 `fulfillments`（**唯一允许删除的窗口**，见该类注释）
3. `UPDATE order_lines SET sku_id, sku_code_snapshot` ——
   **只动这两列**；`product_name_snapshot` 等来源快照是**渠道血缘，刻意不碰**
4. 建新 fulfillment，把 `shipment_items` 按**原样的 `instructed_quantity`** 挪过去
5. 追 `ORDER_LINE_SKU_SUBSTITUTED` 事件，bump `orders.lock_version`

**bundle 机制现状**（可能可复用，也可能是陷阱）：

- `order_lines.line_type` 只有 `SINGLE`(20) 和 `CUSTOM_BUNDLE`(8) 两种
- `CUSTOM_BUNDLE` 行是**单行、`sku_id` 为空、`sku_code_snapshot` 为空、挂 `bundle_id`**，
  `product_name_snapshot` 是礼包名（如「子牧牛肉豪华大礼包6000g（BJ）」）
- 也就是说：**系统已有「一个订单项 → 多个实物 SKU」的概念**，展开发生在订单行以下
- ⚠️ 但 bundle 行同样受 `UNIQUE(order_line_id)` 约束，所以它的展开**不是**靠多个
  fulfillment 实现的。**设计的第一件事就是查清 bundle 到底怎么展开到实物**
  （查 `ProductBundle` / bundle 组件表 / `shipment_items` 的构造），
  再决定 1:N 换品是复用它还是另起。

## 设计必须回答的问题

1. **落在哪一层**
   - (a) 拆订单行：一行变 N 行 —— 但来源快照是渠道血缘，N 行都声称来自同一平台行，
     对账（`OutboundReconPage`、中汇回传）怎么合回去？
   - (b) 放开 `UNIQUE(order_line_id)`，一行挂 N 个 fulfillment —— 改动面小但动的是核心约束，
     所有假设 1:1 的读路径都要审
   - (c) 复用 bundle：把换品结果表达成一个"临时组合"，走已有的展开通路
   - **倾向 (c) 或 (b)，(a) 最伤对账**，但结论要由勘查 bundle 展开机制之后给出

2. **数量怎么配**：平台买的是 1 件 1.2kg。换成 2 件 600g，
   `requested_quantity` / `instructed_quantity` / `source_quantity_snapshot` 各是多少？
   谁是"客户买的"、谁是"我们实际发的"，必须在模型里分得开。

3. **渠道血缘怎么守**：1:1 换品刻意不碰 `product_name_snapshot`。1:N 后，
   平台仍然只买了一个「子牧进口谷饲牛肉饼1.2KG*1」——这个事实**必须仍然可读**，
   否则回传给平台的对账会对不上。

4. **回传与结算**：中汇/彩食鲜回传按什么口径？换品前后金额差异如何记账？
   （价格现在的唯一真源是 `app.skus`，取数自成本表，见 [[price-single-source-of-truth]]）

5. **可逆性**：1:1 换品是"删旧建新"。1:N 之后如果要撤销/再换，怎么回退？

6. **幂等与并发**：现有 `SCOPE = "order_line.substitute_sku"` 的幂等键与
   `expected_order_version` 语义，在一次提交 N 个替代品时怎么定义？

## 建议流程

按用户全局规则，这类"迷雾中的较大改动"走 Matt Pocock 流程：
`~/.agents/skills/wayfinder/SKILL.md` 摸清地形 → `to-spec` 出规格 → `to-tickets` 拆票 →
`implement`。**不要跳过直接让 Codex 写**——这张票动的是订单/履约/发货三层的核心约束，
写错了对账会静默错账。

## 前置依赖

- 票 09（展示优化）可以先做，与本票不冲突
- 本票开工前建议先勘查 bundle 展开机制并把结论回填到本票

## 不做的事

- 🚫 不在设计定稿前改 `UNIQUE(order_line_id)` 或任何订单/履约表结构
- 🚫 不执行任何生产 SQL

## Risk

高。触及订单行、履约单元、发货明细三层的核心不变式，且下游有对账与回传。
好在有天然护栏：现有换品已拒绝一切已发出订单，1:N 也应继承这条。
