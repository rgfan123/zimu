# 08 — 商品档案支持 69 码（条码）搜索

Type: implementation
Status: 已实现（主导者手写，工作区未提交）
Priority: **P0**（已造成对外错误答复，见下）
Requested: Jerry 2026-08-28「商品档案搜索加一个 69 码搜索」

## 已发生的事故（2026-08-28 14:01，企微）

同事大鹏在企微问子牧 Bot：「06977872890432 这个牛肉卷你的数据库信息里面没有吗？」
Bot 答：**「数据库中确实没有条码 06977872890432 对应的商品信息」**，并列出检索路径
「1. SKU 商品主数据 — 未找到；2. 产品成本档案 — 也未找到」。

**第 2 条是错的。** 生产实测该码在 `app.product_archive_sheets` 里有 **3 行**：

```
row 69  原切牛肉卷        300g  状态空白  未挂接   ← 该码的真实归属
row 113 窦清源原切牛肉卷  400g  新品      SKU 85   ← 源 Excel 复制错误，待订正为空
row 114 窦清源精选牛肉卷  400g  新品      SKU 86   ← 同上
```

**大鹏问题的正确答案**：`06977872890432` 就是 **原切牛肉卷 300g**，
成本表 row 69 有它（AI 供货成本 14.43、AJ 售价 23），但系统里**还没建这个 SKU**——
所以「SKU 主数据没有」是真的，「成本档案没有」是假的。

根因就是本票要修的缺口：`search_product_archive` 底层的
`ProductArchiveSheetService.search` 只按 `product_name` 模糊搜，**看不见 D 列国条**，
所以拿 69 码去搜商品名必然零命中。Bot 如实转述了检索结果，是检索能力本身有洞。

（第 1 条「SKU 主数据未找到」是真的：该码是重复码，主数据补录时被通用守卫有意跳过，
`app.skus.barcode` 里确实没有它——但这需要解释，不是「没有这个商品」。）

**影响**：业务同事据此会认为商品不存在。这类错误答复每天都可能再发生。

## 问题

商品档案页的搜索框现在**搜不到条码**。后端 `sku/SkuRepository.java:19-30` 的检索只覆盖三个字段：

```java
WHERE (:pattern IS NULL
       OR lower(p.productName) LIKE lower(:pattern)
       OR lower(s.specification) LIKE lower(:pattern)
       OR lower(s.skuCode)      LIKE lower(:pattern))
```

`s.barcode` 不在其中。前端 placeholder（`SkusPage.tsx:177`）也只写「搜索 SKU 编码 / 商品名称」。
库里已有一批 SKU 补了条码（2026-08-28 主数据补录），拿着 69 码却查不到对应商品。

## 范围

### 1. SKU 检索加条码（主诉求）

`backend/src/main/java/cn/zimu/fulfillment/sku/SkuRepository.java`：

```java
       OR lower(s.barcode)      LIKE lower(:pattern)
```

注意 `barcode` 可空——JPQL 里 `lower(null) LIKE ...` 求值为 null（非 true），
不会把无条码行误当命中，但请**用单测钉死这条**（无条码 SKU 不应被任意关键词命中）。

同步更新该方法上方的 javadoc（现写「商品名/规格/SKU 编码大小写不敏感模糊检索」）。

### 2. ~~成本表档案检索也加 69 码~~ → **已移交票 11**

Jerry 2026-08-28 指出正确做法是「暴露一个 query 端口、提供可选参数、MCP 包装一下」，
而不是在现有的存储转储形状上再打补丁。成本表侧的 69 码检索因此**整体移交票 11**
（`11-archive-query-api-redesign.md`），在那里连同投影形状一起做对。

**本票不要碰** `ProductArchiveSheetService` 与 `McpDomainReadTools.archiveSheetNode`。

### 3. 前端提示同步

- `frontend/src/pages/product/SkusPage.tsx:177` 的 placeholder：
  `搜索 SKU 编码 / 商品名称` → `搜索 SKU 编码 / 商品名称 / 条码`
- 若成本表抽屉/档案检索也有独立搜索框，同步措辞

