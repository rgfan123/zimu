# 聚福宝映射键取证 + 成本表全列进商品档案

日期：2026-08-27
代码基线：`73afb11`（`feat(wecom): 新增企微机器人管理台账`）
生产库：`zimupc` / `zimu-fulfillment-postgres-1` / `fulfillment_hub`
地基文档：`docs/research/jd-code-cost-mapping-2026-08-27.md`（上一位对同一份成本文件的解剖，本文直接引用，不重复）
输入文件：
- `/Users/jerry/zimu-work/inbox/A产品成本核算26.3.29.xlsx`（SHA-256 `e185b33f…`）
- `/Users/jerry/zimu-work/inbox/京东商品编号.xlsx`（SHA-256 `85ca324d…`）

---

## 结论速览

| 任务 | 结果 |
|---|---|
| 聚福宝映射 | **落 0 条，且这是正确结果。** 键是「商品编号」（平台数字 `product_id`），而手上任何文件都没有一个子牧商品的聚福宝 `product_id`。按品名或 EMG 硬造键会得到解析器永远命中不了的假映射。 |
| 成本表全列档案 | **做完并验证。** 新表 `app.product_archive_sheets`（迁移 V63），110 行 × 47 列全列原序，3 行确定挂接。生产尚未建表（V62/V63 未部署），灌库脚本已备，随下次部署执行。 |
| 成本并进 manifest | **执行不了，硬阻断。** 治理指定的价格源工作簿不在本机，生成器指纹门当场失败关闭。详见 §4。 |

---

## 1. 聚福宝映射键：三源互证

### 1.1 证据源一 —— 生产 `app.source_channel_skus` 的既有形态

```
CAISHIXIAN 29 / DAZHE 12 / FEIXIANG 2 / WANGQI 12 / WECOM 6 / ZHONGHUI 14
JUFUBAO 0
```

**聚福宝一条映射都没有**——这正是用户说的「聚福宝商品没有添加映射」。所以这一源提供不了「既有键长什么样」，只确认了缺口本身。

顺带看清了全库两种键形态：

| 形态 | 渠道 | 样例 |
|---|---|---|
| 平台数字商品号 | CAISHIXIAN / FEIXIANG / ZHONGHUI | `2047679` / `6627540` / `60043823` |
| EMG 京东编码 | DAZHE / WANGQI | `EMG4418691851778` |

DAZHE/WANGQI 用 EMG 当 `source_sku_ref` 看似可作先例，但那 12 条正是治理票在途的存疑数据（见地基文档 §3.3），不构成可抄的范例。**全程未触碰 WANGQI 那 12 条。**

### 1.2 证据源二 —— 后端两条聚福宝入口，键语义一致

聚福宝在代码里有**两条**订单入口，两条都把「商品编号」当 `sourceSkuRef`：

**拉单（HTTP pull，当前主线）** —— `backend/src/main/java/cn/zimu/fulfillment/connector/jufubao/JufubaoOrderTransform.java:158,169`：

```java
String productId = text(product, "product_id");
...
items.add(new OrderItemInput(
        subOrderId, LineType.SINGLE, null,
        productId,          // ← 第 4 位 = OrderItemInput.sourceSkuRef
        productName, ...));
```

`OrderItemInput` 第 4 个分量确认就是 `sourceSkuRef`（`backend/src/main/java/cn/zimu/fulfillment/order/dto/OrderItemInput.java:17`）。类注释也写死：`product_id → sourceSkuRef`。

**文件导入（Excel 闭环）** —— `backend/src/main/java/cn/zimu/fulfillment/file/SourceFileParser.java:315`：

```java
first(cells, "商品ID", "商品编码", "商品条码"), value(cells, "商品名称"), ...
```

模板指纹（同文件 `:775`）：`{主单号, 拆单号, 供货商, 渠道订单号, 结算方式, 需结算总额}`。

**解析器只按精确键查，没有品名兜底** —— `backend/src/main/java/cn/zimu/fulfillment/order/OrderCreateService.java:721-731`：

```java
private SourceChannelSku findMapping(SourceChannel channel, String sourceSkuRef) {
    if (sourceSkuRef == null || sourceSkuRef.isBlank()) return null;
    return sourceChannelSkuRepository
            .findBySourceChannelAndSourceSkuRef(channel, sourceSkuRef)   // ← 唯一查法
            .filter(SourceChannelSku::isActive) ...
}
```

