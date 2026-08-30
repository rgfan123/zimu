# 04 — JdSubmissionState 政策模块 + Plan 类型化投影（P2）

**What to build:** 给京东出库「提交状态机 + 对账/可重试政策」一个家
`JdSubmissionState`；给 `JdShipmentSubmissionPlan` 补类型化投影，堵住裸
`Map<String,Object> request` 的表示泄漏。这是 a515c85 已合并重构的下半场。

**Blocked by:** 无（与票 03 有一处交集：extractDeliveryNo 归属，先到先改）
**Status:** ready-for-agent

## 背景

- 状态词汇 `SUBMITTING/SUBMITTED/SYNC_FAILED` + `UNCERTAIN_EXTERNAL_RESULTS` 住在
  `ShipmentJdOutboundPreparer` 的静态常量里，被 Plan.PriorSubmission、Service、
  Executor reach 进去用——构造单元兼任了政策命名空间。
- `retryable = !"RECONCILIATION_REQUIRED".equals(code)` 有 4 处平行实现：
  Service 内联 ×2、`connector/wecom/card/JdOutboundFailureCard.retryable`
  （注释自称「与服务端同源」）、加上 requiresReconciliation 的间接参与。
- Plan 的 `request()` 是裸 Map：Service 用 unchecked cast 挖 `cargoInfos`、
  `customerInfo.ownerNo`、`warehouseNo`；Preview 靠键名脱敏 `pin`；Preparer 先塞
  `skuId` 再 `cargo.remove("skuId")`。京东报文形状知识散在 3-4 个文件里。
- Preparer 还兼任工具箱：`hasText/text/sha256/requiredText/optionalText/blockerMap`
  被全集群静态引用。

## 范围

1. `JdSubmissionState`：状态常量、UNCERTAIN_EXTERNAL_RESULTS、
   `requiresReconciliation(syncStatus,lastErrorCode)`、`retryable(code)` 唯一实现；
   Service/Executor/PriorSubmission/wecom 卡片全部改为消费方，删除「同源」注释；
2. Plan 类型化投影：`cargos()`（orderLine/goodsNo/planQuantity 强类型）、
   `warehouseNo()`、`ownerNo()`、`submittedCargoSnapshot()`、`displayRequest()`
   （脱敏视图，秘密字段在构造时标注而非 Preview 按键名猜）；`request()` 仅
   Executor 传京东用；Service/Preview 不再触碰裸 Map；
3. 工具箱迁出：`sha256` 等通用静态移 `common`，`blockerMap` 归 Plan/Blocker 自身；
4. 测试：政策模块纯单测覆盖 4 消费方语义；出库三类集成测试回归。

## 验收

`git grep 'RECONCILIATION_REQUIRED' backend/src/main` 的判定逻辑只剩
`JdSubmissionState` 一处；Service 无 unchecked cast；Preview 不 import Preparer。
