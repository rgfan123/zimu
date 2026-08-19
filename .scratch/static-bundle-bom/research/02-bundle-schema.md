# 02 — 静态礼包主数据 schema 设计（research 产出）

Type: research
Status: open → 本文件为 02 票的调研结论与 DDL 草案
Label: wayfinder:research
产出路径：`.scratch/static-bundle-bom/research/02-bundle-schema.md`
关联票：01（源文件合并）、03（渠道识别）、04（履约展开边界，Blocked by 本票）、05（PMS 上架）

---

## 0. 结论速览

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 礼包主表 | **新建 `app.product_bundles`**，不复用 `products` 加类型列（理由见 §2） |
| 2 | 组件表 | 新建 `app.bundle_items`：`bundle_id + sku_id(→skus) + quantity_per_bundle(正整数) + sort_no + emg_code_snapshot(缺 EMG=NULL) + source_text_snapshot`；`UNIQUE(bundle_id, sort_no)`、`UNIQUE(bundle_id, sku_id)`；同 provider 由触发器维护 |
| 3 | 别名/识别 | **新建 `app.bundle_aliases` 表**（镜像 `sku_aliases` 模式），不用列 |
| 4 | 与订单快照 | `order_lines` 新增 `bundle_id`（静态礼包命中时非空，当单定制仍为 NULL）；`order_line_components` **不加列**；快照一致性复用现有 `validate_order_line_component` + 可选新增静态快照校验触发器 |
| 5 | BOM 版本 | **不引入 `bundle_versions`**：直接允许修改主数据 + 下单快照隔离（历史订单不受影响）；礼包主表删除由 FK RESTRICT + 触发器挡 |
| 6 | 分析视图 | 既有 `v_product_daily`/`v_channel_daily` 按组件展开已天然正确；**推荐给 `v_product_daily` 加 `bundle_id/bundle_name` 列**（最小改动）；如需礼包独立分析再建 `analytics.v_bundle_daily`（草案一并给出） |

净增量（相对活库 53 表 / 4 视图 / 64 触发器）：**+3 表、+1 列（`order_lines.bundle_id`）、+3~4 触发器函数、+4 索引、（可选）+1 分析视图或 +2 视图列**。

---

## 1. 现状核对（以活库为准）

### 1.1 文档与活库的关系

- `docs/schema.sql` 是 **V1 冻结基线**（38 业务表 / 4 视图 / 67 触发器），`docs/schema.md` §3 已随 V2–V35 增补到 53 张业务表。
- 活库权威结构见 `docs/schema-export-current.sql`（2026-08-17 导出，53 表 / 64 触发器 / 93 索引），且 `backend/src/main/resources/db/migration/` 已有 V30（product 运营字段）等后续迁移，实体 `Product.java` 与 `V30__add_product_archive_fields.sql` 一致。
- 本草案以**活库当前结构**为基准落新对象，DDL 书写风格对齐 `docs/schema.sql`（`VARCHAR + CHECK`、`TIMESTAMPTZ`、`BIGINT IDENTITY`、`lock_version`、`btrim` 非空检查、中文注释）。落地时作为 Flyway 增量迁移（下一个版本号 `V36`）执行，并同步回 `docs/schema.sql` / `docs/schema.md` 权威快照。

### 1.2 商品域相关表（活库现状）

| 表 | 关键列 | 与本票的关系 |
|---|---|---|
| `app.products` | `product_code` UNIQUE、`product_name`、`category_id`、`description`、`active`、`lock_version`、V30 运营字段（`ingredients`/`tags`/`listed_from`/`listed_until`/`lead_time_hours`/`purchase_price`/`retail_price`/`other_cost`/`main_image_ref`） | 无 `barcode`、无 `tax_rate`、无结算成本列；`barcode` 在 `skus`（SKU 级）；被 `skus.product_id` RESTRICT 引用 |
| `app.skus`（即旧决策文档所称 internal_skus） | `sku_code` UNIQUE、`product_id`、`fulfillment_provider_id`（不可变）、`specification`、`unit`、`barcode`、`purchase_price`/`retail_price`（V13）、`active` | 礼包组件的引用目标 |
| `app.sku_aliases` | `sku_id`、`alias_type ∈ NAME/BARCODE/SPECIFICATION/OTHER`、`alias_value`、`active`、`UNIQUE(sku_id, alias_type, alias_value)` | 别名机制的现成范式（礼包别名镜像它） |
| `app.source_channel_skus` | `(source_channel, source_sku_ref)` UNIQUE、`quantity_multiplier`、`sku_id` | 渠道商品映射；礼包命中是"按名称命中礼包主数据"，不是普通 SKU 映射 |
| `app.provider_skus` | `(provider, provider_sku_code)` UNIQUE、`merchant_sku_code`、`external_codes JSONB` | 京东 EMG 编码的权威落点：`provider_sku_code`（京东侧 `goodsNo`/EMG） |
| `app.order_lines` | `line_type ∈ SINGLE/CUSTOM_BUNDLE`、`sku_id`（CUSTOM_BUNDLE 为 NULL）、`fulfillment_provider_id`、各快照列、`requested_quantity` | 本票加 `bundle_id` 列 |
| `app.order_line_components` | `order_line_id`、`component_no`、`sku_id`、`quantity_per_bundle`、`total_quantity`、三个快照列；`UNIQUE(order_line_id, component_no)`、`UNIQUE(order_line_id, sku_id)` | 下单快照的落点，**不改结构** |

