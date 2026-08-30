# 聚福宝 63 个平台商品映射方案

日期：2026-08-29
仓库 / 分支：`/Users/jerry/zimu-work/integration` @ `jry/integration-20260828`
生产库（**全程只读，未发出任何写语句**）：`zimupc` / `zimu-fulfillment-postgres-1` / `fulfillment_hub`
输入：`/tmp/111.xlsx`（聚福宝供应商后台商品导出，63 行 × 25 列）、`/tmp/jfb-catalog.json`（精简版）
前置文档：`docs/research/jufubao-mapping-archive-2026-08-27.md`（当时因缺 `product_id` 落 0 条；本文正是它 §1.5 所缺的那份物料到位后的续篇）

---

## 结论速览

| 项 | 结论 |
|---|---|
| SKU 映射键 | **平台「商品ID」（`product_id`，8 位数字）**。拉单链路与文件链路**一致**。 |
| 礼包映射键 | **两条链路不一致，且生产现存那条映射在两条自动链路上都不生效**——详见 §1.3，这是本次最重要的发现。 |
| 「不映射」判据 | **B 列供货商名称不可用**（63 行全是「京诚乾元」＝我们自己的供货主体）。真正的判据是 **Q 列产品品牌**。 |
| 可确定落库 | SKU 映射 **21 条**（另 1 条已存在），礼包映射 **1 条**（补 ID 键）。 |
| 待确认 | **18 行**（13 行礼包缺主数据档案 + 5 行 SKU 侧缺规格/缺档案/命名冲突）。 |
| 不映射 | **22 行**（大江生鲜 19 + 乔府大院 1 + yosibaby/羊小贝 2）。 |

---

## 1. 键语义结论（代码取证）

### 1.1 SKU 映射键 = 平台「商品ID」，两条链路一致

**拉单链路（HTTP pull，当前主线）**

`backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoOrderTransform.java:161-194`：

```java
private List<OrderItemInput> itemsOf(Map<String, Object> order, String subOrderId) {   // :161
    ...
    String productId = text(product, "product_id");                                     // :172
    ...
    items.add(new OrderItemInput(                                                       // :183
            subOrderId,
            LineType.SINGLE,                                                            // :185
            null,            // skuCode
            productId,       // ← sourceSkuRef
            productName, ...));
```

`OrderItemInput` 第 4 个分量确认为 `sourceSkuRef`（`order/dto/OrderItemInput.java:17`）。类注释 `:29` 也写死 `product_id → sourceSkuRef`。

**文件链路（Excel 闭环）**

`backend/src/main/java/cn/zimu/fulfillment/file/SourceFileParser.java:338`：

```java
first(cells, "商品ID", "商品编码", "商品条码"), value(cells, "商品名称"), ...
```

模板指纹 `SourceFileParser.java:809`：`{主单号, 拆单号, 供货商, 渠道订单号, 结算方式, 需结算总额}`。

**解析只按精确键查，没有任何品名兜底** —— `order/OrderCreateService.java:724-734`：

```java
private SourceChannelSku findMapping(SourceChannel channel, String sourceSkuRef) {   // :724
    if (sourceSkuRef == null || sourceSkuRef.isBlank()) return null;
    return sourceChannelSkuRepository
            .findBySourceChannelAndSourceSkuRef(channel, sourceSkuRef)                // :729 唯一查法
            .filter(SourceChannelSku::isActive)
            .filter(mapping -> mapping.getQuantityMultiplier() != null)
            .filter(mapping -> mapping.getQuantityMultiplier().signum() > 0)
            .orElse(null);
}
```

未命中 → `SKU_MAPPING_REQUIRED` / `NEED_REVIEW`（`OrderCreateService.java:513-519`），**不报错，只静默卡单**。

**生产实证**（`app.source_channel_skus` id=80，唯一一条 JUFUBAO SKU 映射）：

```
JUFUBAO | 65993325 | 【京东配送】子牧澳洲谷饲牛肋排400g*2袋 | ×2.000 | sku_id=25 (SKU-JD-000025 牛肋排 400g)
```

对应订单行 `app.order_lines.id=35`（文件导入，`template_family=JUFUBAO_SOURCE_ORDER`），`raw_cells."商品ID"="65993325"`，已 `RETURN_FILE_READY`。**键是数字 ID，已在生产上跑通。**

### 1.2 礼包映射键：文件链路按「商品ID」，人工补救链路按「商品名称」

**文件链路自动展开** —— `file/SourceImportService.java:836-841`：

```java
private List<OrderItemInput> canonicalItems(SourceChannel channel, ParsedSourceRow row) {   // :836
    StaticSourceBundle sourceBundle = activeSourceBundle(channel, row.sourceSkuRef());      // :837 ← 键 = sourceSkuRef
    if (sourceBundle == null) {
        if (bundleSourceChannel(channel) && looksLikeBundle(row.productName())) {           // :839
            return List.of(unresolvedBundleItem(row));                                      // :840
        }
        return List.of(singleItem(row));
    }
```

`activeSourceBundle`（`:908-923`）的 SQL 是 `WHERE scb.source_channel=? AND scb.source_bundle_ref=?`，第二个参数就是 `row.sourceSkuRef()`。**对聚福宝而言 `sourceSkuRef` = 商品ID（§1.1），所以文件链路的礼包键是「商品ID」。**

（对比：大者 v2 导出表没有编码列，`SourceFileParser.java:434-447` 把 `商品名称` 同时当 `sourceSkuRef` 和 `productName`，所以大者的礼包键才是名称——`SourceFileParser.java:425-427` 那段注释说的是**大者**，不是聚福宝。这正是容易读错的地方。）

**人工补救链路 `resolve-bundle`** —— `order/OrderLineBundleResolutionService.java:202-215`：

```sql
COALESCE(
    NULLIF(ol.sku_code_snapshot, ''),
    (SELECT rir.raw_cells->>'主商品编码' FROM app.raw_import_rows rir
      WHERE rir.order_line_id = ol.id LIMIT 1),
    ol.product_name_snapshot)                       AS source_bundle_ref
```

该值再送进 `requireConsistentMapping`（`:259-266`）查 `app.source_channel_bundles`。

对聚福宝：`sku_code_snapshot` 为空（未映射时不落）、聚福宝导出表**没有「主商品编码」列**（`raw_cells` 实证见下），于是 **COALESCE 落到 `product_name_snapshot`＝商品名称**。

