# 14 — 产品状态做成固定类型，并支持按类型查询

Type: implementation
Status: **ready-for-agent**（Jerry 2026-08-28 已拍板，见下）
Priority: P1
Requested: Jerry 2026-08-28「产品状态应固定为几种类型 type 并且可按 type query」

## 现状：系统里根本没有产品状态

`app.products` 只有 `active boolean NOT NULL`，`app.skus` 同样只有 `active`。
**没有任何生命周期状态字段。**

真正的状态只以**自由文本**存在于成本表 B 列「产品状态」：

```
(空)   45        停产   42        研发   18        新品   4        断货   1
```

## 实测后果：`active` 携带零信息

```
全库 92 个 SKU，active = true 的有 92 个，false 的有 0 个。
其中包括：成本表标「停产」的 23 个、「研发」的 2 个、「断货」的 1 个
（断货那个是 row 90 子牧安格斯极佳眼肉牛排 200g → SKU id 55）。
```

**停产品和在售品在系统里完全无法区分**——照样能被下单、进发货台、进采购建议、
进库存判定。这不是展示问题，是主数据缺了一个维度。

## ✅ 取值表（Jerry 2026-08-28 已拍板）

> 「空值算在售 断货不算 就是停产」

**四个状态，不多不少**：

| 取值 | 中文 | 回填来源 | 行数 |
|---|---|---|---|
| `ON_SALE` | 在售 | 成本表 B 列**空值** | 45 |
| `NEW` | 新品 | B 列「新品」 | 4 |
| `IN_DEVELOPMENT` | 研发 | B 列「研发」 | 18 |
| `DISCONTINUED` | 停产 | B 列「停产」**+「断货」** | 42 + 1 = **43** |

⚠️ **不要引入 `OUT_OF_STOCK`**。成本表里唯一那行「断货」
（row 90 子牧安格斯极佳眼肉牛排 200g → SKU id 55）**按停产处理**。
回填 SQL 里对 B 列的映射要显式写成 `'停产'|'断货' → DISCONTINUED`，
并加注释说明这是 Jerry 的口径，免得后人看到「断货」以为漏了一个状态。

缺省值：`ON_SALE`（与「空值算在售」一致）。

### 决策 2：状态挂在哪一层

**建议挂 `app.skus`（按规格）**，理由：成本表一行 = 一个规格，
`原切牛肋条 500g`（row 61）与 `750g`（row 62）是两行、可以各自停产。
挂 `products` 会丢掉这个粒度。

若 Jerry 认为状态是商品级的，则挂 `products`，但要接受「同商品不同规格不能分别停产」。

## 做法（拍板后）

1. **迁移**（号取当时最大+1）：给 `app.skus` 加

   ```sql
   ALTER TABLE app.skus ADD COLUMN lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'ON_SALE'
       CHECK (lifecycle_status IN ('ON_SALE','NEW','IN_DEVELOPMENT','DISCONTINUED','OUT_OF_STOCK'));
   CREATE INDEX idx_skus_lifecycle_status ON app.skus (lifecycle_status);
   ```

2. **回填**：从成本表 B 列回填已挂接的 47 个 SKU（停产 23 / 研发 2 / 新品 4 / 断货 1，
   其余按决策 1 的空值语义）。回填 SQL 由主导者执行，**Codex 不碰生产库**。

3. **`active` 与 `lifecycle_status` 不合并**：
   - `active` = 系统启用/停用（软删除、运营手动下架）
   - `lifecycle_status` = 业务生命周期
   两者正交。**不要用状态推导 active，也不要反过来**——现在 active 全 true，
   合并只会把两个语义都搞坏。是否让「停产」自动影响下单校验，属另一决策，本票不做。

4. **查询支持**（Jerry 明确要求「可按 type query」）：
   - 后端：`MasterDataService.skus(...)` 与 `SkuRepository.search` 增加
     `lifecycle_status` 过滤参数（可多选）
   - 前端：商品档案页搜索栏旁加状态筛选（多选），与既有「履约方」筛选并列
   - MCP：`search_skus` 增加 `status` 可选参数并同步工具描述
     ⚠️ 若票 11 已落地，`search_product_archive` 的 `status` 参数与本票口径要一致

5. **展示**：商品档案页列表把状态显示为带色 Tag（停产灰、研发蓝、新品绿、断货橙），
   沿用仓库既有 Tag 用法，不新造色板。

## 不做的事

- 🚫 不合并 `active` 与 `lifecycle_status`
- 🚫 不让状态自动阻断下单/发货（那是业务规则变更，另立票）
- 🚫 不改成本表 B 列（它是快照，回填方向是 成本表 → skus，不是反向）
- 🚫 不执行任何生产 SQL（回填由主导者做）

## Acceptance Criteria

- [ ] `lifecycle_status` 列存在，CHECK 约束限定取值，有索引
- [ ] 新建 SKU 时可选状态，缺省按决策 1
- [ ] 编辑弹窗可改状态（与票 06 的编辑界面对齐一并考虑）
- [ ] 商品档案页可按状态多选筛选，与履约方筛选可叠加
- [ ] `search_skus` MCP 工具支持 `status` 参数，描述已同步
- [ ] 列表显示状态 Tag
- [ ] 既有查询零回归（不传状态时结果与今天一致）
- [ ] `docs/openapi.yaml` / `docs/api-contract.md` 同步
- [ ] 定向测试 + `npm run typecheck && npm run build`

## Files likely affected

- 新迁移
- `backend/src/main/java/cn/zimu/fulfillment/sku/{Sku,SkuRepository}.java`
- `backend/src/main/java/cn/zimu/fulfillment/masterdata/MasterDataService.java`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java`（`search_skus` 参数与描述）
- `frontend/src/pages/product/SkusPage.tsx`、`frontend/src/api/{types.ts,endpoints.ts}`
- `docs/openapi.yaml`、`docs/api-contract.md`、对应测试

## 工作区纪律

禁 `git add -A` / `commit` / `checkout|restore|stash`。排在票 06、13 之后（都改同一批表单文件）。

## Risk

中。加列本身低风险（有默认值、不破坏既有行），风险在**取值表定错**——
一旦回填了 92 行再改枚举，就要二次迁移。所以决策 1 必须先拍板。