### 1.3 现有礼包相关触发器（复用参照）

- `trg_component_validation` → `validate_order_line_component()`：父行必须 `CUSTOM_BUNDLE`、履约承诺后不可变、provider 必须已分配且组件同 provider、`total_quantity = requested_quantity × quantity_per_bundle`。
- `trg_component_delete_protection` → `protect_order_line_component_delete()`：承诺后禁止删除组件。
- `trg_order_line_validation` → `validate_order_line()`：`CUSTOM_BUNDLE` 份数为整数、`sku_id IS NULL`、承诺后冻结。
- 风格约定：拒绝用 `RAISE EXCEPTION` + 中文消息；写保护触发器返回 `OLD`。

### 1.4 源文件形态（决定字段设计的事实）

`大者国风上架品（内容详情）-202605更新(1).xlsx`（36 礼包）：

| 列 | 样例 | 备注 |
|---|---|---|
| 商品条码 | `9250522000028` | 13 位 EAN；**旧文件礼包无条码** → `product_bundles.barcode` 可空但建议唯一 |
| 商品名称 | `牛肉大礼包5200g （BJ）` | 带 `（BJ）` 等后缀，规范化属 01 票 |
| 内配 | `牛腩块500g*3 牛腱子…` 或换行列表 | 自由文本 → `bundle_items.source_text_snapshot` 留存血缘 |
| 礼包数量 | `1`/`2`/`3`/`4` | 单份用量，整数件数 |
| 京东商品编号 | `EMG4418727173231` | 165 个组件行仅 70 个带 EMG → **缺 EMG 用 NULL** |
| 税率(%) | `9` | `product_bundles.tax_rate NUMERIC(5,2)` |
| 大者结算成本 | `398.058252427184` | `product_bundles.settlement_cost NUMERIC(14,2)`（源精度超 2 位，应用层四舍五入或按 01 票归一） |

`京东商品编号.xlsx` Sheet2（23 旧礼包）：纯 `名称 + EMG + 数量`，单行内嵌两个礼包（格式错乱，属 01 票解析职责）；无条码/税率/成本。

---

## 2. 决策 1：新建 `product_bundles`（推荐） vs 复用 `products` 加类型列

### 推荐：新建 `app.product_bundles` 主表

### 理由

1. **语义边界**：`products` 是商品族（family），被 `skus.product_id` 以 `ON DELETE RESTRICT` 引用，是"多个规格 SKU 的族级容器"；礼包是**组合品（BOM 聚合）**，一个礼包对应多个 SKU 但**不拥有 SKU、不建 SKU、不计库存**。两者是不同领域对象——复用 `products` 意味着 `skus.product_id` 理论上可指向礼包行，必须新增触发器禁止，且商品目录/分析视图要处处过滤"类型=礼包"的行，污染现有 `products` 的全部消费方（商品管理 API、`v_product_daily`、品类树）。
2. **字段不匹配**：礼包需要**商品条码、税率、大者结算成本、上架状态**。`products` 现有字段：名称/品类/描述/`active`/V30 运营字段——**没有 barcode、tax_rate、settlement_cost**；条码语义在 `skus`（SKU 级），而礼包不建 SKU，条码无处安放。复用 `products` 必须为这三列新增列，且对普通商品 100% 为 NULL；`products` 已被 V30 加宽到 9 个运营字段，继续堆礼包专属列会让商品域表语义混杂。
3. **与已定方向一致**："礼包 = Product + BOM"——新建 `product_bundles` 本身承载"Product 部分"（名称、品类、条码、税率、成本、上架状态），`bundle_items` 承载"BOM 部分"，两者合起来就是"礼包 = Product + BOM"，方向成立且零豁免。若复用 `products`，"Product"指 `products` 行，则需把 `products` 改造为可承载礼包 + 一堆豁免约束，改动面大、回归风险高。
4. **独立生命周期**：礼包有 `DRAFT → ACTIVE → INACTIVE` 流转（组件映射未齐时不可被订单命中），独立别名识别、独立 PMS 上架；独立表让约束（组件齐全才能 ACTIVE、同 provider、唯一组件）都在自己身上，不与 `skus` 的 `provider 不可变` 等既有触发器耦合。
5. **可演进性**：未来礼包若加"礼包级图片/规格/上架时间"，只动 `product_bundles`，不触碰 `products`。

### 若复用 `products` 的代价（为什么否决）