**结论：同一个渠道，自动展开按 ID 查，人工补救按名称查。两个键不是一回事。**

### 1.3 ⚠ 生产现存的那条礼包映射，在两条自动链路上都不生效

生产 `app.source_channel_bundles` id=70：

```
JUFUBAO | source_bundle_ref = 【京东配送】子牧牛肉惠选礼包1400g | bundle_id=33 | ×1 | active
```

对应生产订单行 `app.order_lines.id=34`，其 `raw_cells` 实证（文件导入）：

```json
{"数量":"1","商品ID":"66500527","商品名称":"【京东配送】子牧牛肉惠选礼包1400g",
 "商品编码":"","商品条码":"","礼包名称":"","供货商":"京诚乾元", ...}
```

—— **没有「主商品编码」键**，且 `商品ID` = `66500527`。于是：

| 链路 | 用什么键去查 `source_channel_bundles` | 命中 id=70？ |
|---|---|---|
| 文件导入自动展开（`SourceImportService.java:837`） | `66500527`（商品ID） | ❌ 不命中 |
| 人工 `resolve-bundle`（`OrderLineBundleResolutionService.java:211`） | `【京东配送】子牧牛肉惠选礼包1400g`（商品名） | ✅ 命中 |
| API 拉单 | **根本不查**（见 §1.4） | ❌ |

也就是说，行 34 的真实经过是：文件导入时按 ID 查礼包**没查到** → `looksLikeBundle("…礼包…")` 命中（`:903-906`）→ 造 `CUSTOM_BUNDLE` 待复核行（`:887-901`）→ 运营手工调 `resolve-bundle`，此时才按**名称**命中 id=70 → 展开成 bundle 33 的 3 个组件。

**这条映射是「能人工修好」，不是「能自动展开」。** 每来一单都要人工点一次。

**修法（数据侧，不改代码）**：`app.source_channel_bundles` 只有 `UNIQUE (source_channel, source_bundle_ref)`（`V40__add_wangqi_source_bundle_mappings.sql:47`），`bundle_id` 上**没有**唯一约束，所以同一个礼包可以挂两条不同键的映射。生产已有先例：DAZHE 的 bundle 1 同时挂 `P26011900044`（id=3）和 `子牧原切羊肉礼包6300g（BJ）`（id=19）；bundle 21 同时挂 `P26020400005`（id=38）和名称（id=26）。

→ **给 66500527 补一条 ID 键映射**（保留现有名称键那条给人工补救路径用）。命令见 §5.2。

### 1.4 ⚠ API 拉单链路完全不查礼包映射，且拉进来的礼包行「修不了」

`SourceImportService.importStructured`（`:296`）→ `doImportStructured`（`:322`）里，商品行是**直通**的：

```java
List<OrderItemInput> items = order.canonicalInput().items();   // :354 直接取 transform 产物
...
OrderDetailDto created = orderCreateService.createImported(canonicalInput, ...);  // :396
```

全程**没有**调用 `canonicalItems` / `activeSourceBundle` —— 那两个方法的入参是 `ParsedSourceRow`（文件链路专用），结构化链路够不着。`connector/` 整个包 grep `bundle` **零命中**。

后果链条：

1. 拉单来的每一行都是 `LineType.SINGLE`（`JufubaoOrderTransform.java:185`）。
2. 礼包行找不到 SKU 映射 → `SKU_MAPPING_REQUIRED` / `NEED_REVIEW`。
3. 运营想用 `resolve-bundle` 救 → `OrderLineBundleResolutionService.java:234-236` 直接拒绝：

```java
if (!"CUSTOM_BUNDLE".equals(line.get("line_type"))) {
    throw BusinessException.unprocessable("BUNDLE_LINE_NOT_RESOLVABLE", "只有礼包行可以就地解析礼包");
}
```

**→ API 拉进来的礼包行是死行：进不来，也修不了。**

这正是 `SourceImportService.java:925-942` 那段 2026-08-28 注释描述的死锁，但**那次修复只修了文件链路**（把 `bundleSourceChannel` 对所有渠道放开），结构化/拉单链路的同一个死锁**至今没修**。

生产实证 —— 4 条 JUFUBAO 订单行：

| line id | 来源 | line_type | stage / exception | 商品 |
|---|---|---|---|---|
| 34 | 文件 `JUFUBAO_SOURCE_ORDER` | CUSTOM_BUNDLE | RETURN_FILE_READY（bundle 33 已展开） | 子牧牛肉惠选礼包1400g |
| 35 | 文件 `JUFUBAO_SOURCE_ORDER` | SINGLE | RETURN_FILE_READY（SKU-JD-000025） | 子牧澳洲谷饲牛肋排400g*2袋 |
| 36 | **STRUCTURED（API 拉单）** | SINGLE | **NEED_REVIEW / SKU_MAPPING_REQUIRED** | 乔府大院金饭碗五常大米5kg |
| 37 | **STRUCTURED（API 拉单）** | SINGLE | **NEED_REVIEW / SKU_MAPPING_REQUIRED** | 子牧牛肉馅500g*2袋 |

行 37 正是本次要补的 SKU 映射（`65993155`）；补上后它能自愈。行 36 是别家品牌，属于「本就不该映射」（§2）。

### 1.5 ⚠ 名称含「礼包/礼盒/组合」的**单品**会被文件链路劫持

`looksLikeBundle`（`SourceImportService.java:903-906`）只看名字里有没有 `礼包|礼盒|组合`。而 `canonicalItems`（`:837-842`）的顺序是「先查礼包映射，查不到就看名字像不像礼包」——**SKU 映射根本不参与这个分支判断**。

所以：给一个名字带「组合」的商品配了 SKU 映射，文件链路照样会把它判成待解析礼包行，SKU 映射被绕过；而 API 拉单链路会正常用 SKU 映射。**两条链路对同一个商品给出不同结果。**

`SourceImportService.java:936-937` 的注释明说改动前核实过「生产 `source_channel_skus` 中不存在名称命中这三个词的活跃映射」——本次目录里的 66902619 / 66902622（`…烧烤肉串组合`）如果按 SKU 映射落库，就会**打破这个前提**。故这两行进待确认（§4）。

### 1.6 键语义汇总表

