# 权威 JD 商品清单生成与导入

仓库固化的 `backend/src/main/resources/data/authoritative-jd-sku-catalog.json` 是运行时导入的唯一输入。不要在运行时直接解析 Excel，也不要从类似商品名自动补全价格。

## 受控源文件

- JD 编码源是仓库根目录的 `京东商品编号.xlsx`，固定 SHA-256 为 `85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873`。
- 价格源是授权的内部导出 `合作商品价格查询导出_按商品名称去重.xlsx`，固定 SHA-256 为 `7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160`。由负责商品价格的业务数据负责人提供原始导出，通过访问受控的内部文件渠道交付；不接受截图、手工转录或另存版本。该工作簿可包含商业价格，不得提交到 Git，也不得复制到公开构建产物。

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
OK 882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a
```

`--check` 要求字节级一致，并且生成器会失败关闭校验 63 条源数据、61 个唯一 JD 编码、2 个重复编码、27 个精确定价和 34 个未定价。只有受审查的源变更才允许使用 `--write` 重新生成 manifest，随后必须重跑 `--check` 和 Catalog 的真实 PostgreSQL API 测试。