- 需新增 `kind/is_bundle` 判别列 + `barcode/tax_rate/settlement_cost` 三列（普通商品全 NULL）；
- 需新触发器禁止 `skus.product_id → bundle 行`、禁止普通商品行填写礼包专属列；
- 需改 `v_product_daily`、商品管理 API、SKU 映射链路的所有查询去过滤类型；
- `product_code` 唯一命名空间与礼包编码（可能用条码）冲突。

> 结论：**新建 `product_bundles`**。它不替代 `products`，而是商品域的一个并列主数据实体。

---

## 3. 决策 2：组件表 `bundle_items`

### 3.1 表结构要点

```text
bundle_id            → product_bundles.id  ON DELETE RESTRICT（礼包必须有组件，删礼包即删组件）
sort_no              INTEGER > 0          组件在礼包内的序号（导入顺序/展示顺序）
sku_id               → skus.id  NOT NULL  组件必须是已映射的内部 SKU（旧文档所称 internal_skus）
quantity_per_bundle  NUMERIC(18,3)         正整数 CHECK（trunc = value 且 > 0）
emg_code_snapshot    VARCHAR(64) NULL      来源文件中的京东商品编号快照；缺 EMG = NULL
source_text_snapshot VARCHAR(255) NULL     "内配"原文（如"牛腩块500g"），供 01 票匹配与人工复核
created_at/updated_at
UNIQUE (bundle_id, sort_no)                序号唯一
UNIQUE (bundle_id, sku_id)                 同一礼包内同一 SKU 只允许一行（组件去重）
```

### 3.2 关键设计问题与结论

| 问题 | 结论 | 理由 |
|---|---|---|
| 组件可能缺 EMG，用什么表示 | `emg_code_snapshot = NULL` | EMG 是**来源平台编码快照**，不是内部必需键；权威编码在组件映射 `skus` 后由 `provider_skus.provider_sku_code`（京东 `goodsNo`/EMG）承担。缺 EMG 表示"来源文件未给出京东编号"，不阻塞组件入库 |
| `sku_id` 是否 NOT NULL | **NOT NULL**（主方案） | 组件必须能落到内部 SKU 才能展开履约。165 组件仅 70 带 EMG ≠ SKU 缺失：EMG 缺 ≠ 不能建 SKU（名称匹配可建）。若 01 票确认存在"连 SKU 都未映射"的组件，则改 DDL 为 `sku_id NULL` + 触发器"`bundle.status=ACTIVE` 要求全部组件 `sku_id` 非空"（备选方案见 §7 DDL 注释） |
| 数量约束 | `CHECK (quantity_per_bundle > 0 AND trunc(quantity_per_bundle) = quantity_per_bundle)` | 与 `order_lines` 的 `CUSTOM_BUNDLE` 整数份数约束对齐（Q28 类型仍为 NUMERIC(18,3)，但静态礼包组件是件数，源文件为整数） |
| 同 provider 约束 | **要约束**，存 `product_bundles.fulfillment_provider_id` + 触发器维护 | 现有礼包规则：同一礼包只能一个履约方（Q18、`trg_component_validation`）。`skus.fulfillment_provider_id` 不可变且互斥归属（Q14），因此同 provider = "礼包内所有组件 SKU 的 provider 相同"。触发器在组件插入/更新时推导：bundle 无 provider → 填充为组件 SKU 的 provider；已有 → 校验一致。礼包命中的 order_line 直接取 `product_bundles.fulfillment_provider_id`，无需 join 组件 |
| 快照 vs 引用 | 组件引用 `skus`（实时），**不**快照名称/规格/单位 | `order_line_components` 才是下单快照；`bundle_items` 是主数据，应反映当前 SKU。`source_text_snapshot` 仅留导入血缘 |

---

## 4. 决策 3：别名/识别表 `bundle_aliases`（表，不是列）

### 推荐：新建 `app.bundle_aliases`，镜像 `sku_aliases`

```text
bundle_id    → product_bundles.id  ON DELETE RESTRICT
alias_type   VARCHAR(32) CHECK IN ('NAME','BARCODE','SPECIFICATION','OTHER')
alias_value  VARCHAR(255) NOT NULL
active       BOOLEAN DEFAULT TRUE
created_at
UNIQUE (bundle_id, alias_type, alias_value)
部分索引 idx_bundle_aliases_value (alias_type, alias_value) WHERE active   -- 名称命中查询
```

### 理由

1. **一个礼包多个渠道叫法**：彩食鲜/聚福宝/飞象/企微对同一礼包名称不同（且带 `（BJ）` 后缀变体），列只能存一个，放不下。
2. **与现有 `sku_aliases` 同构**：别名类型、`active` 停用、唯一键、部分索引全部复用现有范式，识别代码可与 SKU 候选机制共享匹配逻辑（03 票扩展）。
3. **别名只用于识别建议，不自动建立业务映射**（延续 Q21 哲学）：`bundle_aliases` 命中后仍需走确认，不造成"名称相似即发货"。
4. 列方案（`product_bundles.alias JSONB` 或单列 `alias_value`）无法表达多别名 + 类型 + 停用，且 JSONB 数组不可被 `WHERE alias_value = ?` 高效命中。

