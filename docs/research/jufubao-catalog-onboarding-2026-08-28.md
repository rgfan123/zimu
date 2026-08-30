# 聚福宝商品表建档与映射方案（63 品 / 2026-08-28）

> 来源文件：`/tmp/111.xlsx`（聚福宝导出，63 商品 × 25 列，供货商全为「京诚乾元」）
> 本文只读产出。文中所有生产数据均通过 `ssh zimupc "docker exec -i zimu-fulfillment-postgres-1 psql -U fulfillment -d fulfillment_hub -t -A"` 只读核过，字段名以 `docs/openapi.yaml` 为准并回读了后端实现分支。**我没有执行任何写操作，也没有调用过网关**（无凭据）。

---

## 1. 一句话结论

63 个品里 **22 个今天就能落地**（21 条只差映射 + 1 个礼包连档案带 BOM 带映射一起建，全部走 API，**不需要改一行代码**）；**5 个**需要先给一句业务口径确认；**5 个新单品**能建档但缺京东货品编码/履约方归属；剩下 **31 个全部卡在货源侧**（12 个子牧礼包没有组件清单、19 个大江生鲜没裁定 SINGLE 还是 BUNDLE）——系统这边无事可做。

另有一条**必须排期的部署**：聚福宝礼包在生产镜像里还没进白名单，礼包档案配好也不会自动生效（第 5 节）。

| 分类 | 数量 | 今天能做什么 |
|---|---|---|
| 已有 SKU，映射三重印证 | 15 | 14 条映射（65993325 已配） |
| 已有 SKU，带一条已知注记 | 6 | 6 条映射 |
| 已有 SKU，口径待确认 | 4 | 等一句话 |
| 已有礼包 bundle 33（66500527） | 1 | 1 条商品ID 别名映射 |
| 新礼包，组件齐备（66526478） | 1 | 建 bundle + BOM + 双键映射 |
| 新单品 | 5 | 缺 provider 归属与货品编码 |
| 子牧礼包，缺 BOM | 12 | 向京诚乾元索取配单清单 |
| 大江生鲜套餐，未裁定 | 19 | 等一句话 |
| **合计** | **63** | |

---

## 2. 映射键决策：用**商品ID**，礼包侧**双键并写**

### 2.1 答案

| 侧 | 键 | 要改代码吗 | 现在能配吗 |
|---|---|---|---|
| `source_channel_skus`（单品） | **商品ID**（如 `65993325`） | **不需要** | **能，今天就能** |
| `source_channel_bundles`（礼包） | **商品ID + 商品全名 两条都写** | 全自动生效需要**一次部署**（改动已在分支上） | 档案与映射今天能建，但要等部署才生效 |

### 2.2 为什么必须是商品ID

1. **系统自己已经在用它了。** 生产 `source_channel_skus` id=80 是 `JUFUBAO:65993325 → sku 25, quantity_multiplier=2.000`，已跑通「导入 → 映射 → 京东 SDK 建单」闭环。解析器 `SourceFileParser.jufubao()` 取的就是 `first(cells,"商品ID","商品编码","商品条码")`——第一个非空值。这条不是新立规矩，是把已有的正确做法推广开。
2. **名称做键必坏，本表就有反例。** 【】前缀是配送方式装饰，本表已出现 5 种（京东配送 31、无前缀 26、京东/顺丰配送 3、德邦配送 2、【子牧】1）。其中 `66526788「【子牧】牛羊惠选礼包A2500g」` 的【】里根本不是承运商而是品牌——平台把任一个品的前缀改一次，名称键当场失效。
3. **回退链的前两级对聚福宝是结构性死路，不是偶发空值。** 本表 `商品编码` 0/63、`SKU` 0/63 全空；聚福宝订单的 `raw_cells` 里**根本没有 `主商品编码` 这个键**（有的是没有「主」字的 `商品编码`，且为空）。所以 `COALESCE(sku_code_snapshot, raw_cells->>'主商品编码', product_name_snapshot)` 对聚福宝礼包行**必然**落到第三级商品全名。今天 66500527 那条脆弱映射不是个例，是必然结果。
4. **商品ID 是本表唯一稳定键**：63/63 填充、无重复、8 位纯数字（65334653~66984019）。发票名称（只有 44/63 等于「名称去前缀」）和产品条码（只有 24/63 有值，还混着 `*N` 后缀和前导零）都不合格。

### 2.3 为什么礼包侧要两条键（这是本次最容易踩的坑）

同一张 `source_channel_bundles` 表，被两条路径用**两个不同的键**读写：

| 路径 | 取键表达式 | 聚福宝下的实际值 |
|---|---|---|
| 导入期自动展开 `SourceImportService.activeSourceBundle(channel, row.sourceSkuRef())` | 解析器投影 `source_sku_ref` | **商品ID** |
| 就地解析 `OrderLineBundleResolutionService.requireConsistentMapping(...)` | `COALESCE(sku_code_snapshot, raw_cells->>'主商品编码', product_name_snapshot)` | **商品全名** |

后果是**双向失效**：

- 只写商品ID → `POST /order-lines/{id}/resolve-bundle` 报 `SOURCE_BUNDLE_MAPPING_MISSING`（人工兜底路径断了）；
- 只写名称（= 现状 `source_channel_bundles` id=70）→ 部署后导入期永远命不中（自动路径断了）。

**双键合法且已有生产先例。** 唯一索引是 `(source_channel, source_bundle_ref)`，`bundle_id` 上没有唯一约束；DAZHE 的 bundle 1 / 2 / 21 早就一个礼包挂多条 ref（如 bundle 1 同时挂 `WANGQI:P26011900044`、`DAZHE:P26011900044`、`DAZHE:子牧原切羊肉礼包6300g（BJ）`）。

**顺带解决一个执行层问题**：`docs/openapi.yaml` 里 `/api/v1/source-bundle-mappings/{id}` **只有 GET，没有 PATCH 也没有 DELETE**。想把 id=70 那条「改键」在网关层根本做不到。新增一条商品ID 别名、保留 id=70 原样，既是唯一可执行路径，也正好是双键方案本身。

