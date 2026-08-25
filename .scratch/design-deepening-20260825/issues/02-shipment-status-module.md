# 02 — ShipmentStatus 枚举 + 生命周期判定模块（P1）

**What to build:** `fulfillment` 包新增 `ShipmentStatus`，成为发货批次状态词汇表
（`app.shipments.shipment_status`）与它回答的业务问题的唯一家。

**Blocked by:** 无
**Status:** 已实现（分支 `claude/ticket02-shipment-status`），待集成门禁

## 背景（开票时的判断，已由实现阶段的核查修正）

开票时认为「已完成发货」存在 4 种互不一致写法。**实现阶段逐处核实后修正如下**：

| 点位 | 实际判定 | 定性 |
|---|---|---|
| `connector/sync/SourceSyncFactsReader.java:62` | `SHIPPED\|DELIVERED` | 「已发货事实」 |
| `recon/OutboundReconService.java:973` | `SHIPPED\|DELIVERED` | 同一条规则的第二份复制 |
| `fulfillment/ShipmentJdTrackingBackfillService.java:751` | `CREATED\|SHIPPED` | **不同的问题**（可否回填运单），不是 bug |
| `file/TrackingFileService.java:469` | `SHIPPED/PARTIAL/FAILED` | **误报**：这是运单导入 Excel「结果」列的词汇表，`PARTIAL` 根本不是合法 shipment_status |

结论：真实问题**不是「互相矛盾」而是「同一条规则被复制、且没有主人」**。
「已发货 = SHIPPED\|DELIVERED」这条规则在全库口径一致，但被复制在 Java 2 处 +
Java 内嵌 SQL 8 处 + V2/V3/V6 迁移视图，任何一次状态增改都要人肉找齐十几处。
第 4 处若被「统一」进生命周期枚举，会把导入文件的列语义混进发货状态——
开票时的描述有误导性，已在 ADR 0012 与总图同步更正。

另外发现第 4 个同族点位：`fulfillment/ShipmentJdOutboundPreparer.java:53/228`
的 `SHIPMENT_STATUS_CREATED` 常量（可否建京东出库单，仅 CREATED），一并收编。

## 取值权威来源（未起数据库）

`V1__baseline.sql:446` 的 CHECK 约束即权威且从未被后续迁移修改：
`CHECK (shipment_status IN ('CREATED','SHIPPED','FAILED','DELIVERED'))`。
合法迁移由同迁移的触发器强制：`CREATED → SHIPPED|FAILED`、`SHIPPED → DELIVERED`。
比在开发库跑 `SELECT DISTINCT` 更可靠（开发库不一定覆盖全部历史取值），
因此本票没有为对账启动本地数据库。

## 已实现

- `fulfillment/ShipmentStatus.java`：4 个枚举常量 + 包私有 `parse` +
  4 个业务问题静态谓词（`isShipped` / `isFailed` / `acceptsTrackingBackfill` /
  `acceptsOutboundSubmit`）。调用方普遍持有 JDBC 原始字符串，故接口取字符串入参；
  null/未知一律落否定分支，与迁移前各点位 `equals` 语义逐位一致。
- 迁移 4 个点位（SourceSyncFactsReader / OutboundReconService /
  ShipmentJdTrackingBackfillService / ShipmentJdOutboundPreparer）。
- 测试：`ShipmentStatusTest` 7 例（含四状态 × 四问题完整真值表、
  `PARTIAL` 不属于本词汇表的显式断言）；`ShipmentStatusRatchetTest` 2 例
  （清单棘轮 + 只增不减自检），已用注入违规实测确认能拦住并精确报出文件行号。

## 语义变化

**无。** 四个点位的真值表与迁移前逐位一致，仅把判定的所有权搬了家。

## 未收编（明确的后续范围，不在本票阻断）

SQL 内联判定仍各自书写字面量：`ReviewCaseResolutionService:600`、
`SourceFollowupProgressService:52`、`DashboardController:73`、
`ImportBatchProgressService:60,64`、`TrackingTaskResolver:128`、
`ContinuationExportService:156`、`ShipmentJdTrackingBackfillService:916`、
`ShipmentTrackingService:59,76,226`（写入）+ V2/V3/V6 迁移视图。
棘轮只守 Java 字面量比较（text block 内的 SQL 显式跳过），新增状态时的同步清单
写在 `ShipmentStatus` 的 javadoc 里。

## 验收

已收编 4 点位全部经由 `ShipmentStatus` 判定；无语义变化；棘轮实测有效；
非 Docker 门禁 13/13 绿。待跑集成门禁：`SourceShipmentSyncServiceIntegrationTest`、
`OutboundReconApiTest`、`ShipmentJdOutboundPreviewApiTest`、
`ShipmentJdOutboundSubmitTest`、`ShipmentJdTrackingBackfillApiTest`（需 Docker）。