---

## 5. 决策 4：与 `order_line_components` 的关系

### 5.1 `order_lines` 新增 `bundle_id`（唯一新增列）

```sql
ALTER TABLE app.order_lines
    ADD COLUMN bundle_id BIGINT REFERENCES app.product_bundles(id) ON DELETE RESTRICT;
```

- **静态礼包命中**：`line_type='CUSTOM_BUNDLE'` + `bundle_id` 非空 + 下单时把 `bundle_items` 展开写入 `order_line_components`（`sku_id`、`quantity_per_bundle`、`total_quantity = requested_quantity × quantity_per_bundle`、名称/规格/单位快照取自 `skus` 当前值）。
- **当单定制礼包**：`line_type='CUSTOM_BUNDLE'` + `bundle_id IS NULL`，行为完全不变（并存，本次不改造）。
- **SINGLE 行**：`bundle_id` 必须 NULL（CHECK 约束）。
- 快照后 `order_line_components` 不可变（现有触发器），主数据后续修改不影响历史订单——**快照隔离是本设计的核心，主数据允许修改**。

### 5.2 `order_line_components` 是否需要新字段

**不需要。** 快照字段（sku/quantity_per_bundle/total_quantity/名称/规格/单位快照）已足够支撑履约导出、完整份数校验、采购展开；礼包溯源由 `order_lines.bundle_id` 承担（组件级 `source_bundle_item_id` 属过度设计，且组件快照不可变后该引用无业务价值）。

### 5.3 快照一致性防御

- **总量守恒**：现有 `validate_order_line_component` 已强制 `total_quantity = requested_quantity × quantity_per_bundle`，静态礼包展开复用，无需新写。
- **组件与主数据一致（推荐新增）**：`validate_static_bundle_snapshot()` 触发器（BEFORE INSERT/UPDATE ON `order_line_components`）：父行 `bundle_id` 非空时，校验本组件 `(sku_id, quantity_per_bundle)` 与该礼包 `bundle_items` 当前值一致。这防应用层快照 bug 写成错误组件；主数据在快照之后被改不影响（快照只比较写入时点）。
- 组件**完整性**（每个主数据组件都有快照行）由应用下单事务保证（与"履约导出逐行血缘 + 延迟约束"同模式，但下单快照是一次性写入，逐行校验即可）。

---

## 6. 决策 5：防御触发器清单 + BOM 版本结论

### 6.1 新增触发器（风格对齐现有 64 个）

| 触发器 | 表 | 函数 | 职责 |
|---|---|---|---|
| `trg_bundle_item_validation` | `bundle_items` BEFORE INSERT OR UPDATE | `validate_bundle_item()` | 数量正整数（列 CHECK 兜底）；推导/校验礼包 provider：bundle 无 provider → 填充为组件 SKU 的 provider；已有 → 必须一致，否则 `RAISE EXCEPTION 'all bundle components must belong to one fulfillment provider'`（与现有文案风格一致） |
| `trg_bundle_status_validation` | `product_bundles` BEFORE INSERT OR UPDATE | `validate_bundle_status()` | `status='ACTIVE'` 前置：礼包必须已有 ≥1 组件；若组件 `sku_id` 允许 NULL（备选方案）则要求全部组件已映射 |
| `trg_order_line_bundle_ref` | `order_lines` BEFORE INSERT OR UPDATE | `validate_order_line_bundle_ref()` | `SINGLE → bundle_id IS NULL`；`bundle_id` 非空 → 礼包存在且 `status='ACTIVE'` |
| `trg_static_bundle_snapshot` | `order_line_components` BEFORE INSERT OR UPDATE | `validate_static_bundle_snapshot()` | 父行 `bundle_id` 非空时校验组件与主数据一致（§5.3） |
| `trg_bundle_delete_protection`（可选） | `product_bundles` BEFORE DELETE | `protect_bundle_delete()` | 友好报错"礼包已被订单引用，禁止删除"；FK RESTRICT 已挡，此触发器仅统一报错文案（参照 `trg_component_delete_protection` 风格） |
| `set_updated_at` 扩展 | — | 复用 `set_updated_at()` | 把 `product_bundles`、`bundle_items`、`bundle_aliases` 加入现有 DO 循环数组 |

### 6.2 BOM 修改版本：**不引入 `bundle_versions`，直接允许修改 + 快照隔离（推荐）**

理由：

1. **方向已定**：识别命中时**下单快照 BOM** 到 `order_line_components`，主数据后续修改不影响历史订单——快照隔离本身就是版本管理，历史事实永久保留在订单侧（含 `order_versions` 审计）。
2. 主数据修改与 `products`/`skus` 一样是日常运营（换供应商、调成分），引入 `bundle_versions` 会在每次修改时复制整份 BOM，收益（可回看历史 BOM）与现有 `audit_logs` + `order_versions` 重叠，属过度设计。
3. 需要"某礼包历史 BOM"时：**已下单的看订单快照**（每单都有），**未下单的历史主数据形态**用 `audit_logs`（记录变更前后摘要）即可，不建版本表。
4. 若未来出现"BOM 生效时间窗/预定版本"硬需求（如节日礼包按版本发布），再单独开票引入 `bundle_versions`，本次不预建。

