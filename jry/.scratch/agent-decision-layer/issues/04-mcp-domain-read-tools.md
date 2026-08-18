# 04 — MCP 领域只读工具扩展

**What to build:** 在既有 `McpToolRegistry` 中新增采购、SKU 价格、库存与主数据的只读工具，作为“增加更多 MCP 供 Agent 调用”的核心交付；一期不新增写工具。

**Blocked by:** 无（与 01/02/03 并行，依赖既有 QueryService/Repository）

**Status:** resolved

## 范围

新增只读工具（新组件 `McpDomainReadTools`，并入 `McpToolRegistry`，遵循 `McpReadTools` 的白名单投影模式，不直写业务表、不含凭据/配置）：

- **采购**（数据源 `procurement/`）：
  - `list_procurement_tickets`（状态/时间范围/分页）；
  - `get_procurement_ticket`（缺口、回执摘要、关联订单行）；
  - `list_procurement_receipts`（工单的全部不可变回执）。
- **SKU / 价格**（数据源 `sku/`，含 `SkuCommercialPrice`、`ProviderSku`、`FulfillmentProvider`）：
  - `search_skus`（商品名/规格/编号模糊查询，分页）；
  - `get_sku`（含 `SkuCommercialPrice` 进货价/零售价、履约方归属）；
  - `list_provider_skus`（履约方外部编码映射，供比价对照）。
- **库存**（数据源 `inventory/`）：
  - `get_inventory_overview`；
  - `get_inventory_detail`（SKU 级覆盖与观察，参考 `InventoryDetailObservation`）。
- **主数据**（数据源 `masterdata/`）：
  - `list_products` / `list_categories`（非 PII）；
  - `list_fulfillment_providers`。
- **不做**：客户/收货人/供应商 PII 投影（客户查询保留给既有 `get_order_draft_candidates` 等既有工具）；不新增任何写工具。

## 非范围

- 业务 Agent（05/06/07 票）；
- 写工具（确认订单/运单、采购回执等维持现状，仍走人工）；
- 客户/收货人/供应商 PII 投影（客户查询保留给既有 `get_order_draft_candidates` 等既有工具）；一期比价仅使用 `SkuCommercialPrice` 主数据价格，无独立报价单数据源（范围已确认，2026-08-16）。

## 验收标准

- [ ] 全部新工具注册进 `McpToolRegistry`，`tools/list` 可见，名称唯一；
- [ ] 每个工具都有 table-driven 测试：正常路径、参数校验（非法 ID/页码/状态）、空结果；
- [ ] 响应仅含白名单字段；不暴露配置、凭据、下载地址或受控文件引用；
- [ ] 价格以 decimal-string 规范化输出（复用 `SkuCommercialPrice` 语义，SCALE=2）；
- [ ] 分页上限与既有 `MAX_PAGE_SIZE=200` 一致；
- [ ] 真实 PostgreSQL 集成测试通过，既有 MCP 测试保持绿。

## 验证原则

- 工具行为可重复验证，不以模型为验收对象；
- 与 `McpReadTools` 保持同一代码风格与安全约束。

## Answer

**2026-08-16 收票。** 前序 subagent 中断前已留以下完整可编译半成品（检查后无需修复缺陷，补齐验证与收尾）：

- 新组件 `backend/src/main/java/cn/zimu/fulfillment/mcp/McpDomainReadTools.java`（`@Component`，只读，不直写业务表）；
- 测试 `backend/src/test/java/cn/zimu/fulfillment/mcp/McpDomainReadToolsTest.java`；
- `McpToolRegistry` 构造器已并入 `McpDomainReadTools.tools()`，重名工具抛 `IllegalStateException`。

**工具清单（11 个，范围全覆盖）：**

- 采购 3：`list_procurement_tickets`（状态/日期范围/分页）、`get_procurement_ticket`（明细缺口+回执+关联订单行投影）、`list_procurement_receipts`；
- SKU/价格 3：`search_skus`（商品名/规格/编号 ILIKE，可选履约方过滤）、`get_sku`（进货价/零售价 decimal-string SCALE=2 + 履约方归属）、`list_provider_skus`（只投影已知外部编码键 `provider_sku_name`/`jd_pieces_per_unit`，不转储 `external_codes` 原始 JSON）；
- 库存 2：`get_inventory_overview`（OBSERVED/NOT_OBSERVED，无观测不补零）、`get_inventory_detail`（观测事实+新鲜度+可用能力）；
- 主数据 3：`list_products` / `list_categories` / `list_fulfillment_providers`（均非 PII，不暴露 `config` 凭据）。
- 无新增写工具；无客户/收货人/供应商 PII 投影（订单行投影仅 SKU/商品名快照）。

