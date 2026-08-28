# 05 — 商品档案支持导出表格（xlsx）

Type: implementation
Status: ready-for-agent
Priority: P1
Requested: 用户 2026-08-28「整个商品档案要支持导出表格」

## 需求

商品档案页（`/product/skus`）加「导出表格」按钮，产出一份 **xlsx**：
每行 = 一个内部 SKU，列 = 固定列 + 成本表 A..AU **全部 47 列**（不随页面「列设置」勾选变化——导出就是要全量；页面勾选只影响屏显）。

## 方案：后端 POI 生成（沿用仓库既有范式，不引前端新依赖）

前端无 xlsx 库；后端已有成熟先例：`BatchPreShipConfirmCardSource.workbook()`（Apache POI XSSF，
列宽数组、表头加粗、逐行写 cells）。**照这个范式做，不新造。**

### 后端：新增 `GET /api/v1/skus/export`

- 返回 `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`，
  `Content-Disposition: attachment; filename*=UTF-8''子牧商品档案YYYYMMDD.xlsx`
  （文件名日期用业务日 Asia/Shanghai；带 filename* 编码，参考既有下载端点怎么写的）
- 数据：全部 active SKU（含 TP），JOIN products、categories、
  LEFT JOIN `product_archive_sheets ON matched_sku_id`（product 级挂接的行按 matched_product_id 兜底）
- **列序（固定 8 列 + 47 档案列）**：
  1. SKU 编码 2. 商品名称 3. 京东EMG编号（provider_skus 的 provider_sku_code，无则空）
  4. 品类 5. 规格 6. 单位 7. 条码 8. 履约方
  然后 A..AU 47 列，**顺序严格按 fields 数组下标**，表头用 `fields->N->>'name'`
  （AK 无表头行沿用「（AK 列无表头）」占位——跳过会让其后列错位）
- 未挂接 SKU 的 47 列留空（不是 0、不是 '—'——导出给 Excel 用，空单元格才可再加工）
- ⚠️ **价格治理红线不适用于导出**：47 列本就含成本/售价列，导出是把成本表还给用户看，照实导；
  但**系统自身的 skus/products 价格字段不导**（那是另一口径，混进去会打架）
- 权限：走既有 /api 网关（Basic Auth 在边缘），无需额外鉴权逻辑

### 前端：SkusPage 加「导出表格」按钮

- 位置：工具行「列设置」旁
- 复用 `endpoints.ts` 既有 `downloadFile` 辅助（`:504` 一带，Content-Disposition 解析 + blob 下载）——
  **不要**像 `JdWarehousePage` 那样在页面内复制一份
- 导出中 loading 态；失败 message.error 可重试；**不要新开 5173 之外的窗口**

### 契约同步

`docs/openapi.yaml` + `docs/api-contract.md` 同步新端点（`OpenApiContractConsistencyTest` 门禁）。

## Acceptance Criteria

- [ ] 点击按钮下载 xlsx，文件名含业务日；Excel 打开中文不乱码
- [ ] 每行一个 SKU；固定 8 列 + 47 档案列，档案列序与 `fields` 下标严格一致，AK 占位保留
- [ ] 已挂接行 47 列有值；未挂接行 47 列为空单元格
- [ ] product 级挂接（matched_sku_id 为空、matched_product_id 非空）的行也能带出档案值
- [ ] 系统价格字段（skus/products 的 purchase/retail/other_cost）不出现在导出中
- [ ] 前端复用既有 downloadFile，无复制粘贴
- [ ] openapi/api-contract 已同步；前端 typecheck/test/build 全绿；后端触及测试通过
  （Testcontainers 集成测试如因 Docker 跑不了，如实说明——主导者会在本机补跑契约门禁）

## Files likely affected

- 新 `backend/.../masterdata/SkuArchiveExportController.java`（或并入 MasterDataController，看既有组织方式）
- 新 `backend/.../masterdata/SkuArchiveExportService.java`（POI workbook，参照 BatchPreShipConfirmCardSource.workbook）
- `frontend/src/pages/product/SkusPage.tsx` + `frontend/src/api/endpoints.ts`
- `docs/openapi.yaml` / `docs/api-contract.md`
- 后端单测（导出行数=SKU 数、列序、未挂接空值、AK 占位）

## 工作区纪律

多会话并行：禁 git add -A / commit / checkout|restore|stash。
不碰：`mcp/McpServer.java`、`McpWriteGate.java`、`docs/ops/deploy-runbook.md`、`.claude/`。

## Risk

低-中。纯读导出，无写操作。注意 110 行 × 47 列数据量很小，无需流式；
但 JOIN 逻辑要覆盖「SKU 级挂接 / product 级挂接 / 未挂接」三种形态，单测必须各有一例。