仓库层也只有这一个查法（`SourceChannelSkuRepository.findBySourceChannelAndSourceSkuRef`）。**没有任何按 `source_product_name` 的回退。** 这条是决定性的：键写错，映射永远不命中，而且不会报错，只会一直卡 `SKU_MAPPING_REQUIRED`。

### 1.3 证据源三 —— 生产历史行

```
import_batches   where source_channel='JUFUBAO'  → 0 批
raw_import_rows  经 JUFUBAO 批次                  → 0 行
orders           where source_channel='JUFUBAO'  → 0 单
```

**聚福宝在生产上一单都没进来过**，没有 `raw_cells` 可取证。唯一的真实抓包在仓库里：

`docs/research/golden/jufubao-order-golden.json:20` → `"product_id": 66662134`
`docs/research/jufubao-supplier-export-api.md:123` → ``product_list[].product_id/product_name → source product reference/snapshot（`商品编号`语义）``

即真实 `product_id` 形如 **8 位纯数字 `66662134`**，且该抓包的商品是「yosibaby/羊小贝山羊奶」（供应商京诚乾元），不是子牧自有商品。

### 1.4 判定与为什么落 0 条

**三源互证一致：聚福宝映射键 = 商品编号（平台数字 `product_id`），不是品名，不是条码，不是 EMG。**

而我手上**没有任何一个子牧商品的聚福宝 `product_id`**：

| 候选来源 | 实际内容 | 有 `product_id` 吗 |
|---|---|---|
| `京东商品编号.xlsx` Sheet1 C/D 列 | 37 行「聚福宝商品名 + 件数」，如 `【京东配送】子牧澳洲谷饲上脑牛肉片1kg` | ❌ |
| `京东商品编号.xlsx` Sheet4 聚福宝参照表 | 37 行「EMG 码 + 聚福宝名 + 件数 + 渠道 + 单品」 | ❌ |
| `A产品成本核算26.3.29.xlsx` | 根本没有聚福宝列 | ❌ |

对四个 sheet 全表扫描 7–9 位纯数字：**0 个命中**（Sheet1/2/3/4 各 0）。

所以两条路都不能走：

- 按**品名**建映射 → `findMapping` 拿着 `66662134` 这类数字去查，永远查不到品名键 → 假映射，比没有更糟（看着像修好了，实际一直卡单）。
- 按 **EMG** 建映射 → 同理不命中；且 EMG 是京东出库侧编码，不是聚福宝渠道键（地基文档 §1.1 已定性）。

**净落库 0 条，`app.source_channel_skus` 未做任何写入。** 顺带说明：仓库权威清单本来就把 Sheet4 显式判为 `JUFUBAO_REFERENCE_OUT_OF_SCOPE`。

### 1.5 要把聚福宝映射真正建起来，需要什么

只缺一样东西：**一份带聚福宝「商品ID」的物料**。任一即可：

1. 聚福宝供应商后台导出的订单/商品表，含 `商品ID`（或 `商品编码`/`商品条码`）列——文件导入解析器这三个列名都认；
2. 跑 `scripts/jufubao_fetch_orders.py` 拉一批真实订单 JSON（落 `data-local/聚福宝订单-*.json`），从 `product_list[].product_id` + `product_name` 取「编号 ↔ 品名」对照；
3. 业务侧直接给「聚福宝商品ID ↔ 子牧商品」对照表。

拿到之后映射是机械活：`(JUFUBAO, product_id) → sku_id`，`quantity_multiplier` 按聚福宝件数（Sheet1 D 列/Sheet4 第 3 列已有 37 行件数可复用）。**本次没有猜。**

---

## 2. 成本表全列进商品档案（V63）

### 2.1 表设计与关键取舍

新表 `app.product_archive_sheets`，迁移 `backend/src/main/resources/db/migration/V63__product_archive_sheets.sql`。

**`fields` 是 jsonb 数组，不是对象——这是整张表的设计核心。** PostgreSQL 的 jsonb **对象**不保证键序（按键长+字节序重排），用对象存「列头→值」会把原表列序洗掉；jsonb **数组**保留元素顺序。所以：

```
fields[1] = A 列 …… fields[47] = AU 列
元素形如 {"column":"AI","name":"线下供货成本/份","value":"8.8761888888889"}
```

其余取舍：