**修复内容：** 半成品经审查无缺陷——编译通过；价格走 `SkuCommercialPrice.text()`（null 安全、SCALE=2）；`MAX_PAGE_SIZE=200` 与 `McpReadTools` 一致；参数校验（非法 ID/页码/状态/日期/warehouse_code/query 长度）统一返回 `INVALID_PARAMETERS`，不存在返回 `NOT_FOUND`；白名单投影不含配置/凭据/下载地址。未改动任何其他文件，01 票 agent/ 包未触碰。

**测试：** `McpDomainReadToolsTest` 11 个测试全绿（真实 PostgreSQL 16 via Testcontainers；覆盖工具发现+名称唯一+Schema 无泄露、采购/SKU/库存/主数据各族正常路径、table-driven 参数校验、空结果、响应无 secret/http/source_ref/raw_payload/app_key 断言）。

**回归：** `mvn -q test-compile` 通过；`McpProtocolAcceptanceTest` 14/14 绿；全量 `mvn -q test` 81 测试类 / 549 测试，0 失败 0 错误 7 skipped（surefire fork 关闭 30s 警告为容器类测试收尾的已知无害信息）。

**遗留事项：** 无阻塞项。05/06 业务 Agent 票可基于本票工具面开工；`list_provider_skus` 的 `external_codes` 白名单键为已知枚举（`provider_sku_name`/`jd_pieces_per_unit`），新增第三方平台键时需同步扩展投影。

## Comments

**2026-08-16 修复轮：清除 review 发现的硬违规（MCP 层裸 JdbcTemplate 绕过既有应用用例）。**

改动文件（仅与本违规相关）：

- `mcp/McpDomainReadTools.java`：删除全部裸 JDBC 查询与相关助手（`orderLine`/`sku`/`providerSku`/`externalCodes`/`skuJoins`/`skuFilters`/`nullableId`/`instantText`）及未使用 import（`JdbcTemplate`/`ResultSet`/`PGobject`/`BigDecimal`/`OffsetDateTime`/`ArrayList`/`Transactional`）；工具名、参数、Schema、INVALID_PARAMETERS/NOT_FOUND 语义、分页 200 上限、decimal-string SCALE=2 输出不变；MCP 层仅保留白名单投影（对齐 `McpReadTools` 模式）。
- `fulfillment/FulfillmentReadService.java`：新增 `orderLineForFulfillment(long)`——复用既有 `TICKET_FROM` 同款 `o.data_scope='BUSINESS'` 过滤与 `id`/`nullableId` 助手，消除 Feature Envy / Shotgun Surgery；保留原投影键（含空值键），行为不变。
- `sku/SkuRepository.java`：新增 `search(pattern, providerId, Pageable)` JPQL 模糊检索（product_name/specification/sku_code 大小写不敏感，按 id 升序）。
- `sku/ProviderSkuRepository.java`：新增 `findByFulfillmentProviderId(Long, Pageable)`。
- `sku/SkuDetail.java`、`sku/ProviderSkuDetail.java`：新增只读投影 DTO（价格已按 `SkuCommercialPrice` SCALE=2 规范化；外部编码仅投影白名单键）。
- `masterdata/MasterDataService.java`：新增只读用例 `searchSkus(page, size, query, providerId)`、`skuDetail(id)`（NOT_FOUND 语义与既有 `sku(id)` 一致）、`providerSkus(providerId, page, size)`（`providers.existsById` 前置校验，NOT_FOUND；排序 `providerSkuCode, id` 与旧 SQL 一致），经 `ProductRepository`/`FulfillmentProviderRepository` 组装归属投影。

**行为不变验证：** `mvn -q test-compile` 通过；`McpDomainReadToolsTest` 11/11 绿；`McpProtocolAcceptanceTest` 14/14 绿；`Agent*`（21 个测试类）全绿。
