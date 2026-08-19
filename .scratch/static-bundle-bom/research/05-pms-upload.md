# 05 — 礼包 PMS 上架方案（research 产出）

> 关联票：`.scratch/static-bundle-bom/issues/05-pms-bundle-upload.md`
> 一手来源：`pms_openapi.md`（中汇好泰 PMS 商品录入 API，HAR 逆向）、
> `backend/src/main/java/cn/zimu/fulfillment/connector/zhonghui/`（批量上传服务实现）、
> `backend/src/main/java/cn/zimu/fulfillment/product/Product.java`、`sku/Sku.java`、
> `大者国风上架品（内容详情）-202605更新(1).xlsx`（36 个礼包源文件）。
> 日期：2026-08-19。

## 0. TL;DR 结论

1. **PMS 不能结构化表达「一个商品 = 多个 SKU 组件」**。`CreateGoodsRequest` 是扁平商品模型：
   顶层只有一份 `goodsPrice/goodsNum/supplyPrice`，字段清单里没有组合/BOM/组件概念；
   商品列表返回 `GoodsSummary` 也没有任何组件字段。`AttrAndStock` 最可能是
   **「多规格属性 + 库存」变体模型**（同一商品的规格维度，如重量/包装），不是跨 SKU 组件清单，
   且其元素结构在 HAR 里完全未定义（`items: {}`），无法可靠承载「组件 SKU + 数量」。
2. **推荐方案：礼包按独立 PMS 商品上架**（组合品的替代表达）——
   `goodsName`=礼包名（含规格后缀）、`goodsItem`=goodsBar=礼包条码、`goodsTax`=9、
   `supplyPrice`=大者结算成本、`goodsPrice`=上传时人工填写（overrides，必填）、
   `saleUnit`=件；**组件清单渲染进 `details` HTML + `desc` 文本**。
3. **需要新入口**：礼包无 internal_sku，现有 `ZhonghuiPmsBatchUploadService`（按 `sku_ids` 上传）
   需新增按 `bundle_ids` 上传的入口（`POST /api/v1/zhonghui-pms/bundle-uploads`），
   复用登录/品牌/资质/图片上传/创建/列表校验整条链路。
4. **幂等与查重**：PMS 创建无幂等（`pms_openapi.md` 已知限制 #2）。礼包按 `goodsItem`=条码查重；
   源文件 36 个礼包条码**已验证全部唯一**（15 个 9250 开头、21 个 9260 开头，13 位为主，1 个 14 位），
   与现有 SKU 的 `goodsItem`（`SKU-{provider}-{6位流水}`）命名空间不冲突。
5. **必须人工确认**（见 §5）：`AttrAndStock` 真实业务含义、PMS 是否支持「组合品」类目、
   `goodsItem` 取值规则、非 GS1 条码（9250/9260 前缀、多数不通过 EAN-13 校验位）能否进 `goodsBar`、
   礼包库存语义、售价来源。

---

## 1. 组合品能力结论（带证据）

### 1.1 `CreateGoodsRequest` 是扁平商品模型，没有组合字段

证据（`pms_openapi.md`）：

- `CreateGoodsRequest` 全部属性（L602–760）：`goodsName / thirdId / goodDescr / goodsItem /
  goodsTax / photoStr / details / desc / jdParam / attrFlag / AttrAndStock / banSaleFlag /
  limitAreaTempId / saleLimit / goodsPrice / weight / goodsNum / supplyPrice / goodsBar /
  saleUnit / specsName / noReasonReturnDay / goodsPurchaseMultiplier / certificationType /
  certificationId / jdSkuId / brandId / logisticsCarrier / logisticsCarrierDescription /
  producingArea / specialisedIds / origincountry` —— **没有任何组合品/组件/BOM 字段**。
- 价格与库存是**顶层单值**：一个商品只有一份 `goodsPrice`、`supplyPrice`、`goodsNum`（L686–700），
  没有「每个组件一份」的结构。
- 商品列表返回 `GoodsSummary`（L856–897）也只有 `goodsId/goodsName/goodsItem/goodsSta/goodsNum/
  coverPhoto/goodsPrice/supplyPrice/grossProfit/saleUnit/specsName/goodsBar/updateDateStr`，
  同样无组件字段。PMS 侧数据模型（Web 后台）看起来就是一张**扁平的 goods 表**：
  一个 PMS 商品 = 一条记录 = 一份价格 + 一份库存。

### 1.2 `AttrAndStock`（默认 `[]`）解读：多规格属性+库存，不是组合品