| 决定 | 选择 | 理由 |
|---|---|---|
| 空单元格 | **保留元素、`value` 记 null**（不跳过） | 同一列在每行的数组下标恒定，读的人可以按位取列 |
| 列范围 | **A..AU 共 47 列**（表格正身） | 见 §2.2 |
| AK 列（表头行为空） | **收进来**，命名「（AK 列无表头）」 | 它在正身区间内部且列列有数据，是真列 |
| 幂等键 | `UNIQUE (source_file_sha256, row_no)` | 同一份文件重灌不产生第二行 |
| 挂接 | `matched_sku_id` / `matched_product_id` 可空 | 档案先落，挂接后补 |

列语义按 2026-08-27 用户裁决**钉死在迁移注释、表注释、DTO javadoc 与 OpenAPI 描述里**：

- **AI「线下供货成本/份」= 成本**（按份 = 500g 单袋口径）
- **AJ「售价」= 不含运费的售价**

并明确记下：**AJ 不入 `retail_price`**（口径与库内京东整售零售价不同）；**本表不回写 `skus.purchase_price / retail_price`**。

### 2.2 一个需要说明的边界：AU 之后的零散格子

成本表在表格正身右侧还有手工草稿格：**AW/AX/AY 在第 56-60 行、AZ..BF 在第 8 行**，共 20 行受影响。这些格子**没有表头，也不是表的列**。

处理方式：不混进 `fields` 冒充档案字段（否则每个商品档案都会多出 11 个名叫「（AW 列无表头）」的空字段），另存 `extra_cells` jsonb 数组。**一格都没丢**，但也不假装它们是档案字段。

### 2.3 灌库与校验

灌库脚本 `scripts/load_product_archive_sheets.py`，沿用权威目录 manifest 的治理纪律：**源文件 SHA-256 与固定值不符即拒绝生成**（`source drift` 失败关闭），不接受另存版本。输出幂等 `INSERT … ON CONFLICT (source_file_sha256, row_no) DO NOTHING`。

**生产尚未建表**：生产 `flyway_schema_history` 停在 61 条，`to_regclass('app.product_archive_sheets')` 为空——V62/V63 都还没部署。按纪律不部署，故 110 行**随下次部署后执行灌库脚本**即可。

为补上「没能跑生产」的验证缺口，在一次性 PostgreSQL 16 容器上把 **V63 DDL + 完整 110 行灌库 + 重复灌库**跑通（容器用完已销毁）：

| 校验项 | 结果 |
|---|---|
| DDL 在空库执行 | ✅ |
| 灌库 110 行 | ✅ `rows=110, distinct_rows=110` |
| 重复灌库（幂等） | ✅ 仍是 110 行，无第二份 |
| 每行列数 | ✅ 全部 `jsonb_array_length(fields) = 47`，无一例外 |
| 列序保持 | ✅ `fields[0].name='产品名称'(A)`，`fields[46].column='AU'` |
| AI/AJ 语义位 | ✅ 第 54 行 `AI 线下供货成本/份=58.3867368421053`、`AJ 售价=78` |
| 空单元格保位 | ✅ 1201 个 `value=null` 元素被保留 |
| extra_cells | ✅ 20 行带草稿格 |
| 挂接 | ✅ 3 行 `matched_sku_id` 非空 |

### 2.4 挂接：只落 3 条，2 条挂起等裁决

沿用地基文档 §4 的匹配结论并按「按份」口径复核了争抢是否仍成立：

**落库的 3 条**（品名逐字节相同 + 规格一致 + 无第二行争抢）：

| 成本行 | 品名 | 成本表规格 | SKU | 库内规格 |
|---|---|---|---|---|
| row54 | 新西兰羔羊羊颈排 | 1000g | `SKU-JD-000011` | 1kg |
| row55 | 新西兰羔羊肉卷 | 200g | `SKU-JD-000012` | 200g |
| row74 | 肩胛烤肉片 | 120g | `SKU-JD-000033` | 120g |

**挂起的 2 条，建议人工一句话裁决即可放行**：

| 成本行 | 品名/规格 | 目标 SKU | 争抢者 | 我的读法 |
|---|---|---|---|---|
| row70 | 原切西冷牛排 150g | `SKU-JD-000031`（150g） | row40「澳洲谷饲西冷牛排」**800g** | 争抢者是**剥词后**才命中且**规格对不上**，row70 是精确同名同规格 |
| row72 | 原切眼肉牛排 150g | `SKU-JD-000032`（150g） | row39「澳洲谷饲眼肉牛排」**800g** | 同上 |

