# 02 — ShipmentStatus 枚举 + 生命周期判定模块（P1）

**What to build:** `fulfillment` 包新增 `ShipmentStatus` 枚举与 `ShipmentLifecycle`
判定模块，成为「发货批次处于什么阶段/算不算已发完」这条规则的唯一家。

**Blocked by:** 无
**Status:** ready-for-agent

## 背景

`app.shipments` 目前被 27 个文件、10 个包直接写 SQL（57 处），没有枚举、没有实体、
没有仓储；「已完成发货」这一条判定存在 4 种互不一致写法：

- `connector/sync/SourceSyncFactsReader.java:62` — `"SHIPPED" || "DELIVERED"`
- `recon/OutboundReconService.java:973` — 同上（另一份）
- `fulfillment/ShipmentJdTrackingBackfillService.java:751` — `"CREATED" || "SHIPPED"`
- `file/TrackingFileService.java:469` — `List.of("SHIPPED","PARTIAL","FAILED")`

对比：Order 侧有 `order/domain/Order.java` + `OrderStatus` 可作形态参照。

## 范围

1. `ShipmentStatus` 枚举：先枚举现网出现过的全部取值（上线前先
   `SELECT DISTINCT shipment_status FROM app.shipments` 对账，含历史数据）；
2. `ShipmentLifecycle`（或并入枚举的静态判定）：`isShipmentComplete`、
   `canBackfillTracking`、`isTerminal` 等以上 4 个点位真正需要的判定，逐一
   与各点位现行语义核对——**4 种写法很可能有的是 bug、有的是各自正确的不同问题**，
   先判性质再收拢，语义变化单独列出；
3. 迁移上述 4 个点位 + 同文件顺手可迁的字面量；
4. 棘轮：新增测试守门（模式同 `antdBoundary.test.ts` 的清单棘轮）——已迁移文件
   清单只增不减，清单内文件禁止出现裸 `shipment_status` 字符串字面量比较；
5. **不做**：JPA entity / repository / 27 文件一次性大迁移。

## 验收

4 个分歧点位全部经由同一模块判定；判定语义差异（若有）在票评论中逐条记录并
经用户确认；相关测试改打在模块接口上。
