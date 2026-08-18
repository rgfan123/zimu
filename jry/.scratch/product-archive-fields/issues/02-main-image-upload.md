# 02 — 商品主图：上传、存储与展示

**What to build:** 主图完整垂直切片：新增图片上传端点（multipart，类型/大小限制，内容寻址存储到独立命名空间）与读取端点；商品档案表单出现「主图」上传控件（预览/替换/清除，值落 `products.main_image_ref`）；商品列表与商品档案页显示缩略图、点击可看大图；配套测试。依赖票 01 的主图引用列与投影。

**Blocked by:** 01 — 商品档案字段：数据层与 API（含毛利计算）

**Status:** resolved

- [x] POST 上传端点：仅接受 png/jpeg/webp、限 10MB；存储返回引用与可访问 URL
- [x] GET 读取端点按引用返回图片内容（含 Content-Type、365 天缓存）
- [x] 商品创建/更新 API 接受/清除主图引用（清空 = 显式 null）
- [x] 前端上传控件：选择→上传→预览，可替换、可清除；错误提示
- [x] 商品列表与商品档案页主图缩略图，点击看大图
- [x] 后端 API 测试（上传成功/非法类型/超限/读取/替换/清除）与前端测试

## Answer

已实现并验证（2026-08-18）。实现要点：

- 上传端点 `POST /api/v1/product-images`（multipart）与读取端点 `GET /api/v1/product-images?ref=...`；`ContentAddressedFileStore` 改为 public 并支持相对引用解析（向后兼容既有绝对引用）。
- 主图引用为 URL 安全形态 `product-images/<sha256>.png`（相对存储根），直接进查询参数，避免绝对路径与双重编码问题。
- `ProductImageService`：类型白名单（png/jpeg/webp）、10MB 上限、非法引用 400、缺失 404；`ProductImageController` 返回 Cache-Control immutable。
- `spring.servlet.multipart` 提升到 12MB。
- 前端：`productImagesApi.upload` + `productImageUrl`；`MainImageUpload` 表单控件（预览/替换/清除）与 `MainImageThumb` 缩略图（AntD Image 点击看大图）；MasterDataCrud 新增 `upload` 字段类型；ProductsPage/SkusPage 主图列。
- `apiRequest` 支持 FormData（自动跳过 JSON 序列化与 Content-Type）。
- ProductImageApiTest 3 用例全绿；前端 productArchiveFields.test.ts 覆盖载荷构建与格式化。

