# 中汇 60043846 未映射事故：来源 SKU 映射的表、键与修复路径

日期：2026-08-27
代码基线：`ad0614ddf8a679c8dfa4d5e0179761c4cb40304a`（`fix(file): 已发货的来源行不得建单——中汇/飞象补上已履约事实拦截`）
生产库快照：`zimupc` / `zimu-fulfillment-postgres-1` / `fulfillment_hub`，2026-08-27 10:0x 只读查询
调研方式：只读。仅执行 `SELECT`，未改任何代码、未改任何生产数据。

## 结论

**`60043846`（子牧羊腿小切500g\*2）在 `app.source_channel_skus` 里根本没有 ZHONGHUI 这一条——不是配了没命中，是压根没配。**
同一个内部 SKU（`sku_id=7`，`SKU-JD-000007` 羊腿小切 500g/件）在**彩食鲜**渠道配过（`2056585`，乘数 2.000），中汇渠道漏配。
运营在 **我的工作台 → 复核收件箱** 打开事项 `RC-ORD-D28A057AC7A84B01A8E77A8F07614667-2`，选 SKU「羊腿小切（SKU-JD-000007）」、数量换算填 `2.000`、点「确认并解决」，系统会**同时**补建 ZHONGHUI 映射并修好这张订单行——一步到位。

---

## 1. 中汇渠道的 SKU 映射存在哪张表、按什么键匹配？

**表：`app.source_channel_skus`。键：`(source_channel, source_sku_ref)`，其中中汇的 `source_sku_ref` 取 Excel 的「商品编号」列，不是「商品名称」。**

链路（全部一手代码，行号对应上述基线）：

1. **解析**：`backend/src/main/java/cn/zimu/fulfillment/file/SourceFileParser.java:377-388`
   `zhonghuiRow(...)` 把 `value(cells, "商品编号")` 放进 `sourceSkuRef` 位，`value(cells, "商品名称")` 放进 `productName` 位。名称只做快照展示，不参与匹配。

2. **规范化**：`backend/src/main/java/cn/zimu/fulfillment/file/SourceImportService.java:812-819`
   `canonicalItems()` 先试 `activeSourceBundle(channel, row.sourceSkuRef())`；但 `bundleSourceChannel()`（`SourceImportService.java:902-906`）只认 `DAZHE / WANGQI / WANQI`，**中汇不在内**，所以中汇永远直接落 `singleItem(row)`（`SourceImportService.java:908-919`），`LineType.SINGLE`，携带 `sourceSkuRef`。
   → **`app.source_channel_bundles` 与中汇订单完全无关**，中汇不会查这张表。

3. **匹配**：`backend/src/main/java/cn/zimu/fulfillment/order/OrderCreateService.java:507-517` → `findMapping()`（同文件 `721-731`）：

   ```java
   return sourceChannelSkuRepository
           .findBySourceChannelAndSourceSkuRef(channel, sourceSkuRef)
           .filter(SourceChannelSku::isActive)
           .filter(mapping -> mapping.getQuantityMultiplier() != null)
           .filter(mapping -> mapping.getQuantityMultiplier().signum() > 0)
           .orElse(null);
   ```

   `null` → 订单行落 `NEED_REVIEW` + `exceptionCode = SKU_MAPPING_REQUIRED`（`OrderCreateService.java:511-516`）。
   仓储方法定义在 `backend/src/main/java/cn/zimu/fulfillment/sku/SourceChannelSkuRepository.java:11-12`，**只按 `(sourceChannel, sourceSkuRef)` 精确等值查**，没有任何名称/模糊/相似度回退。

4. **落到 raw 行的错误码**：`SourceImportService.java:1299-1308` 把 `SKU_MAPPING_REQUIRED` 翻译成 raw 行的 `SKU_MATCH`——这就是用户看到的 `SKU_MATCH`。