### 2.4 要改代码吗——分两件事说

**（A）为了配映射：不用改。** 单品侧解析器已经用商品ID；礼包侧的写入接口也已存在。今天全部动作都是纯 API 调用。

**（B）为了让聚福宝礼包自动展开：需要一次部署，这是硬前置。**

- 生产运行镜像 `zimu-fulfillment-backend:real-0e1c2a41`（`docker ps` 实测）。我 `git show 0e1c2a41:.../SourceImportService.java` 核过，其中：
  ```java
  private boolean bundleSourceChannel(SourceChannel channel) {
      return channel == SourceChannel.DAZHE
              || channel == SourceChannel.WANGQI
              || channel == SourceChannel.WANQI;
  }
  ```
  **不含 JUFUBAO。**
- `channel != null`（全渠道开放）只存在于 **`jry/integration-20260828`**（worktree `/Users/jerry/zimu-work/integration`），**未部署**。
- ⚠️ **纠正一处背景陈述**：「白名单现已对全渠道开放」在分支上为真、**在生产上为假**。

**部署之前，礼包侧真实处境是什么：**
聚福宝礼包行会以 `line_type=SINGLE` 落库（`activeSourceBundle` 第一行就因白名单 return null，连 `looksLikeBundle` 都不会问），落 `SKU_MAPPING_REQUIRED` 卡住；而 `resolve-bundle` 只受理 `CUSTOM_BUNDLE` 行——**双键映射救不了 SINGLE 行**。所以：

> **部署前的临时方案不是「用 resolve-bundle 兜底」，而是「先把档案和两条键都配好，等部署」。** 今天配的东西全部无副作用、部署后立刻生效；但在部署落地之前，新来的聚福宝礼包订单仍会卡住，且 API 修不了。

**（C）可选的彻底修法（非本次必须）**：把 `backend/src/main/java/cn/zimu/fulfillment/order/OrderLineBundleResolutionService.java` 中 `requireResolvableLine()` 里硬编码的 `raw_cells->>'主商品编码'` 换成解析器投影 `SourceFileParser.projection(channel, cells)` 的 `source_sku_ref`（`SourceImportService.projectionFor(channel, rawCellsJson)` 是现成调用范例）。改完之后两条路径读同一个键，名称那条别名就可以不写了。**硬加一个 `raw_cells->>'商品ID'` 是错的**——那只是把大者的坑复制给下一个渠道。

---

## 3. 执行清单

### 3.0 公共前置

```bash
BASE=http://114.244.13.53:28443
AUTH='<用户名>:<密码>'     # 自己填；本文不含任何凭据
```

- 所有写接口都要 `Idempotency-Key` 头（**≥8 字符**，`docs/openapi.yaml` `components.parameters.IdempotencyKey`）。
- **不用传 `X-Operator`**：网关 `docker/nginx/default.conf` 的 `/api/` location 会 `include /etc/nginx/backend-auth.inc`，用已认证身份覆盖客户端传的值。
- 建议每条命令回显 HTTP 码：加 `-w '\n%{http_code}\n'`。重放同一 `Idempotency-Key` 会幂等返回，不会建重复行。

---

### 批一 · 已有档案，只差映射（21 条写入，今天可全部执行）

#### 1-A 三重印证组（15 品，其中 65993325 已配 → 14 条待写）

这 15 条**不是**靠条码猜的，是三条独立证据互相印证：

1. **权威目录名称精确命中**：`backend/src/main/resources/data/authoritative-jd-sku-catalog.json`（sha256 冻结、有一致性校验守着）的 `source_rows[].jufubao_name` 与本表商品名**逐字符相同**，且 `jufubao_quantity` 直接给出倍率；
2. **条码命中**（本表 13 位 ↔ 系统 14 位带前导零，`lpad(...,14,'0')` 归一化后一致）；
3. **EMG 码 ↔ sku_id 一致**（该目录项的 `jd_code` 就是生产 `provider_skus.provider_sku_code`）。

| 商品ID | 平台名称 | sku_id | 规格 | 倍率 | 权威 EMG 码 |
|---|---|---|---|---|---|
| 65992754 | 【京东配送】子牧牛腩块500g*2袋 | 22 | 500g | **2** | EMG4418727173231 |
| 65992894 | 【京东配送】子牧谷饲安格斯牛腱子肉500g*2袋 | 37 | 500g | **2** | EMG4418824976893 |
| 65992900 | 【京东配送】子牧筋头巴脑500g*2袋 | 26 | 500g | **2** | EMG4418705676249 |
| 65992994 | 【京东配送】子牧牛后腿肉500g*2袋 | 35 | 500g | **2** | EMG4418778272348 |
| 65993155 | 【京东配送】子牧牛肉馅500g*2袋 | 30 | 500g | **2** | EMG4418904462240 |
| 65993209 | 【京东配送】子牧澳洲原切谷饲上脑牛排150g*4袋 | 20 | 150g | **4** | EMG4418824973489 |
| 65993237 | 【京东配送】子牧原切眼肉牛排150g*4袋 | 32 | 150g | **4** | EMG4418904462756 |
| 65993325 | 【京东配送】子牧澳洲谷饲牛肋排400g*2袋 | 25 | 400g | 2 | EMG4418727173759 ✅**已配（id=80）** |
| 65993370 | 【京东配送】子牧澳洲谷饲牛蝎子400g*2袋 | 28 | 400g | **2** | EMG4418691848770 |
| 65993381 | 【京东配送】子牧生鲜羊蝎子500g*2袋 | 5 | 500g | **2** | EMG4418727170819 |
| 65998050 | 【京东配送】子牧原切纯肉羊腿肉500g*2袋 | 9 | 500g | **2** | EMG4418691852262 |
| 65998054 | 【京东配送】子牧原切纯肉羊肉块500g*2袋 | 8 | 500g | **2** | EMG4418767460168 |
| 65998070 | 【京东配送】子牧原切带骨羊肉块500g*2袋 | 3 | 500g | **2** | EMG4418861052375 |
| 65998078 | 【京东配送】子牧羊寸排块500g*2袋 | 4 | 500g | **2** | EMG4418767459988 |
| 65998272 | 【京东配送】子牧法式羊排400g*2袋 | 10 | 400g | **2** | EMG4418819504770 |

