# 09 — 京东库存阻断卡片与就地处置抽屉的展示优化

Type: implementation
Status: 已实现并合入 main 工作区（未提交）
Priority: P1
Requested: Jerry 2026-08-28，附发货台截图，逐条列的 4 点里的前 3 点
（第 4 点「一个缺货品换成多个品」是领域变更，另立**票 10**）

## 现场

2026-08-28 14:20，Jerry 实测发彩食鲜的货，发货台「待我人工复核」出现：

```
京东库存判定未通过                                    1 项
RC-JD-STOCK-B9C7F7B5529146B89A0E7631E82CB6D51 项      [就地处置]
牛肉饼(1.2kg)
```

阻断本身是真的（京东仓 118085840 可用 0 件、需要 1 件，`OBSERVED_ZERO`），不是系统误判。
问题全在**展示**。

## 1. 卡片要显示产品的**平台名称**（Jerry 两次点名，这是本票主诉求）

**现状**：卡片显示「牛肉饼(1.2kg)」——这是我们的内部 `products.product_name`。
业务同事对着平台单看，平台上根本没有这个名字。

**平台身份已在库里，无需新增字段。三处已实测逐字一致**：

```
平台原始文件 raw_cells 的「商品名称」  = 子牧进口谷饲牛肉饼1.2KG*1
order_lines.product_name_snapshot      = 子牧进口谷饲牛肉饼1.2KG*1   ← 用这个
source_channel_skus.source_product_name= 子牧进口谷饲牛肉饼1.2KG*1
```

⚠️ **取数陷阱：名字取快照，不要取映射表。**
`source_channel_skus`（id=3, CAISHIXIAN / 2047704）现在也叫这个名，但映射是**可变的**；
`order_lines.product_name_snapshot` 是下单时快照、不会漂。用映射取名，
日后映射一改，历史阻断卡片上的名字就跟着变了，那是错的。

**平台商品编号只存在于映射表**：`source_channel_skus.source_sku_ref` = `2047704`。
它可以取映射（编号本就是映射的身份），但**映射可能不存在**，要能优雅缺省。

**做法**：

- 后端 `fulfillment/ShipmentJdStockCheckService.java:534-552` 的 `observationMap`
  现在只从 `SkuLabel` 取 `sku_code` / `product_name`（都是内部口径）。
  blocker 里已有 `order_line_ids`，用它 JOIN `app.order_lines` 补
  **`source_product_name`**（取 `product_name_snapshot`），
  LEFT JOIN `app.source_channel_skus` 补 **`source_sku_ref`**，
  外加订单级的 `source_channel` / `source_ref`。
- 前端 `pages/workbench/ShippingWorkbenchPage.tsx:367` 现在是
  `blockers.map((b) => b.productName ?? b.goodsNo ?? b.code).join(' · ')`。
  改为**平台名称就是这张卡片显示的名字**——不要写成
  `平台名（内部：内部名）` 那种并列括号，那还是把内部名塞在人眼前。
  用既有 `ProductIdentity`（`pages/product/SkuMappingsPage.tsx:141` 有先例）：
  `name` = 平台名称，`code` = 平台商品编号。
  内部 SKU 编码降级为次要信息（放 `zs-l2` 行或悬停），排障时找得到即可，
  **不与平台名称争夺视觉主位**。
- 平台名称缺失时（老数据、快照为空）才回退到内部名，并明确标注是内部名。
- `source_product_name` 这个键名仓库里已有（`api/types.ts:417/580/732`、
  `presentation/publicReady.ts:184` 标签为「来源商品名称」），**沿用，不要另起名**。

## 2. 案件号被数字粘住（顺带修，同一屏）

**现状**：`ShippingWorkbenchPage.tsx:365-366`

```tsx
{stockCase.caseNo ?? `事项 ${stockCase.caseId}`}
<span className="zs-c">{stockCase.blockers.length} 项</span>
```

`zs-c` 没有左边距，于是真实案件号 `RC-JD-STOCK-…82CB6D5` 后面紧跟计数 `1`，
屏幕上读作 `…82CB6D51 项`——**谁复制案件号都会多带一个 1**。

**做法**：在 `pages/workbench/workbench.css` 给 `.zs-rqi .zs-l1 .zs-c` 加左边距
（现有 `.zs-rqg > summary .zs-c` 在 `:305` 有同类样式可参照），
或用独立元素隔开。案件号本身应可整段选中，不要被计数污染。

## 3. 「就地处置」按钮的样式

**现状**：`ShippingWorkbenchPage.tsx:368-372` 用 `<button className="zs-lnk">`，
`zs-lnk` 在 `workbench.css` 里**没有定义**（grep 无命中），所以它继承成了裸 `<button>`，
截图里就是那个灰底、边界模糊、不像可点的方块。