5. **候选表排除**：
   - `app.source_channel_bundles`：只服务 DAZHE/WANGQI/WANQI（上文第 2 点）。
   - `app.provider_skus`：京东侧编码（`EMG…`），出库阶段用，不参与来源解析。
   - `app.skus`：内部 SKU 主数据，是映射的**目标**（`source_channel_skus.sku_id` 外键），不是匹配键。

**唯一性**：`backend/src/main/resources/db/migration/V1__baseline.sql:153-168` 建表带 `UNIQUE (source_channel, source_sku_ref)`——所以「多命中歧义」在结构上不可能发生。
ZHONGHUI 是 `V31__add_zhonghui_source_channel.sql` 加进 `source_channel` CHECK 的。

---

## 2. 生产库里 ZHONGHUI 渠道现有多少条映射、覆盖哪些来源标识？

**13 条，全部 `active=true`。**

```sql
SELECT id, source_channel, source_sku_ref, source_product_name, source_specification,
       quantity_multiplier, sku_id, active, created_at
FROM app.source_channel_skus WHERE source_channel='ZHONGHUI' ORDER BY id;
```

真实输出（`psql -t -A`，`|` 分隔）：

```
29|ZHONGHUI|60043823|子牧澳洲谷饲上脑牛肉片1KG|1KG|1.000|1|t|2026-08-18 16:57:44.003053+08
30|ZHONGHUI|60043832|子牧牛后腿肉500g*2|500g*2|2.000|35|t|2026-08-18 16:57:47.846823+08
31|ZHONGHUI|60043849|子牧原切纯肉羊腿肉500g*2|500g*2|2.000|9|t|2026-08-18 16:57:48.004817+08
32|ZHONGHUI|60043845|子牧羊小腿（羊腱子）500g*2|500g*2|2.000|2|t|2026-08-18 16:57:48.148719+08
33|ZHONGHUI|60043825|子牧澳洲纯种和牛牛肉饼200g*4（八片）|200g*4（八片）|8.000|46|t|2026-08-18 16:57:51.0559+08
35|ZHONGHUI|60043831|子牧 原切牛肋条 500g*2|500g*2|2.000|21|t|2026-08-19 17:04:00.647523+08
61|ZHONGHUI|60043847|子牧带骨羊后腿块500g*2||2.000|6|t|2026-08-21 10:45:27.782882+08
62|ZHONGHUI|60043837|子牧原切澳洲谷饲牛蝎子400g*2||2.000|28|t|2026-08-21 10:45:28.248729+08
63|ZHONGHUI|60043848|子牧羊肉馅500g*2||2.000|13|t|2026-08-21 10:45:28.31246+08
64|ZHONGHUI|83755271|子牧雷山高海拔农家散养土黑猪五花肉450g*2|来源未提供|1.000|63|t|2026-08-21 10:50:11.422816+08
65|ZHONGHUI|83755270|子牧雷山高海拔农家散养土黑猪排骨 450g*2|来源未提供|1.000|64|t|2026-08-21 10:50:11.542479+08
68|ZHONGHUI|60043831-RESEND-UNIT|子牧 原切牛肋条 500g 单袋补发||1.000|21|t|2026-08-21 15:18:55.990113+08
69|ZHONGHUI|60043836|子牧澳洲谷饲牛肋排400g*2||2.000|25|t|2026-08-24 10:08:16.508512+08
```

覆盖的来源标识：`60043823 / 60043825 / 60043831 / 60043832 / 60043836 / 60043837 / 60043845 / 60043847 / 60043848 / 60043849 / 83755270 / 83755271`，外加一条**人造**标识 `60043831-RESEND-UNIT`（见 §7.3）。

各渠道条数：

```sql
SELECT source_channel, count(*), count(*) FILTER (WHERE active) FROM app.source_channel_skus GROUP BY 1 ORDER BY 1;
```

```
CAISHIXIAN|29|29
DAZHE|12|12
FEIXIANG|2|2
WANGQI|12|12
WECOM|6|6
ZHONGHUI|13|13
```

（`JUFUBAO` 0 条，`WANQI` 0 条。）

---