> **倍率不是可选项。** 系统 SKU 的粒度是单袋（500g / 150g / 400g），平台卖的是「×2袋 / ×4袋」。已配的 65993325 立了先例（`quantity_multiplier=2.000`）。**填 1 就会少发一半（或四分之三）的货。**

#### 1-B 带一条已知注记，仍建议直接配（6 品）

| 商品ID | 平台名称 | sku_id | 倍率 | 依据 | 注记 |
|---|---|---|---|---|---|
| 66683887 | 子牧澳洲谷饲肥牛涮烤片150g*2盒 | 76 | **2** | 条码 6977872890739；价比 2.09 落在带内 | ⚠️ **sku 76 在 `provider_skus` 里完全没有记录**，配了映射也推不到京东（见 5.3） |
| 66693946 | 子牧A5澳洲和牛霜降肥牛卷200g*3盒 | 48 | **3** | 条码 6977872890609；价比 1.77 落在带内（填 1 则为 5.32，荒谬） | ⚠️ 平台叫 A5、系统叫 M5。彩食鲜 `2152081「子牧A5澳洲和牛霜降肥牛卷」` 已映射到同一个 sku 48，说明 A5/M5 是命名不一致而非异品；但**彩食鲜那条倍率填的是 1.000**，与 200g 规格不自洽——建议顺手复核 |
| 66902619 | 【京东配送】牛羊烧烤肉串组合：羊肉串10串+… | 61 | **1** | sku 61「牛羊肉烧烤组合」已有京东码 EMG4419279687164 | 🔴 **改判为 SINGLE，不要建礼包**（见下方说明） |
| 66902622 | 【京东配送】子牧鸡肉烧烤肉串组合：鸡肉串5串+… | 60 | **1** | sku 60「鸡肉烧烤组合」已有京东码 EMG4419200212053 | 🔴 同上 |
| 66811280 | 【京东/顺丰配送】…土黑猪五花肉450g*2 | 63 | **1** | 中汇 `83755271` 已映射同一 sku、倍率 1；系统 SKU 规格本身就是「450g*2」 | provider 2（第三方履约），不是京东仓 |
| 66811301 | 【京东/顺丰配送】…土黑猪里脊450g*2 | 68 | **1** | 彩食鲜 `2066613` 已映射同一 sku、倍率 1 | ⚠️ 该 SKU 的 `provider_sku_code` 是占位值 `SKU-TP-000066`（与 sku_code 同值），不是真实货源码 |

**为什么 66902619 / 66902622 改判 SINGLE：** 名字里枚举了 5 个组件，看起来是最强的礼包信号，但生产库里它们已经是**带真实京东货品编码的单品 SKU**。有 EMG 码就意味着京东能按单一商品码整单发货——这正是礼包不存在的理由（礼包之所以要拆，是因为没有单一可下单编码）。若按礼包建，反而会因为「羊肉串/牛心管/毛肚串」这些组件在 `skus` 里根本不存在而生成不了 `bundle_items`，直接卡死。
**倍率为什么确定是 1**：sku 61 成本 59.12 / 平台货源进货价 94.0（比值 1.59）；sku 60 成本 44.89 / 79.0（比值 1.76）。若倍率取 2，我方成本将高于平台进货价（118.24>94、89.78>79），商业上不成立。

<details>
<summary>价比带的来历（用于佐证倍率，不是契约）</summary>

用 1-A 那 15 条已知正确的映射算「平台货源进货价 ÷（我方 SKU 成本 × 倍率）」，得到经验带 **1.32 ~ 2.80**（最低 65992894 牛腱子 1.32，最高 65993370 牛蝎子 2.80，中位约 1.72）。倍率猜错一档，比值会掉出带外一个数量级，因此可作为独立佐证。**这是 15 个样本推的经验带，不是系统约束。**
</details>

#### 1-C 礼包别名（1 条）

66500527 已建为 `product_bundles` id=33（`BUNDLE-9260828000001`，BOM = 牛腩块500g×1 + 牛后腿肉500g×1 + 卓宸牛蝎子400g×1 = 1400g，全 provider 1）。现有映射 id=70 用的是商品全名。**新增商品ID 别名，不动 id=70。**

#### 1-D 可直接执行的命令