| | 拉单（API / STRUCTURED） | 文件导入（Excel） | 人工 `resolve-bundle` |
|---|---|---|---|
| SKU 映射键 | `product_id`（商品ID） | `商品ID`（回退 `商品编码`→`商品条码`） | — |
| 礼包映射键 | **不查** | `商品ID` | `商品名称`（COALESCE 兜底） |
| 一致性 | SKU 键 ✅ 一致 / 礼包 ❌ 不一致 | | |

---

## 2. 「不该映射」的判据：B 列不可用，Q 列才是判据

**任务给的前提需要修正。** `/tmp/111.xlsx` B 列「供货商名称」63 行**全部是「京诚乾元」**：

```
B 列（供货商名称）：京诚乾元 × 63
```

「京诚乾元」是**我们自己的供货主体**（京诚乾元（北京）供应链管理有限公司），是子牧在聚福宝的供货商账号，仓库里到处是它：`connector/jufubao/JufubaoPullConnectorTest.java:47` 的 `supplier_name`、`docs/excel-closed-loop-spec.md:188` 的京东授权码 pin `京诚乾元01`。**它区分不了任何东西。**

真正的结构化判据是 **Q 列「产品品牌」**：

```
Q 列（产品品牌）：子牧 41 / 大江生鲜 19 / yosibaby/羊小贝 2 / 乔府大院 1
```

这不是凭品名猜——`产品品牌` 是导出表的独立字段，且聚福宝拉单 API 的 `product_list[]` 里同样带 `brand_name`（`connector/jufubao/JufubaoOrderTransformTest.java:36`：`"brand_id":104311,"brand_name":"yosibaby\/羊小贝"`），两侧同源。

**旁证**：生产 `app.skus`（92 条）里没有任何 大江生鲜 / 乔府大院 / 羊小贝 的商品档案——就算想映也无处可映。这三家的品是「京诚乾元」这个供货商账号代其他品牌代运营/铺货的，不是子牧履约范围。

→ **22 行不映射**：第 20–38 行（大江生鲜，19 行）、第 51 行（乔府大院）、第 52–53 行（yosibaby/羊小贝）。

---

## 3. 63 行分型表

判据缩写：`品牌`=Q 列产品品牌；`名+规`=品名与内部 SKU 品名一致且规格一致；`跨渠道`=其他渠道已有同名商品的活跃映射可比对；`BOM`=生产礼包 BOM 反证。