按上一位「一 SKU 多行争抢即全跳过」的保守规则它们被推翻；但两个争抢者都是 LOOSE 匹配且规格不符（800g vs 150g），严格说不构成真竞争。**我按保守规则留空，等一句确认就能补挂。**

其余 105 行 `matched_*` 留空——其中 67 行库内根本没有对应 SKU（烧烤串品、研发品、鸵鸟/鸸鹋、窦清源品牌线），那是**新建 SKU** 的需求，不是挂接需求。

### 2.5 前后端改动

**后端（最小读法）**：既有商品档案详情是 `GET /api/v1/products/{id}` 返回 `MasterDataRecord`（`attributes` 开放 map）。把 47 列塞进 `attributes` 会污染所有商品的通用投影，故另开只读端点：

- `GET /api/v1/products/{id}/archive-sheet` → `List<ProductArchiveSheet>`（`MasterDataController`，商品不存在给 404，未挂接给空数组）
- `ProductArchiveSheet` / `ProductArchiveSheetService`（JdbcTemplate 只读，SQL 不对 fields 做任何重排）
- `docs/openapi.yaml` 补路径与 schema（契约门禁 `OpenApiContractConsistencyTest` 要求手写契约与运行时 spec 一致）

**前端**：商品档案页是 `/product/skus` → `SkusPage.tsx`（列表页，仓库里没有商品详情路由）。按 `ProcurementTicketsPage` 既有的 Drawer + `admin-detail-section` 风格加最小改动：

- `ProductArchiveSheetDrawer.tsx`（新）：只读表格，**按拿到的顺序铺开，不排序不过滤**
- `SkusPage.tsx`：加一列「成本档案 / 查看」打开抽屉，用该 SKU 的 `product_id` 取数
- `api/types.ts` + `api/endpoints.ts`：类型与 `productsApi.archiveSheet`

### 2.6 迁移棘轮与并行冲突

棘轮两处（`ProductionMigrationHistoryCompatTest`、`docs/schema.sql`）当时正被另一代理改着 V62，**基于他的版本追加，未覆盖**。

**实际发生的并行事故（记录备查）**：他的提交 `73afb11` 把我改在 `ProductionMigrationHistoryCompatTest` 里的 V63 条目**一并卷走了**，但没带上 `V63__product_archive_sheets.sql` 与 `docs/schema.sql` 的 V63 块——所以 `73afb11` 自身是不自洽的（棘轮断言 63 条历史，仓库里只有 62 个迁移文件）。本次提交补齐迁移文件与 schema 块后恢复自洽。

---

## 3. 测试结果

| 门禁 | 结果 |
|---|---|
| `npm run typecheck` | ✅ 通过 |
| `npm test`（node:test + vitest） | ✅ `Test Files 2 passed / Tests 8 passed`，unit 套件亦通过 |
| V63 DDL + 110 行灌库 + 幂等重灌（一次性 PG16 容器） | ✅ 见 §2.3 全表 |
| 后端 `mvn test`（`ProductArchiveSheetApiTest` 等） | ⚠️ **跑不了**：见下 |

**后端测试未能运行的原因，与我的改动无关。** 我的代码在 `mvn -o test-compile` 下先编译通过；随后第三个并行代理正在给 `CanonicalOrderInput` 加 `sourceOrderedAt` 分量，调用方尚未同步，工作区当前编译失败（33 个错误，全部落在 `OrderDraftService`/`OrderCreateService`/`SourceImportService`/`JufubaoOrderTransform` 这些他的在改文件）。**等他的重构落地后需补跑**：

```bash
cd backend && export JAVA_HOME=$(/usr/libexec/java_home -v 24) && unset JD_LOP_CLIENT_MODE
mvn -q -Dtest='ProductArchiveSheetApiTest,ProductionMigrationHistoryCompatTest,SchemaSnapshotMigrationEquivalenceTest,OpenApiContractConsistencyTest' test
```

新增测试 `ProductArchiveSheetApiTest` 的断言刻意用 `containsExactly` 盯列序：任何一次「把 fields 从数组改成对象」的优化，都会被 PostgreSQL 的对象键重排打穿。

---

## 4. 成本并进 manifest：硬阻断，一步都没走

用户拍板「成本按份记（AI 列）、走 manifest 治理路线」。**这条路当前执行不了**，原因是缺件而不是缺意愿：