### 4. MCP 工具描述同步

`mcp/McpDomainReadTools.java` 的 `search_skus` 描述现写
「按商品名/规格/SKU 编号模糊检索 SKU 主数据」（约 `:107`），
以及 `query` 参数的描述（约 `:110`），都要加上条码。
`search_product_archive` 同理（约 `:135-137`）。

**只改描述字符串，不要动 `McpServer.java` 与 `McpWriteGate.java`**——那两个是别的会话的在制品。

## 不做的事

- 🚫 不改条码的写入/校验逻辑（本票只加检索）
- 🚫 不做条码唯一性约束或格式校验（库里有已知的重复码与过渡码 `PROD-LOCAL-R*`，
  加约束会炸，属另一决策）
- 🚫 不碰 `McpServer.java` / `McpWriteGate.java` / `docs/ops/deploy-runbook.md` / `.claude/`
- 🚫 不执行任何生产 SQL

### 5. 重复 69 码必须返回全部命中，不得只返首条

⚠️ **先看数据订正**：成本表 row 111-114（窦清源 4 品）的 D 列国条是**源 Excel 的复制错误**
——Jerry 2026-08-28 明确：窦清源属研发中、还没有 69 码，D 列应留空。
订正 SQL 见 `/Users/jerry/zimu-work/inbox/douqingyuan-blank-barcode-20260828.sql`。
（SKU 侧本来就是对的：这 4 个 SKU 的 `barcode` 为空，补录守卫当初跳过是正确的。）

**订正后**，成本表里真正的重复码只剩一个：

```
06977872890135 ×2   row61 原切牛肋条(500g)  |  row62 原切牛肋条(750g)   ← 同品两规格共用一码
```

另外两个码订正后各自唯一归属：

```
06977872890432  →  row69 原切牛肉卷 300g（未建品）
06977872890579  →  row56 原切精选羔羊肉卷 200g（未建品）
```

即便如此，按 69 码检索**仍可能多命中**（牛肋条那对）。实现与测试必须覆盖：
返回全部匹配行（含各自规格与挂接状态），**不要 limit 1、不要静默取首条**——
只返一条会让使用者以为该码唯一，比查不到更危险。用 `06977872890135` 断言返回 2 行。

## Acceptance Criteria

- [ ] **回归事故用例**：检索 `06977872890432` 能在成本表侧返回 3 行（69/113/114），
      不再出现「未找到」——这是本票的验收锚点
- [ ] 重复码返回全部命中，无 limit 1 / 取首条
- [ ] 在商品档案页输入完整 69 码能搜到对应 SKU；输入前缀/片段也能命中（LIKE 模糊）
- [ ] 无条码的 SKU 不会被任意关键词误命中（单测钉死）
- [ ] 成本表档案检索可按 D 列 69 码命中，**含未挂接行**
- [ ] 前端 placeholder 已含「条码」
- [ ] `search_skus` / `search_product_archive` 的 MCP 描述已含条码
- [ ] 既有三字段检索行为零回归（商品名/规格/SKU 编码原样可搜）
- [ ] 定向测试：SkuRepository 检索单测、ProductArchiveSheetService 检索测试、
      `npm run typecheck && npm run build`；不跑重负载全量并行套件

## Files likely affected

- `backend/src/main/java/cn/zimu/fulfillment/sku/SkuRepository.java`
- `backend/src/main/java/cn/zimu/fulfillment/masterdata/ProductArchiveSheetService.java`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java`（仅描述串）
- `frontend/src/pages/product/SkusPage.tsx`（仅 placeholder）
- 对应测试

## 工作区纪律

- **禁** `git add -A` / `git commit` / `git checkout|restore|stash`
- ⚠️ **必须等票 06 落地后再开工**：两票都会改 `MasterDataService.java` 与 `SkusPage.tsx`，
  并行会互相覆盖

## Risk

低。纯检索扩展，加一个 OR 条件 + 一个 jsonb 取值条件，无写操作、无 schema 变更。
唯一需要注意的是可空 `barcode` 的 null 语义，已在 AC 里钉死。