### 6.3 删除保护

- `bundle_items.bundle_id` 与 `order_lines.bundle_id` 均 `ON DELETE RESTRICT`：被订单/组件引用的礼包主表无法删除。
- 组件行删除：主数据允许（快照隔离），不设 delete 保护触发器；但 `UNIQUE(bundle_id, sku_id)` + 触发器保证删除后礼包仍同 provider、仍满足 ACTIVE 前置条件。

---

## 7. 完整 DDL 草案

> 书写风格对齐 `docs/schema.sql`（VARCHAR+CHECK、TIMESTAMPTZ、BIGINT IDENTITY、lock_version、btrim 非空、中文注释）。落地为 Flyway `V36__add_static_bundle_master.sql`；同时需并入 `docs/schema.sql` / `docs/schema.md` 权威快照并更新对象计数。

```sql
-- ===========================================================================
-- V36: 静态礼包 BOM 主数据
-- 礼包 = product_bundles(商品族属性) + bundle_items(组件清单)
-- 礼包本身不创建 internal_sku、不单独计库存；订单命中时下单快照 BOM 到
-- order_line_components，主数据后续修改不影响历史订单。
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) 礼包主表
-- ---------------------------------------------------------------------------
CREATE TABLE app.product_bundles (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    bundle_code         VARCHAR(64) NOT NULL UNIQUE,
    bundle_name         VARCHAR(200) NOT NULL,
    category_id         BIGINT REFERENCES app.categories(id) ON DELETE RESTRICT,
    barcode             VARCHAR(64) UNIQUE,
    description         TEXT,
    tax_rate            NUMERIC(5, 2),
    settlement_cost     NUMERIC(14, 2),
    -- 礼包级履约方：由 bundle_items 触发器推导维护；NULL=组件未齐
    fulfillment_provider_id BIGINT REFERENCES app.fulfillment_providers(id) ON DELETE RESTRICT,
    -- 上架状态：DRAFT=组件未齐不可被订单命中；ACTIVE=可识别命中；INACTIVE=下架
    status              VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    lock_version        BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (btrim(bundle_code) <> ''),
    CHECK (btrim(bundle_name) <> ''),
    CHECK (barcode IS NULL OR btrim(barcode) <> ''),
    CHECK (tax_rate IS NULL OR (tax_rate >= 0 AND tax_rate <= 100)),
    CHECK (settlement_cost IS NULL OR settlement_cost >= 0)
);

-- ---------------------------------------------------------------------------
-- 2) 组件表
-- ---------------------------------------------------------------------------
CREATE TABLE app.bundle_items (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    bundle_id           BIGINT NOT NULL REFERENCES app.product_bundles(id) ON DELETE RESTRICT,
    sort_no             INTEGER NOT NULL CHECK (sort_no > 0),
    sku_id              BIGINT NOT NULL REFERENCES app.skus(id) ON DELETE RESTRICT,
    quantity_per_bundle NUMERIC(18,3) NOT NULL
                        CHECK (quantity_per_bundle > 0
                               AND trunc(quantity_per_bundle) = quantity_per_bundle),
    -- 来源文件中的京东商品编号快照；缺 EMG = NULL（权威编码在 provider_skus.provider_sku_code）
    emg_code_snapshot   VARCHAR(64),
    -- "内配"原文，供匹配与复核（01 票输入）
    source_text_snapshot VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bundle_id, sort_no),
    UNIQUE (bundle_id, sku_id),
    CHECK (emg_code_snapshot IS NULL OR btrim(emg_code_snapshot) <> ''),
    CHECK (source_text_snapshot IS NULL OR btrim(source_text_snapshot) <> '')
);

-- 备选（若 01 票确认存在连 SKU 都未映射的组件）：将 sku_id 改为可空，
-- 并在 validate_bundle_status() 中要求 status='ACTIVE' 时全部组件 sku_id 非空。
-- CREATE UNIQUE INDEX uq_bundle_items_unmapped ON app.bundle_items(bundle_id)
--     WHERE sku_id IS NULL;  -- 每礼包至多一行未映射组件（如需允许多行则去掉）

-- ---------------------------------------------------------------------------
-- 3) 别名/识别表（镜像 sku_aliases；只用于识别建议，不自动建立业务映射）
-- ---------------------------------------------------------------------------
CREATE TABLE app.bundle_aliases (
    id                  BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    bundle_id           BIGINT NOT NULL REFERENCES app.product_bundles(id) ON DELETE RESTRICT,
    alias_type          VARCHAR(32) NOT NULL
                        CHECK (alias_type IN ('NAME', 'BARCODE', 'SPECIFICATION', 'OTHER')),
    alias_value         VARCHAR(255) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (bundle_id, alias_type, alias_value),
    CHECK (btrim(alias_value) <> '')
);

-- ---------------------------------------------------------------------------
-- 4) order_lines 增加 bundle_id（静态礼包命中时非空；当单定制仍为 NULL）
-- ---------------------------------------------------------------------------
ALTER TABLE app.order_lines
    ADD COLUMN bundle_id BIGINT REFERENCES app.product_bundles(id) ON DELETE RESTRICT;

ALTER TABLE app.order_lines
    ADD CONSTRAINT order_lines_bundle_single_only
    CHECK (line_type <> 'SINGLE' OR bundle_id IS NULL);

-- ---------------------------------------------------------------------------
-- 5) 触发器函数
-- ---------------------------------------------------------------------------

-- 5.1 组件写入：数量正整数（列 CHECK 兜底）+ 推导/校验礼包履约方
CREATE FUNCTION app.validate_bundle_item() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    sku_provider_id BIGINT;
    bundle_provider_id BIGINT;
BEGIN
    SELECT fulfillment_provider_id INTO STRICT sku_provider_id
    FROM app.skus WHERE id = NEW.sku_id;

    SELECT fulfillment_provider_id INTO bundle_provider_id
    FROM app.product_bundles WHERE id = NEW.bundle_id;

    IF bundle_provider_id IS NULL THEN
        -- 首个组件：把礼包履约方定为该组件 SKU 的履约方
        UPDATE app.product_bundles
           SET fulfillment_provider_id = sku_provider_id
         WHERE id = NEW.bundle_id;
    ELSIF bundle_provider_id <> sku_provider_id THEN
        RAISE EXCEPTION 'all bundle components must belong to one fulfillment provider';
    END IF;
    RETURN NEW;
END;
$$;

-- 5.2 礼包状态：ACTIVE 前置条件（至少一个组件；备选方案下还要求组件已映射 SKU）
CREATE FUNCTION app.validate_bundle_status() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'ACTIVE' AND NOT EXISTS (
        SELECT 1 FROM app.bundle_items WHERE bundle_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'active bundle requires at least one component';
    END IF;
    -- 若采用 sku_id 可空备选方案，追加：
    -- IF NEW.status = 'ACTIVE' AND EXISTS (
    --     SELECT 1 FROM app.bundle_items
    --     WHERE bundle_id = NEW.id AND sku_id IS NULL
    -- ) THEN
    --     RAISE EXCEPTION 'active bundle requires every component mapped to a SKU';
    -- END IF;
    RETURN NEW;
END;
$$;

-- 5.3 order_lines.bundle_id 引用校验
CREATE FUNCTION app.validate_order_line_bundle_ref() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    bundle_status_value VARCHAR(16);
BEGIN
    IF NEW.line_type = 'SINGLE' AND NEW.bundle_id IS NOT NULL THEN
        RAISE EXCEPTION 'single order line cannot reference a bundle';
    END IF;
    IF NEW.bundle_id IS NOT NULL THEN
        SELECT status INTO STRICT bundle_status_value
        FROM app.product_bundles WHERE id = NEW.bundle_id;
        IF bundle_status_value <> 'ACTIVE' THEN
            RAISE EXCEPTION 'order line can only reference an ACTIVE bundle';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- 5.4 静态礼包快照一致性：父行 bundle_id 非空时，组件须与主数据当前 BOM 一致
CREATE FUNCTION app.validate_static_bundle_snapshot() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
DECLARE
    parent_bundle_id BIGINT;
    matched BOOLEAN;
BEGIN
    SELECT bundle_id INTO parent_bundle_id
    FROM app.order_lines WHERE id = NEW.order_line_id;

    IF parent_bundle_id IS NOT NULL THEN
        SELECT EXISTS (
            SELECT 1 FROM app.bundle_items
            WHERE bundle_id = parent_bundle_id
              AND sku_id = NEW.sku_id
              AND quantity_per_bundle = NEW.quantity_per_bundle
        ) INTO matched;
        IF NOT matched THEN
            RAISE EXCEPTION 'static bundle snapshot does not match bundle master BOM';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

-- 5.5 礼包删除保护（可选；FK RESTRICT 已挡，此触发器统一报错文案）
CREATE FUNCTION app.protect_bundle_delete() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'bundle referenced by orders or components cannot be deleted';
END;
$$;

CREATE TRIGGER trg_bundle_item_validation
BEFORE INSERT OR UPDATE ON app.bundle_items
FOR EACH ROW EXECUTE FUNCTION app.validate_bundle_item();

CREATE TRIGGER trg_bundle_status_validation
BEFORE INSERT OR UPDATE ON app.product_bundles
FOR EACH ROW EXECUTE FUNCTION app.validate_bundle_status();

CREATE TRIGGER trg_order_line_bundle_ref
BEFORE INSERT OR UPDATE ON app.order_lines
FOR EACH ROW EXECUTE FUNCTION app.validate_order_line_bundle_ref();

CREATE TRIGGER trg_static_bundle_snapshot
BEFORE INSERT OR UPDATE ON app.order_line_components
FOR EACH ROW EXECUTE FUNCTION app.validate_static_bundle_snapshot();

CREATE TRIGGER trg_bundle_delete_protection
BEFORE DELETE ON app.product_bundles
FOR EACH ROW EXECUTE FUNCTION app.protect_bundle_delete();

-- 5.6 set_updated_at 循环数组追加（与现有 DO 块同款）
-- 'product_bundles', 'bundle_items', 'bundle_aliases'

-- ---------------------------------------------------------------------------
-- 6) 索引
-- ---------------------------------------------------------------------------
CREATE INDEX idx_bundle_items_bundle ON app.bundle_items(bundle_id);
CREATE INDEX idx_bundle_items_sku ON app.bundle_items(sku_id);
CREATE INDEX idx_bundle_aliases_value ON app.bundle_aliases(alias_type, alias_value) WHERE active;
CREATE INDEX idx_order_lines_bundle ON app.order_lines(bundle_id) WHERE bundle_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 7) 分析视图（最小方案：v_product_daily 加礼包维度列；完整方案见 §8）
-- ---------------------------------------------------------------------------
-- 方案 A（推荐最小改动）：CREATE OR REPLACE VIEW analytics.v_product_daily
-- 在两个分支各加：
--     pb.id AS bundle_id,
--     pb.bundle_name,
-- 并从 app.product_bundles pb ON pb.id = ol.bundle_id LEFT JOIN（两个分支），
-- GROUP BY 追加 bundle_id, bundle_name。
-- 说明：静态礼包命中后 order_line 为 CUSTOM_BUNDLE + bundle_id，
-- v_product_daily 既有 CUSTOM_BUNDLE 分支已经按 order_line_components 展开组件，
-- 组件口径不变；加列只用于下钻"哪些行来自哪个礼包"。
```