**治理指定的价格源工作簿不在本机。** `docs/authoritative-jd-catalog.md` 把价格源固定为 `合作商品价格查询导出_按商品名称去重.xlsx`（SHA-256 `7fc1d34e…`），生成器 `scripts/generate-authoritative-jd-sku-catalog.mjs:18-23` 在**解析之前**同时校验两个指纹，任一不符即抛错。实测（只读，安全）：

```
$ node scripts/generate-authoritative-jd-sku-catalog.mjs \
    inbox/京东商品编号.xlsx inbox/A产品成本核算26.3.29.xlsx --check …
Error: source drift: jd=85ca324d…（符合）, price=e185b33f…（不符，这是成本表的指纹）
```

`mdfind` 全盘搜索 `合作商品价格查询导出` **0 命中**；`/Users/jerry/zimu-work/inbox/` 只有成本表、京东商品编号表和上一位的交接 CSV。**没有这份工作簿，`--write` 和 `--check` 都跑不起来。**

即便拿到它，还有三道必须由人先拍的关口：

1. **成本表喂不进这个生成器。** 它的价格 join 是「Sheet1 的彩食鲜商品名 **逐字节**等于价格表的商品名称」（`:186` 注释写明 intentionally strict），价格列固定读 `一件代发价格`/`建议售价`（sheet 名 `"0"`，表头 6 列固定）。成本表是另一种形状、另一套品名（`原切西冷牛排` vs `子牧澳洲谷饲上脑牛肉片1KG*1`），要把 AI 列并进去等于**改写生成器接受第三个源**——那是对治理工具本身的实质变更，不属于「按规程执行」。
2. **覆盖率断言会挡。** `:217-222` 硬断言 27 定价 / 34 未定价，加任何一条价都会 `authoritative coverage changed; review before regenerating` 抛错，这个常量必须作为受审查变更的一部分改。
3. **改了也不会在部署时生效。** 导入只由 `POST /api/v1/admin/catalog-imports/jd-authoritative` 触发（没有任何 startup runner 调它），且写入是**只填空、不覆盖**：`compatiblePrice` 让「库里已有价 ≠ manifest 新价」直接判 drift，整批 409 回滚。另外 manifest 换 hash 后，`AuthoritativeSkuCatalogManifestLoader.MANIFEST_SHA256` 与生产 61 条 `provider_skus.external_codes.catalog_manifest_sha256` 都要同步处理。

**建议**：这件事按治理文档要求另起变更审查票，先由业务数据负责人经受控渠道交付新的授权价格导出。**在那之前，AI 按份成本已经原值躺在 V63 全列档案里，读的人随时可查**——这一点本次已经做到。

---

## 5. 本次对生产做过什么 / 没做什么

**写过的（全部经 API，且只增不改）**：

| 对象 | 内容 | 用途 |
|---|---|---|
| `app.products` id=64 | `PROD-TP-CAISHIXIAN-2066449` / 子牧雷山黑猪套餐1800g / 品类 3 猪肉 | 彩食鲜解卡插队任务，见 §6 |
| `app.products` id=65 | `PROD-TP-CAISHIXIAN-2066613` / 子牧雷山黑猪里脊450g*2 / 品类 3 猪肉 | 同上 |

**没写过的**：`app.source_channel_skus`（聚福宝 0 条）、`app.skus`、`app.provider_skus`、`app.skus.purchase_price / retail_price`、任何订单业务数据、WANGQI 那 12 条 EMG（全程未触碰）、`app.product_archive_sheets`（生产尚未建表）。

---

## 6. 插队任务：彩食鲜两单解卡（未完成，卡在一个需要授权的生产修复）

### 6.1 单子与卡点（已查清）

| 订单 | order_no | 状态 | 行 | 渠道品名 | 渠道键 | 复核工单 |
|---|---|---|---|---|---|---|
| 6 | `ORD-0225AD9594CA48DEB131E717251FCD3D` | NEED_REVIEW | line 6 | 子牧雷山黑猪套餐1800g | `2066449` | RC id **13** OPEN |
| 20 | `ORD-6601C2206F744F87942C2AD8F1B79C82` | NEED_REVIEW | line 21 | 子牧雷山黑猪里脊450g*2 | `2066613` | RC id **38** OPEN |

（order 5 已是 `SKU_MAPPED`，不在卡点内。）两单 `exception_code` 均为 `SKU_MAPPING_REQUIRED`。