| # | 商品ID | 品牌 | 产品名称 | 分型 |
|---|---|---|---|---|
| 1 | 65334653 | 子牧 | 【京东配送】子牧清真牛肉生鲜礼包1300g | 待确认（无礼包档案） |
| 2 | 65335556 | 子牧 | 【京东配送】子牧清真牛羊肉礼包1900g | 待确认（无礼包档案） |
| 3 | 65335660 | 子牧 | 【京东配送】子牧清真牛羊肉生鲜礼包2400g | 待确认（无礼包档案） |
| 4 | 65335889 | 子牧 | 【京东配送】子牧锡盟苏尼特羔羊肉&安格斯优品黑牛肉组合3200g | 待确认（无礼包档案） |
| 5 | 65992754 | 子牧 | 【京东配送】子牧牛腩块500g*2袋 | **SKU映射** |
| 6 | 65992894 | 子牧 | 【京东配送】子牧谷饲安格斯牛腱子肉500g*2袋 | **SKU映射** |
| 7 | 65992900 | 子牧 | 【京东配送】子牧筋头巴脑500g*2袋 | **SKU映射** |
| 8 | 65992994 | 子牧 | 【京东配送】子牧牛后腿肉500g*2袋 | **SKU映射** |
| 9 | 65993155 | 子牧 | 【京东配送】子牧牛肉馅500g*2袋 | **SKU映射**（可自愈生产行 37） |
| 10 | 65993209 | 子牧 | 【京东配送】子牧澳洲原切谷饲上脑牛排150g*4袋 | **SKU映射** |
| 11 | 65993237 | 子牧 | 【京东配送】子牧原切眼肉牛排150g*4袋 | **SKU映射** |
| 12 | 65993325 | 子牧 | 【京东配送】子牧澳洲谷饲牛肋排400g*2袋 | SKU映射**（已存在 id=80，不重复落）** |
| 13 | 65993370 | 子牧 | 【京东配送】子牧澳洲谷饲牛蝎子400g*2袋 | **SKU映射** |
| 14 | 65993381 | 子牧 | 【京东配送】子牧生鲜羊蝎子500g*2袋 | **SKU映射** |
| 15 | 65998050 | 子牧 | 【京东配送】子牧原切纯肉羊腿肉500g*2袋 | **SKU映射** |
| 16 | 65998054 | 子牧 | 【京东配送】子牧原切纯肉羊肉块500g*2袋 | **SKU映射** |
| 17 | 65998070 | 子牧 | 【京东配送】子牧原切带骨羊肉块500g*2袋 | **SKU映射** |
| 18 | 65998078 | 子牧 | 【京东配送】子牧羊寸排块500g*2袋 | **SKU映射** |
| 19 | 65998272 | 子牧 | 【京东配送】子牧法式羊排400g*2袋 | **SKU映射** |
| 20 | 66486817 | 大江生鲜 | 大江生鲜大口吃肉甄选套餐A冷鲜+冷冻装2140g | 不映射（他家品牌） |
| 21 | 66486882 | 大江生鲜 | 大江生鲜大口吃肉优品套餐A冷鲜+冷冻装2640g | 不映射（他家品牌） |
| 22 | 66486903 | 大江生鲜 | 大江生鲜家家鲜甄选套餐A冷鲜装2100g | 不映射（他家品牌） |
| 23 | 66486913 | 大江生鲜 | 大江生鲜家家鲜大吉大利套餐A冷鲜装2200g | 不映射（他家品牌） |
| 24 | 66486989 | 大江生鲜 | 大江生鲜大口吃肉甄选套餐B冷鲜+冷冻装2840g | 不映射（他家品牌） |
| 25 | 66487069 | 大江生鲜 | 大江生鲜家家鲜优品套餐A冷鲜装2310g | 不映射（他家品牌） |
| 26 | 66487090 | 大江生鲜 | 大江生鲜家家鲜大吉大利优品套餐A冷鲜装2400g | 不映射（他家品牌） |
| 27 | 66487102 | 大江生鲜 | 大江生鲜大口吃肉优品套餐B冷鲜+冷冻装2990g | 不映射（他家品牌） |
| 28 | 66487120 | 大江生鲜 | 大江生鲜家家鲜甄选套餐B冷鲜装2310g | 不映射（他家品牌） |
| 29 | 66487136 | 大江生鲜 | 大江生鲜大口吃肉甄选套餐C冷鲜+冷冻装3640g | 不映射（他家品牌） |
| 30 | 66487142 | 大江生鲜 | 大江生鲜家家鲜优品套餐B冷鲜装2460g | 不映射（他家品牌） |
| 31 | 66487145 | 大江生鲜 | 大江生鲜家家鲜大吉大利优品套餐B冷鲜装3400g | 不映射（他家品牌） |
| 32 | 66487148 | 大江生鲜 | 大江生鲜大口吃肉优品套餐C冷鲜+冷冻装4090g | 不映射（他家品牌） |
| 33 | 66487153 | 大江生鲜 | 大江生鲜家家鲜甄选套餐C冷鲜装3510g | 不映射（他家品牌） |
| 34 | 66487158 | 大江生鲜 | 大江生鲜家家鲜优品套餐C冷鲜装3620g | 不映射（他家品牌） |
| 35 | 66487212 | 大江生鲜 | 大江生鲜家家鲜甄选套餐D冷鲜装3760g | 不映射（他家品牌） |
| 36 | 66487213 | 大江生鲜 | 大江生鲜家家鲜优品套餐D冷鲜装4120g | 不映射（他家品牌） |
| 37 | 66487215 | 大江生鲜 | 大江生鲜家家鲜甄选套餐E冷鲜装4820g | 不映射（他家品牌） |
| 38 | 66487219 | 大江生鲜 | 大江生鲜家家鲜优品猪肉鸡肉套餐E 5180g | 不映射（他家品牌） |
| 39 | 66487969 | 子牧 | 子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g | **SKU映射** |
| 40 | 66500521 | 子牧 | 【京东配送】子牧牛羊肉惠选礼包A1500g | 待确认（无礼包档案） |
| 41 | 66500526 | 子牧 | 【京东配送】子牧牛羊肉惠选礼包B1900g | 待确认（无礼包档案） |
| 42 | 66500527 | 子牧 | 【京东配送】子牧牛肉惠选礼包1400g | **礼包映射**（bundle 33，补 ID 键） |
| 43 | 66500558 | 子牧 | 【京东配送】子牧牛羊肉惠选礼包B2900g | 待确认（无礼包档案） |
| 44 | 66500564 | 子牧 | 【京东配送】子牧羔羊肉惠选礼包2400g | 待确认（无礼包档案） |
| 45 | 66500565 | 子牧 | 【京东配送】子牧牛肉惠选礼包2300g | 待确认（无礼包档案） |
| 46 | 66500653 | 子牧 | 【京东配送】子牧牛肉智选礼包4500g | 待确认（无礼包档案） |
| 47 | 66500673 | 子牧 | 【京东配送】子牧智选牛肉礼包5900g | 待确认（无礼包档案） |
| 48 | 66526478 | 子牧 | 【京东配送】子牧羊蝎子牛肋排组合1800g | 待确认（无礼包档案） |
| 49 | 66526788 | 子牧 | 【子牧】牛羊惠选礼包A2500g | 待确认（无礼包档案） |
| 50 | 66597389 | 子牧 | 子牧鲜椒鸵鸟肉酱180g*2罐 | **SKU映射** |
| 51 | 66605101 | 乔府大院 | 乔府大院金饭碗五常大米5kg | 不映射（他家品牌，正卡在生产行 36） |
| 52 | 66662134 | yosibaby/羊小贝 | yosibaby/羊小贝山羊奶整箱200ml*10盒 | 不映射（他家品牌） |
| 53 | 66662137 | yosibaby/羊小贝 | yosibaby/羊小贝有机配方羊奶粉380g | 不映射（他家品牌） |
| 54 | 66683887 | 子牧 | 子牧澳洲谷饲肥牛涮烤片150g*2盒 | **SKU映射** |
| 55 | 66693946 | 子牧 | 子牧A5澳洲和牛霜降肥牛卷200g*3盒 | **SKU映射** |
| 56 | 66811280 | 子牧 | 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪五花肉450g*2 | **SKU映射** |
| 57 | 66811285 | 子牧 | 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪仔排450g*2 | **SKU映射** |
| 58 | 66811301 | 子牧 | 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪里脊450g*2 | **SKU映射** |
| 59 | 66902617 | 子牧 | 【京东配送】日式和牛厚切烧肉M8-9 150g*2 | 待确认（SKU 规格待维护） |
| 60 | 66902619 | 子牧 | 【京东配送】牛羊烧烤肉串组合：羊肉串10串+… | 待确认（命名冲突 + 规格待维护） |
| 61 | 66902622 | 子牧 | 【京东配送】子牧鸡肉烧烤肉串组合：鸡肉串5串+… | 待确认（命名冲突 + 规格待维护） |
| 62 | 66983856 | 子牧 | 【德邦配送】新疆库尔勒香梨超特香梨约14斤 | 待确认（无商品/SKU 档案） |
| 63 | 66984019 | 子牧 | 【德邦配送】子牧新疆库尔勒香梨全母梨王约14斤 | 待确认（无商品/SKU 档案） |

**合计**：SKU 映射 21 新建 + 1 已存在，礼包映射 1，不映射 22，待确认 18。

---

## 4. 确定匹配（可直接落库）

### 4.1 匹配判据（先说方法，再看结果）

1. **键**：`source_sku_ref` = 平台商品ID（§1.1 已证）。**不用条码、不用品名。**
2. **目标 SKU**：按「内部 SKU 品名 + 规格」与平台品名去掉配送前缀后比对；多候选时用「专有品牌前缀」消歧（主数据里 `东乡贡羊…` / `窦清源…` / `小龙坎…` 是独立系列，平台品名不带该前缀时不取）。
3. **乘数**：平台品名尾部的 `*N`（`500g*2袋` / `150g*4袋` / `200g*3盒`）就是渠道包装乘数。这是有明文治理依据的：`masterdata/MasterDataService.java:286-292` ——「商品名里的 `*N`（如 `500g*2`）是渠道销售捆绑数，属于 `source_channel_skus.quantity_multiplier`」。内部 SKU 规格串本身已含 `*N` 的（雷山黑猪三兄弟，规格就是 `450g*2`）则乘数为 1。
4. **跨渠道互证**：绝大多数行在 CAISHIXIAN / ZHONGHUI / FEIXIANG 上已有**同名或近同名**商品的活跃映射，直接抄同一个 `sku_id` + 同一套乘数口径。这是最强的判据——不是我按品名猜的，是运营早就在别的渠道确认过的。
5. **条码只作旁证**，不作键（聚福宝导出表 F 列条码有 `*4` 后缀、有前导 0 的脏形态，如 `6977872890142*4`、`06977872890388`）。