```bash
BASE=http://114.244.13.53:28443
AUTH='<用户名>:<密码>'

# ---- 20 条 source_channel_skus 映射（1-A 的 14 条 + 1-B 的 6 条）----
while IFS='|' read -r ref sku mult name; do
  [ -z "$ref" ] && continue
  printf '>> %s -> sku %s x%s : ' "$ref" "$sku" "$mult"
  curl -sS -u "$AUTH" -X POST "$BASE/api/v1/source-sku-mappings" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: jfb-scs-${ref}-20260828" \
    -w '\n  HTTP %{http_code}\n' \
    -d "{\"source_channel\":\"JUFUBAO\",\"source_sku_ref\":\"${ref}\",\"source_sku_name\":\"${name}\",\"sku_id\":\"${sku}\",\"quantity_multiplier\":\"${mult}\",\"active\":true}"
done <<'ROWS'
65992754|22|2.000|【京东配送】子牧牛腩块500g*2袋
65992894|37|2.000|【京东配送】子牧谷饲安格斯牛腱子肉500g*2袋
65992900|26|2.000|【京东配送】子牧筋头巴脑500g*2袋
65992994|35|2.000|【京东配送】子牧牛后腿肉500g*2袋
65993155|30|2.000|【京东配送】子牧牛肉馅500g*2袋
65993209|20|4.000|【京东配送】子牧澳洲原切谷饲上脑牛排150g*4袋
65993237|32|4.000|【京东配送】子牧原切眼肉牛排150g*4袋
65993370|28|2.000|【京东配送】子牧澳洲谷饲牛蝎子400g*2袋
65993381|5|2.000|【京东配送】子牧生鲜羊蝎子500g*2袋
65998050|9|2.000|【京东配送】子牧原切纯肉羊腿肉500g*2袋
65998054|8|2.000|【京东配送】子牧原切纯肉羊肉块500g*2袋
65998070|3|2.000|【京东配送】子牧原切带骨羊肉块500g*2袋
65998078|4|2.000|【京东配送】子牧羊寸排块500g*2袋
65998272|10|2.000|【京东配送】子牧法式羊排400g*2袋
66683887|76|2.000|子牧澳洲谷饲肥牛涮烤片150g*2盒
66693946|48|3.000|子牧A5澳洲和牛霜降肥牛卷200g*3盒
66902619|61|1.000|【京东配送】牛羊烧烤肉串组合
66902622|60|1.000|【京东配送】子牧鸡肉烧烤肉串组合
66811280|63|1.000|【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪五花肉450g*2
66811301|68|1.000|【京东/顺丰配送】子牧雷山高海拔农家散养土黑猪里脊450g*2
ROWS

# ---- 1 条礼包别名映射（66500527 → bundle 33）----
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/source-bundle-mappings" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: jfb-scb-66500527-20260828' \
  -w '\n  HTTP %{http_code}\n' \
  -d '{"source_channel":"JUFUBAO","source_bundle_ref":"66500527","source_bundle_name":"【京东配送】子牧牛肉惠选礼包1400g","quantity_multiplier":"1","bundle_id":"33","active":true}'
```

字段依据：`SourceSkuMappingWrite`（required `source_channel/source_sku_ref/sku_id/quantity_multiplier`；`quantity_multiplier` 是 `PositiveDecimalQuantity` 十进制**字符串**）、`SourceBundleMappingWrite`（required `source_channel/source_bundle_ref/bundle_id`；`quantity_multiplier` 正则 **`^1$`**——写 `"1.000"` 会被拒）。

**执行后自查：**
```bash
curl -sS -u "$AUTH" "$BASE/api/v1/source-sku-mappings?source_channel=JUFUBAO&size=200"
curl -sS -u "$AUTH" "$BASE/api/v1/source-bundle-mappings?source_channel=JUFUBAO&size=200"
```
期望：SKU 侧 21 条（20 新 + 1 旧），礼包侧 2 条（都指向 bundle 33）。

---

### 批二 · 需新建单品（5 品）——**命令就绪，但有硬前置**

| 商品ID | 名称 | 条码 | 进价 / 零售 | 前缀 | 履约方 |
|---|---|---|---|---|---|
| 66605101 | 乔府大院金饭碗五常大米5kg | 6937004413052 | 89.0 / 158.0 | 无 | **未知** |
| 66662134 | yosibaby/羊小贝山羊奶整箱200ml*10盒 | 6975334570755 | 69.0 / 88.0 | 无 | **未知** |
| 66662137 | yosibaby/羊小贝有机配方羊奶粉380g | 6972528091584 | 168.0 / 215.0 | 无 | **未知** |
| 66983856 | 【德邦配送】新疆库尔勒香梨超特香梨约14斤 | — | 136.0 / 179.0 | 德邦 | **非京东** |
| 66984019 | 【德邦配送】子牧新疆库尔勒香梨全母梨王约14斤 | — | 120.0 / 169.0 | 德邦 | **非京东** |

已核：这 5 个在生产 `products` / `product_bundles` 里按「大米/五常/乔府/羊奶/山羊/奶粉/香梨/库尔勒」搜索**零命中**，确为净新增。

**硬前置（不满足就先别建）：**
1. **`provider_id` 必须先定。** `InitialSkuWrite.provider_id` 是必填；`createProviderMapping` 有 `SKU_PROVIDER_MISMATCH` 校验（`skus.fulfillment_provider_id` 必须等于 provider）。选错了后面得重建。京东云仓 = `1`，第三方履约 = `2`。两个香梨写着「德邦配送」，几乎肯定不是 provider 1。
2. **京东货品编码要用 EMG 命名空间。** 生产 provider 1 的 61 条 `provider_sku_code` **全部**是 `EMG` + 13 位数字（长度恒为 16），纯数字 0 条。`item.jd.com` URL 里的数字（如羊奶粉那条 `100091029125`）是京东**商品页 ID**，不是 EMG 货品编码，**不能直接写进 `provider_skus`**。
3. **`unit` 建议一律填「件」。** `ShipmentJdSkuMappingGateService` 的换算分支：`external_codes` 没有 `jd_pieces_per_unit` 且 `skus.unit != '件'` 时直接判 `UNIT_CONVERSION_MISSING` 阻断；单位是「件」才有确定性系数 1。要填「箱/袋」就必须同时给 `jd_pieces_per_unit`。

```bash
BASE=http://114.244.13.53:28443
AUTH='<用户名>:<密码>'
PROVIDER=1          # ← 先定！京东云仓=1，第三方履约=2；德邦香梨大概率不是 1
CATEGORY=8          # CAT-UNCLASSIFIED（生产 categories：1牛肉 2羊肉 3猪肉 4禽肉 5其他肉类 6混合组合 7设备物料 8待分类）

new_product () {   # $1=名称 $2=规格 $3=条码(可空) $4=进价 $5=零售 $6=幂等后缀
  bc=''; [ -n "$3" ] && bc=",\"barcode\":\"$3\""
  curl -sS -u "$AUTH" -X POST "$BASE/api/v1/products/with-sku" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: jfb-newsku-$6-20260828" \
    -w '\n  HTTP %{http_code}\n' \
    -d "{\"product\":{\"product_name\":\"$1\",\"category_id\":\"$CATEGORY\",\"active\":true},
         \"sku\":{\"provider_id\":\"$PROVIDER\",\"specification\":\"$2\",\"unit\":\"件\"$bc,
                  \"purchase_price\":\"$4\",\"retail_price\":\"$5\",\"active\":true}}"
}

new_product '乔府大院金饭碗五常大米'          '5kg'        '6937004413052' '89.00'  '158.00' '66605101'
new_product 'yosibaby羊小贝山羊奶'             '200ml*10盒' '6975334570755' '69.00'  '88.00'  '66662134'
new_product 'yosibaby羊小贝有机配方羊奶粉'     '380g'       '6972528091584' '168.00' '215.00' '66662137'
new_product '新疆库尔勒香梨超特'               '约14斤/箱'  ''              '136.00' '179.00' '66983856'
new_product '子牧新疆库尔勒香梨全母梨王'       '约14斤/箱'  ''              '120.00' '169.00' '66984019'
```