成本表里**没有任何猪肉行**（`猪` 命中 0 行 / 110 行），所以这两个商品拿不到成本，SKU 按未定价建——不编造。品类取 3（猪肉），与既有同系列 `PROD-TP-ZHONGHUI-83755270`（雷山黑猪排骨，品类 3）一致。

### 6.2 卡在哪：`app.sku_code_seq` 落后 62，API 建 SKU 全部 409

建商品成功（64/65），建 SKU 两次都是：

```json
{"business_code":"DUPLICATE_RESOURCE","http_status":409,"details":{"sql_state":"23505"}}
```

根因查清：`app.skus.sku_sequence_no` 默认取 `nextval('app.sku_code_seq')`，触发器 `enforce_sku_identity` 用它拼 `sku_code = 'SKU-'||provider_code||'-'||lpad(seq,6,'0')`。而

```
app.sku_code_seq.last_value = 2        ← 序列停在 2
max(app.skus.sku_sequence_no) = 64     ← 实际已用到 64
```

第 3..64 号是被种子/权威目录导入**显式指定** `sku_sequence_no` 插进去的，序列从未跟进。于是 API 建的下一个 SKU 拿到 seq=3 → `SKU-TP-000003` → 撞 `skus_sku_code_key`。

**这不只挡我：现在任何人从界面或 API 新建 SKU 都会 409。这是一个在产缺陷。** 我核对了同类序列，只有这一个落后（`skus_id_seq`/`provider_skus_id_seq`/`source_channel_skus_id_seq`/`review_cases_id_seq` 都正常）。

修复是一条幂等语句：

```sql
SELECT setval('app.sku_code_seq', (SELECT max(sku_sequence_no) FROM app.skus), true);
```

**我没有执行它**：它是走 API 之外的生产直写，被权限系统拦下了，而我不绕过这类拦截。它不动任何业务数据（只是把身份生成器追到实际水位），但**需要你明确授权**。

### 6.3 授权后的剩余步骤（已备好，按序执行即可）

1. `setval` 修复序列（上面那条）
2. `POST /api/v1/skus` ×2：`{"provider_id":"2","product_id":"64","specification":"1800g","unit":"套"}`、`{"provider_id":"2","product_id":"65","specification":"450g*2","unit":"袋"}`
3. `POST /api/v1/provider-sku-mappings` ×2：`provider_id=2`，`provider_sku_code` 用各自 `sku_code`（TP 路由要求 `provider_skus` 命中，否则卡 `PROVIDER_SKU_MAPPING_REQUIRED`）
4. `POST /api/v1/review-cases/13/resolve-sku`、`/38/resolve-sku`，body 形状（`ResolveSkuReviewCommand`）：
   `{"expected_version":<工单版本>,"sku_id":"<新 SKU>","source_channel":"CAISHIXIAN","source_sku_ref":"2066449|2066613","quantity_multiplier":"1.000"}`，
   头 `X-Operator: jry` + `Idempotency-Key`
5. 验收：两单 `order_status` 到 `SKU_MAPPED` → 等 1-2 分钟查 `app.wecom_business_cards` 出现 `card_domain='preship-batch'`、`status='SENT'`

`quantity_multiplier` 取 `1.000`：两个商品都按「整包 = 一个 SKU」建（套餐不展 BOM，里脊 SKU 规格本身就是 `450g*2`），与既有 ZHONGHUI 雷山黑猪映射的 `1.000` 一致。

**确认卡到达后不替用户点**——确认是用户在企微里点的。点完后 TP 八列发货清单会自动推到履约方 TP 的企微单聊（`wecom_group_chat_id = jry`，已核对）。

---

## 7. 待人决策清单

1. **授权 `setval('app.sku_code_seq', …)`**（§6.2）——否则彩食鲜两单无法解卡，且全站 SKU 新建持续 409。
2. **聚福宝映射需要一份带「商品ID」的物料**（§1.5）——三种取得方式任选。
3. **row70 / row72 是否补挂**（§2.4）——两行精确同名同规格，只因保守的争抢规则被挂起。
4. **成本进 manifest 需另起变更审查票**（§4）——先要授权的价格源工作簿。
5. **V62/V63 部署后执行灌库**：`python3 scripts/load_product_archive_sheets.py /Users/jerry/zimu-work/inbox/A产品成本核算26.3.29.xlsx | psql …`（幂等，可重复跑）。
6. **补跑后端测试**（§3）——等并行的 `CanonicalOrderInput` 重构落地。