---

## 8. 决策 6：分析视图影响

| 视图 | 现状 | 静态礼包影响 | 结论 |
|---|---|---|---|
| `analytics.v_channel_daily` | CUSTOM_BUNDLE 按 `si.shipped_quantity × olc.quantity_per_bundle` 展开组件 | 静态礼包命中后走同一分支，口径不变 | **不改** |
| `analytics.v_product_daily` | CUSTOM_BUNDLE 分支 JOIN `order_line_components` 按组件展开到 SKU/Product | 同上，天然正确 | **推荐加 `bundle_id/bundle_name` 列**（方案 A，最小改动），组件口径不变 |
| `analytics.v_fulfillment_daily` / `v_fulfillment_channel_daily` | 按 Fulfillment 统计，与 line_type 无关 | 无影响 | **不改** |
| `app.v_order_progress_summary` | 按 order_lines 聚合 | 无影响 | **不改** |

如需"礼包独立分析"（按礼包维度看订单数/份数/实发件数），追加方案 B：

```sql
-- 方案 B（可选）：礼包维度日报（上海自然日 × SourceChannel × Bundle）
CREATE VIEW analytics.v_bundle_daily AS
SELECT
    (s.shipped_at AT TIME ZONE 'Asia/Shanghai')::DATE AS metric_date,
    o.source_channel,
    pb.id AS bundle_id,
    pb.bundle_code,
    pb.bundle_name,
    count(DISTINCT o.id)::BIGINT AS order_count,
    count(DISTINCT s.id)::BIGINT AS shipment_count,
    sum(si.shipped_quantity)::NUMERIC(18,3) AS bundle_count,
    sum(si.shipped_quantity * olc.quantity_per_bundle)::NUMERIC(18,3) AS expanded_component_quantity
FROM app.shipment_items si
JOIN app.shipments s ON s.id = si.shipment_id AND s.shipment_status IN ('SHIPPED', 'DELIVERED')
JOIN app.fulfillments f ON f.id = si.fulfillment_id
JOIN app.order_lines ol ON ol.id = f.order_line_id AND ol.line_type = 'CUSTOM_BUNDLE'
JOIN app.orders o ON o.id = ol.order_id AND o.data_scope = 'BUSINESS'
JOIN app.product_bundles pb ON pb.id = ol.bundle_id
LEFT JOIN app.order_line_components olc ON olc.order_line_id = ol.id
WHERE si.shipped_quantity > 0 AND s.shipped_at IS NOT NULL
GROUP BY 1, 2, 3, 4, 5;
```

