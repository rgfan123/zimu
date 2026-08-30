# 06 — 价格收敛：成本表为唯一真源，删 products 三列，编辑界面对齐

Type: implementation
Status: 已实现（工作区未提交）
Priority: P0
Requested: Jerry 2026-08-28「直接覆盖原来的价格…将多来源收敛成唯一数据表 并删去旧的 然后优化编辑界面 和当前能力对齐」

> 本票**推翻**了此前「保留两套价格并加来源标注」的方案。不要再实现那个。

## 背景：价格有 4 个来源，现在收敛成 1 个

| # | 来源 | 现状 | 处置 |
|---|---|---|---|
| A | `authoritative-jd-sku-catalog.json`（27 个定价条目） | fill-null 回写 `skus`，不一致时报 drift | **票 07** 切断 |
| B | `app.products.purchase_price / retail_price / other_cost` | 85 行里只有 2 行有值，且**与对应 SKU 的值逐字节相同**（id 62/63 ↔ SKU-TP-000063/64，均 84.00/118.00） | **本票删列**（零信息损失） |
| C | `app.skus.purchase_price / retail_price` | 29 行有值，但来自 A，与成本表对不上 | **保留 → 成为唯一系统真源** |
| D | `app.product_archive_sheets`（成本表 110 行 × 47 列） | 最新最全，用户认定的权威 | 保留为**不可变的导入快照**（有 `source_file_sha256` 指纹，改它会让快照对原文件说谎），只作为 C 的取数来源 |

**为什么唯一真源是 C 而不是 D**：D 是带文件指纹的导入留存表（`UNIQUE (source_file_sha256, row_no)`），
必须保持不可变才可审计；把它当可编辑主数据会破坏幂等键的语义。所以 D 供数、C 存数、C 可编辑。

### 已实证的关键事实（别再重新论证）

- **规格 1:1，无需换算**：`skus.specification` 与成本表 C 列逐行精确相等（500g↔500、150g↔150、1.2kg↔1200）。
  V63 注释里担心的「份(500g) vs 500g*2」口径问题**在实际挂接行上不存在**。
- **旧价格不是从成本表推出来的**：系统进货价 / 成本表各列的比值在 1.46 ~ 8.47 间乱跳，无任何稳定倍数。
  典型荒谬值：上脑牛排 150g 记进货价 84.00，而成本表说线下供货成本 9.92、售价 19。
- **列映射**（Jerry 2026-08-27 亲自拍板，钉在 V63 注释里，不要再猜）：
  - 进货价 `purchase_price` ← **AI「线下供货成本/份」**（权威成本列）
  - 零售价 `retail_price` ← **AJ「售价」**（不含运费售价）
- 覆盖面：90 个 SKU 中 75 个有成本表行，AI 可用 72、AJ 可用 74。
  **实测落库口径（对账按这个，不要按 73）**：
  - 有进货价 29 → **75** = 72（AI 覆盖）+ 1（牛肉饼 AI=0，保留旧值 109.50）+ 2（TP，无成本表，保留 84.00）
  - 有零售价 29 → **77** = 74（AJ 覆盖）+ 1（牛肉饼保留 158.00）+ 2（TP 保留 118.00）

## 数据侧 ✅ 已完成（2026-08-28，Jerry 执行）

`/Users/jerry/zimu-work/inbox/price-convergence-20260828.sql` 已在生产执行完毕，三段自验全 0：

- AI 为正数的行，`purchase_price` = `round(AI,2)` —— 不一致 0 行
- AI 非正数的行保持原值 —— 被误改 0 行（牛肉饼 1.2kg 保住 109.50）
- 无成本表的 SKU 未被触碰 —— 被误改 0 行（TP 那 2 个保住 84.00/118.00）
- 有进货价 29 → **75**，有零售价 29 → **77**
- 备份：`app.zz_price_conv_skus_20260828`（旧值完整，按 id 可还原）

**收敛暴露的 4 个毛利倒挂 SKU 均有正当解释，不是数据错误**：
SKU-JD-000067/000069（成本表 B 列 = **停产**），SKU-JD-000081/000082（窦清源，B 列 = **新品**，研发中未定价）。
成本表全 110 行的 AS 毛利率无一为负——对这 4 行原表就是留空的，库内口径与原表一致。

