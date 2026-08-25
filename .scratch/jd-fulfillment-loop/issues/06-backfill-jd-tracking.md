# 06 — 幂等回填京东运单与履约进度

**Type:** implementation

**What to build:** 已创建京东出库单的 Shipment 可以定时或按需查询京东结果；系统把可接受的单一运单幂等写回既有 Shipment，并按当前数量与状态规则推进运营视图。

**Blocked by:** 05 — 受控创建京东出库单

**Status:** resolved

> 2026-08-19 复核：真实 `querySoOrder` 回填已于 2026-08-18 在 jd-real-sdk-switch 07 下实测通过（真实运单 JDVA46541389064 等 4 单回填成功、彩食鲜回填表 source_return_exports id=2 生成），原 blocked-external 阻塞解除，收口为 resolved。

**Claimed by:** codex-root

## Comments

- 2026-08-14 最终双轴复审：Standards 与 Spec/AC 均无剩余 P0–P2，本地实现和契约可收口。真实 `querySoOrder` 的生产目标、凭据/权限、返回形态、限流和只读探针仍未验证，因此票据转为 `blocked-external`，不以 Mock/Controlled JD 与本地 PostgreSQL 证据冒充真实京东验收。
- 2026-08-14 用户决定：暂缓真实京东建单/回填验证（涉及公司真实业务，风险未接受）。真实 `addSoOrder` 与 `querySoOrder` 验证保持外部门禁，等待用户另行授权或提供真实出库单号。

- [x] 系统按 Shipment 的稳定商户侧出库引用查询京东状态，并支持明确的手动触发与可配置轮询。
- [x] 相同物流公司和运单号重复返回时重放既有结果，不创建重复 Tracking、事件或版本。
- [x] 只有满足现有 Shipment 物流接收规则的结果才能写入 Tracking 并推进业务状态。
- [x] 未发货或部分结果保持等待状态，不提前把 Shipment、OrderLine 或 Order 标记为完成。
- [x] 京东返回多个或冲突运单时不猜测拆单，创建或复用 `MULTIPLE_TRACKINGS_FOR_OUTBOUND` ReviewCase。
- [x] 查询失败可安全重试并保留最近诊断；成功、失败和人工处理均有脱敏审计。
- [x] Mock 模式覆盖首次回填、重复轮询、部分结果、查询失败和冲突运单。

## Answer

- 已实现公开手工入口 `POST /api/v1/shipments/{id}/jd-tracking-backfill` 与默认关闭的可配置轮询；两者共用同一应用用例。查询按 Shipment 的稳定 `erpDeliveryNo` 和提交时持久化的 owner authority 发起，pin 只取当前 provider 配置并保持临时、不落库；外调在事务外执行，并在完成事务内重新锁定 Shipment，核对提交时持久化的 `client_mode`、仓库、货主、货品与数量快照。
- 只有严格匹配商户引用、京东出库单引用、提交仓库、货品数量快照，且能唯一映射到已启用内部 Carrier 主数据的单运单结果，才复用既有 Shipment Tracking 接收 seam 原子写入 Tracking、事件、版本和审计。pending、partial、查询失败、引用/仓库/货品漂移、Carrier 映射失败及畸形响应均失败关闭，不推进 Shipment、OrderLine 或 Order。
- 同幂等键在任何远端读取前重放；不同键并发仍由 Shipment-first 锁序与终态规则守恒。`TRACKED` 不会被迟到的 pending/partial/failure/conflict 回退；已存在 OPEN `MULTIPLE_TRACKINGS_FOR_OUTBOUND` Case 时，迟到的完整结果也不会越过人工裁决自动写 Tracking。
- 京东返回拆单、本地运单冲突、Carrier 无法唯一映射，或进入取消成功/拉回/拒收异常终态时创建/复用唯一 OPEN ReviewCase，并提供版本化、幂等且有人工审计的处理入口。异常终态人工解决后持久化 `TERMINAL_REVIEWED`，无论此前是 `CONFLICT` 还是已 `TRACKED` 都不再轮询或重新外调，同时保留既有 Tracking 事实；订单页明确显示“人工终结”，不误报为“同步中”。远端响应只接受有界的字符串、枚举/标记、承运商/运单 token 和最多 20 个候选；对象、数组、超长字段、超量候选或畸形数量统一成为安全 `JD_TRACKING_RESPONSE_MALFORMED`，不持久化原始含个人信息响应。connector 抛错收敛为稳定可重试诊断；所有 JD connector 审计只保留有界白名单摘要，不写入完整 SDK 响应、账号、客户备注或自由文本。
- 多实例轮询的幂等键由数据库持久的 `tracking_last_query_at` 全精度 generation 生成，不再依赖节点本地 wall-clock bucket；即使实例时钟或 `min-interval` 配置漂移，同一代候选也只触发一次外部读取。候选在 `LIMIT` 前排除缺提交 owner、缺当前 pin 与已人工终结的历史行，避免无资格旧记录长期饿死有效 Shipment。Compose 与 `.env.example` 已透传四个 `JD_TRACKING_BACKFILL_*` 配置，默认仍为 disabled。
- 共享幂等 seam 同时收口：payload 先转为 JSON tree 并递归排序对象字段，同语义 Map 不再因插入顺序产生冲突，数组顺序保留；首次 claim、FAILED 重领与过期接管的租约到期时间均由 PostgreSQL `CURRENT_TIMESTAMP` 计算，不受节点时钟偏差影响。
- 当前共享树本地自动化 grouped 证据：`ShipmentJdTrackingBackfillApiTest` 48/48，`ReadOnlyExternalIdempotencyApiTest` 3/3，`IdempotencyServiceIntegrationTest` 5/5，`JdWarehouseClientRequestMappingTest` 3/3；总计 59/59、0 failure/error/skipped、exit 0、BUILD SUCCESS，使用 PostgreSQL 16.14 + Flyway V1→V27。前端 `orderJdFulfillment.test.ts` 3/3、`npm run typecheck`、OpenAPI YAML parse 与 scoped diff-check 均通过。关键新增 seam 均有真实 RED→GREEN：租约过期提交、异常终态人工关闭与已 Tracking 后不重开、轮询候选 owner/pin 饥饿、前端人工终态投影。全程使用 Controlled/Mock JD 与真实本地 PostgreSQL，未触发真实 JD 请求。
- 未执行真实京东 `querySoOrder`，也未验证生产域名与目标身份、凭据/owner-pin 权限、真实返回形态、限流策略或生产只读探针；这些仍是独立外部验收门禁，不能用 Mock 与本地 PostgreSQL 通过代替。真实 `addSoOrder` 继续要求用户独立明确授权、命名测试订单并确认后续处置方案。
