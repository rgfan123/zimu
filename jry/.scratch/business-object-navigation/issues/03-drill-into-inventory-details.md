# 03 — 从总库存下钻专业库存明细

**What to build:** 运营人员从总库存的某个 SKU/仓库/履约方直接下钻，查看该履约方实际支持的批次、库存水位变化、效期、流水或序列号细节；这些低频能力是下钻工具，不是多个一级业务板块。

**Blocked by:** 02 — 上线可信的总库存

**Status:** resolved

**Claimed by:** codex-root / inventory_drill_03

- [x] 总库存行可带入 SKU、仓库和履约方上下文进入明细，返回总览时保留原筛选与分页位置。
- [x] 明细按“批次/水位变化/效期”、“库存流水”、“序列号”等能力分组，而不是在侧边导航中为每类原始查询建立入口。
- [x] 仅展示当前履约方已接入且契约明确的能力；不支持的能力显示为“未接入”，不伪造空数据。
- [x] 明细始终显示数据来源、查询时间、实时/缓存/模拟状态和过期风险，不将调试结果冒充权威库存。
- [x] 不凭空增加“在途”、“预留”或其他未有领域证据的库存数字，对各字段保留口径说明。
- [x] 原京东库存与序列号直达 URL 仍可作为系统工具访问，但不与标准化总库存的用户心智混淆。
- [x] 通过公开 HTTP 和真实路由 UI seam 验证下钻上下文、能力缺失、权限、失败、时效性和返回总览。

## Answer

已完成从“库存中心 → 总库存”到隐藏业务对象路由 `/inventory/details` 的生产纵切。总库存行携带履约方、SKU、目标仓与完整 `return_to`；明细返回后会从 URL 恢复原页码、页大小和三类筛选，而不是回到默认第一页。

新增公开只读 `GET /api/v1/inventory/details`：一次查询投影主数据上下文与最新已落库快照，未观测数量保持 `null`；京东且有活动商品映射时按三类返回明确接入的只读工具，第三方与缺映射分别返回 `NOT_INTEGRATED` / `CONTEXT_MISSING`，不调用京东、不返回原始载荷。页面展示来源、服务端查询时间、缓存/无观测、Mock/Real 运行模式、时效边界和 stale 风险；Real 仅表示运行模式，就绪与权限仍由系统京东工具确认。原库存/序列号 URL 仍位于“系统管理 → 京东工具”，详情只用字段白名单预填。

TDD 与本地证据：公开 HTTP `InventoryDetailsApiTest` 3/3（真实 PostgreSQL Testcontainers）通过；库存明细/总览/导航/JD 预填的真实 `App + MemoryRouter` 与纯 helper seam 21/21 通过；`npm run typecheck`、OpenAPI YAML 解析、`git diff --check` 均通过。评审中先后用红测修复“返回 URL 未恢复筛选分页”和 OpenAPI `warehouse_code` 正则双重转义。最终独立双轴复核为 Standards P0/P1/P2/P3 = 0、Spec P0/P1/P2/P3 = 0。

遵循用户要求，本票没有启动浏览器或 Playwright，没有调用真实京东，也未 stage、commit、push、部署或上线。
