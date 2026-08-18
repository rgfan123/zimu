# 04 — 让总订单承载京东履约事实

**What to build:** 运营人员仍在“订单中心 → 全部订单”中统一查找业务订单，并在订单/发货详情中看到京东履约方、出库单号、同步状态、失败阶段、运单和更新时间，而不把“京东订单”建成另一套销售订单。

**Blocked by:** 01 — 按业务对象重组导航，并收纳京东工具；外部依赖：`jd-fulfillment-loop / 01 — 统一 Shipment 级京东出库单边界`、`05 — 受控创建京东出库单`、`06 — 幂等回填京东运单与履约进度`

**Status:** resolved

**Claimed by:** codex-root

- [x] 全部订单仍以 CanonicalOrder 作为唯一统计和分页对象，一张业务订单不因京东履约而在“总订单”中重复出现。
- [x] 订单列表支持按履约方筛选，但不把渠道来源与履约方混为同一概念，多履约方订单仍是一张公司订单。
- [x] 订单或其 Shipment 详情展示履约方、京东出库引用、同步状态、最近失败阶段、运单与最近更新时间，不向浏览器暴露凭据、原始 PII 或供应商原始响应。
- [x] 调整单、销毁单、采购单、加工单、作业关联等京东专业查询仍属于系统工具，不计入全部订单。
- [x] 原京东订单查询 URL 仍可访问并明确标识为渠道工具，不让用户误解为公司总订单。
- [x] 公开 API 契约定义上述白名单字段、筛选和时效性，并区分“未建单”、“同步中”、“失败”、“已回传”状态。
- [x] 通过公开 HTTP 和真实订单路由 UI seam 证明单一订单身份、履约方筛选、Shipment 级京东事实、脱敏和各状态展示。

## Answer

已完成“全部订单”的京东履约事实纵切：

- `GET /api/v1/orders` 新增与 `source_channel` 独立的 `provider_id` 筛选，通过 OrderLine `EXISTS` 限定，因此多履约方订单仍只分页和统计一个 CanonicalOrder。
- `GET /api/v1/orders/{order_id}/shipments` 使用独立白名单投影，只返回履约方、商户/京东出库引用、同步/失败阶段、Tracking 与时间；不返回 Shipment 收件人快照、重试/错误详情或供应商原始数据。“最近更新”比较 Shipment、Tracking 和 JD 集成记录的时间并取最新值。
- 订单真实路由已展示履约方、出库号、未建单/同步中/失败/已回传、失败阶段、运单和最近更新；履约方目录失败时显示安全提示与重试，已有 `jd_outbound` 事实仍继续展示。
- 原 `/fulfillment/jd-order` 保持可访问，并明确标识“系统渠道工具”和“不计入公司总订单”。OpenAPI 使用 `OrderShipment` / `OrderShipmentJdOutbound` 独立 schema 冻结了该白名单与状态/时效口径。

最终证据：`InternalOrderApiTest` 聚焦 HTTP 2/2（0 failure / 0 error，真实 PostgreSQL + HTTP）；前端全量 Node 117/117，Ticket04 终态聚焦 15/15；`npm run typecheck`、`npm run build`、OpenAPI YAML/全部 schema ref 校验与 `git diff --check` 均通过。Standards 复审修正了履约方目录失败时的静默降级，Spec 复审修正了“最近更新”的时间优先级误差；两轴终审均为 P0/P1/P2/P3 = 0。

遵循当前门禁，本票未启动浏览器/Playwright，未调用真实京东，未 stage、commit、push 或部署；因此上述是本地契约、Mock/数据库事实和路由级验证，不是真实京东环境验收。