## 3. `60043846` 到底是真的没配，还是配了但没命中？

**真的没配。** 逐条排除四个候选原因：

| 候选原因 | 结论 | 证据 |
|---|---|---|
| `active=false` | 排除 | ZHONGHUI 13 条全 `active=t`（§2），且里面没有 `60043846` |
| 多命中歧义 | 结构上不可能 | `UNIQUE (source_channel, source_sku_ref)`，`V1__baseline.sql:166` |
| `source_channel` 值不是 `'ZHONGHUI'` | 排除 | 全表 LIKE 扫描只找到 CAISHIXIAN 的同名商品，见下 |
| `quantity_multiplier` 不为 1 | 不适用 | 代码只要求 `!= null && signum() > 0`（`OrderCreateService.java:728-729`），`2.000` 完全合法；何况这条根本不存在 |
| 键写成名称而不是编号 | 排除 | 中汇 `source_sku_ref` 只可能来自「商品编号」（`SourceFileParser.java:380,384`），且表里也没有一条 ZHONGHUI 的名称键 |

**全渠道扫描（编号或名称任一命中）：**

```sql
SELECT id, source_channel, source_sku_ref, source_product_name, sku_id, active
FROM app.source_channel_skus
WHERE source_sku_ref LIKE '%60043846%' OR source_product_name LIKE '%羊腿小切%';
```

```
25|CAISHIXIAN|2056585|子牧羊腿小切500g*2|7|t
```

**只有一行，而且是彩食鲜的。** 这就是关键事实：同一件商品（内部 `sku_id=7`）在彩食鲜配过、中汇漏配。彩食鲜那条完整值：

```sql
SELECT id, source_channel, source_sku_ref, source_product_name, source_specification,
       quantity_multiplier, sku_id, active FROM app.source_channel_skus WHERE id=25;
```

```
25|CAISHIXIAN|2056585|子牧羊腿小切500g*2||2.000|7|t
```

→ **乘数 2.000**。这直接给出了中汇该配的值。

**排除脏数据（空格/不可见字符/前导零）：**

```sql
SELECT '['||(raw_cells->>'商品编号')||']' AS bracketed, length(raw_cells->>'商品编号') AS len
FROM app.raw_import_rows WHERE id=27;
```

```
[60043846]|8
```

恰好 8 位、无空白。查询键干净。

**该批次全部中汇商品编号 vs 已配置：**

```sql
SELECT DISTINCT r.raw_cells->>'商品编号' AS ref, r.raw_cells->>'商品名称' AS nm,
       (SELECT count(*) FROM app.source_channel_skus s
        WHERE s.source_channel='ZHONGHUI' AND s.source_sku_ref = r.raw_cells->>'商品编号') AS mapped
FROM app.raw_import_rows r JOIN app.import_batches b ON b.id=r.import_batch_id
WHERE b.source_channel='ZHONGHUI' ORDER BY 3, 1;
```

```
60043846|子牧羊腿小切500g*2|0
60043831|子牧 原切牛肋条 500g*2|1
60043849|子牧原切纯肉羊腿肉500g*2|1
83755270|子牧雷山高海拔农家散养土黑猪排骨 450g*2|1
```

四个编号里唯一 `mapped=0` 的就是 `60043846`。注意 ZHONGHUI 已配了 `60043845 / 60043847 / 60043848 / 60043849` ——**编号序列里恰好跳过了 `60043846`**，是一次人工配置遗漏，不是系统性缺陷。

**事故现场（订单 15 / 批次 29）：**

```sql
SELECT id, line_no, line_type, sku_id, product_name_snapshot, specification_snapshot,
       source_quantity_snapshot, mapping_multiplier_snapshot, requested_quantity,
       processing_stage, exception_code, exception_reason
FROM app.order_lines WHERE order_id=15 ORDER BY line_no;
```