### 4.2 SKU 映射表

| # | source_sku_ref | 平台品名 | → sku_id | sku_code | 内部品名 / 规格 | ×N | 判据 |
|---|---|---|---|---|---|---|---|
| 5 | 65992754 | 子牧牛腩块500g*2袋 | 22 | SKU-JD-000022 | 牛腩块(500g) / 500g | 2 | 跨渠道（CAISHIXIAN `2047789` 同名×2；FEIXIANG `6627540`）+ BOM（bundle 33） |
| 6 | 65992894 | 子牧谷饲安格斯牛腱子肉500g*2袋 | 37 | SKU-JD-000037 | 牛腱子(谷饲牛腱子) / 500g | 2 | 跨渠道（WECOM `子牧谷饲安格斯牛腱子肉` 逐字同名；CAISHIXIAN `2047705` ×2） |
| 7 | 65992900 | 子牧筋头巴脑500g*2袋 | 26 | SKU-JD-000026 | 筋头巴脑(500g) / 500g | 2 | 跨渠道（CAISHIXIAN `2047778` ×2；FEIXIANG `6627975` ×2） |
| 8 | 65992994 | 子牧牛后腿肉500g*2袋 | 35 | SKU-JD-000035 | 牛后腿肉 / 500g | 2 | 跨渠道（ZHONGHUI `60043832` **逐字同名** ×2）+ BOM（bundle 33） |
| 9 | 65993155 | 子牧牛肉馅500g*2袋 | 30 | SKU-JD-000030 | 牛肉馅 / 500g | 2 | 跨渠道（CAISHIXIAN `2047840` **逐字同名** ×2） |
| 10 | 65993209 | 子牧澳洲原切谷饲上脑牛排150g*4袋 | 20 | SKU-JD-000020 | 上脑牛排 / 150g | 4 | 跨渠道（CAISHIXIAN `2047826` **逐字同名** ×4）；规格 150g 排除 SKU 69（200g*4） |
| 11 | 65993237 | 子牧原切眼肉牛排150g*4袋 | 32 | SKU-JD-000032 | 原切眼肉牛排 / 150g | 4 | 跨渠道（CAISHIXIAN `2047846` **逐字同名** ×4）；规格排除 SKU 70（200g*4） |
| 13 | 65993370 | 子牧澳洲谷饲牛蝎子400g*2袋 | 28 | SKU-JD-000028 | 卓宸澳洲谷饲牛蝎子 / 400g | 2 | 跨渠道（ZHONGHUI `60043837` 同名 ×2）+ BOM（bundle 33 的 400g 牛蝎子组件就是它） |
| 14 | 65993381 | 子牧生鲜羊蝎子500g*2袋 | 5 | SKU-JD-000005 | 羊蝎子 / 500g | 2 | 跨渠道（CAISHIXIAN `2047847` `子牧羊蝎子500g*2` ×2）；据此排除 SKU 40（东乡贡羊系列） |
| 15 | 65998050 | 子牧原切纯肉羊腿肉500g*2袋 | 9 | SKU-JD-000009 | 羊腿肉 / 500g | 2 | 跨渠道（ZHONGHUI `60043849` **逐字同名** ×2）；据此排除 SKU 7 羊腿小切 / SKU 6 带骨羊后腿块 |
| 16 | 65998054 | 子牧原切纯肉羊肉块500g*2袋 | 8 | SKU-JD-000008 | 羊肉块 / 500g | 2 | 跨渠道（CAISHIXIAN `2047679` `子牧羊肉块500g*2` ×2）；「纯肉」排除 SKU 3 带骨羊肉块 |
| 17 | 65998070 | 子牧原切带骨羊肉块500g*2袋 | 3 | SKU-JD-000003 | 带骨羊肉块 / 500g | 2 | 跨渠道（CAISHIXIAN `2047798` 同名 ×2） |
| 18 | 65998078 | 子牧羊寸排块500g*2袋 | 4 | SKU-JD-000004 | 羊排块(羊寸排) / 500g | 2 | 跨渠道（CAISHIXIAN `2047860` `子牧羊排块（羊寸排）500g*2` ×2）；据此排除 SKU 39（东乡贡羊系列） |
| 19 | 65998272 | 子牧法式羊排400g*2袋 | 10 | SKU-JD-000010 | 法式羊排 / 400g | 2 | 名+规唯一命中；跨渠道 DAZHE/WANGQI `法式羊排`→10（单件 ×1，此处 `*2` 故 ×2） |
| 39 | 66487969 | 子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g | 87 | SKU-JD-000085 | 蒙元鸵新鲜鸵鸟蛋 / 1.5kg | 1 | 主数据唯一鸵鸟蛋 SKU；平台「1个」→ 乘数 1 |
| 50 | 66597389 | 子牧鲜椒鸵鸟肉酱180g*2罐 | 88 | SKU-JD-000086 | 蒙元鸵鸵鸟肉酱 / 180g | 2 | 主数据唯一鸵鸟肉酱 SKU，规格 180g 一致；`*2罐` → ×2 |
| 54 | 66683887 | 子牧澳洲谷饲肥牛涮烤片150g*2盒 | 76 | SKU-JD-000074 | 子牧谷饲肥牛涮烤片 / 150g | 2 | 名+规唯一命中；「肥牛」排除 SKU 52 子牧谷饲**板腱**涮烤片 |
| 55 | 66693946 | 子牧A5澳洲和牛霜降肥牛卷200g*3盒 | 48 | SKU-JD-000048 | M5霜降肥牛卷 / 200g | 3 | 跨渠道（CAISHIXIAN `2152081` **`子牧A5澳洲和牛霜降肥牛卷` 逐字同名** → sku 48）；A5/M5 的口径差异运营已在彩食鲜确认过，`*3盒` → ×3 |
| 56 | 66811280 | 子牧雷山高海拔农家散养土黑猪五花肉450g*2 | 63 | SKU-TP-000063 | 同名 / 450g*2 | 1 | 跨渠道（ZHONGHUI `83755271` **逐字同名 ×1**）；内部规格串已是 450g*2，故乘数 1 |
| 57 | 66811285 | 子牧雷山高海拔农家散养土黑猪仔排450g*2 | 64 | SKU-TP-000064 | …土黑猪排骨450g*2 / 450g*2 | 1 | 跨渠道（ZHONGHUI `83755270` ×1；WECOM `子牧雷山高海拔农家散养土黑猪排骨`）；「仔排/排骨」为同一 SKU 的两种叫法，雷山系列只有这一个排骨品 |
| 58 | 66811301 | 子牧雷山高海拔农家散养土黑猪里脊450g*2 | 68 | SKU-TP-000066 | 子牧雷山黑猪里脊450g*2 / 450g*2 | 1 | 跨渠道（CAISHIXIAN `2066613` `子牧雷山黑猪里脊450g*2` ×1） |

