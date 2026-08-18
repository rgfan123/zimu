# JD 云仓履约闭环

**Status:** ready-for-agent

## Problem Statement

运营人员希望京东云仓订单不再依赖发货 Excel：系统应当先确认 SKU、单位、地址和库存均可用，再安全创建京东出库单，并持续回填运单和履约进度。

当前二阶段拆分把京东出库单号和同步状态放在逐订单行的 Fulfillment 上，但一个 Shipment 可以包含同一订单、同一履约方、同一收货地址和同一批次下的多个 Fulfillment。若继续按 Fulfillment 建单，会让同一发货批次重复创建京东出库单，也会把 JD 集成状态错误地混入 OrderLine 的权威 `processing_stage`。

当前实现还对单位换算、自由文本地址拆分、库存不足后的动作和真实写接口验证作了未经确认的默认判断。这些判断可能造成错误数量、重复建单、错误采购或真实环境数据污染。

## Solution

将京东出库单作为 Shipment 级的外部履约事实：一个 Shipment 最多拥有一个京东出库单，ShipmentItem 继续表达该批次包含的各 Fulfillment 及数量。系统在建单前生成可审计的请求预览，只有 SKU 映射、整数件换算、结构化收货地址、履约方配置和实时库存全部通过时，才允许进入受控写接口。

同一 Shipment 的重复请求使用稳定幂等键并重放原结果，不创建第二张京东出库单。京东库存不足、查询失败或业务映射不完整时系统默认阻断，不自动改变 FulfillmentProvider，也不创建仅适用于我方库存的 ProcurementTicket。京东返回物流结果后，系统通过既有 Shipment 物流接收用例幂等回填；无法安全自动解释的多运单或冲突结果进入 ReviewCase。

普通自动化测试全部使用 Mock JD 客户端。真实环境默认只允许查询类探针；`addSoOrder` 始终被视为生产写操作，只有取得明确授权、指定测试订单并确认处置方案后才能单独执行。

## User Stories

1. As an 订单运营人员, I want to see which Shipment will be submitted to JD, so that I do not submit one outbound order per OrderLine by mistake.
2. As an 订单运营人员, I want one JD outbound order to include all eligible ShipmentItems in the batch, so that products shipped together share one outbound reference.
3. As an 订单运营人员, I want to preview the exact JD request before submission, so that I can catch bad quantities, addresses, and configuration without creating external data.
4. As an 订单运营人员, I want the preview to show the source of every mapped field, so that I can diagnose which master data needs correction.
5. As an 订单运营人员, I want incomplete structured receiver addresses to block automatic submission, so that the system never guesses province, city, county, town, or detail address.
6. As an SKU 运营人员, I want every internal SKU to have an explicit active JD goods mapping, so that an order cannot reach JD with an unknown item.
7. As an SKU 运营人员, I want invalid or inconsistent JD goods information to open an actionable ReviewCase, so that I can fix the mapping and retry.
8. As an SKU 运营人员, I want quantity conversion to be explicit and exact, so that a non-integral package conversion never silently rounds the shipment quantity.
9. As an 订单运营人员, I want the system to query current JD availability using the same exact quantity that will be submitted, so that stock checking and order creation do not use different units.
10. As an 订单运营人员, I want a failed or malformed JD stock response to block submission, so that an unavailable integration never silently allows an order through.
11. As an 订单运营人员, I want insufficient JD stock to create a visible blocking result, so that a human can choose the next business action without the system changing providers.
12. As an 订单运营人员, I want stock to be checked again immediately before submission, so that a stale preview is not presented as a reservation.
13. As an 订单运营人员, I want repeated submission of the same Shipment to return the original result, so that retries do not create duplicate JD outbound orders.
14. As an 订单运营人员, I want a changed request under the same idempotency key to be rejected, so that accidental payload drift is visible.
15. As an 订单运营人员, I want a successful JD submission to show its external reference and current state on the Shipment, so that I can trace the external execution.
16. As an 订单运营人员, I want a failed submission to remain retryable without a half-created local shipment, so that recovery is safe and understandable.
17. As an 订单运营人员, I want the system to poll JD for shipping results, so that I no longer need a returned tracking Excel for the JD path.
18. As an 订单运营人员, I want repeated polling to update the same tracking fact, so that duplicate records are never created.
19. As an 订单运营人员, I want partial or not-yet-shipped JD results to preserve the current processing stage, so that the order is not completed early.
20. As an 订单运营人员, I want conflicting or multiple tracking numbers to open a ReviewCase, so that P0 does not guess how to split a Shipment.
21. As an 审计人员, I want every preview, query, write, retry, and backfill to carry operator, request, trace, result, and business code, so that actions are explainable without exposing secrets or PII.
22. As an 系统管理员, I want JD writes disabled by default and independently configurable from read access, so that enabling queries cannot accidentally enable order creation.
23. As a developer, I want the complete loop to run against Mock JD and a real database, so that schema, application behavior, and state transitions are verified together.
24. As a release operator, I want real read probes and real write acceptance to be separate procedures, so that a successful query never implies permission to create an order.
25. As a product owner, I want external permission failures recorded as gates rather than disguised as completed acceptance, so that local completion and production readiness remain distinct.

## Implementation Decisions

