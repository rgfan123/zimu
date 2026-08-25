# 设计深化收敛 · 总图（2026-08-25）

决策依据与方向见 [ADR 0012](../../docs/adr/0012-knowledge-ownership-deepening.md)。
来源：JD 出库集群逐行评审（合并 `a515c85` 后的 master）+ 全后端设计扫描。
主题只有一个：**给无主知识找家**——同一条领域规则收进一个深模块，接口小、调用方多。

## 票序（杠杆 × 风险）

| 票 | 目标模块 | 等级 | 规模 | 状态 |
|---|---|---|---|---|
| 01 | `JdGoodsNameMatch` 比对内核归一 | P0 缺陷 | 半天 | 已并 master `343233f` |
| 02 | `ShipmentStatus` + 生命周期判定 | P1 | 4 处 Java 判定已收，SQL 点位挂棘轮 | 已实现，待集成门禁 |
| 03 | `JdIscGateway` + `JdPiiProjection` | P1 | ~1000-1400 行纯删 | ready-for-agent |
| 04 | `JdSubmissionState` + Plan 类型化投影 | P2 | 出库集群内部 | ready-for-agent |
| 05 | `LeasedTaskLoop` worker 骨架 | P2 | 8 worker ≈600 行收拢 | ready-for-agent |
| 06 | 挂账清理（ExportWecom 状态机 / MasterData 拆分 / PlatformRefresh 归接缝 / MCP 参数 / internal 镜像 / POI 接缝） | P3 | 改到哪补到哪 | 挂账 |
| 07 | 平台回传工作台 UI（8/24 设计结论存档） | 产品件 | 前端 | 待产品排期 |

## 关键证据点位（核对过的）

- Shipment 生命周期（**票 02 实施时核实修正**）：`SourceSyncFactsReader:62` 与
  `OutboundReconService:973` 是同一条规则的两份复制；
  `ShipmentJdTrackingBackfillService:751` 是**不同问题**（可否回填运单）；
  `file/TrackingFileService.java:469` 属运单导入文件「结果」列的另一套词汇表
  （**误报，不得并入**）。另收编 `ShipmentJdOutboundPreparer:53/228`。
  取值权威 = `V1__baseline.sql:446` CHECK 约束；SQL 内联 8 处 + V2/V3/V6 视图未收编，
  清单写在 `ShipmentStatus` javadoc。
- connector/jd：7 份 client 内核（110 行差 5 行）、`SUCCESS_CODES` ×7、PII 脱敏 ×6
  （+`recon/OutboundReconService.java:954`、`connector/jufubao/JufubaoOrderTransform.java:290` 变体）；
  规范实现在 `common/audit/SecretRedactor.java:55`。
- retryable/对账 ×4：`ShipmentJdOutboundService`（2 处内联）、
  `connector/wecom/card/JdOutboundFailureCard.retryable`（注释自称「同源」）、
  `JdShipmentSubmissionPlan.PriorSubmission.requiresReconciliation`。
- 名称比对分歧：`sku/JdSkuMappingCheckService.java:215`（无参照名→静默放行）
  vs `sku/ShipmentJdSkuMappingGateService.java:623`（无参照名→NAME_MISMATCH 警示；
  门禁侧名称比对只出警示、不阻断提交），normalize/token 逐字节复制。
- worker 循环 ×8：wecom/card、order/card、file×2、notification、message、agent×2；
  `message/AsyncTaskStore` 18 公开方法。
- Mock/REAL 形状分叉：`fulfillment/ShipmentJdOutboundExecutor.extractDeliveryNo`
  （`data.deliveryNo` vs `data.response.deliveryNo` 双形状兼容）。