Codex **不要**执行任何生产 SQL。本票只做代码。

## 本票范围

### 1. 迁移 V78：删 `products` 三个价格列

> ⚠️ **迁移号**：生产在 V72，但同事的集成分支 `jry/integration-20260828` 已占用 **V73 和 V77**
> （四红修复）。本票用 **V78**。落笔前先 `ls backend/src/main/resources/db/migration/` 核对最大号，
> 取「最大号 + 1」，不要照抄本文的数字。

```sql
ALTER TABLE app.products
    DROP CONSTRAINT products_purchase_price_nonnegative,
    DROP CONSTRAINT products_retail_price_nonnegative,
    DROP CONSTRAINT products_other_cost_nonnegative,
    DROP COLUMN purchase_price,
    DROP COLUMN retail_price,
    DROP COLUMN other_cost;
```

（约束名以 `V30__add_product_archive_fields.sql:21-26` 为准，落笔前核对实际名字。
生产已到 V72，**新迁移必须从 V73 起**。迁移头部写清「为什么能删」：这两行值是 skus 的重复副本，
价格唯一真源已收敛到 `app.skus`，取数来源是 `app.product_archive_sheets`。）

### 2. 后端

- `product/Product.java`：删 `purchasePrice / retailPrice / otherCost` 字段与 getter/setter，
  并删掉 `:55` 那句「毛利 = 零售价 - 进货价 - 其他成本」的注释（它现在描述的是 SKU 的事）
- `product/ProductWrite.java`、`product/ProductPatch.java`：删对应入参与 `*Present` 标志
- `masterdata/MasterDataService.java`
  - `product()` 投影（`:912-915`）：删 `purchase_price / retail_price / other_cost / margin` 四项
  - `sku()` 投影（`:946-949`）：删 `product_purchase_price / product_retail_price / product_other_cost` 三项；
    **`margin` 改用 SKU 自己的两列计算** → `marginText(value.getRetailPrice(), value.getPurchasePrice(), null)`
    （这修掉一个既存 bug：此前 `sku.margin` 取的是 product 的价格，而 product 只有 2 行有值，
    导致 88 个 SKU 的毛利恒为空/错）
  - `marginText`（`:1138-1139`）：`otherCost` 参数保留但允许 null 视为 0，或改成两参重载——按你觉得更干净的来，
    但注释要同步（现注释说「任一输入缺失则视为未定价」，改完要说准）
- 校验：`ProductWrite/Patch` 相关的价格校验测试要跟着删或改

### 2b. MCP 输出形状会跟着变（务必在报告里点名，不要额外改 MCP 代码）

`mcp/McpDomainReadTools.java` 的 `list_products`（约 `:361`）直接 `json(masterData.products(...))`，
走的就是 `product()` 投影——删掉四项后，**`list_products` 的返回里会少掉
`purchase_price / retail_price / other_cost / margin`**。这是预期结果，不需要改 MCP 代码。

对照：`get_sku` / `search_skus`（`:552-570`）吐的是 `SkuDetail` 的 **SKU 级**价格，
本票不动，行为不变。

**不要碰 `McpServer.java` 与 `McpWriteGate.java`**（别的会话的在制品）。

### 3. 前端

- `pages/product/ProductsPage.tsx`
  - 删 `createFields` / `updateFields` 里的 `purchase_price / retail_price / other_cost` 三项（`:113-131`、`:161-179`）
  - 删「毛利」列（`:81-85`）——它取 product 的 `margin`，删列后恒空
  - 页头或空态加一句指路：**商品价格在「商品档案（SKU）」维护，来源为成本核算表**
- `pages/product/productArchiveFields.ts`：`buildProductCreateBody` / `buildProductUpdateBody` 删三个价格键（`:100-102`、`:120-122`）；
  若 `marginLabel`（`:135`）只被 ProductsPage 用，一并删
- `api/types.ts` / `api/endpoints.ts`：删 product 侧价格字段类型（`endpoints.ts:284-286`、`:300-302`）；
  **SKU 侧的 `purchase_price / retail_price` 保留**（`:345-346`、`:372-373`）