```
15|1|SINGLE|9|子牧原切纯肉羊腿肉500g*2|500g*2|1.000|2.000|2.000|NEED_REVIEW||
16|2|SINGLE||子牧羊腿小切500g*2|500g*2|||1.000|NEED_REVIEW|SKU_MAPPING_REQUIRED|未找到来源 SKU 映射: 60043846
```

```sql
SELECT id, sheet_index, row_index, status, error_code, order_id, order_line_id
FROM app.raw_import_rows WHERE import_batch_id=29 ORDER BY sheet_index, row_index;
```

```
26|0|2|ACCEPTED||15|15
27|0|3|NEED_REVIEW|SKU_MATCH|15|16
28|0|4|ACCEPTED||16|17
29|0|5|ACCEPTED||17|18
30|0|6|ACCEPTED||18|19
```

批次 29 状态 `COMPLETED_WITH_REVIEW`、`confirmed_at` 为 NULL——**整批卡在这一行上**（确认闸门要求 `raw_import_rows` 全部 `ACCEPTED`，`SourceImportService.java:951-957`）。

---

## 4. 中汇订单的映射解析路径，与彩食鲜/飞象/大者有何不同？

### 4.1 路径分叉只有一处：是不是「礼包渠道」

`SourceImportService.java:902-906`：

```java
private boolean bundleSourceChannel(SourceChannel channel) {
    return channel == SourceChannel.DAZHE
            || channel == SourceChannel.WANGQI
            || channel == SourceChannel.WANQI;
}
```

| 渠道 | 查 `source_channel_bundles`？ | 命中不了怎么办 | 最终查 `source_channel_skus`？ |
|---|---|---|---|
| ZHONGHUI | **否** | — | 是（永远走 SINGLE） |
| CAISHIXIAN | 否 | — | 是（永远走 SINGLE） |
| FEIXIANG | 否 | — | 是（永远走 SINGLE） |
| JUFUBAO | 否 | — | 是（永远走 SINGLE） |
| DAZHE / WANGQI / WANQI | **是**（`activeSourceBundle`） | 名称含「礼包/礼盒/组合」→ 造一个必然未映射的组件 `__BUNDLE_MAPPING_REQUIRED__:<ref>` 强制进复核（`SourceImportService.java:863-882`）；否则降级 SINGLE | 命中礼包则不查；降级 SINGLE 才查 |

**所以中汇和彩食鲜/飞象走的是完全同一套解析：SINGLE → `findMapping(channel, sourceSkuRef)` → `source_channel_skus`。差异只在 `sourceSkuRef` 从哪一列取。**
大者不同：它多了一层礼包表前置解析。

### 4.2 各渠道的 `source_sku_ref` 取值列（`SourceFileParser.java`）

| 渠道 | 取值列 | 行号 |
|---|---|---|
| CAISHIXIAN | `商品编号` | `302` |
| FEIXIANG | `商品ID`（回退 `订单商品ID`） | `345` |
| ZHONGHUI | `商品编号` | `384` |
| WANGQI（及 DAZHE v1，15 列） | `主商品编码` | `426`（DAZHE v1 复用 `wangqi()`，见 `252-254`） |
| WANQI（52 列） | `skuid` | `439` |
| **DAZHE v2（11 列）** | **`商品名称`** | `404-416` |

DAZHE v2 用名称是**有意为之**且写了理由（`SourceFileParser.java:390-403`）：那份导出根本没有编码列，「硬要伪造一个编码只会让映射错得更隐蔽」。

### 4.3 会不会因此产生键空间冲突？

**跨渠道：不会。** 主键是 `(source_channel, source_sku_ref)` 复合，查询也永远带 channel（`SourceChannelSkuRepository.java:11`）。中汇的 `60043846` 和大者的「子牧xxx礼包」不在同一个命名空间里。

**渠道内、跨模板：会，而且已经在 DAZHE 上真实存在。** 这是本次调研的意外发现：

同一个 `DAZHE` 渠道，v1 文件按 `主商品编码` 建键、v2 文件按 `商品名称` 建键，两者共用同一张 `UNIQUE (source_channel, source_sku_ref)` 命名空间。生产库里 DAZHE 的 12 条 `source_channel_skus` **全部是 `EMG…` 编码键**：