字段定义（`pms_openapi.md` L667–670）：

```yaml
AttrAndStock:
  type: array
  default: []
  items: {}
```

证据链：

- **字段名**：`AttrAndStock` = 「属性 + 库存」——语义上就是每个规格属性组合携带一份库存，
  即**多规格（SKU 变体）模型**：同一商品的不同规格（如 500g/1kg、不同包装）各自挂库存。
  这是国内电商/PMS（尤其京东风味的商家后台）常见的「规格+库存」结构。
- **相邻字段 `attrFlag`（默认 `"0"`）**：一个开关字段，`"0"` 表示无属性模式（HAR 样例即 `"0"`），
  推测 `"1"` 启用属性/规格模式后 `AttrAndStock` 才填值。HAR 中 `AttrAndStock` 恒为 `[]`，
  与 `attrFlag="0"` 一致，说明该商品没有启用规格变体。
- **`items: {}`（元素结构未定义）**：逆向文档的作者在 HAR 里没有见到任何非空 `AttrAndStock`
  样例，无法给出元素 schema。也就是说**无法可靠构造** PMS 期望的属性/库存对象，更别说表达
  「组件 SKU + 数量」。
- **关键推理**：即使 `AttrAndStock` 是多规格模型，它也表达的是**同一个商品的不同规格变体**
  （变体共享商品身份），而不是**多个不同 SKU 的组合**（礼包 = 牛腩块 + 牛腱子 + 羊蝎子……不同商品）。
  一个礼包组件是 5~12 个**不同商品**，规格变体模型没有「组件商品引用」和「每份数量」的位置。
- **结论**：`AttrAndStock` **不能**用来表达礼包结构。它默认 `[]`（不启用）在礼包场景下保持原样即可。

### 1.3 `goodsPurchaseMultiplier` / `jdSkuId`

- `goodsPurchaseMultiplier`（integer, 默认 1，`pms_openapi.md` L718–720）：
  字面「采购倍数/进货乘数」——一件商品对应多少基础单位（如 1 箱=12 件）的采购换算，
  与渠道包装换算同族，**不是组件清单**。礼包保持默认 1。
- `jdSkuId`（string, 默认 `""`，L730–732）：京东 SKU 关联 ID——把 PMS 商品关联到京东侧 SKU
  （本商家通过京东云仓履约，见 `docs/excel-closed-loop-spec.md` / `wayfinder` 票）。
  单个字符串，**无法表达多个京东 SKU**。礼包保持 `""`。
- 附带佐证：`jdParam`（京东商品参数，默认 `"[]"`）同样只是参数位，与组合无关。

### 1.4 结论与替代表达

**结论：PMS 不支持结构化组合品/礼包。** 在一个 PMS 商品里无法表达「礼包 = 组件 A×2 + 组件 B×3」，
任何用 `AttrAndStock` 塞组件清单的做法都是无 schema 依据的猜测，风险高。

**替代表达（推荐）**：礼包作为一个独立 PMS 商品上传——

- 商品身份：`goodsName`=礼包名、`goodsItem`=礼包条码（兼作查重键）、`goodsBar`=礼包条码；
- 内配清单：渲染进 `details`（HTML，必填字段）与 `desc`（文本），审核与消费者可见；
- 价格：`supplyPrice`=大者结算成本；`goodsPrice`=上传时人工填写（数据文件没有售价）；
- 库存：`goodsNum`=配置默认 99 或批次覆盖（礼包本身不单独计库存，见 map.md 已定决策，
  此值是 PMS 侧名义可售量，与组件库存脱节——见 §5 人工确认）。

**风险提示**：

1. **审核接受度**：礼包在 PMS 侧就是一个名称含「礼包」的普通商品。若 PMS 审核对
   「组合品/套装」有专门类目或命名要求（`/api/a1/cms/merchant/goodsFamily` 商品分类树
   存在但 schema 不透明），可能被驳回——需人工确认（§5 #2）。
2. **库存口径**：PMS 的 `goodsNum` 是礼包名义库存，售出扣减的是礼包库存，与组件实际库存
   （internal_skus 的 provider_stock_snapshots）**脱节**。我方履约仍按组件展开（map.md 已定决策），
   所以订单侧不受影响；但 PMS 侧「可售量」需要人工维护口径（限量/定期同步）——人工确认 #5。
3. **组件清单机器不可读**：清单只进 HTML/文本，PMS 无法做结构化拆单/对账。我方订单不走
   PMS 下单（走三平台 Excel / 企微），影响可控。
