# 01 — 统一来源礼包映射的查找键

**What to build:** 三条代码路径查 `app.source_channel_bundles` 时使用同一个键，且这个键与 SKU 映射同源。运营配一次礼包映射，文件导入、API 拉单、人工补救三条路都能命中。

**Blocked by:** None

**Status:** open

**Claimed by:** —

取证依据：`docs/research/jufubao-catalog-mapping-2026-08-29.md` §1.2–§1.6（2026-08-29，含生产实证）。

---

## 现状：一个字段，三种语义

`source_channel_bundles.source_bundle_ref` 被三条路径用三种不同的值去查：

| 路径 | 拿什么值查 | 代码位置 |
|---|---|---|
| 文件导入自动展开 | `sourceSkuRef`（聚福宝＝商品ID） | `file/SourceImportService.java:837` → `activeSourceBundle` `:908-923` |
| 人工 `resolve-bundle` | `COALESCE(sku_code_snapshot, raw_cells->>'主商品编码', product_name_snapshot)`，聚福宝实际落到**商品名称** | `order/OrderLineBundleResolutionService.java:202-215`、`:259-266` |
| API 拉单（STRUCTURED） | **根本不查** | `file/SourceImportService.java:354,396` 直通 `createImported`，全程不经 `canonicalItems` |

**对比：SKU 映射键在两条链路是一致的**（都用 `sourceSkuRef`，`OrderCreateService.java:724-734` 精确匹配、无品名兜底）。礼包这条线是唯一的例外。

### 生产实证

`app.source_channel_bundles` id=70（JUFUBAO）存的是**名称**：

```
source_bundle_ref = 【京东配送】子牧牛肉惠选礼包1400g   bundle_id=33  ×1  active
```

对应订单行 34 的 `raw_cells` 里 `商品ID=66500527`、**没有「主商品编码」键**。结果：

- 文件导入自动展开拿 `66500527` 去查 → ❌ 不命中
- 人工 `resolve-bundle` 拿品名去查 → ✅ 命中

所以行 34 的真实经过是：自动展开查不到 → `looksLikeBundle("…礼包…")` 命中（`:903-906`）→ 造 `CUSTOM_BUNDLE` 待复核行 → 运营手工点 `resolve-bundle` 才展开。**这条映射是「能人工修好」，不是「能自动展开」——每来一单都要人点一次。**

2026-08-29 已用数据侧变通绕过：给 66500527 补了一条 ID 键映射（id=71），两条并存。**本票要做的是真正统一，让这种双写不再必要。**

---

## 连带的两个同族缺陷（一并修，别分票）

它们和键语义是同一个根：礼包解析逻辑只长在文件链路上。

### A. API 拉单进来的礼包行是死行

1. 拉单每行恒为 `LineType.SINGLE`（`connector/jufubao/JufubaoOrderTransform.java:185`）
2. 礼包商品找不到 SKU 映射 → `SKU_MAPPING_REQUIRED` / `NEED_REVIEW`
3. 想用 `resolve-bundle` 救 → `OrderLineBundleResolutionService.java:234-236` 直接拒：

```java
if (!"CUSTOM_BUNDLE".equals(line.get("line_type"))) {
    throw BusinessException.unprocessable("BUNDLE_LINE_NOT_RESOLVABLE", "只有礼包行可以就地解析礼包");
}
```

**进不来，也修不了。** `connector/` 整个包 grep `bundle` 零命中。

`SourceImportService.java:925-942` 那段 2026-08-28 的注释描述的正是这个死锁，但那次只修了文件链路（把 `bundleSourceChannel` 对所有渠道放开），结构化链路至今没修。

### B. 名字含「礼包/礼盒/组合」的**单品**会被文件链路劫持

`looksLikeBundle`（`SourceImportService.java:903-906`）只看品名有没有这三个词，而 `canonicalItems`（`:837-842`）的顺序是「先查礼包映射，查不到就看名字像不像礼包」——**SKU 映射根本不参与这个分支判断**。

后果：给一个名字带「组合」的商品配了 SKU 映射，文件链路照样判成待解析礼包行、绕过 SKU 映射；而 API 拉单会正常用 SKU 映射。**同一个商品，两条链路给出不同结果。**

`:936-937` 的注释明说改动前核实过「生产 `source_channel_skus` 中不存在名称命中这三个词的活跃映射」。聚福宝目录里的 `66902619` / `66902622`（`…烧烤肉串组合`）一旦按 SKU 映射落库就会打破这个前提——目前因此挂在待确认清单里没落。

---

## 验收标准

- [ ] **单一解析入口**：三条路径（文件导入、API 拉单、人工 `resolve-bundle`）经由同一个礼包解析 seam 查 `source_channel_bundles`，键与 SKU 映射同源。新增或改动查找逻辑只需改一处。
- [ ] **存量数据不破**：生产现有 39 条礼包映射里既有 ID 键也有名称键（DAZHE bundle 1 同时挂 `P26011900044` 与名称；bundle 21 同理）。改造后这些**全部仍然命中**——要么保留显式的双键查找并写明为什么，要么提供迁移把名称键转成 ID 键。**不允许静默失配。**
- [ ] **API 拉单能展开礼包**：拉单进来的礼包商品命中映射即展开成组件行，与文件导入结果一致；命中不了时落成 `CUSTOM_BUNDLE` 待复核行，使 `resolve-bundle` 能救（而不是现在的 `SINGLE` 死行）。
- [ ] **SKU 映射优先于名字猜测**：`looksLikeBundle` 不再能劫持已有活跃 SKU 映射的商品。判定顺序必须是「礼包映射 → SKU 映射 → 名字启发式」，两条链路一致。
- [ ] 用例覆盖：同一个商品分别走文件导入与 API 拉单，结果一致；名称键与 ID 键的存量映射都能命中；名字含「组合」但有 SKU 映射的单品不被判成礼包。
- [ ] `docs/schema.sql`、`docs/openapi.yaml`、迁移链守卫（`ProductionMigrationHistoryCompatTest`、`SchemaSnapshotMigrationEquivalenceTest`）全绿。

---

## 硬约束

- **不碰生产数据。** 需要数据迁移就写成 Flyway 迁移，由部署执行，不要手工 SQL 改库。
- **不部署。**
- 迁移编号：生产当前已到 **V84**。新迁移从 V85 起，且必须同步 `docs/schema.sql`（漏了守卫会红）。
- 仓库**没有** `./mvnw`，用 `mvn`。
- 契约文件在 `docs/openapi.yaml`，**不在** `backend/src/main/resources/`。
- 注释与文案中文，说清「为什么」而不只是「做了什么」；判断有取舍的地方要写明取舍。
