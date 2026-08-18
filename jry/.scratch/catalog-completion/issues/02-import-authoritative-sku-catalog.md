# 02 — 全量导入权威 JD 商品清单

**What to build:** 管理员能够用仓库中的权威工作簿把全部 JD 商品及明确匹配的价格重复、安全地导入商品主数据，并看到精确的导入/缺失报告。

**Blocked by:** 01 — 为 SKU 增加进货价与零售价

**Status:** resolved

**Claimed by:** codex-root

- [x] 只以 JD 编码作为外部唯一键导入 `京东商品编号.xlsx` Sheet1 的全部非空 JD 商品行，不按相似商品名猜测归并。
- [x] 精确匹配价格工作簿商品名时导入进货价与零售价；未匹配、重复编码、礼包/渠道映射差异进入可审计报告。
- [x] 同一输入重放不重复创建 Product、SKU 或 ProviderSku；内容漂移和非法行失败关闭且不留下半批数据。
- [x] 公开 seam + 真实 PostgreSQL 证明权威清单覆盖数、唯一编码、价格覆盖数、未定价数和幂等性。
- [x] 导入后 SKU 管理 API 能分页读取全部导入记录，JD ProviderSku 映射可被现有预览和门禁消费。

## Answer

已完成实现、真实 PostgreSQL 公开 seam 验证和当前本地 Compose 运行库的实际导入；Standards / Spec 独立双轴终审均无 P0–P2，本票现已 `resolved`。

- 两份权威工作簿指纹已锁定：`85ca324d607c651117f660007893aee6c88ad1681a7625dde0176e88a5deb873` 与 `7fc1d34e2217207abe108b97e3d02c21c4263558448c8352626f087656e45160`。可重复生成的 manifest SHA-256 为 `882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a`；Sheet1 63 条数据行按 JD 编码确定性归并为 61 个唯一编码，显式报告 2 个重复编码，仅商品名精确匹配得到 27 个定价 SKU，余下 34 个价格为 `null`，无模糊匹配或以 0 代表未知价格。
- 新增独立 catalog import 模块：冻结 manifest/加载验证器、幂等且原子的导入服务、可审计报告和管理 HTTP seam `POST /api/v1/admin/catalog-imports/jd-authoritative`。导入以 JD goodsNo 为外部唯一键，写入 Product / SKU / ProviderSku；幂等注册表、PostgreSQL transaction advisory lock 和全批 preflight 共同防止重复/并发写入与部分批落库。内容漂移返回 `AUTHORITATIVE_CATALOG_DRIFT`，不调用任何 JD 外部写接口。
- 管理端导入响应与幂等快照包含源/manifest 指纹、63/61/2/27/34 覆盖数、新建/复用/更新计数、重复编码、27 个定价明细、34 个未定价明细、渠道映射差异和排除工作表；audit response payload 只保留三个指纹、覆盖计数和变更计数，不持久完整商品或价格明细。
- 公开 seam 真实 PostgreSQL 测试证明：首次导入创建 61 Product + 61 SKU + 61 ProviderSku；同 key 返回同一响应快照，不同 key 重放为 0 新建/61 复用；SKU/ProviderSku 分页公开 API 可读取 61 条，其中 27 定价/34 未定价；制造一个可修复缺价项与一个冲突价格后，整批 409 且可修复项仍为 `null`，证明 fail-before-write。
- 验证证据：`mvn -Dtest=AuthoritativeSkuCatalogImportApiTest,AuthoritativeSkuCatalogLegacyStateApiTest test` 于 2026-08-14 01:21 完整 `exit 0`，Testcontainers PostgreSQL 16 + Flyway v15，5/5 通过、0 failure/error，覆盖二次唯一键预检、导入与主数据并发串行、legacy extra-code fail-closed 和审计摘要。`npm --prefix scripts ci` 后 generator `--check` 字节级复现 manifest SHA-256 `882e6bb6f9d822e9b9f21305ded02e581ce6cdcbcc2cb0508910bb4896eea68a`，`npm audit --omit=dev` 为 0 漏洞，本票范围 `git diff --check` 通过。

运行态证据（2026-08-14）：

- 保留 PostgreSQL volume 并将 backend 无损升级到 Flyway v17；价格列 2 个、非负约束 2 个均已落库。导入前 EMG ProviderSku 为 0。
- 以固定 `Idempotency-Key` 调用公开管理 seam 成功：63 行源数据、61 唯一 JD 码、2 重复码、27 双价格、34 双空价；新建 61 Product + 61 SKU + 61 ProviderSku。
- 同 key 连续重放响应 SHA-256 完全一致；新 key 语义重放为 0 新建 / 61 复用。数据库反查为 61 EMG 映射、61 关联 SKU、27 双价格、34 双空价、0 半价格。
- SKU 与 ProviderSku 分页 API 已在运行容器中返回导入数据。两个不同 key 的实际执行各有一条审计，同 key 重放不新增；审计仅含指纹/计数摘要，不含逐 SKU 价格明细。
- backend/frontend/nginx 容器均健康，Nginx 仅绑定 `127.0.0.1:8088`。未 stage、commit、push，未执行任何 JD 外部写请求。