> `POST /api/v1/products/with-sku` 的 201 响应就是新 SKU 的 `MasterDataRecord`，`id` 即 `sku_id`。记下这 5 个 id，然后各补一条 provider 映射与一条 source 映射：

```bash
# 有了 EMG 码之后（每个品各一次）
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/provider-sku-mappings" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: jfb-ps-<商品ID>-20260828" \
  -d '{"provider_id":"1","sku_id":"<新sku_id>","provider_sku_code":"EMG<...>","provider_sku_name":"<名称>","jd_pieces_per_unit":"1.000","active":true}'

# 然后建渠道映射（倍率：整箱/整袋按「1 个平台单位 = 几个系统 SKU 单位」填；unit=件+规格写整箱 → 1.000）
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/source-sku-mappings" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: jfb-scs-<商品ID>-20260828" \
  -d '{"source_channel":"JUFUBAO","source_sku_ref":"<商品ID>","source_sku_name":"<平台全名>","sku_id":"<新sku_id>","quantity_multiplier":"1.000","active":true}'
```

**注意价格字段：** 表里价格是浮点文本（`"96.0"`），`NullableCommercialPrice` 正则是 `^(0|[1-9][0-9]{0,11})(\.[0-9]{1,2})?$`，要写成两位小数（`96.00`），别直接透传。另外这 5 个的「进价/零售」是**平台侧**价格，不是我方成本；`purchase_price` 的口径按成本表为准（见 `docs/research/jd-code-cost-mapping-2026-08-27.md`），此处先落平台值只是占位，请业务确认。

---

### 批三 · 需新建礼包（**只有 1 个组件齐全**）

**66526478「【京东配送】子牧羊蝎子牛肋排组合1800g」** 是本表唯一 BOM 可唯一确定、组件在库、履约方一致的新礼包：

- 名称直接点名两个品，两个都在本表内且都是在库 SKU：`sku 5 羊蝎子(500g, provider 1)`、`sku 25 牛肋排(400g, provider 1)`；
- `500a + 400b = 1800`（a,b ≥ 1 的整数）**唯一解 a=2, b=2**；
- **数量是 2 和 2，不是 1 和 1。** 系统 SKU 粒度是单袋，平台名里两个品都是「×2袋」。按 1+1 建只有 900g，差一半。`bundle_items` 有 `UNIQUE(bundle_id, sku_id)`，同一 SKU 不能拆两行，倍数必须写进 `quantity_per_bundle`。
- 口径与今天刚建的 bundle 33 一致（500+500+400=1400g，各 ×1），克重加和模型在本系统成立。

```bash
BASE=http://114.244.13.53:28443
AUTH='<用户名>:<密码>'

# ① 建礼包档案 —— status 必须直接给 ACTIVE
#    （createSourceBundleMapping 有 BUNDLE_NOT_ACTIVE 校验；服务端先落 DRAFT 再翻到请求状态）
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/product-bundles" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: jfb-bundle-66526478-20260828' \
  -w '\n  HTTP %{http_code}\n' \
  -d '{"bundle_code":"BUNDLE-9260828000002",
       "bundle_name":"子牧羊蝎子牛肋排组合1800g",
       "status":"ACTIVE",
       "items":[{"sku_id":"5","quantity_per_bundle":"2"},
                {"sku_id":"25","quantity_per_bundle":"2"}]}'
# 记下响应里的 id，下面记作 <NEW_BUNDLE_ID>

# ② 商品ID 键（部署后导入期自动展开靠这条）
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/source-bundle-mappings" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: jfb-scb-66526478-id-20260828' \
  -d '{"source_channel":"JUFUBAO","source_bundle_ref":"66526478","source_bundle_name":"【京东配送】子牧羊蝎子牛肋排组合1800g","quantity_multiplier":"1","bundle_id":"<NEW_BUNDLE_ID>","active":true}'

# ③ 商品全名键（人工 resolve-bundle 兜底靠这条）
curl -sS -u "$AUTH" -X POST "$BASE/api/v1/source-bundle-mappings" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: jfb-scb-66526478-name-20260828' \
  -d '{"source_channel":"JUFUBAO","source_bundle_ref":"【京东配送】子牧羊蝎子牛肋排组合1800g","source_bundle_name":"【京东配送】子牧羊蝎子牛肋排组合1800g","quantity_multiplier":"1","bundle_id":"<NEW_BUNDLE_ID>","active":true}'
```

**字段说明（对齐 `BundleWrite`）**：required 只有 `bundle_code / bundle_name / items`；`category_id`、`barcode`、`tax_rate`、`settlement_cost` 全部可省——生产现存 33 个礼包这四列**全是 NULL**，本方案照现有惯例留空。`bundle_code` 有唯一索引，`BUNDLE-9<yyMMdd><6位序>` 是既有格式，`BUNDLE-9260828000001` 已被 bundle 33 占用，故取 `...000002`。`quantity_per_bundle` 正则 `^[1-9][0-9]*$`（正整数字符串，不能写 `2.000`）。

> ⏳ **建完不会立刻生效**：在 2.4 说的那次部署之前，含 66526478 的聚福宝订单仍会以 SINGLE 落库并卡住。今天建它是零副作用的提前量。

