# 07 — 切断权威目录 JSON 的价格通道

Type: implementation
Status: 已实现并合入 main 工作区（未提交）
Priority: P1
Requested: Jerry 2026-08-28「将多来源收敛成唯一数据表 并删去旧的」

## 问题

价格收敛后（成本表 → `app.skus` 唯一真源），仓库里还留着**第二个自称权威的价格源**：
`backend/src/main/resources/data/authoritative-jd-sku-catalog.json`，27 个条目带
`purchase_price / retail_price`。

它现在的行为（`AuthoritativeSkuCatalogImportService.java:245-268`、`:320-345`）：

```java
updatePrices = purchaseCompatible && retailCompatible
        && ((purchasePrice != null && sku.getPurchasePrice() == null) || ...);
```

- **不会覆盖**已有值（fill-null 语义），所以不会破坏收敛后的数据
- 但**每次导入都会对不一致的 SKU 吐 `Drift`**——收敛后这 27 个条目全部与库内新值不符，
  drift 报告会稳定地宣称「库里的价格是错的」。**这就是要收敛掉的「多来源」本身。**
- 新建 SKU 分支（`:327-328`）仍会用 JSON 的价格初始化，等于给新 SKU 注入旧口径

另有一处文档级冲突：`docs/authoritative-jd-catalog.md` 写着
「不要从类似商品名自动补全价格」，而这份 JSON 的价格恰恰来自
**按商品名去重**的 `合作商品价格查询导出.xlsx`——正是名字匹配的产物。
实测后果：150g 的原切西冷牛排被匹配到一个整箱挂牌价（88.00），
而成本表说这个规格的供货成本是 12.15、售价 20。

## 范围

### 1. 让 manifest 不再承载价格

`AuthoritativeSkuCatalogManifestLoader.java` 有**硬断言**（`:107-110`）：

```java
if (!sourceRows.equals(expectedRows) || duplicateCodes != 2
        || priced != 27 || manifest.items().size() - priced != 34) {
    throw invalid("权威京东商品 manifest 明细与汇总不一致");
}
```

所以**不能只把 JSON 里的价格置空** —— loader 会拒绝加载。必须成对修改：

- JSON：27 个条目的 `purchase_price / retail_price / price_match_name / price_source_row` 置 null；
  `expected.pricedCount` → 0、`unpricedCount` → 61（以实际条目数为准，落笔前核对）
- loader：`priced != 27` → `priced != 0`；`requireItem` 里 `priced` 分支（`:167-183`）
  改为「携带任何价格字段即非法」，把「不得携带价格」变成**受测的不变量**，防止价格日后被写回来
- `AuthoritativeSkuCatalogManifest.java:31-32` 的 `purchasePrice / retailPrice` 记录字段：
  保留字段但恒为 null（JSON schema 兼容），还是彻底删掉——你判断哪个更干净；
  **若删，`priceMatchName / priceSourceRow` 一并删**

### 2. 删掉导入服务的价格写入

`AuthoritativeSkuCatalogImportService.java`：

- 删 `updatePrices` 计算（`:247-269`）与两处 `sku.setPurchasePrice/setRetailPrice`（`:327-328`、`:335-336`）
- 删 `sku.purchase_price` / `sku.retail_price` 两类 drift（`:252-264`）
- `ItemPlan` 记录里的 `purchasePrice / retailPrice / updatePrices`（`:600-601`）随之删
- `AuthoritativeSkuCatalogImportReport.java:37-38` 的价格字段随之删
- **新建 SKU 分支不再设价**：新 SKU 的价格留空，等成本表挂接后由主数据流程补
- 其余职责（品类、规格、provider 映射、别名、drift）**完全不动**

### 3. 生成器与文档

- `scripts/generate-authoritative-jd-sku-catalog.mjs`：停止产出价格字段；
  两个文件指纹校验（源 xlsx 的 SHA-256）**保持不变**，不要动
- `docs/authoritative-jd-catalog.md`：写清楚**价格已不由本清单承载**，
  唯一真源是 `app.skus`，取数来源是 `app.product_archive_sheets`（成本核算表），
  并说明为什么（按商品名去重的挂牌价与实际规格对不上，见上文西冷牛排例）
- 跑 `--check` 确认生成器输出与仓库固化 JSON 一致

## 不做的事

- 🚫 不动源 xlsx 的两个 SHA-256 指纹校验
- 🚫 不动品类/规格/映射/别名/drift 的既有行为
- 🚫 不执行任何生产 SQL
- 🚫 不动成本表与商品档案页

## Acceptance Criteria

- [ ] `grep -n "purchase_price\|retail_price" backend/src/main/resources/data/authoritative-jd-sku-catalog.json` 无非 null 命中
- [ ] loader 对「manifest 携带价格」抛错，且有单测覆盖这条新不变量
- [ ] 导入服务不再写任何 SKU 价格；新建 SKU 的两个价格字段为 null，有测试断言
- [ ] drift 报告不再出现 `sku.purchase_price` / `sku.retail_price`
- [ ] 生成器 `--check` 对仓库固化 JSON 通过
- [ ] `docs/authoritative-jd-catalog.md` 说明价格已迁出及原因
- [ ] catalog 包全部测试通过（Testcontainers 若在沙箱内拿不到 Docker socket，如实说明，主导者本机复跑）
- [ ] 前端 `npm run typecheck && npm test && npm run build` 全绿（若报告结构变更波及前端类型）

## Files likely affected

- `backend/src/main/resources/data/authoritative-jd-sku-catalog.json`
- `backend/src/main/java/cn/zimu/fulfillment/catalog/{AuthoritativeSkuCatalogManifest,ManifestLoader,ImportService,ImportReport}.java`
- `scripts/generate-authoritative-jd-sku-catalog.mjs`
- `docs/authoritative-jd-catalog.md`、catalog 包测试

## 工作区纪律

同票 06：禁 `git add -A` / `commit` / `checkout|restore|stash`；
不碰 `mcp/McpServer.java`、`McpWriteGate.java`、`docs/ops/deploy-runbook.md`、`.claude/`。

## Risk

中-高。这是**受治理的导入通道**，有指纹校验和一整套契约测试。
好在改动方向是「减法」——移除写入职责，不新增。
先跑一遍现有 catalog 测试建立基线，再动手，改完逐条对齐。
