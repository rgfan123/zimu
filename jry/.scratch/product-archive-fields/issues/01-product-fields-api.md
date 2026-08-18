# 01 — 商品档案字段：数据层与 API（含毛利计算）

**What to build:** 后端为商品档案新增字段提供完整数据能力：`products` 表新增 原料、商品标签（JSONB 数组）、上市周期（起止日期）、发货时效（小时）、零售价/进货价/其他成本（毛利计算输入）、主图引用 各列及约束；商品创建/更新 API 支持这些字段并校验（价格格式、小时数为正整数、日期先后、标签数量与长度）；商品查询投影（含 SKU 档案投影）返回新属性并实时计算毛利（零售 − 进货 − 其他成本，任一缺失则 null）；新增「全部标签候选」端点供前端标签复用；配套 API 测试与 CONTEXT.md 术语补充。主图的上传/读取端点在票 02 实现，本票只落引用列。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Flyway 迁移（V30）为 `app.products` 增加上述列及 CHECK 约束（价格非负、发货时效 > 0、上市周期 from ≤ until、标签为 JSONB 数组）
- [x] Product 实体、创建/更新 DTO 支持新字段；更新按 SkuPatch 模式区分「未传」与「显式清空」
- [x] 商品与 SKU 档案查询投影返回新属性与派生毛利（decimal string，两位小数）
- [x] GET /api/v1/products/tags 返回全部已用标签去重候选
- [x] 后端 API 测试覆盖创建/更新/清空/校验失败/毛利计算（ProductArchiveFieldsApiTest 3 用例全绿）
- [x] CONTEXT.md 收录 毛利/商品标签/主图/原料/上市周期/发货时效 术语

## Answer

已实现并验证（2026-08-18）。实现要点：

- `V30__add_product_archive_fields.sql`：products 新增 ingredients/tags(JSONB)/listed_from/listed_until/lead_time_hours/purchase_price/retail_price/other_cost/main_image_ref 及 CHECK 约束（PostgreSQL 不允许 CHECK 内子查询，标签元素类型由服务层校验，与 V1 evidence_refs 先例一致）。
- `Product` 实体新增字段（tags 用 `@JdbcTypeCode(SqlTypes.JSON)`，仓库既有模式）。
- `ProductWrite` 新增字段；`ProductPatch` 重写为带 presence 标记的 POJO（SkuPatch 模式），`anyArchiveFieldPresent()` 使「显式 null 清空」成为有效修改。
- `MasterDataService`：create/patch 校验（价格复用 SkuCommercialPrice、日期 YYYY-MM-DD 与先后、标签去重/去空白、发货时效正整数）；product()/sku() 投影返回新属性，毛利 = 零售 − 进货 − 其他成本 读时计算；审计载荷改用手工构建的 Map（productPatchPayload，镜像 skuPatchPayload——ProductPatch 非标准 getter 无法被 Jackson 序列化）。
- `GET /api/v1/products/tags`：`ProductRepository.distinctTags()` 原生查询（jsonb_array_elements_text 去重排序）。
- openapi.yaml 同步新增端点与字段；CONTEXT.md 收录 6 个术语。

