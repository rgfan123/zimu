# 13 — 统一商品编码为内部序列，平台身份交还映射表

Type: implementation
Status: ready-for-agent（排在票 06 之后）
Priority: P1
Requested: Jerry 2026-08-28「新建商品中编号应该自动为当前最新的+1 而不是手动填」
→ 追问「**为什么不统一编码？我们的 sku 是内部统一的，其他平台的有 sku 映射来维护**」

> 📌 本票第一版曾主张「保留来源编码，砍了就丢血缘」——**那是错的，已作废**。
> 血缘 100% 存在于映射表，编码里那份是重复存储。详见下方实测。

## 现状：product_code 把平台身份编进了内部主键

生产 85 个商品，4 种形态：

| 形态 | 数量 | 平台身份被编进了编码 |
|---|---|---|
| `PROD-JD-EMG<京东编码>` | 61 | 京东 EMG 码 |
| `PROD-LOCAL-R<成本表行号>` | 22 | 成本表行号 |
| `PROD-TP-CAISHIXIAN-<平台码>` | 2 | 彩食鲜商品编号 |
| `PROD-TP-ZHONGHUI-<平台码>` | 2 | 中汇商品编号 |

而 SKU 侧早就做对了：`sku_code_seq` → `SKU-JD-000091`，纯内部序号；
平台身份在 `provider_skus`（京东 EMG）与 `source_channel_skus`（彩食鲜/中汇）里维护。
**product 侧没跟上，于是同一个事实存了两处。**

## 实测：编码里的血缘 100% 冗余

```
61 / 61  PROD-JD-EMG* 的 EMG 码，可从 provider_skus.provider_sku_code 还原（还原不了：0）
 4 /  4  PROD-TP-* 的商品，都有 source_channel_skus 映射
22 / 22  PROD-LOCAL-R<行号> 的行号，与 product_archive_sheets.row_no 精确对上（对不上：0）
```

**统一编码不会丢失任何信息。**

## 真正的耦合：导入器拿它当匹配键

`catalog/AuthoritativeSkuCatalogImportService.java:184`：

```java
Product product = products.findByProductCode(productCode).orElse(null);  // "PROD-JD-" + jdCode
```

这才是不能直接改名的原因——不是「信息会丢」，是**改了导入就匹配不上，会建 61 个重复商品**。

但讽刺的是，**正确的匹配方式就在同一个方法里、往下三行**（`:199-200`）：

```java
ProviderSku mapping = providerSkus
        .findByFulfillmentProviderIdAndProviderSkuCode(provider.getId(), item.jdCode());
```

导入器同时走了两条路：一条拼字符串查商品，一条查映射表。**后者才是对的，前者是多余的。**

## 范围

### 1. 导入器改用映射表匹配（先做，这是解耦的前提）

`AuthoritativeSkuCatalogImportService`：

- 把 `providerSkus.findByFulfillmentProviderIdAndProviderSkuCode(...)` 提到 product 查找**之前**
- 有映射时：`mapping.getSkuId()` → `sku.getProductId()` → 该 product，**不再用 product_code 查**
- 无映射时：走既有的新建路径（新建 product 时不再自己拼 `PROD-JD-<code>`，见第 2 节）
- 删掉 `productCode(Item)`（`:513-515`）
- `seed/JdInitialSkuLibraryInitializer.java:96-97` 同款处理

**门禁**：catalog 包既有测试必须全绿，且要新增一例
「product_code 与 jdCode 无关时，导入仍能凭映射匹配到既有商品、不新建重复商品」。

### 2. 编码统一为内部序列

- 迁移（号取当时最大+1，V78 已被票 06 认领）：

  ```sql
  CREATE SEQUENCE app.product_code_seq;
  ```

- `MasterDataService` 创建 product 时一律生成
  `'PROD-' || lpad(nextval('app.product_code_seq')::text, 6, '0')`（形如 `PROD-000001`），
  沿用 SKU 侧 `sku_code_seq` 的既有写法。
- `ProductWrite.productCode` 由 `@NotBlank` 改为可选/移除。
  **不保留「调用方可指定编码」的口子**——那正是平台身份漏进内部编码的入口。
  确需外部标识就写映射表。
- 前端 `ProductsPage.tsx:91` 与 `SkusPage.tsx:101` 删掉 `product_code` 输入项
  （占位符写的 `如 P-1001` 库里根本不存在这种形态，一并消失）。

### 3. 存量 85 个编码迁移到统一序列

**这一段由主导者执行生产 SQL，Codex 只写迁移脚本与验证查询，不碰生产库。**

- 按 id 升序重新编号为 `PROD-000001..PROD-000085`，序列游标推到 86
- 备份表保留旧编码，可完整回滚
- 前置验证（已实测通过，迁移脚本里要再跑一遍作为守卫）：
  三类血缘各自 100% 可从映射表/档案表还原

**改名安全性已核实**：`product_code` 在界面上**一处都不显示**（只作为两个新建表单的输入项），
不在 xlsx 导出的 8 个固定列里，`useAnalyticsData.ts:170` 只在
`product_id` 与 `sku_id` 双双缺失时把它当兜底键。无外部消费。

## 不做的事

- 🚫 不动 `provider_skus` / `source_channel_skus` 里的平台身份数据——那是它们该待的地方
- 🚫 不动 `sku_code` 的既有规则（它已经是对的）
- 🚫 Codex 不执行任何生产 SQL
- 🚫 不碰 `McpServer.java` / `McpWriteGate.java` / `docs/ops/deploy-runbook.md` / `.claude/`
- 🚫 不动 catalog 的价格通道（那是票 07）

## Acceptance Criteria

- [ ] 导入器凭 `provider_skus` 映射匹配商品，不再用 `product_code` 拼串查找
- [ ] 新增测试：product_code 与 jdCode 无关时，导入匹配到既有商品且**不新建重复商品**
- [ ] catalog 包既有测试全绿（Testcontainers 拿不到 Docker socket 时如实说明）
- [ ] 新建商品无「商品编码」输入项；服务端生成 `PROD-NNNNNN`，并发下不重复
- [ ] `ProductWrite` 不再接受外部指定 product_code（或明确拒绝）
- [ ] 存量迁移脚本 + 前置血缘校验守卫 + 回滚备份表齐备（不执行）
- [ ] `docs/openapi.yaml` / `docs/api-contract.md` 同步
- [ ] `npm run typecheck && npm run build` 全绿

## Files likely affected

- 新迁移（序列 + 存量改名脚本分开）
- `backend/src/main/java/cn/zimu/fulfillment/catalog/AuthoritativeSkuCatalogImportService.java`
- `backend/src/main/java/cn/zimu/fulfillment/seed/JdInitialSkuLibraryInitializer.java`
- `backend/src/main/java/cn/zimu/fulfillment/product/ProductWrite.java`
- `backend/src/main/java/cn/zimu/fulfillment/masterdata/MasterDataService.java`
- `frontend/src/pages/product/{ProductsPage.tsx,SkusPage.tsx}`
- `frontend/src/pages/product/{skuCommercialPrice.ts,productArchiveFields.ts}`（构造 body 处）
- `docs/openapi.yaml`、`docs/api-contract.md`、对应测试

## 工作区纪律

禁 `git add -A` / `commit` / `checkout|restore|stash`。排在票 06 之后。

## Risk

中。风险集中在**导入器的匹配键切换**——切错会建重复商品。
所以顺序是「先改匹配、测试证明不重复、再改编码」，不能反过来。
存量改名本身安全（无外部消费，已核实），但仍走备份 + 可回滚。