**已存在，不要重复落**：#12 `65993325` → `app.source_channel_skus.id=80`（sku 25 ×2，active）。重复 POST 会得到 `SOURCE_SKU_MAPPING_EXISTS` 409（`MasterDataService.java:689-691`）。

> 注：#56–58 履约方是 `fulfillment_provider_id=2`（非京东），与其余京东品不同；这不影响映射本身，但排产/导出走的是另一条履约链。

### 4.3 礼包映射表

| # | source_bundle_ref | 平台品名 | → bundle_id | bundle_code | ×N |
|---|---|---|---|---|---|
| 42 | **66500527** | 【京东配送】子牧牛肉惠选礼包1400g | 33 | BUNDLE-9260828000001 | 1 |

判据：bundle 33 的 BOM 已在生产核实，`牛腩块500g + 牛后腿肉500g + 卓宸澳洲谷饲牛蝎子400g = 1400g`，与平台品名重量完全对上。这条是**给现有名称键那条（id=70）补一个 ID 键**，让文件导入能自动展开，不再每单人工 `resolve-bundle`（§1.3）。

---

## 5. 待确认清单（**不猜、不填**）

### 5.1 缺礼包档案 —— 13 行

生产 `app.product_bundles` 只有 33 个礼包，全部是大者/万旗/万齐系（`…（BJ）`、`…（DZ）`、`2026…` 命名）**加**唯一一个聚福宝的 bundle 33。下列 13 行**在生产里没有任何名称或重量对得上的礼包档案**：

| # | 商品ID | 品名 | 说明 |
|---|---|---|---|
| 1 | 65334653 | 子牧清真牛肉生鲜礼包1300g | 无 1300g 礼包档案 |
| 2 | 65335556 | 子牧清真牛羊肉礼包1900g | 生产有 `牛腩块羊蝎子大礼包1900g`（bundle 10），**重量撞车但配方名不同**，不可据此映射 |
| 3 | 65335660 | 子牧清真牛羊肉生鲜礼包2400g | 无 |
| 4 | 65335889 | 子牧锡盟苏尼特羔羊肉&安格斯优品黑牛肉组合3200g | 无 |
| 40 | 66500521 | 子牧牛羊肉惠选礼包A1500g | 无 |
| 41 | 66500526 | 子牧牛羊肉惠选礼包B1900g | 同 #2，1900g 撞车 bundle 10，配方不同 |
| 43 | 66500558 | 子牧牛羊肉惠选礼包B2900g | 无 |
| 44 | 66500564 | 子牧羔羊肉惠选礼包2400g | 无 |
| 45 | 66500565 | 子牧牛肉惠选礼包2300g | 无 |
| 46 | 66500653 | 子牧牛肉智选礼包4500g | 无 |
| 47 | 66500673 | 子牧智选牛肉礼包5900g | 无 |
| 48 | 66526478 | 子牧羊蝎子牛肋排组合1800g | 无（bundle 4 `羊蝎子鸵鸟肉排组合 1080g` 是万齐的品，配方与重量都不同） |
| 49 | 66526788 | 【子牧】牛羊惠选礼包A2500g | 无 |

**解锁条件**：运营先提供这 13 个礼包的 BOM（每个组件的内部 SKU + 每份数量），建 `app.product_bundles` + `app.bundle_items` 档案，再落 `source_channel_bundles`（键用商品ID，见 §1.3）。

**在此之前这 13 行都会卡单**：文件导入 → `looksLikeBundle` 命中「礼包/组合」→ `CUSTOM_BUNDLE` 待复核；API 拉单 → `SINGLE` + `SKU_MAPPING_REQUIRED` 且**无法用 `resolve-bundle` 修**（§1.4）。

> ⚠️ **绝对不要**为省事把礼包映到某个单品 SKU。礼包一单要发 3–5 个组件，映到单品会少发货。

### 5.2 SKU 侧待确认 —— 5 行

| # | 商品ID | 品名 | 为什么不能定 | 解锁条件 |
|---|---|---|---|---|
| 59 | 66902617 | 【京东配送】日式和牛厚切烧肉M8-9 150g*2 | 目标 SKU 身份是清楚的（`SKU-JD-000058 M8-9日式和牛厚切烧肉`，逐字同名），但**它的 `specification` 是「待维护」**——无法判断内部单位是「150g 一份」（则 ×2）还是「150g*2 整盒」（则 ×1）。乘数错一倍就是发多/发少一半。 | 主数据把 sku 58 的规格补成 `150g` → 落 `sku_id=58, ×2` |
| 60 | 66902619 | 【京东配送】牛羊烧烤肉串组合：羊肉串10串+牛肉串5串+牛心管5串+鱼豆腐5串+毛肚串10串 | 双重问题：① 候选 `SKU-JD-000061 牛羊肉烧烤组合` 规格「待维护」，35 串的构成无法核对；② **品名含「组合」，会被 `looksLikeBundle`（`SourceImportService.java:903`）在文件链路劫持成待解析礼包行，SKU 映射被绕过**，与 API 链路行为不一致（§1.5），且会打破 `:936` 注释里记录的前提 | 先定它是单品 SKU 还是礼包；若为单品，需同步处理 `looksLikeBundle` 的命名冲突 |
| 61 | 66902622 | 【京东配送】子牧鸡肉烧烤肉串组合：鸡肉串5串+… | 同 #60（候选 `SKU-JD-000060 鸡肉烧烤组合`，规格待维护） | 同上 |
| 62 | 66983856 | 【德邦配送】新疆库尔勒香梨超特香梨约14斤 | `app.products` / `app.skus` 里**没有任何香梨商品**，无处可映 | 先建商品 + SKU 档案 |
| 63 | 66984019 | 【德邦配送】子牧新疆库尔勒香梨全母梨王约14斤 | 同上；且与 #62 是两个不同等级（超特 / 全母梨王），必须建成两个 SKU，不能合并 | 同上 |

