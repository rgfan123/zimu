# 05 — 受控创建京东出库单

**Type:** implementation

**What to build:** 运营人员对校验通过且库存可用的 Shipment 发起一次受控京东建单；成功后看到外部引用和状态，重试不会重复建单，失败不会留下第二个或半截业务批次。

**Blocked by:** 04 — 以京东实时库存判定 Shipment 可提交性

**Status:** resolved

**Claimed by:** codex-root

- [x] 提交前重新执行影响建单安全性的校验与库存检查，过期或变化的预览不得直接写入京东。
- [x] `addSoOrder` 默认被写门闩拒绝；只有具备授权的操作入口和显式写模式能够调用。
- [x] 同一 Shipment 和相同请求重放原结果；相同幂等键但请求事实变化时返回冲突，不创建新单。
- [x] 同一 Shipment 的多个 Fulfillment 只提交一个京东出库单，成功结果关联到 Shipment 级集成记录。
- [x] 成功与失败均记录业务码、请求 ID、操作人、事件、版本和脱敏审计；失败不伪造 Shipment、Tracking 或完成阶段。
- [x] Mock 模式能演示成功、门闩关闭、JD 拒绝、超时后安全重试和幂等重放。
- [x] 操作视图展示提交中、已创建、失败及可否重试，不把本地 Mock 成功描述为真实权限验证。

## Answer

- 已在 Shipment 边界实现预览、实时库存复查、持久化 `SUBMITTING` 意图、事务外 `addSoOrder`、完成阶段资格/指纹重验和不确定结果对账。同一 Shipment 仅一条京东出库集成记录，幂等重放不会再次调用外部 seam。
- 通用 `/api/v1/jd-write/order/so-create` 已对外 fail closed；Shipment 提交要求写门闩、服务端复验通过的 Basic Auth 主体、网关覆盖的 `X-Operator` 一致，且主体在显式授权名单中。缺失凭据或仅伪造白名单操作人均返回 403，拒绝事实以独立事务审计。
- 旧通用 JD 写 HTTP 面由独立开关保持关闭，并在 `client_mode=REAL` 下永久拒绝；真实写只能逐业务纵切建设。未决尝试持久化其 `client_mode`，跨 MOCK/REAL 恢复返回 409 且不对账、不覆盖历史；模式也属于幂等请求事实。
- Shipment 详情展示 `SUBMITTING` / `SUBMITTED` / `SYNC_FAILED`、服务端 `retryable` 判断和历史尝试的 `MOCK` / `REAL` / `UNKNOWN` 模式；真实模式 readiness 未通过，或当前详情/预览/运行状态仍在加载、失败、属于另一 Shipment 时，页面均禁止提交。OpenAPI 使用独立 `ShipmentJdOutboundSubmitResult` 表达 201，与详情诊断 schema 分离。
- 本地自动化证据：`mvn -DskipTests test-compile` exit 0；`ShipmentJdOutboundSubmitTest` 24/24、`ShipmentJdOutboundWriteModeDisabledTest` 1/1、`ShipmentJdOutboundClientModeMigrationTest` 1/1、`JdWriteOpsGateTest` 8/8（共 34/34，0 failure/error，真实 PostgreSQL + Flyway V1→V17 + Mock JD）。前端 JD Shipment 聚焦用例/契约 7/7，全量前端测试 100/100；TypeScript typecheck exit 0；使用占位秘密渲染的 `docker compose config --quiet` exit 0；`git diff --check` exit 0。已记录并修复的红灯包括伪造白名单 `X-Operator`、成功响应缺少/错配外部引用、不确定响应盲目重建、通用 REAL 写面、REAL 未决记录被 MOCK 跨模式对账，以及对账前预检/对账审计异常抹除未决事实。
- 未执行真实 `addSoOrder`：真实京东写入仍是独立外部验收门禁，必须由用户明确授权并提供命名测试 Shipment、目标身份、预期副作用和取消/处置方案；不用 Mock 成功代替该证据。