- Fulfillment remains the execution unit for one OrderLine. It does not own a JD outbound order number or JD integration lifecycle.
- Shipment remains the outbound batch. A Shipment can contain multiple ShipmentItems and therefore multiple Fulfillments, but it can have at most one JD outbound-order integration record.
- The system-generated Shipment outbound order number is the stable merchant-side reference submitted to JD as `erpDeliveryNo`. If JD returns a separate identifier, it is stored as external response data rather than replacing the internal reference.
- JD-specific request hash, external response reference, sync state, failure phase, retryability, and last query information live in a one-to-one JD outbound-order integration record linked to Shipment. They must not live on each Fulfillment or be added to the generic Shipment lifecycle.
- JD sync state is separate from OrderLine `processing_stage`. Existing ProcessingStage values remain authoritative for the business Excel and fulfillment workflow.
- If the in-flight Fulfillment-scoped JD migration has not reached a shared environment, it is corrected before release. If it has been applied anywhere containing durable data, migration follows expand–migrate–contract and does not destructively rewrite history.
- A request preview is generated from one Shipment and all its ShipmentItems. It is the only payload shape consumed by stock checking and order creation, preventing different mapping logic between the two steps.
- Every internal SKU used by JD requires an active provider mapping with the JD goods identifier and an explicit unit conversion. Unit `件` may deterministically use factor 1; other units require configured conversion.
- `planQuantity` must be an exact positive integer after conversion. Non-integral, zero, negative, missing, or invalid conversions block submission; the system does not round shipment quantities up or down.
- Receiver province, city, county, town when required, and detailed address must be structured and confirmed business data. Free text may be presented for human correction but is not automatically guessed into address components.
- Warehouse, shop, customer, owner, and related JD identifiers come from the selected FulfillmentProvider configuration and master data. They are never hard-coded or inferred from product names.
- Missing SKU mappings, invalid conversions, incomplete receiver data, missing provider configuration, inactive JD goods, and material goods mismatches create or reuse a blocking ReviewCase with actionable details.
- JD realtime stock is checked using the exact preview quantities. Query failure, malformed data, missing warehouse rows, or insufficient stock fail closed.
- A JD stock check is advisory rather than a reservation. The system repeats the check immediately before the write and treats any JD rejection as authoritative.
- JD shortage does not automatically change the FulfillmentProvider and does not create a ProcurementTicket intended for inventory managed by the company. It leaves the Shipment unsubmitted and exposes a blocking review outcome.
- `addSoOrder` is protected by the existing write gate plus authorization, audit, and idempotency. Read access and write access remain independent.
- The idempotency key is stable per Shipment and operation. The same key and request hash replay the original result; the same key with a different request hash returns a conflict.
- Successful submission appends the appropriate OrderEvent and audit facts, then exposes the JD state through the existing operator-facing order or fulfillment view. Failure records phase and diagnostic code without creating a second Shipment.
- `querySoOrder` backfill reuses the existing Shipment tracking application seam. The backfill is idempotent and does not bypass shipment ownership, quantity conservation, event, version, or audit rules.
- P0 continues to allow at most one Tracking per Shipment. Multiple or conflicting JD tracking numbers create `MULTIPLE_TRACKINGS_FOR_OUTBOUND` ReviewCase instead of silently creating or reassigning shipments.
- A partial or pending JD response does not mark Shipment shipped or advance an OrderLine to a completed stage. Only accepted tracking facts and existing quantity rules can advance fulfillment state.
- Real environment read probes may cover `queryGoodsInfo`, stock query, and `querySoOrder`. `addSoOrder` is never described or executed as a read-only probe.

## Testing Decisions

- The primary test seam is the public fulfillment decision/submission application boundary through to a real PostgreSQL schema, persisted Shipment/JD state, OrderEvents, ReviewCases, Tracking, versions, and audit facts, with the JD client replaced by a deterministic Mock.
- Tests assert external behavior and durable business facts rather than private helper calls or SDK DTO shapes.
- A representative happy path covers Shipment preview, SKU validation, exact quantity conversion, sufficient stock, idempotent creation, tracking backfill, and visible completion state.
- Representative blocking paths cover missing mapping, non-integral conversion, incomplete structured address, missing provider configuration, query failure, insufficient stock, write gate disabled, changed idempotent payload, JD rejection, partial result, and conflicting tracking numbers.
- A multi-line Shipment test proves that several Fulfillments share one JD outbound request and one merchant-side reference.
- Existing Spring Boot integration tests with Testcontainers, command idempotency tests, connector gate tests, and Shipment tracking acceptance tests are prior art. New tests should extend those public seams rather than introduce a parallel orchestration API only for testing.
- Thin frontend tests verify that preview errors, blocking ReviewCases, submission state, and tracking results are rendered from public API responses. A browser smoke verifies the complete operator path after the application seam is green.
- Ordinary test commands never require JD credentials and never call external services.
- Real read probes are explicit manual commands that redact secrets and PII while reporting request ID, business code, and data presence.
- A real `addSoOrder` acceptance is a separate manual runbook step. It requires explicit user authorization, a named test Shipment/order, confirmed target identity, expected side effect, and a cancellation or operational cleanup plan.

## Out of Scope

- Automatically enabling JD API permissions or changing JD Open Platform configuration.
- Automatically executing `addSoOrder` in production as part of tests or probes.
- Automatically switching an OrderLine to a different FulfillmentProvider when JD stock is insufficient.
- Creating company-managed ProcurementTickets for inventory that the company does not manage.
- Automatically splitting one Shipment into several Shipments when JD returns multiple tracking numbers.
- Customer delivery confirmation or `DELIVERED` lifecycle support.
- Removing the existing Excel fallback before the JD API path has independent production acceptance.
- The SaaS visual-system refresh, which is tracked separately.

## Further Notes

- This spec supersedes only the JD SDK bridge spec's second-phase fulfillment-loop description and its earlier 00–05 breakdown. It does not supersede the SDK interface coverage tickets.
- An implementation was already in flight when this spec was approved. Before claiming ticket 01, reconcile that snapshot against this contract; do not treat an existing migration or passing targeted tests as evidence that the domain boundary is correct.
- Completion of Mock acceptance is local verification. Production readiness additionally requires recorded target identity, permissions, write-gate state, and the separate real-write authorization described above.