**其余 12 个子牧礼包（65334653 / 65335556 / 65335660 / 65335889 / 66500521 / 66500526 / 66500558 / 66500564 / 66500565 / 66500653 / 66500673 / 66526788）没有列进批三**——本表没有任何一行给出组件明细，克重反推的解空间是 10² ~ 10⁵ 量级（1400g 有 72 解、3200g 有 594,976 解），按克重猜会以极高概率发错货。缺 BOM 就建不了 `bundle_items`（`BundleWrite.items` 是 `minItems: 1`），**没有可执行命令**。

---

## 4. 退回人工判断的项

### 4.1 一句话就能全部落地的（19 个大江生鲜套餐）

`66486817 / 66486882 / 66486903 / 66486913 / 66486989 / 66487069 / 66487090 / 66487102 / 66487120 / 66487136 / 66487142 / 66487145 / 66487148 / 66487153 / 66487158 / 66487212 / 66487213 / 66487215 / 66487219`

**要问的是**：这些套餐能不能在京东按**一个货品编码整单下单**？

- **能** → 全部按 SINGLE 建（`products/with-sku` + `provider_skus` + `source_channel_skus`）。但**必须向京东侧索取 EMG 码**：那 16 个 `item.jd.com` URL 里的数字（10145471768612、10143274144136…）在生产 `provider_skus` 的 `provider_sku_code` / `merchant_sku_code` / `external_codes` 三处**全部查无此项**，且长度/前缀都不符 EMG 形态。
- **不能** → 按 BUNDLE 建，但需要向大江生鲜索取组件清单，**并且要先建一整套大江单品 SKU**——生产库里 `大江|家家鲜|大口吃肉|大吉大利` 在 products / product_bundles / source_channel_skus / source_channel_bundles 四张表**命中数全为 0**，可用组件 SKU 数是 0。

**注意一个已有判例**：`looksLikeBundle()` 的关键词是「礼包‖礼盒‖组合」，**不含「套餐」**；且生产 `CAISHIXIAN:2066449 =「子牧雷山黑猪套餐1800g」` 已被建为 **SINGLE**。所以即使部署完成，这 19 个不配映射也会静默落 SINGLE，而不是进礼包分支。

**另有一条数据本身要先澄清**：`66487069「家家鲜优品套餐A冷鲜装2310g」`（进价 203 / 零售 290）与 `66487120「家家鲜甄选套餐B冷鲜装2310g」`（进价 235 / 零售 336）**共用同一个京东来源 URL `10145474423620`**，克重也都是 2310g。至少有一行 URL 填错，映射前必须让平台/货源确认。

### 4.2 需要一句业务确认才能配的已有 SKU（4 个）

| 商品ID | 平台名称 | 疑似对应 | 拿不准在哪 | 确认后动作 |
|---|---|---|---|---|
| 66811285 | …土黑猪**仔排**450g*2 | sku 64 `SKU-TP-000064`「…土黑猪**排骨**450g*2」 | 系统侧该 SKU **条码为空**，无法用条码裁定；中汇 `83755270` 也叫「排骨」。三兄弟（五花肉/排骨/里脊）成套、同规格、同价（84.00），倾向同品，但没有硬证据 | 同品 → 倍率 1.000 直接配 |
| 66487969 | 子牧蒙元驼新鲜鸵鸟蛋1个约1000g-1500g | sku 87 `SKU-JD-000085`「蒙元鸵新鲜鸵鸟蛋」规格 **1.5kg** | 系统侧条码为空，只能名称匹配；规格写死 1.5kg，平台是 1000–1500g 区间。价比 90/80=1.13，低于经验带下沿 1.32，毛利偏薄也需确认 | 同品 → 倍率 1.000；顺便修 sku 87 规格 |
| 66597389 | 子牧**鲜椒**鸵鸟肉酱180g*2罐 | sku 88 `SKU-JD-000086`「**蒙元鸵**鸵鸟肉酱」180g | 「鲜椒」是不是同一口味未证实（系统只有这一款肉酱）。倍率 2 是从名称「*2罐」推的，价比对 1 和 2 都落在带内，价格不能裁 | 同品 → 倍率 2.000 |
| 66902617 | 【京东配送】日式和牛厚切烧肉M8-9 150g*2 | sku 58 `SKU-JD-000058`「M8-9日式和牛厚切烧肉」 | **`specification` 是「待维护」、`purchase_price` 为空** → 倍率既推不出也算不出：若该 SKU 代表单份 150g 则倍率 2，若代表整袋 150g*2 则倍率 1 | 先补 sku 58 规格与成本，再定倍率 |

### 4.3 被上一轮质疑退回的礼包判定（1 个，连带影响 12 个）

`65334653「【京东配送】子牧清真牛肉生鲜礼包1300g」`被降级为 **UNCLEAR**（不是改判 SINGLE）。质疑成立的两条：

- 「条码/编码/SKU 三列全空 ⇒ 礼包」**无效**：本表 6 行三列全空，其中 66597389 肉酱、66902617 和牛烧肉、66983856/66984019 香梨都是明确单品；更要命的是 66902619/66902622 名字带「组合」且三列全空，却在生产库里是带真实京东码的 SINGLE。空码是导出数据质量伪影，不是结构信号。**正确方向是单向的：有条码 ⇒ 必为单品；无条码 ⇒ 不可判定。**
- 「超过任一单品总重上限 1000g」**与事实不符**：本表单品最大 1500g（鸵鸟蛋）、5000g（大米）、7000g（香梨）。1000g 上限只在「子牧牛羊生鲜」子集内成立。

**但这不影响执行**：无论 65334653 判 BUNDLE 还是 SINGLE，本表都拿不到它的组件清单/京东编码，下一步动作完全相同——**向京诚乾元索取**。这 12 个子牧礼包一律如此。
⚠️ **绝对不要因为「拿不准」就回退建成普通 SKU**，那正是今天卡死聚福宝订单的那个 bug。

### 4.4 要向京诚乾元索取的一张表

对 12 个子牧礼包 + 65334653 同族，逐个要：