```sql
SELECT id, source_sku_ref, source_product_name, quantity_multiplier, sku_id, active
FROM app.source_channel_skus WHERE source_channel='DAZHE' ORDER BY id;
```

```
48|EMG4418691851778|羊小腿|1.000|2|t
49|EMG4418861053763|带骨羊后腿块|1.000|6|t
50|EMG4418861052375|带骨羊肉块|1.000|3|t
51|EMG4418819504770|法式羊排|1.000|10|t
52|EMG4418727170819|羊蝎子|1.000|5|t
53|EMG4418819505546|羊棒骨|1.000|15|t
54|EMG4418904458684|羊肉馅|1.000|13|t
55|EMG4418727173231|牛腩块|1.000|22|t
56|EMG4418824976893|牛腱子|1.000|37|t
57|EMG4418705676249|筋头巴脑|1.000|26|t
58|EMG4418697325133|新西兰羔羊肉卷|1.000|12|t
59|EMG4418767459988|羊排块|1.000|4|t
```

而生产库里唯一一个 DAZHE 批次是 **v2**：

```sql
SELECT source_channel, template_family, template_version, count(*), min(received_at)::date, max(received_at)::date
FROM app.import_batches GROUP BY 1,2,3 ORDER BY 1,3;
```

```
CAISHIXIAN|CAISHIXIAN_SOURCE_ORDER|v1|6|2026-08-24|2026-08-26
DAZHE|DAZHE_SOURCE_ORDER|v2-11-columns|1|2026-08-26|2026-08-26
FEIXIANG|FEIXIANG_SOURCE_ORDER|v1|12|2026-08-24|2026-08-26
ZHONGHUI|ZHONGHUI_SOURCE_ORDER|v1|1|2026-08-27|2026-08-27
```

→ **那 12 条 EMG 键的 DAZHE 映射，v2 文件永远查不到**（v2 拿名称去查）。目前唯一那一行 DAZHE v2 是礼包，靠 `source_channel_bundles` 的名称键命中了，所以还没爆；一旦大者 v2 里出现一个**非礼包的单品行**，它必然落 `SKU_MAPPING_REQUIRED`，即使那件商品的 EMG 映射就躺在表里。详见 §7.1。

顺带：这 12 条 DAZHE 的 `source_sku_ref` **就是京东履约编码**——

```sql
SELECT s.source_sku_ref, s.sku_id, ps.provider_sku_code, ps.sku_id AS provider_sku_id,
       CASE WHEN ps.provider_sku_code IS NULL THEN 'NOT_A_JD_CODE'
            WHEN ps.sku_id=s.sku_id THEN 'SAME_SKU' ELSE 'DIFFERENT_SKU' END AS verdict
FROM app.source_channel_skus s
LEFT JOIN app.provider_skus ps ON ps.provider_sku_code=s.source_sku_ref
WHERE s.source_channel='DAZHE' ORDER BY s.id;
```

12 行全部 `SAME_SKU`。WANGQI 的 12 条也是同一批 EMG 键。这和 `SourceImportService.java:810` 那句注释「EMG 是京东履约编码，不能冒充来源渠道 SKU 映射」是**直接冲突**的——注释说的规矩，数据里没守住。（这是观察，不是本次事故的成因。）

---

## 5. 复核事项 27 的 `detail`

```sql
SELECT id, case_no, case_type, status, reason_code, responsible_team, order_id, order_line_id, created_at
FROM app.review_cases WHERE id=27;
```

```
27|RC-ORD-D28A057AC7A84B01A8E77A8F07614667-2|SKU_MAPPING|OPEN|SKU_MAPPING_REQUIRED|SKU_OPS|15|16|2026-08-27 09:39:16.233218+08
```

```sql
SELECT jsonb_pretty(detail) FROM app.review_cases WHERE id=27;
```