**做法**：在 `workbench.css` 补 `.zs-lnk` 定义，或改用仓库既有的按钮样式。
要求：看得出可点、有 hover/focus 态、键盘可达（`:focus-visible` 可见轮廓）。
**不要引 AntD Button 破坏这块自绘卡片的视觉一致性**——同屏其它动作也是 `zs-lnk`，
一并统一。

## 4. 就地处置抽屉要显示订单信息

**现状**：`JdBlockerFixDrawer` 在库存模式（`:257`）渲染 `StockBlockerPanel`，
只有阻塞明细和换品控件，**看不到这单是谁的、发哪儿**。运营必须另开订单详情页对照。

**要显示**（Jerry 点名）：平台来源、收货人、手机号、平台购买商品、收货地址。
建议再加平台单号（`source_ref`）——对账时比订单内部号有用得多。

**做法**：

- 数据走既有 `ordersApi.detail(orderId)`。`shipmentsApi.detail(shipmentId)` 已能拿到
  `order_id`，抽屉里已经在调它（`SubstituteSkuAction.tsx:57` 一带有先例）。
- **PII 纪律**：收货人/手机/地址是 PII。`OrderDetailPage.tsx:257-265` 是既有的、
  已获准的展示口径（`detail.receiver.name · phone`、省市区镇+详址拼接）。
  **原样复用那段投影**，不要新开端点、不要 JSON dump 整个 order 对象
  （参见 `presentation/fileOperations.ts:151` 的纪律注释）。
- 🚫 **绝对不要从 `app.raw_import_rows.raw_cells` 取数**。实测该 jsonb 一格里同时装着
  收货人、联系电话、详细地址、供应商编码、子订单号等原始平台字段——
  它是导入留痕，不是展示源。从它取一个商品名，等于把整块 PII 拖进了新的代码路径。
  平台名称走 `order_lines.product_name_snapshot`，收件信息走 `ordersApi.detail` 既有投影。
- 「平台购买商品」列平台原名 + 数量，即该订单全部 `order_lines` 的
  `product_name_snapshot` × `requested_quantity`，**并把当前被阻断的那行高亮**——
  运营一眼看出「这单买了 5 样，卡住的是这一样」。
- 抽屉可能在订单信息未加载完时打开：给 loading 骨架，失败给可重试的 `Alert`，
  **不要让 PII 区域在报错时渲染半截**。

## 不做的事

- 🚫 不做 1:N 换品（票 10）
- 🚫 不改库存判定逻辑本身——京东返回 0 就是 0，不要在前端"兜底"成可发
- 🚫 不新增 PII 字段或新端点；只复用 `ordersApi.detail` 既有投影
- 🚫 不碰 `McpServer.java` / `McpWriteGate.java` / `docs/ops/deploy-runbook.md` / `.claude/`
- 🚫 不执行任何生产 SQL

## Acceptance Criteria

- [ ] **卡片显示的商品名就是平台名称**（`子牧进口谷饲牛肉饼1.2KG*1`），
      带平台商品编号（`2047704`）；内部名/SKU 编码降为次要信息，不与之并列争位
- [ ] 平台名称取自 `order_lines.product_name_snapshot`（快照），**不是** `source_channel_skus`；
      有单测钉死这条（改映射表的名字，历史阻断卡片显示的名字不变）
- [ ] 无平台映射时不报错，编号缺省；快照为空时才回退内部名并标注
- [ ] 案件号可整段复制，不被计数数字污染；计数与编号有明确视觉分隔
- [ ] `.zs-lnk` 有定义：可点感、hover/focus 态、键盘可达
- [ ] 抽屉显示平台来源、平台单号、收货人、手机号、收货地址、平台购买商品清单，
      且被阻断行有高亮
- [ ] PII 只经 `ordersApi.detail` 既有投影渲染，无新端点、无 JSON dump
- [ ] 订单信息加载中有骨架、失败有可重试 Alert，PII 区域不渲染半截
- [ ] 既有换品行为零回归（`SubstituteSkuAction` 逻辑不动）
- [ ] 定向测试 + `npm run typecheck && npm run build`；后端触及类的测试
- [ ] 后端若改了 blocker/observation 的 JSON 形状，同步 `docs/openapi.yaml` 与
      `docs/api-contract.md`（`OpenApiContractConsistencyTest` 门禁）

## Files likely affected

- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdStockCheckService.java`
- `frontend/src/pages/workbench/ShippingWorkbenchPage.tsx`
- `frontend/src/pages/workbench/JdBlockerFixDrawer.tsx`
- `frontend/src/pages/workbench/StockBlockerPanel.tsx`
- `frontend/src/pages/workbench/workbench.css`
- `docs/openapi.yaml`、`docs/api-contract.md`、对应测试

## 工作区纪律

禁 `git add -A` / `commit` / `checkout|restore|stash`。
⚠️ 排在票 06 之后开工，避免与其并行改动打架。

## Risk

低-中。展示层为主，唯一需要小心的是 PII——必须走既有投影，不能图省事把整个
order 对象塞进抽屉。