4. **条码非 GS1**：36 个条码中 31 个不通过 EAN-13 校验位（见 §4.1），若 PMS 校验 `goodsBar`
   格式可能拒收——人工确认 #4。

---

## 2. 推荐上架方案

### 2.1 新入口（按礼包 ID 上传）

现状：`ZhonghuiPmsBatchUploadService.upload(BatchUploadCommand)` 以 `sku_ids` 为输入，
每个 SKU 读 `Sku + Product` 映射为 `GoodsCreateCommand`（`ZhonghuiPmsBatchUploadService.java` L152–183、
L236–295），先落批次意图（`zhonghui_pms_upload_batches` + `zhonghui_pms_upload_batch_items`），
逐商品创建后回写，符合 api-contract §3.5「先持久化意图，Adapter 执行并回写」。

新增（建议，不改动现有 SKU 入口）：

```
POST /api/v1/zhonghui-pms/bundle-uploads
{ "bundle_ids": [1, 2, ...], "overrides": { ..., "goods_price": 399, "supply_price": 320 } }
```

流程（与现有 upload() 同构）：

1. `client.authenticated()` 前置校验（未登录整批拒绝）；
2. 落批次意图：复用 `zhonghui_pms_upload_batches`；逐礼包 PENDING 行需扩表（见 2.3）；
3. 逐礼包执行：
   a. 读礼包主数据（礼包实体 + BOM 组件清单，引用 internal_skus + 数量，见 issue 02 schema）；
   b. **查重**：`queryGoods(goodsItem=条码)` 命中 → 该行 FAILED + 「PMS 已存在该条码商品」，
      跳过创建（PMS 创建无幂等，先查后建）；
   c. `uploadMainImage`（复用 `Product.mainImageRef` → `/upload/imgs` 换公网 URL；无图给 warning）；
   d. `buildBundleCommand()` → `createGoods` → 回写 SUCCESS/FAILED；
   e. 成功后再 `queryGoods` 列表校验取回 `goodsId`/审核状态（best-effort，同现有）；
4. 批次收尾置 COMPLETED。

### 2.2 组件清单渲染进 details / desc

礼包主数据 BOM（bundle_items：组件 internal_skus 引用 + quantity_per_bundle，见 issue 02）
渲染到 `details`（HTML，`CreateGoodsRequest` 必填字段之一）：

```html
<p><img src="{主图公网URL}"></p>
<p>礼包含：</p>
<p>牛腩块500g ×2<br>牛腱子500g ×2<br>…</p>
```

- 组件名取 BOM 关联 internal_skus 的规范名（商品名+规格）；未完成组件映射的行
  （issue 01 未决：165 个组件行仅 70 个带 EMG）回退到礼包源文件的「内配」文本快照，
  并标记 warning「组件清单含未映射行，请复核」。
- `desc` = 礼包描述（Product.description）+ 内配清单文本（HTML 转义，复用现有 `escapeHtml`）。
- 无主图时 `details` 仍渲染组件清单（不能为空——details 是必填），并写 WARNING_NO_MAIN_IMAGE。

### 2.3 批次表扩展（V36 迁移建议）

`zhonghui_pms_upload_batch_items.sku_id NOT NULL`（V35 L21）不适用礼包。建议 V36：

- 增加 `source_type VARCHAR(16) NOT NULL DEFAULT 'SKU' CHECK (source_type IN ('SKU','BUNDLE'))`；
- 增加 `bundle_id BIGINT NULL`，`sku_id` 放宽为 NULL（CHECK：SKU 行 sku_id 非空、BUNDLE 行 bundle_id 非空）。

（或独立新表 `zhonghui_pms_upload_batch_bundle_items`；优先前者，追踪/审计同批次更简单。）

---

## 3. 字段映射表（礼包 → `CreateGoodsRequest`）

