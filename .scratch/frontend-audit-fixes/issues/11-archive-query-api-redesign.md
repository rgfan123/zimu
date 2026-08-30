# 11 — `search_product_archive` 改成真正的查询接口（停止外泄存储形态）

Type: implementation
Status: 已实现（工作区未提交）
Priority: P1
Requested: Jerry 2026-08-28「正确的应该是暴露一个 query 端口 提供可选参数 mcp 将它包装一下」

## 问题：现在这个工具不是「查询」，是存储转储

`McpDomainReadTools.archiveSheetNode`（`:578-601`，出自 `9126b657`）把
`app.product_archive_sheets` 的行**原样序列化**推给调用方：

```json
{
  "source_file_name":   "A产品成本核算26.3.29.xlsx",
  "source_file_sha256": "e185b33f…",
  "sheet_name":         "成品",
  "row_no":             69,
  "fields": [ {"column":"A","name":"产品名称","value":"原切牛肉卷"},
              {"column":"D","name":"国条","value":"06977872890432"}, … 共 47 项 ],
  "extra_cells": []
}
```

调用方必须**先懂 Excel 列字母**才能用它。三个已观测到的故障是同一个根因：

1. **Bot 以为自己在读 Excel**（2026-08-28 企微截图）：它回答里写「成本档案（产品成本核算表）」
   「结果超长已截断」，是照着 `source_file_name` 和表格形态念的。它没幻觉——收到的就是一张表。
   副作用：内部文档名（含版本日期 `26.3.29`）与 SHA-256 流进了聊天。
2. **69 码查不到**（大鹏 2026-08-28 14:01 被答「数据库中确实没有」）：D 列是
   `fields` 数组里的一个元素，不是可检索参数；底层 `ProductArchiveSheetService.search`
   只 `ILIKE` 商品名。
3. ~~成本与毛利外泄~~ —— **Jerry 拍板全给，不是问题，本票不做列过滤**（见下）。

## 目标形状

```
search_product_archive(
  query?      商品名模糊
  barcode?    69 码（精确匹配，对应原表 D 列「国条」）
  brand?      品牌（E 列）
  meat_type?  肉类（F 列）
  status?     产品状态（B 列）：在产 / 停产 / 研发 / 新品
  linked?     是否已挂接 SKU（true/false）
  page?, size?
)
→ [ {
      // 身份列提升为一等字段
      product_name, brand, specification_g, barcode, meat_type, material, status,
      linked,                 // 是否已挂接
      sku_code, sku_id,       // 挂接了才有，否则 null

      // 其余成本列全量保留，但只留「列头名 + 值」，不留 Excel 列字母
      costing: [ { "name": "原料成本kg/元", "value": "49" },
                 { "name": "毛利率",        "value": "0.0671654" }, … ]
  } ]
```

**不再出现**：`source_file_name`、`source_file_sha256`、`sheet_name`、`row_no`、
`fields[].column`、`extra_cells`。

这五项才是让 Bot 以为自己在读 Excel 的元凶——它回答里写「产品成本核算表」是照着
`source_file_name` 念的，写「第 69 行」是照着 `row_no` 念的，还顺带把内部文档名
（含版本日期 `26.3.29`）和 SHA-256 推进了企微聊天。**数据一列不少，出身证明不给。**

`costing` 数组保持原表列序（身份列已被提升的不再重复出现在 costing 里）。
**不要为 47 列手工映射英文键名**——那是另一项工程，且列头会随成本表版本变；
保留中文列头名即可，它本来就是业务同事认识的说法。

## ✅ 成本与毛利列：Jerry 2026-08-28 拍板「全给，维持现状」

**47 列全部继续对外**，本票**不做列过滤**。
（主导者曾建议只给身份列，Jerry 明确否决——成本可见是业务需要。此决定已记录，勿再改动。）

**但这不影响形状要改。** 本票的目标从头到尾是「停止外泄存储形态」，
和「给多少列」是两件事：给全部数据，但不要以 Excel 的样子给。

## 实现要点

- **后端服务层**：`masterdata/ProductArchiveSheetService.search`（`:62`）现在只按
  `product_name` 模糊搜。改为按上述可选参数组合查询。
  取值仍按 `column` 字母筛（`e->>'column'='D'`），**不要按数组下标硬编码**——
  沿用仓库既有取数范式。
- **投影层**：新建一个领域形状的 record（如 `ProductArchiveSummary`），
  由服务层产出；`archiveSheetNode` 改为投影它，而不是投影 `ProductArchiveSheet` 原体。
- **内部面保留全保真**：`ProductArchiveSheetService.byProduct` 与商品档案页/导出走的路径
  **不动**——前端要展示 47 列、导出要全量，那是内部管理台，本票不碰。
  受影响的只有 MCP 对外工具。
- **工具描述同步**：`search_product_archive` 的 description 与各参数说明要写清楚
  「可按 69 码精确查」，否则 Bot 不知道自己能这么查。
- `barcode` 用**精确匹配**不用 LIKE：69 码是标识符，模糊匹配只会带来假阳性。
  但注意成本表里 `…890135` 被原切牛肋条 500g/750g 两行共用，**必须返回全部命中，
  不得只返首条**（详见票 08 同款要求）。

## 不做的事

- 🚫 不动 `app.product_archive_sheets` 表结构与数据（它是带指纹的不可变快照，
  保真是对的——问题在投影层，不在存储层）
- 🚫 不动商品档案页的 47 列展示、列设置、导出
- 🚫 不动 `McpServer.java` / `McpWriteGate.java`
- 🚫 不执行任何生产 SQL

## Acceptance Criteria

- [ ] `search_product_archive` 接受 `barcode` 参数，传 `06977872890432` 返回「原切牛肉卷 300g」
- [ ] 传 `06977872890135` 返回 **2 行**（原切牛肋条 500g 与 750g），不截断为 1 行
- [ ] 返回体中**不含** `source_file_name` / `source_file_sha256` / `sheet_name` /
      `row_no` / `column` / `extra_cells`——有测试逐项断言其缺席
- [ ] 47 列数据**一列不少**（Jerry 决策：全给），但以 `costing:[{name,value}]` 承载，不带列字母
- [ ] `query` / `brand` / `meat_type` / `status` / `linked` 各有一例测试
- [ ] 已挂接行带 `sku_code`，未挂接行为 null
- [ ] 商品档案页与导出行为零回归（它们走的是另一条路径）
- [ ] `docs/openapi.yaml` / `docs/api-contract.md` 同步（若该工具在契约里有声明）
- [ ] 定向测试通过；Testcontainers 拿不到 Docker socket 时如实说明

## Files likely affected

- `backend/src/main/java/cn/zimu/fulfillment/masterdata/ProductArchiveSheetService.java`
- `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java`
- 可能新增 `ProductArchiveSummary` 记录类
- 对应测试、`docs/openapi.yaml`、`docs/api-contract.md`

## 与其它票的关系

- **票 08** 原本包含「成本表检索加 69 码」，那半**已移交本票**用正确形状做；
  票 08 只保留 SKU 侧（`SkuRepository.search` 加 `barcode`）与前端 placeholder。
- 大鹏那个具体问题在数据侧已解（SKU-JD-000091 已建、条码已挂），
  票 08 的 SKU 侧改完即可答；本票解决的是**这类问题不再复发**。

## 工作区纪律

禁 `git add -A` / `commit` / `checkout|restore|stash`。排在票 06 之后。

## Risk

低-中。纯读路径重构，无写操作、无 schema 变更。
唯一要小心的是**别把内部管理台的全保真路径一起改了**——那条路径必须继续吐 47 列。