```json
{
    "line_no": 2,
    "source_unit": "份",
    "evidence_items": [
        {
            "unit": "份",
            "quantity": "1.000",
            "product_name": "子牧羊腿小切500g*2",
            "specification": "500g*2",
            "source_sku_ref": "60043846"
        }
    ],
    "source_channel": "ZHONGHUI",
    "source_quantity": "1.000",
    "source_row_index": 3,
    "source_sheet_name": "Sheet1",
    "source_product_name": "子牧羊腿小切500g*2",
    "source_specification": "500g*2",
    "missing_source_sku_refs": [
        "60043846"
    ]
}
```

**`missing_source_sku_refs = ["60043846"]` 就是系统实际拿去查的键**（`OrderCreateService.java:516` 把 `item.sourceSkuRef()` 原样塞进去，`914` 落进 detail）。系统拿的是**编号**，不是名称——问题 1 的答案在这里得到运行时确证。

`missing_source_sku_refs` 只有 1 个元素，这一点很重要：前端 `buildSkuResolution` 要求恰好 1 个才允许在抽屉里直接解决（`frontend/src/pages/workbench/manualReviewActions.ts:57-60`），后端 `requireSingleLineEvidence` 同样校验（`ReviewCaseResolutionService.java:472-483`）。**本例满足，可以在复核抽屉里一步修好。**

---

## 6. 运营该怎么修（确切操作）

### 6.1 推荐路径：复核收件箱一步闭环

1. 打开 **我的工作台 → 复核收件箱**（`/workbench/reviews`，菜单标签见 `frontend/src/navigation.ts:23`）。
2. 找到事项 **`RC-ORD-D28A057AC7A84B01A8E77A8F07614667-2`**（事项类型显示为「SKU 映射待确认」，`frontend/src/constants/reasonLabels.ts:15`；关联订单 `ORD-D28A057AC7A84B01A8E77A8F07614667`，收件人 陈治周）。
3. 抽屉里「证据」区会显示：商品名称 `子牧羊腿小切500g*2` / 规格 `500g*2` / 单位 `份` / 数量 `1.000` / 来源商品编号 `60043846`。
4. 在 **SKU 选择框**（占位符「选择已确认 SKU」）里选 **`羊腿小切（SKU-JD-000007）`** —— 内部 `sku_id = 7`。
5. 在 **「数量换算」** 输入框填 **`2.000`**。
   依据：来源是「500g\*2」两袋装，内部 SKU 是 500g/件；且同商品在彩食鲜渠道已按 `2.000` 配置（`source_channel_skus.id=25`），中汇同类 `*2` 商品也一律 `2.000`（§2）。
6. 点 **「确认并解决」**。

**这一步会同时做完三件事**（`ReviewCaseResolutionService.java:221-258`、`486-528`）：

- 在 `app.source_channel_skus` 补建 `('ZHONGHUI','60043846', sku_id=7, quantity_multiplier=2.000, active=true)`，`source_product_name/source_specification` 从订单行快照带入；
- 修好订单行 16：`sku_id=7`、`requested_quantity = 1.000 × 2.000 = 2.000`、清空异常码；
- 订单 15 无其他 OPEN 复核 → 置 `SKU_MAPPED`，raw 行 27 翻成 `ACCEPTED`，批次 29 变成可确认。

7. 回到来源导入批次 29（`IMP-891EF2C956C34959AD04F3E4B9CEBE85`），执行**确认批次**，后续出库/发货照常。

**为什么不走「SKU 映射矩阵」页**：`/product/sku-mappings` 的矩阵只支持 `FEIXIANG / CAISHIXIAN / JUFUBAO` 三个渠道（`frontend/src/pages/product/skuMappingMatrix.ts:3-12`），**中汇不在列**，页面上根本没有中汇这一列可以点「未映射 · 添加」。同页上方的「映射资料核对」面板也只为这三个渠道建候选别名表（`ProviderSkuMappingReferenceService.java:106`），中汇上传样表会拿到空候选。详见 §7.2。