> 建议：P0 只做方案 A（`v_product_daily` 加两列）；`v_bundle_daily` 等 03/05 票确认"礼包独立看板"真实需求后再落。

---

## 9. 与现有 schema 的差异清单

### 9.1 新增对象

| 类型 | 对象 | 说明 |
|---|---|---|
| 表 | `app.product_bundles` | 礼包主数据（商品族属性 + 条码/税率/结算成本 + 上架状态 + 礼包级履约方） |
| 表 | `app.bundle_items` | 组件清单（sort_no、sku_id、quantity_per_bundle、EMG 快照、内配原文快照） |
| 表 | `app.bundle_aliases` | 礼包识别别名（镜像 sku_aliases） |
| 列 | `app.order_lines.bundle_id` | 静态礼包命中溯源；当单定制仍为 NULL（并存） |
| 函数 | `app.validate_bundle_item` / `validate_bundle_status` / `validate_order_line_bundle_ref` / `validate_static_bundle_snapshot` / `protect_bundle_delete`（可选） | 触发器函数 |
| 触发器 | `trg_bundle_item_validation` / `trg_bundle_status_validation` / `trg_order_line_bundle_ref` / `trg_static_bundle_snapshot` / `trg_bundle_delete_protection`（可选） | 防御触发器；`set_updated_at` 循环追加 3 表 |
| 索引 | `idx_bundle_items_bundle` / `idx_bundle_items_sku` / `idx_bundle_aliases_value`(部分) / `idx_order_lines_bundle`(部分) | FK 前导索引 + 识别查询 |
| 视图 | `analytics.v_product_daily` 加 `bundle_id/bundle_name`（方案 A）；可选 `analytics.v_bundle_daily`（方案 B） | 分析口径 |