| PMS 字段 | 礼包来源 | 说明 / 决策 |
|---|---|---|
| `goodsName` | 礼包名（商品名，含规格后缀，如「牛肉大礼包5200g （BJ）」） | 名称已含重量规格，不再拼接（现有 SKU 流程「名称不含规格时拼接」不适用）；「（BJ）」后缀去留随 issue 01/04 名称规范化结论，建议**上架名与主数据名一致** |
| `goodsItem` | **礼包条码**（13 位，9250/9260 开头） | 替代无 internal_sku 场景的「SKU 编码」；**兼作查重键**。条码唯一性已验证（§4.1）。无条码的礼包（旧 Sheet2 并入的 23 个）回退生成内部编码（如 `BUNDLE-{id}`）|
| `goodsBar` | 礼包条码（与 goodsItem 同值） | 现有 SKU 流程 goodsBar=SKU 条码，礼包沿用「条码」语义；**风险**：9250/9260 前缀非 GS1 注册段、多数不通过 EAN-13 校验位，若 PMS 校验格式可能拒收（人工确认 #4）；被拒则回退 `""` 并反馈 |
| `goodsTax` | 礼包税率 9（源文件 36/36 均为 9） | 恒 9（源文件税率列）；overrides 可覆盖 |
| `goodsPrice` | **overrides.goods_price（必填，人工填写）** | 源文件没有售价，只有「大者结算成本」；**禁止静默用成本当售价**（毛利率失真）。缺省拒绝（复用 PRICE_MISSING 风格），与现有「售价/供货价缺失拒绝」一致（`ZhonghuiPmsBatchUploadService` L242–247）|
| `supplyPrice` | **大者结算成本**（源文件 G 列） | 视为供货价/结算价；overrides.supply_price 可覆盖 |
| `goodsNum` | overrides.goods_num → 配置默认 99 | 礼包不单独计库存（map.md 已定），此值是 PMS 名义可售量；语义缺口见 §5 #5 |
| `saleUnit` | 「件」（配置默认） | 礼包按「份/件」售卖 |
| `specsName` | 留空 `""` | 礼包无规格变体；现有 SKU 流程 specsName=SKU 规格 |
| `photoStr` | 复用 `Product.mainImageRef` → `/upload/imgs` → `"1,<公网URL>"` | 与现有 `uploadMainImage` 一致（L227–234）；无主图给 warning（PMS 可能拒绝创建）|
| `details` | 主图 + **组件清单 HTML**（§2.2） | 必填字段；承载内配清单的表达位置 |
| `desc` | 礼包描述 + 内配清单文本 | 与现有 `joinDescription` 一致，追加清单文本 |
| `certificationType` / `certificationId` | overrides → 配置默认（`ZHONGHUI_PMS_DEFAULT_*`） | **与现有实现完全一致**（现有上传即 overrides→默认，`pms_openapi.md`「当前仍需确认的字段」）；礼包（多原料组合）是否需不同资质 → 人工确认 #7 |
| `thirdId` / `limitAreaTempId` / `brandId` / `logisticsCarrier` / `producingArea` / `origincountry` | overrides → 配置默认 | 同现有（`pms_openapi.md` 待确认字段，正式使用前与中汇确认）|
| `AttrAndStock` | `[]`（不启用） | 无 schema 依据，不承载组合（§1.2）|
| `goodsPurchaseMultiplier` | `1` | 采购倍数，与组合无关 |
| `jdSkuId` | `""` | 京东 SKU 关联位，礼包留空 |
| `weight` | `null` | 与现有流程一致；礼包重量已在名称中 |
| `jdParam` / `attrFlag` / `banSaleFlag` / `saleLimit` / `noReasonReturnDay` / `logisticsCarrierDescription` / `specialisedIds` | 固定默认值 | 同「可以暂时作为默认值的字段」（`pms_openapi.md` L1114–1133）|

现有映射参照：`ZhonghuiPmsBatchUploadService.buildCommand`（L236–295）+ `pms_openapi.md` L1040–1053 映射表。

---

## 4. 幂等与查重

### 4.1 礼包条码唯一性（已验证）

对源文件 `大者国风上架品（内容详情）-202605更新(1).xlsx` 36 个礼包逐条核验：

- **36 个条码全部唯一**（无重复）；前缀：`9260`×21、`9250`×15；
- 长度：13 位×35、14 位×1；
- **EAN-13 校验位：仅 5/36 通过**（9250910000213、9250923000026、9260119000006、
  9260129000003、9260204000003），其余 31 个不通过——`9250/9260` 非 GS1 注册前缀
  （中国为 690–699），属于**内部自编码条码**，不是 GS1 注册 EAN-13。
- 与现有商品档案条码（`data-local/caishixian-sku-mapping.csv` 中为 6977/2100 等 GS1 段）
  **无 9250/9260 冲突**。

结论：**用条码作 goodsItem 查重键是合适的**（唯一、稳定、业务可读）。

### 4.2 查重流程（PMS 创建无幂等的补偿）

`pms_openapi.md` 已知限制 #2：PMS 创建商品无幂等，重试可能产生重复商品，
现有实现靠「goodsItem=SKU 编码」事后查重。礼包流程强化为**先查后建**：