| 需要的字段 | 用途 |
|---|---|
| 京东货品编码（EMG 开头）——若该礼包能整单下单 | 直接建 SINGLE + `provider_skus`，不用建礼包 |
| 配单明细：每袋部位 + 克重 + 袋数 | 建 `bundle_items`（`sku_id` + `quantity_per_bundle`） |
| 是否预包装礼盒（有独立条码） | 有条码 ⇒ 单品 |

---

## 5. 风险与前置

### 5.1 🔴 部署是礼包线的硬前置（最高优先级）

生产镜像 `real-0e1c2a41` 的 `bundleSourceChannel()` 不含 JUFUBAO（已 `git show` 核实）。**在部署 `jry/integration-20260828` 的那一行改动之前：**

- 聚福宝礼包行以 `line_type=SINGLE` 落库 → `SKU_MAPPING_REQUIRED` → `NEED_REVIEW`；
- `resolve-bundle` 只受理 `CUSTOM_BUNDLE` 行，**API 修不了**；
- 今天 66500527 那条之所以能收尾，是因为中间发生过一次 SINGLE→CUSTOM_BUNDLE 的翻转，而这次翻转在审计日志里**没有任何记录**（极可能是手工改库）。**这个手法不可复制到 16 个礼包上。**

### 5.2 🔴 部署之前，绝对不要给任何礼包商品ID 建 `source_channel_skus` 映射

`activeSourceBundle()` 在 `looksLikeBundle` 之前判定，礼包映射优先级更高；但**代码层没有任何约束**阻止同一个 `(渠道, ref)` 同时出现在 `source_channel_skus` 和 `source_channel_bundles` 两张表。一旦给礼包的商品ID 建了 SKU 映射：

- 部署前 → 礼包会被**静默当成单品发货**（比卡住严重得多）；
- 部署后 → 礼包映射会静默压掉 SKU 映射，行为突变。

已核：当前**同渠道同 ref 跨两表碰撞 0 行**，配置窗口是干净的。批一的 20 条 ref 里没有任何一个是礼包（66902619/66902622 是名字像礼包的真单品）。**配置时请自行保证一个商品ID 只进一张表。**

### 5.3 🟠 3 个已有 SKU 会打破「渠道能下单 ⇒ 能推给履约方」这条不变量

当前 80 条 `source_channel_skus` 里，指向「无 `provider_skus` 记录的 SKU」的有 **0 条**。本批会首次打破它：

| SKU | 对应平台品 | 状况 |
|---|---|---|
| sku 76 `SKU-JD-000074` 子牧谷饲肥牛涮烤片 | 66683887 | `provider_skus` **完全没有记录** |
| sku 87 `SKU-JD-000085` 蒙元鸵新鲜鸵鸟蛋 | 66487969 | 同上 |
| sku 88 `SKU-JD-000086` 蒙元鸵鸵鸟肉酱 | 66597389 | 同上 |
| sku 68 `SKU-TP-000066` 子牧雷山黑猪里脊 | 66811301 | `provider_sku_code` 是**占位值**（与 sku_code 同值），不是真实货源码 |

订单能进来、能映射、但推到履约方时会撞 `JD_STOCK_SKU_MAPPING_MISSING` / `MAPPING_MISSING` 门闩。**建议这 4 个与货源码同批补齐，或先不开渠道映射。**

### 5.4 🟠 倍率填错 = 少发货，且系统不会报错

平台卖「500g*2袋 / 150g*4袋 / 200g*3盒 / 200ml*10盒」，系统 SKU 粒度是单袋/单盒。`quantity_multiplier` 是唯一的换算位。填 1 不会触发任何校验，只会**默默少发一半到四分之三的货**。批一的倍率有权威目录 `jufubao_quantity` 背书，请照抄，不要"看着填 1 保险"。
顺带：彩食鲜 `2152081` → sku 48 那条倍率是 `1.000`，与 200g 规格不自洽，建议一并复核（不属于本次范围）。

### 5.5 🟡 履约方分裂与跨家礼包拆单

本表跨 4 个品牌：子牧 41、大江生鲜 19、乔府大院 1、yosibaby/羊小贝 2。

- 黑猪三兄弟（sku 63/64/68）在 **provider 2（第三方履约）**，其余子牧品在 provider 1（京东云仓）。
- 全库 92 个 SKU 里只有 5 个是 provider 2（62/63/64/67/68）。
- 12 个待建子牧礼包的全部候选组件都是 provider 1，**不会**触发 `BUNDLE_MIXED_PROVIDERS`。
- 但混合礼包在数据层是被容忍的：bundle 4「羊蝎子鸵鸟肉排组合1080g」= sku 5(prov1) + sku 62(prov2)，其 `fulfillment_provider_id` 为 NULL——这就是「跨家 → NULL 且拆单」的活体先例。**若将来有礼包同时含子牧肉品与黑猪，就落入这个形态**：`resolve-bundle` 会直接报 `BUNDLE_MIXED_PROVIDERS` 拒绝，而导入期展开会按履约方分组成多个订单行。
- 两个香梨（66983856/66984019）写着「德邦配送」，履约方几乎肯定不是京东仓，建 SKU 前必须先定。

### 5.6 🟡 单位与京东件数换算

`ShipmentJdSkuMappingGateService`：`provider_skus.external_codes` 没有 `jd_pieces_per_unit` 且 `skus.unit != '件'` → 直接判 `UNIT_CONVERSION_MISSING` 阻断出库。新建 SKU 时 `unit` 填「件」最安全；填「箱/袋」就必须同时给 `jd_pieces_per_unit`。现存 sku 58/60/61 的 `external_codes` 里**没有** `jd_pieces_per_unit` 键，但它们 `unit='件'`，走确定性系数 1 分支，**不构成阻断**。

### 5.7 🟡 顺带发现的存量脏数据（不影响本次）

`source_channel_bundles` 里同时存在渠道代码 `WANGQI`（2 条）和 `WANQI`（2 条），而 `source_channel_skus` 只有 `WANGQI`。两者都在 `SourceChannel` 枚举里，属既有拼写分裂，建议单独排查。

