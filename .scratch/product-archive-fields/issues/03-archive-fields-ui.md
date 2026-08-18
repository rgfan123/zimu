# 03 — 商品档案字段表单与毛利展示

**What to build:** 前端完整字段能力：通用 CRUD 组件新增 多行文本/标签多选/日期区间 字段类型；商品「管理」页（/product/products）新建与编辑表单出现 原料、商品标签（候选来自标签端点）、上市周期（日期区间，只填开始也可）、发货时效（小时数）、零售价/进货价/其他成本；商品档案页（/product/skus）与商品列表新增 毛利、标签、原料、上市周期、发货时效、主图 展示列（毛利按票 01 派生值格式化：未定价 / ¥金额）；配套前端测试。主图表单控件与缩略图由票 02 提供，本票负责接线与其余字段。

**Blocked by:** 01 — 商品档案字段：数据层与 API（含毛利计算）；02 — 商品主图：上传、存储与展示（同改 ProductsPage，串行避免冲突）

**Status:** resolved

- [x] MasterDataCrud 支持 textarea / tags / date-range 字段类型（含 loadValue 自定义装载）
- [x] 商品新建/编辑表单：原料、标签（含候选复用）、上市周期、发货时效、三个价格输入；提交载荷与清空语义正确
- [x] 商品列表新增 毛利/标签/主图 列
- [x] 商品档案页（/product/skus）新增 毛利/标签/原料/上市周期/发货时效/主图 产品级展示列
- [x] 毛利展示：未定价 / ¥金额 两种态；标签渲染为 Tag 列表
- [x] 前端测试：载荷构建、毛利格式化、标签转换

## Answer

已实现并验证（2026-08-18）。实现要点：

- `MasterDataCrud`：`CrudField.type` 扩展 textarea（Input.TextArea）/ tags（Select mode=tags + 候选 options）/ date-range（`ListingPeriodPicker`，RangePicker allowEmpty，值形态 `{from,to}`）；新增 `loadValue` 钩子用于把 attributes 拆成表单值（上市周期）。
- `ProductsPage`：新建/编辑表单完整接入 原料/标签（候选来自 `GET /api/v1/products/tags`，可自由输入可复用）/上市周期/发货时效（正整数校验）/零售价/进货价/其他成本/主图；载荷经 `buildProductCreateBody`/`buildProductUpdateBody`（清空语义：显式 null）。
- `SkusPage` 商品档案：新增 主图/毛利/标签/原料/上市周期/发货时效 产品级只读列（`product_*` 投影属性）。
- `productArchiveFields.ts`：纯函数载荷构建与展示格式化（marginLabel/leadTimeLabel/listingPeriodLabel/normalizeTags），node 测试 6 用例全绿；adminMasterDataRoute.test.ts 随新列与 tags 请求更新。
- 全量前端测试 178/178 通过；tsc 无错；vite build 成功。

