# 权威 JD 商品清单生成与导入

仓库固化的 `backend/src/main/resources/data/authoritative-jd-sku-catalog.json` 是运行时导入商品、SKU 与履约方映射的唯一目录输入。它不承载价格，也不得写入或校验 SKU 的进货价与零售价。

SKU 价格的唯一真源是 `app.skus`。价格取数来自 `app.product_archive_sheets` 中的成本核算表：AI「线下供货成本/份」写入进货价，AJ「售价」写入零售价。权威 JD 商品清单不得从商品名、别名或旧价格导出推导价格。

旧价格导出按商品名称去重，无法可靠区分实际规格。例如 150g 原切西冷牛排曾匹配到整箱挂牌价 88.00，而成本核算表中该规格的供货成本为 12.15、售价为 20。继续让该匹配进入清单，会对正确的 `app.skus` 价格持续产生错误 drift，并可能给新 SKU 注入旧口径价格，因此该通道已切断。

## 受控源文件

- JD 编码源是仓库根目录的 `京东商品编号.xlsx`，固定 SHA-256 为 `85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873`。
- 历史价格导出 `合作商品价格查询导出_按商品名称去重.xlsx` 的固定 SHA-256 仍为 `7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160`。它只保留在生成器的指纹与结构校验中，用于审计连续性；生成器不再把其中的价格、匹配商品名和来源行写入清单。该工作簿可包含商业价格，不得提交到 Git，也不得复制到公开构建产物。

生成器会在解析前同时校验两个指纹。任一指纹不同都表示源已变更：停止生成，另起变更审查，不得直接更换脚本中的指纹。

## 可重复校验

需要 Node.js 22。生成器依赖在 `scripts/package-lock.json` 中精确锁定：

```bash
npm --prefix scripts ci
node scripts/generate-authoritative-jd-sku-catalog.mjs \
  '京东商品编号.xlsx' \
  '/authorized/private/path/合作商品价格查询导出_按商品名称去重.xlsx' \
  --check backend/src/main/resources/data/authoritative-jd-sku-catalog.json
```

成功时输出应为：

```text
OK f9d47bf4ee5b1766e7539762bb79593f44820de9a8e56c4679d3ae4551cc1a4b
```

`--check` 要求字节级一致，并且生成器会失败关闭校验 63 条源数据、61 个唯一 JD 编码、2 个重复编码、0 个清单定价和 61 个未定价。每个条目的 `price_match_name`、`price_source_row`、`purchase_price`、`retail_price` 仅作为兼容字段保留且必须为 null。只有受审查的源变更才允许使用 `--write` 重新生成 manifest，随后必须重跑 `--check` 和 Catalog 的真实 PostgreSQL API 测试。