---

## 6. 未验证项（如实列出）

1. **所有 curl 命令均未实测。** 我没有网关凭据，一次接口都没调过。字段名、必填项、正则、header 全部对照 `docs/openapi.yaml` 与后端实现（`MasterDataService` / `BundleMasterDataService`）逐条核过，但**返回码与实际行为未经验证**。建议先跑批一的第一条，确认 201 后再放开循环。
2. **`/tmp/111.xlsx` 我没有重新解析**，沿用上游解析结果。只对其中能与生产对照的部分做了交叉验证：15 个商品名与权威目录 `jufubao_name` 逐字符一致、17 个条码归一化后命中、15 组价格落在经验带内。**其余字段（发票税率、配送模版、上线时间等）未复核。**
3. **19 个大江生鲜的京东商品页 URL 我没有访问。**「URL 里的数字不是 EMG 命名空间」是从生产 `provider_skus` 全表形态（provider 1 的 61 条全是 `EMG`+13 位、纯数字 0 条）推断的，**不是京东侧证实的**。
4. **66487069 / 66487120 共用 URL 的冲突我没有独立复核原始 Excel**，沿用上游结论。
5. **「17:02 那次 SINGLE→CUSTOM_BUNDLE 翻转是手工改库」是推断**（审计日志里查不到该状态变更）。我没有证据证明具体手法，只能确认审计链有断口。
6. **三处同一性未证实**：66811285 仔排/排骨、66597389 鲜椒/蒙元鸵、66487969「1个约1000-1500g」/「1.5kg」。均为名称近似 + 旁证，无条码可裁。
7. **sku 58 的 `specification='待维护'` 我没找到权威规格来源**，因此 66902617 的倍率无法给出建议值。
8. **价比带 1.32–2.80 是 15 个样本推出的经验带**，不是系统约束，也不是商业契约。它只用作倍率的**佐证**，不作为唯一依据。
9. **批二的 5 个新品，`purchase_price` 我填的是平台侧「货源进货价」**，不是我方成本。成本口径的唯一真源是成本表（`skus` 取成本表 AI/AJ 列），入库前应由业务替换。
10. **我没有验证「部署 `jry/integration-20260828` 之后 66526478 会自动展开」**——那需要真实跑一单。我只验证了代码路径（`activeSourceBundle` → `bundle_items` 展开 → 按 provider 分组）和 BOM 的算术唯一性。

---

## 附：复核用只读 SQL

```bash
ssh zimupc "docker exec -i zimu-fulfillment-postgres-1 psql -U fulfillment -d fulfillment_hub -t -A"
```

```sql
-- 聚福宝现有映射与键形态
select 'SCS',id,source_sku_ref,source_product_name,quantity_multiplier,sku_id
from app.source_channel_skus where source_channel='JUFUBAO'
union all
select 'SCB',id,source_bundle_ref,source_bundle_name,quantity_multiplier,bundle_id
from app.source_channel_bundles where source_channel='JUFUBAO';

-- 批一 20 条的目标 SKU 与其京东码（应全部有 EMG 码，除 sku 76）
select s.id,s.sku_code,p.product_name,s.specification,s.unit,s.fulfillment_provider_id,
       ps.provider_sku_code
from app.skus s join app.products p on p.id=s.product_id
left join app.provider_skus ps on ps.sku_id=s.id
where s.id in (3,4,5,8,9,10,20,22,25,26,28,30,32,35,37,48,60,61,63,68,76) order by s.id;

-- 「渠道能下单 ⇒ 能推给履约方」不变量（当前应为 0）
select count(*) from app.source_channel_skus scs
 where not exists (select 1 from app.provider_skus ps where ps.sku_id=scs.sku_id);

-- 同 ref 跨两表碰撞（配置前后都应为 0）
select scs.source_channel, scs.source_sku_ref
from app.source_channel_skus scs
join app.source_channel_bundles scb
  on scb.source_channel=scs.source_channel and scb.source_bundle_ref=scs.source_sku_ref;

-- bundle 33 配方（克重加和口径的地面真值）
select bi.sort_no,bi.sku_id,p.product_name,s.specification,bi.quantity_per_bundle
from app.bundle_items bi join app.skus s on s.id=bi.sku_id
join app.products p on p.id=s.product_id where bi.bundle_id=33 order by bi.sort_no;

-- 京东 provider 码命名空间（EMG 前缀 61 条 / 纯数字 0 条）
select fulfillment_provider_id,count(*),
       count(*) filter (where provider_sku_code like 'EMG%') emg,
       count(*) filter (where provider_sku_code ~ '^[0-9]+$') numeric_code
from app.provider_skus group by 1;

-- 双键并存先例
select bundle_id,string_agg(source_channel||':'||source_bundle_ref,' ## '),count(*)
from app.source_channel_bundles group by bundle_id having count(*)>1;
```

**权威倍率来源**（仓库内，`AuthoritativeSkuCatalogManifestLoader` 用 `MANIFEST_SHA256` 硬校验，漂移即抛 `AUTHORITATIVE_CATALOG_SOURCE_DRIFT`）：
`backend/src/main/resources/data/authoritative-jd-sku-catalog.json` → `items[].source_rows[].jufubao_name` / `jufubao_quantity`（全表 37 行带聚福宝名，本次命中 15 行）。

> 一处需要说明的细节：我读的是 worktree `/Users/jerry/zimu-work/integration` 的副本，其 sha256 = `882e6b…a68a`，与 loader 的 `MANIFEST_SHA256` 以及生产 `provider_skus.external_codes.catalog_manifest_sha256` **三者一致**。`/Users/jerry/zimu-work/main` 的工作副本 sha 是 `f9d47b…1a4b`（该 worktree 有未提交改动），但我逐项比对过：**两份的 37 行 `(jd_code, jufubao_name, jufubao_quantity)` 完全相同**，所以本文引用的 15 个倍率不受影响。