### 6.2 兜底路径（需要工程配合）：直接调主数据 API

若因权限或界面问题走不通复核抽屉：

```
POST /api/v1/master-data/source-sku-mappings
Headers: Idempotency-Key: <uuid>, X-Operator: <工号>
Body: {
  "source_channel": "ZHONGHUI",
  "source_sku_ref": "60043846",
  "source_sku_name": "子牧羊腿小切500g*2",
  "sku_id": "7",
  "quantity_multiplier": "2.000",
  "active": true
}
```

（`MasterDataController.java:130-133` → `MasterDataService.createSourceMapping`，`686-703`）
**注意**：这条只补主数据，**不会**自动修好已经落成 `NEED_REVIEW` 的订单行 16——之后仍要回复核收件箱把事项 27 处理掉（此时抽屉里再选 sku 7 + 2.000 会走「已存在且一致」分支，不会报冲突，`ReviewCaseResolutionService.java:223-234`）。所以**优先用 6.1**。

### 6.3 后续不会再被卡的确认

内部 `sku_id=7` 的京东履约映射已存在且启用：

```sql
SELECT id, fulfillment_provider_id, sku_id, provider_sku_code, active FROM app.provider_skus WHERE sku_id IN (7,9);
```

```
9|1|9|EMG4418691852262||t
7|1|7|EMG4418737161147||t
```

→ 修完来源映射后不会再撞 `JD_SKU_MAPPING_BLOCKED`。

---

## 7. 你没问、但应该知道的

### 7.1 大者 v2 的键空间已经断了（潜在同型事故，尚未爆）

见 §4.3。DAZHE 的 12 条 `source_channel_skus` 全部按 `EMG…` 编码键配置，而生产上唯一的大者模板是 v2（按**名称**建键）。**这 12 条映射对 v2 文件是死数据。**
目前没爆，只因唯一那行 v2 是礼包、走了 `source_channel_bundles`（`id=7`，`source_bundle_ref='子牧牛肉豪华大礼包6000g（BJ）'`，名称键）。
一旦大者 v2 出现非礼包单品行 → 必然 `SKU_MAPPING_REQUIRED`，且运营在矩阵页也**看不到 DAZHE 列**去配（同 §7.2）。
另有一个更隐蔽的相邻风险（**推断**，未在生产观察到）：WANGQI/WANQI 的礼包行如果编号没配进 `source_channel_bundles`、名称又不含「礼包/礼盒/组合」，`looksLikeBundle()`（`SourceImportService.java:879-882`）不会拦，会降级 SINGLE 去查 `source_channel_skus`——若同编号恰好配过单品映射，就会**把一个礼包静默当成单品发出去**。这条纯属代码路径推演，现网数据里两套键（`P26…` vs `EMG…`）不重叠，暂时撞不上。

### 7.2 运营根本无法为中汇/大者/万齐/网旗预先配置来源 SKU 映射

`SOURCE_MAPPING_CHANNELS = ['FEIXIANG','CAISHIXIAN','JUFUBAO']`（`frontend/src/pages/product/skuMappingMatrix.ts:8-12`）。
后端 `SourceChannel` 枚举有 8 个值（`common/domain/SourceChannel.java`），前端矩阵只暴露 3 个。
「映射资料核对」面板同样只给这 3 个渠道建别名表（`ProviderSkuMappingReferenceService.java:106`）。

**结果：ZHONGHUI / DAZHE / WANGQI / WANQI 的来源 SKU 映射，界面上只能「事后在复核抽屉里补」，无法「事前在主数据页配」。** 本次事故的运营体感（「为什么别的都好好的，就这个要复核」）就来自这里——中汇的 13 条映射也全是这么一条条补出来的（**推断**：审计日志只保留到 2026-08-25，08-18～08-24 那批的创建路径无法从 `audit_logs` 证实；仓库里也没有任何 migration/seed 插入这些行）。
生产上 `review_case.resolve_sku` 已被用过 3 次（`SELECT service, operation, count(*) FROM app.audit_logs WHERE operation LIKE '%sku%'` → `ReviewCaseResolutionService|review_case.resolve_sku|3|2026-08-25|2026-08-26`），对应 2 条 FEIXIANG + 1 条 CAISHIXIAN 的 RESOLVED 事项——**这条路径是经过生产验证的**。