### 5.3 建议运营一次性复核（不阻断落库）

- **#50 `66597389` 子牧鲜椒鸵鸟肉酱**：平台名带口味「鲜椒」，内部 `SKU-JD-000086 蒙元鸵鸵鸟肉酱` 不带口味限定。目录里只有这一个肉酱品，故映射唯一且无歧义；但若日后上架第二种口味，必须先拆 SKU 档案。
- **#57 `66811285` 仔排 / 排骨**：平台叫「仔排」，内部叫「排骨」，靠 ZHONGHUI/WECOM 先例判定为同一 SKU。雷山系列三个品（五花肉/仔排/里脊）与内部三个 TP SKU 一一对应，无剩余候选。
- **#55 `66693946` A5 / M5**：平台标 A5，内部 SKU 名叫 M5。彩食鲜已按同名映到 sku 48，此处沿用同一口径；若运营认为这是两个不同等级的货，需先拆 SKU。

---

## 6. 可执行的写入方式（**只给命令，未执行**）

### 6.0 契约要点

- 端点：`POST /api/v1/source-sku-mappings`（`masterdata/MasterDataController.java:42` + `:146`）、`POST /api/v1/source-bundle-mappings`（`masterdata/BundleMasterDataController.java:28` + `:87`）。
- 请求体 **snake_case**（`common/web/JacksonConfig.java:14` 全局 `PropertyNamingStrategies.SNAKE_CASE`）。
- 必须带 `Idempotency-Key` 头；`X-Operator` 由生产网关注入（`docker/nginx/default.conf:97-108` 的 `location /api/` 里 `include backend-auth.inc`），本机**不要**自己传。
- 网关 Basic 凭据：`-u zimu-admin:123456`，打 `http://localhost`。
- `sku_id` / `bundle_id` 是**字符串形式的正整数**（`common/dto/Patterns.java` 的 `IDENTIFIER = ^[1-9][0-9]*$`）；`quantity_multiplier` 也是字符串。礼包乘数一期**必须为 `"1"`**（`product/SourceBundleMappingWrite.java` 的 `@Pattern(regexp = "^1$")`）。
- 远端是 Windows cmd：JSON 里的双引号用 `\"` 转义，外层用单引号包住整条 ssh 命令。

### 6.1 SKU 映射（21 条）

```bash
# 【京东配送】子牧牛腩块500g*2袋 -> sku_id=22 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65992754-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65992754\",\"source_sku_name\":\"【京东配送】子牧牛腩块500g*2袋\",\"sku_id\":\"22\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧谷饲安格斯牛腱子肉500g*2袋 -> sku_id=37 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65992894-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65992894\",\"source_sku_name\":\"【京东配送】子牧谷饲安格斯牛腱子肉500g*2袋\",\"sku_id\":\"37\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧筋头巴脑500g*2袋 -> sku_id=26 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65992900-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65992900\",\"source_sku_name\":\"【京东配送】子牧筋头巴脑500g*2袋\",\"sku_id\":\"26\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧牛后腿肉500g*2袋 -> sku_id=35 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65992994-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65992994\",\"source_sku_name\":\"【京东配送】子牧牛后腿肉500g*2袋\",\"sku_id\":\"35\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧牛肉馅500g*2袋 -> sku_id=30 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65993155-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65993155\",\"source_sku_name\":\"【京东配送】子牧牛肉馅500g*2袋\",\"sku_id\":\"30\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧澳洲原切谷饲上脑牛排150g*4袋 -> sku_id=20 x4
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65993209-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65993209\",\"source_sku_name\":\"【京东配送】子牧澳洲原切谷饲上脑牛排150g*4袋\",\"sku_id\":\"20\",\"quantity_multiplier\":\"4\",\"active\":true}"'

# 【京东配送】子牧原切眼肉牛排150g*4袋 -> sku_id=32 x4
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65993237-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65993237\",\"source_sku_name\":\"【京东配送】子牧原切眼肉牛排150g*4袋\",\"sku_id\":\"32\",\"quantity_multiplier\":\"4\",\"active\":true}"'

# 【京东配送】子牧澳洲谷饲牛蝎子400g*2袋 -> sku_id=28 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65993370-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65993370\",\"source_sku_name\":\"【京东配送】子牧澳洲谷饲牛蝎子400g*2袋\",\"sku_id\":\"28\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧生鲜羊蝎子500g*2袋 -> sku_id=5 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65993381-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65993381\",\"source_sku_name\":\"【京东配送】子牧生鲜羊蝎子500g*2袋\",\"sku_id\":\"5\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧原切纯肉羊腿肉500g*2袋 -> sku_id=9 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65998050-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65998050\",\"source_sku_name\":\"【京东配送】子牧原切纯肉羊腿肉500g*2袋\",\"sku_id\":\"9\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧原切纯肉羊肉块500g*2袋 -> sku_id=8 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65998054-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65998054\",\"source_sku_name\":\"【京东配送】子牧原切纯肉羊肉块500g*2袋\",\"sku_id\":\"8\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧原切带骨羊肉块500g*2袋 -> sku_id=3 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65998070-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65998070\",\"source_sku_name\":\"【京东配送】子牧原切带骨羊肉块500g*2袋\",\"sku_id\":\"3\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧羊寸排块500g*2袋 -> sku_id=4 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65998078-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65998078\",\"source_sku_name\":\"【京东配送】子牧羊寸排块500g*2袋\",\"sku_id\":\"4\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 【京东配送】子牧法式羊排400g*2袋 -> sku_id=10 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-65998272-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"65998272\",\"source_sku_name\":\"【京东配送】子牧法式羊排400g*2袋\",\"sku_id\":\"10\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g -> sku_id=87 x1
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66487969-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66487969\",\"source_sku_name\":\"子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g\",\"sku_id\":\"87\",\"quantity_multiplier\":\"1\",\"active\":true}"'

# 子牧鲜椒鸵鸟肉酱180g*2罐 -> sku_id=88 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66597389-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66597389\",\"source_sku_name\":\"子牧鲜椒鸵鸟肉酱180g*2罐\",\"sku_id\":\"88\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 子牧澳洲谷饲肥牛涮烤片150g*2盒 -> sku_id=76 x2
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66683887-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66683887\",\"source_sku_name\":\"子牧澳洲谷饲肥牛涮烤片150g*2盒\",\"sku_id\":\"76\",\"quantity_multiplier\":\"2\",\"active\":true}"'

# 子牧A5澳洲和牛霜降肥牛卷200g*3盒 -> sku_id=48 x3
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66693946-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66693946\",\"source_sku_name\":\"子牧A5澳洲和牛霜降肥牛卷200g*3盒\",\"sku_id\":\"48\",\"quantity_multiplier\":\"3\",\"active\":true}"'

# 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪五花肉450g*2 -> sku_id=63 x1
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66811280-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66811280\",\"source_sku_name\":\"【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪五花肉450g*2\",\"sku_id\":\"63\",\"quantity_multiplier\":\"1\",\"active\":true}"'

# 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪仔排450g*2 -> sku_id=64 x1
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66811285-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66811285\",\"source_sku_name\":\"【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪仔排450g*2\",\"sku_id\":\"64\",\"quantity_multiplier\":\"1\",\"active\":true}"'

# 【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪里脊450g*2 -> sku_id=68 x1
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-sku-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-sku-66811301-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"66811301\",\"source_sku_name\":\"【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪里脊450g*2\",\"sku_id\":\"68\",\"quantity_multiplier\":\"1\",\"active\":true}"'

```

