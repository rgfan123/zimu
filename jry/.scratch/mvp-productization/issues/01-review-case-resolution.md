# 01 — 人工复核解决闭环

Type: development
Status: resolved
Blocked by: None — can start immediately

**What to build:** 让运营人员可以从复核工作台显式解决 OPEN ReviewCase，同时保留版本冲突保护、解决证据和审计日志。

- [x] 公共 HTTP 命令可解决 OPEN ReviewCase，重复/过期版本不得静默覆盖。
- [x] 客户/SKU 解决必须引用已明确维护的主数据，不猜测映射。
- [x] 解决后列表/详情可见 resolved_by/resolution/resolved_at，并有 AuditLog。
- [x] 前端提供正式处理动作和可执行错误提示。
- [x] 作业中心同时展示非阻断 OperationalAlert，可以带版本和备注确认；确认提醒不得推进业务状态。

## Validation

- `cd backend && mvn -q -Dtest=ReviewCaseResolutionApiTest -DforkCount=0 test` — 4 tests, 0 failures/errors；覆盖客户/SKU 解决、stale version 409、来源跟进门禁/幂等/事件/版本/审计、OperationalAlert BUSINESS 隔离/分页/确认不推进业务状态。
- `cd backend && mvn -q -Dtest=ReviewCaseResolutionApiTest,PublicReadySafetyApiTest -DforkCount=0 test` — 4/4 + 2/2 green（主 agent 独立复跑）。
- `cd frontend && npm test` — 14/14 green，含 expected_version 命令 builder 与专用来源跟进命令。
- `cd frontend && npm run typecheck` — green。
- `cd frontend && npm run build` — green（仅既有 chunk-size warning）。

## Resolution

- 新增 `/api/v1/review-cases/{id}`、`resolve-customer`、`resolve-sku`、`complete-source-followup`：只接受既有启用主数据，OPEN→RESOLVED 写 `resolution/resolved_by/resolved_at`，以 `expected_version`、悲观锁和幂等注册表保护并发，并写 BUSINESS AuditLog。
- 客户/SKU 明确映射后恢复可履约订单；多 Shipment 来源跟进只有在全部订单行有终局 Fulfillment、全部真实 Shipment 已发货且有 Tracking、无其他 OPEN ReviewCase 时，才同事务推进至 COMPLETED/CLOSED 并追加 `MANUAL_SOURCE_FOLLOWUP_COMPLETED` Event/Version/Audit。
- 新增 OperationalAlert BUSINESS-only 分页与 acknowledge 命令；确认仅写处理人、时间、备注、版本和 AuditLog，不改变订单/履约状态。
- 人工作业中心可切换“阻断复核/运营提醒”，执行客户、单 SKU、来源跟进和提醒确认；复杂 SKU/缺少主数据时提供主数据跳转，错误提示可执行且 detail/resolution 只经展示白名单输出。
- OpenAPI 与前端类型/端点同步；移除 ReviewCase 命令内创建客户/SKU 的契约分支。本切片无需 schema migration，现有列与约束已满足。