建议（未实施，只读调研）：把 `SOURCE_MAPPING_CHANNELS` 补齐到全部文件来源渠道，让运营能在导入前把映射配好，而不是每次靠一单卡住才补。

### 7.3 `source_channel_skus` 被当成手工补发的换算表用

`id=68`：`ZHONGHUI / 60043831-RESEND-UNIT / 子牧 原切牛肋条 500g 单袋补发 / 乘数 1.000 / sku_id=21`。
这个 `source_sku_ref` **任何中汇导出文件里都不会出现**——它是为了「补发单袋」人造的键。同类还有 WECOM 渠道的 `CORR-SKU-CAISHIXIAN-2152074`、`CORR-SKU-ZHONGHUI-60043831`、`WECOM-DRAFT-*-L1`。
不影响本次事故，但意味着这张表的「来源标识」语义已经混了真实平台编号和内部人造编号两类，未来做对账/覆盖率统计时会被这些行污染。

### 7.4 只剩一条同型 OPEN 事项

```sql
SELECT id, case_no, reason_code, status, order_id, detail->>'source_channel', detail->>'missing_source_sku_refs'
FROM app.review_cases WHERE case_type='SKU_MAPPING' AND status='OPEN';
```

```
13|RC-ORD-0225AD9594CA48DEB131E717251FCD3D-1|SKU_MAPPING_REQUIRED|OPEN|6|CAISHIXIAN|["2066449"]
27|RC-ORD-D28A057AC7A84B01A8E77A8F07614667-2|SKU_MAPPING_REQUIRED|OPEN|15|ZHONGHUI|["60043846"]
```

彩食鲜 `2066449` 也是同一个毛病（漏配），顺手可以一起处理。

---

## 8. 已核实 vs 推断

**已核实（代码行号 + 生产 SQL 输出双证）：**

- 中汇按 `app.source_channel_skus.(source_channel, source_sku_ref)` 精确匹配，键取「商品编号」；中汇不查 `source_channel_bundles`。
- ZHONGHUI 现有 13 条映射，全部 active，不含 `60043846`。
- `60043846` 未配置；`active=false`／多命中／channel 值错／乘数问题／键写成名称，五个候选原因全部排除。
- 同商品在彩食鲜配过（`id=25`，`sku_id=7`，乘数 `2.000`）。
- 事项 27 的 `missing_source_sku_refs = ["60043846"]`、`evidence_items[0].source_sku_ref = "60043846"`。
- 复核抽屉「确认并解决」会同时补建映射 + 修订单行 + 解锁批次确认。
- 前端 SKU 映射矩阵与映射资料核对面板均只覆盖 FEIXIANG/CAISHIXIAN/JUFUBAO。
- DAZHE 12 条映射全部是 EMG 京东编码键；生产唯一 DAZHE 批次是 v2（名称键）。
- `sku_id=7` 已有京东 provider 映射 `EMG4418737161147`。

**推断（标明为推断，未取得直证）：**

- 数量换算应填 `2.000` —— 依据是彩食鲜同商品的既有配置与中汇同类 `*2` 商品的一致做法，**不是**中汇平台文档的直证。执行前建议运营用一件商品实物核对一次。
- ZHONGHUI 那 13 条映射是通过复核抽屉或直接调 API 建的 —— `audit_logs` 只保留到 2026-08-25，早于此的创建路径无法证实；仓库里没有对应的 migration/seed。
- §7.1 里「礼包被静默当成单品发出」的路径是代码推演，现网未观察到实例。
- 「`60043846` 是一次人工遗漏而非系统缺陷」这一判断，依据是编号序列 `…845/847/848/849` 已配、独缺 `846`，属强证据但仍是推断。