### 6.2 礼包映射（1 条，补 ID 键）

```bash
# 【京东配送】子牧牛肉惠选礼包1400g -> bundle_id=33（保留现有名称键 id=70 不动）
ssh zimupc 'curl -s -u zimu-admin:123456 -X POST http://localhost/api/v1/source-bundle-mappings -H "Content-Type: application/json" -H "Idempotency-Key: jfb-bundle-66500527-20260829" -d "{\"source_channel\":\"JUFUBAO\",\"source_bundle_ref\":\"66500527\",\"source_bundle_name\":\"【京东配送】子牧牛肉惠选礼包1400g\",\"quantity_multiplier\":\"1\",\"bundle_id\":\"33\",\"active\":true}"'
```

### 6.3 落库后的校验（只读）

```bash
# 应为 22 条 JUFUBAO SKU 映射（21 新 + 既有 65993325）
ssh zimupc "docker exec -i zimu-fulfillment-postgres-1 psql -U fulfillment -d fulfillment_hub -t -A -c \"SELECT count(*) FROM app.source_channel_skus WHERE source_channel='JUFUBAO' AND active\""

# 应为 2 条 JUFUBAO 礼包映射（名称键 + ID 键，同指 bundle 33）
ssh zimupc "docker exec -i zimu-fulfillment-postgres-1 psql -U fulfillment -d fulfillment_hub -t -A -c \"SELECT id, source_bundle_ref, bundle_id FROM app.source_channel_bundles WHERE source_channel='JUFUBAO'\""

# 卡住的行 37（子牧牛肉馅）应能在重新解析后自愈；行 36（乔府大院）保持 NEED_REVIEW 是正确结果
ssh zimupc "docker exec -i zimu-fulfillment-postgres-1 psql -U fulfillment -d fulfillment_hub -t -A -c \"SELECT ol.id, ol.line_type, ol.processing_stage, ol.exception_code, ol.product_name_snapshot FROM app.order_lines ol JOIN app.orders o ON o.id=ol.order_id WHERE o.source_channel='JUFUBAO' ORDER BY ol.id\""
```

> 注：落映射本身**不会**自动重放已存在的 `NEED_REVIEW` 行。生产行 37 需要走复核事项的 `resolve-sku` 或重新拉单才会应用新映射；这属于运营动作，不在本文范围。

---

## 7. 需要立项的代码问题（数据补不掉的）

按严重度排列。三条都不是本次映射落库能解决的，**建议单独开票**。

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| A | **API 拉单链路的礼包行是死行**：`importStructured` 不查 `source_channel_bundles`，产出的行恒为 `SINGLE`，而 `resolve-bundle` 只受理 `CUSTOM_BUNDLE` | `SourceImportService.java:354,396`（items 直通）；`JufubaoOrderTransform.java:185`；`OrderLineBundleResolutionService.java:234-236` | 聚福宝礼包**只能靠人工上传 Excel** 才能进系统。§5.1 那 13 个礼包档案建好后，拉单侧依然全部卡死。2026-08-28 的修复只覆盖了文件链路（`SourceImportService.java:925-942`） |
| B | **礼包映射键在两条链路上语义不一致**：文件自动展开按商品ID，人工 `resolve-bundle` 按商品名 | `SourceImportService.java:837` vs `OrderLineBundleResolutionService.java:202-215` | 一个礼包必须配两条映射才能既自动展开又能人工补救。生产 id=70 只配了名称键，所以「看着配好了，其实每单都要人工点一次」 |
| C | **`looksLikeBundle` 会劫持名字带「组合」的单品**，且判断在 SKU 映射之前 | `SourceImportService.java:837-842`、`:903-906`、`:936-937` | 目录里 66902619 / 66902622 一旦配 SKU 映射，文件链路与拉单链路会对同一商品给出不同结果（§1.5、§5.2） |

---

## 8. 只读承诺与取证边界

- 生产库全程只发 `SELECT` / `information_schema` 查询，**0 条写语句**。
- **未执行**任何创建映射的 API 调用；§6 全部是待运营/负责人确认后手工执行的命令。
- 本文所有代码结论都给了文件路径 + 行号，基线为 `jry/integration-20260828` 工作树当前内容。
- 分型判据全部来自结构化字段（Q 列产品品牌、商品ID、内部 SKU 规格、其他渠道既有映射、生产礼包 BOM），未使用「看名字像什么」作为唯一依据。匹配不上的一律进待确认，**没有为任何一行编造映射**。