### 9.2 不动的对象

- `products` / `skus` / `sku_aliases` / `source_channel_skus` / `provider_skus` / `order_line_components`：结构零改动（`order_line_components` 仅新增触发器）。
- `order_lines` 的 `line_type` 枚举、SINGLE/CUSTOM_BUNDLE 既有约束：不改（静态礼包复用 `CUSTOM_BUNDLE`）。
- 既有 64 个触发器：全部保留。

### 9.3 需要同步的文档

- `docs/schema.md`：表清单（§3.1 商品域加 3 表）、核心关系 ER（`PRODUCT_BUNDLES ||--o{ BUNDLE_ITEMS`、`ORDER_LINE o|--o| PRODUCT_BUNDLES`）、不变量清单（同 provider、ACTIVE 前置、快照隔离）、§10 验证门槛的对象计数。
- `docs/schema.sql`：并入本 DDL，更新触发器计数（64 → 69，若含 delete 保护则 70）。
- `docs/schema-smoke.sql`：追加断言（见 §10）。
- `docs/api-contract.md`：§4.6 商品域加 ProductBundle 资源（`GET/POST /api/v1/product-bundles`、`PATCH /api/v1/product-bundles/{id}`、组件/别名子资源），由实现票落。
- `CONTEXT.md`：领域词汇增补「静态礼包 StaticBundle」（与「定制礼包 CustomBundle」并列）。

---

## 10. 触发器冒烟测试要点（对齐 schema-smoke.sql 风格）

在 `docs/schema-smoke.sql` 追加以下 DO 断言（每条期望拒绝都须被捕获，缺拒绝即失败）：

1. 礼包无组件时置 `ACTIVE` → 拒绝（`trg_bundle_status_validation`）。
2. 同礼包插入不同 provider 的组件 → 拒绝（`trg_bundle_item_validation`）；首个组件自动填充 `product_bundles.fulfillment_provider_id`。
3. `quantity_per_bundle = 0` 或 `1.5` → 拒绝（CHECK 正整数）。
4. 同礼包插入相同 `sku_id` 两行 → 拒绝（UNIQUE）；同 `sort_no` 两行 → 拒绝（UNIQUE）。
5. `emg_code_snapshot` 为空字符串 → 拒绝；为 NULL → 允许（缺 EMG 表示）。
6. SINGLE 行带 `bundle_id` → 拒绝；CUSTOM_BUNDLE 行带 INACTIVE/DRAFT 礼包 `bundle_id` → 拒绝（`trg_order_line_bundle_ref`）。
7. 静态礼包行快照组件 `(sku_id, quantity_per_bundle)` 与主数据不一致 → 拒绝（`trg_static_bundle_snapshot`）；一致时 `total_quantity` 仍须等于份数 × 单份用量（既有 `trg_component_validation`）。
8. 删除被订单引用的礼包 → 拒绝（FK RESTRICT / `trg_bundle_delete_protection`）。
9. 主数据修改后，既有 `order_line_components` 快照不被改写（快照隔离语义验证：改 `bundle_items` 数量，历史组件行不变）。

---

## 11. 待联动事项（非本票范围）

- 01 票：源文件合并（条码唯一性 vs 旧文件无条码；`source_text_snapshot` 与名称匹配；结算成本精度归一）。
- 03 票：渠道识别命中 `bundle_aliases` 的匹配规则与 NEED_REVIEW 分支；`bundle_id` 在 CanonicalOrder 转换链中的传递。
- 04 票：命中静态礼包后订单行展开/导出/采购复用边界（依赖本 DDL 的 `order_lines.bundle_id` 与快照触发器）。
- 05 票：PMS 上架（礼包形态、条码/税率/成本字段来源）。