### 4. 编辑界面对齐（`pages/product/SkusPage.tsx`）

`updateFields`（`:125-143`）现在只有 5 项，`createFields`（`:100-123`）有 10 项。补齐：

- **加「单位」**（`unit`）：SKU 自有字段、NOT NULL、新建时必填却不可改，属明显缺口
- **两个价格字段的 label 与提示改成反映新口径**：
  - 进货价 → `进货价（元）`，提示改为「来自成本核算表 AI 线下供货成本/份，可人工覆盖」
  - 零售价 → `零售价（元）`，提示改为「来自成本核算表 AJ 售价，可人工覆盖」
  - 两个字段**保持可编辑**（收敛后它们就是真源，人工修正是正当操作）
- **商品名/品类指路**：它们在 `products` 上，本票不搬进 SKU 弹窗；
  但弹窗内加一行说明 + 指向「管理商品名称」的链接，别让用户以为改不了

## 不做的事

- 🚫 不执行任何生产 SQL（数据覆盖由主导者做）
- 🚫 不改 `app.product_archive_sheets` 的表结构或数据（它是带指纹的不可变快照）
- 🚫 不动 `authoritative-jd-sku-catalog.json` 与 catalog 包（那是**票 07**）
- 🚫 不把 `products` 的商品名/品类搬进 SKU 编辑弹窗
- 🚫 不动成本表 47 列的展示、列设置与导出

## Acceptance Criteria

- [ ] 迁移（V78 或当时的最大号+1）干净删除 products 三列 + 三个约束；迁移头注释说明「为什么能删」
- [ ] 后端零残留：`grep -rn "otherCost\|OtherCost" backend/src/main` 只剩 catalog 包之外无命中
- [ ] `sku.margin` 改用 SKU 自己的进货/零售价，且有单测覆盖「两价齐全→有毛利」「缺一→null」
- [ ] ProductsPage 无价格字段、无毛利列，且有指向 SKU 页维护价格的说明
- [ ] SkusPage 编辑弹窗可改「单位」；两个价格字段提示写明成本表来源列；商品名/品类有跳转指路
- [ ] 既有编辑行为零回归：保存逻辑、`expected_version` 乐观锁语义不变
- [ ] `npm run typecheck && npm test && npm run build` 全绿
- [ ] 后端触及测试通过；`OpenApiContractConsistencyTest` 绿（product 价格字段若在 openapi 里有声明，同步删）
- [ ] `docs/openapi.yaml` / `docs/api-contract.md` 同步

## Files likely affected

- 新 `backend/src/main/resources/db/migration/V78__drop_product_price_columns.sql`（号以实际最大号+1 为准）
- `backend/src/main/java/cn/zimu/fulfillment/product/{Product,ProductWrite,ProductPatch}.java`
- `backend/src/main/java/cn/zimu/fulfillment/masterdata/MasterDataService.java`
- `frontend/src/pages/product/{ProductsPage.tsx,SkusPage.tsx,productArchiveFields.ts}`
- `frontend/src/api/{types.ts,endpoints.ts}`
- `docs/openapi.yaml`、`docs/api-contract.md`、对应测试

## 工作区纪律

多会话并行；`jry/wecom-card-closed-loop` 的 HEAD=daba519 已移交同事做合成部署。
- **禁** `git add -A` / `git commit` / `git checkout|restore|stash`
- **不碰**：`backend/.../mcp/McpServer.java`、`McpWriteGate.java`、`docs/ops/deploy-runbook.md`、`.claude/`
- 只留工作区改动，提交时机由主导者与同事协调
- ⚠️ **同事正在构建合成部署版**（集成分支 `jry/integration-20260828 @ a6375b70`，含四路 + 四红修复）。
  本票的改动**一律排在那次发布之后**，不得混入其集成分支

## Risk

中。删生产列不可逆，但已实证那 2 行是 skus 的重复副本、零信息损失，且迁移前主导者会做整表备份。
`margin` 换算基准变更会改变 API 响应值——这是**修 bug**（此前 88 个 SKU 毛利恒错），需在 PR 说明里点名。
