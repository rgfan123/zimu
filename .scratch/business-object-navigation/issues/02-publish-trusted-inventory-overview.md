# 02 — 上线可信的总库存

**What to build:** 运营人员在“库存中心 → 总库存”中按 SKU、仓库和履约方查看最新库存事实，能明确区分“库存为零”、“尚未观测”和“数据已过期”，不把京东的局部数据冒充全公司实时库存。

**Blocked by:** 01 — 按业务对象重组导航，并收纳京东工具；发布导航与最终验收仍依赖 `jd-fulfillment-loop / 04` 冻结可信快照写入契约（目标仓、缺行、单位与观测时间）

**Status:** resolved

**Claimed by:** codex-root

- [x] 提供标准化、可分页的总库存查询，每行以 SKU、仓库、履约方为粒度，并支持这三类筛选。
- [x] 每行展示总库存、可用库存、不可用差额、观测/同步时间、履约方和数据来源；任何派生数值有明确口径。
- [x] “0件”与“无观测”是不同状态；未接入库存观测的履约方不被默认为零库存。
- [x] 总览显示当前数据覆盖的履约方/仓库范围和最近更新时间，过期或部分覆盖有可见提示。
- [x] 页面提供正常、空、加载、无权限、请求失败和数据过期状态，不向浏览器暴露原始响应、凭据或供应商私密数据。
- [x] 公开 API 契约说明口径、粒度、分页、筛选与时效性，标识符遵循现有字符串 ID 约定。
- [x] 通过公开 HTTP 和真实路由 UI seam 验证跨履约方查询、零库存/无观测区分、筛选分页与各用户可见状态。

## Comments

### 2026-08-13 — 隔离只读切片（codex-root / inventory_02_read_slice）

- 新增 `GET /api/v1/inventory/overview` 的独立 controller/read service/response 类。查询仅投影活动 SKU 及 `(FulfillmentProvider, SKU, warehouse_code)` 的最新已落库快照；不返回 `raw_payload` 或 `source_ref`。
- 从未观测的 SKU 返回 `NOT_OBSERVED` 且数量/仓库/来源均为 `null`；显式零快照保留 `"0.000"`。`unavailable_quantity = total_quantity - available_quantity`，只在有观测时派生。
- 新增可独立导入的 `InventoryOverviewPage` 和纯 view helper，完成加载、空、权限、失败、部分覆盖与时效策略待确认呈现；本切片未注册导航/路由。
- TDD 核心 tracer 证据（review 覆盖口径细化前）：`mvn -Dtest=InventoryOverviewApiTest test` 为 `1/1` green，真实 PostgreSQL + 公开 HTTP；红灯先证明缺 endpoint 时为 HTTP 404。
- 前端当前证据：专属 Node seam `4/4` green，`npm run typecheck` green，全量 Node 回归 `74/74` green，`git diff --check` green。未启动浏览器/Playwright。
- code-review Standards 轴无 P0–P2；Spec 轴发现并修正“履约方仅部分 SKU 有观测时误报完整覆盖”，现改为同时暴露 provider/SKU 覆盖。
- review 后新增的 provider/SKU 覆盖字段，以及筛选/分页/最新仓库快照/跨履约方 HTTP 断言，尚未在本轮重跑 Maven；不将其记为已验证。

## Answer

已完成并接入当前应用路由的可信总库存纵切：

- `GET /api/v1/inventory/overview` 以活动 SKU 为主数据范围，投影每个履约方/SKU/仓库的最新已落库快照；三类筛选、分页、字符串 ID、数量单位与派生差额口径已在 OpenAPI 冻结。
- 目标仓筛选在 latest-snapshot CTE 内生效；匹配主数据的 SKU 在该仓缺行时仍返回 `NOT_OBSERVED` 且数量为 `null`，显式零快照仍为 `0.000`。查询在 `REPEATABLE_READ` 下使计数、页面和覆盖摘要共享同一数据库快照。
- coverage 描述完整筛选范围，包含履约方/SKU/仓库覆盖、最新及最早观测、过期行数与 `PT15M` 时效策略，不受当前分页遮蔽。
- `/inventory/overview` 已注册在“库存中心 → 总库存”；真实 `App + MemoryRouter` 组件 seam 覆盖加载、空、403、安全失败、筛选、分页、目标仓无观测/显式零及跨页过期提示；页面对快照来源使用白名单文案，API 不返回 `raw_payload` 或 `source_ref`。

当前工作树证据：`InventoryOverviewApiTest` 2/2 与 `ProviderStockSnapshotMigrationTest` 1/1 的新鲜 Surefire 报告均为 0 failure / 0 error；2026-08-14 最终复核重跑前端库存纯视图+真实路由 seam 10/10，`npm run typecheck` exit 0。本轮未启动浏览器/Playwright，未部署或上线。

最终双轴复核为 Standards P0/P1/P2 = 0、Spec P0/P1/P2 = 0，因此本地实现范围标记为 `resolved`。仅保留一项不阻断 P3：行时效与 coverage 时效分别获取当前时间，极窄的 `PT15M` 边界上可能短暂出现行标签与覆盖提示不一致；后续可改为每次请求只捕获一个评估时刻。