1. 上传前 `POST /api/a1/cms/goodsInfos`（`queryGoods`）按 `goodsItem=礼包条码` 精确匹配
   （现有 `ZhonghuiPmsHttpClient.queryGoods` L214–244 已实现「按 goodsItem 匹配」，可直接复用）；
2. 命中 → 该礼包 FAILED + 提示「PMS 已存在条码 {barcode} 的商品，跳过」；
3. 未命中 → 创建 → 创建后再次列表校验取 goodsId/审核状态。

命名空间说明：现有 SKU 上传 `goodsItem=sku_code`（`SKU-{provider}-{6位流水}`），
礼包 `goodsItem=13 位条码`，**两者不冲突**；同一 PMS 账号下 goodsItem 是否全局唯一仍需
PMS 确认（人工确认 #3）。

---

## 5. 需要向 PMS 侧人工确认的未知项 + 默认假设

| # | 未知项 | 为什么重要 | 默认假设（未确认前按此执行） |
|---|---|---|---|
| 1 | **`AttrAndStock` 真实业务含义**（是否=多规格属性+库存；与 `attrFlag` 的联动规则；元素结构） | 若它真的是「组件 SKU+数量」结构，组合品就有结构化通道 | 多规格属性+库存变体模型，礼包不启用（保持 `[]`）；**不改用** |
| 2 | **PMS 是否支持「组合品/套装」商品形态**，审核对礼包商品的要求（类目、命名、条码） | 决定替代表达能否过审；`goodsFamily` 分类树 schema 不透明 | 按普通商品上传（名称含「礼包」），组件清单进详情；若 PMS 有组合品类目则后续切类目 |
| 3 | **`goodsItem` 取值规则**：长度/字符集/是否全局唯一/能否用 13 位内部条码；HAR 样例 `0000000000000000000` 是否只是占位 | 决定查重键可用性 | 用礼包条码（13 位数字）；PMS 若强制格式，回退内部编码并反馈 |
| 4 | **`goodsBar` 是否校验 GS1/EAN-13 格式**；9250/9260 内部条码（31/36 不通过校验位）能否入 `goodsBar` | 非 GS1 条码可能被 PMS 拒收 | 先按「goodsBar=条码」上传；失败回退 `goodsBar=""` 并保留 goodsItem=条码 |
| 5 | **礼包库存语义**：PMS `goodsNum` 扣减逻辑；组合品是否支持组件库存联动；我方礼包无独立库存实体，PMS 侧可售量如何维护 | 库存口径脱节风险（§1.4 #2） | `goodsNum` 用配置默认 99 或批次覆盖，作为名义可售量；不与组件库存联动 |
| 6 | **售价来源**：`goodsPrice` 由上传时必填，还是 PMS 侧定价流程；礼包售价策略（毛利率） | 决定上架 UI 是否强制填售价 | 上传时人工填写（overrides.goods_price），缺省拒绝；后续可走 PMS 侧改价 |
| 7 | **资质要求**：礼包（多原料组合）的 `certificationType/certificationId` 是否与单品不同 | 组合商品资质可能特殊 | 沿用现有「overrides → 配置默认」逻辑，与单品一致 |
| 8 | **`details` HTML 是否审核可见、组件清单文本放详情是否被接受** | 组件清单是唯一的内配表达位置，若审核只看结构化字段则不可见 | 渲染进 details + desc；若 PMS 要求结构化，回到人工确认 #2 |
| 9 | **`thirdId` / `limitAreaTempId`** 生成规则（现有已挂账的待确认项） | 现有 SKU 上传同样依赖，礼包无差别 | 沿用现有配置默认 + overrides；正式上架前与中汇确认 |

---

## 6. 实施落点（仅指引，不在本票改代码）

- **后端**：`ZhonghuiPmsBatchUploadService` 增加 `uploadBundles(BundleUploadCommand)`；
  新增 `buildBundleCommand(礼包, BOM, mainImageUrl, overrides)` 实现 §3 映射；
  `ZhonghuiPmsController` 增加 `POST /api/v1/zhonghui-pms/bundle-uploads`；
  V36 迁移扩 `zhonghui_pms_upload_batch_items`（§2.3）。
- **前端**：`ZhonghuiPmsUploadModal`（或新弹窗）增加礼包选择 + 售价/供货价输入
  （现覆盖表单无价格字段，需补 `goods_price`/`supply_price`）。
- **依赖**：礼包主数据实体与 BOM（issue 02 schema）、组件名称映射（issue 01）先行落地；
  上架名规范化随 issue 04 结论。
