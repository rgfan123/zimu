# 217: 采购入库单闭环——补货入仓线上化，库存来路有据

**What to build:** 采购补货入京东仓的完整闭环：运营在系统内为一批到仓货物创建采购入库单
（`orderPurchaseCreate`/addPoOrder，走 `JdWriteOpsService` 写门闩），提交后轮询
`queryPurchase`（queryPoOrderDetail）读回实收数量与批次，实收与计划的差异呈现给运营；
必要时可关闭未完成的采购单（`orderPurchaseClose`）。京东仓库存的「入口侧」从此在系统内
有单据、有实收、有差异记录——与出库侧（addSoOrder + 对账）对称。

**上下文：**
- 防腐层已就绪：写 `JdWriteOpsService.orderPurchaseCreate/orderPurchaseClose`
  （HTTP 层 `app.jd.write-mode` 默认 OFF 锁死），读 `JdOrderService.queryPurchase`。
- 库存快照已接入库存决策（FulfillmentStockDecisionService）：采购入库是快照增量的
  另一主要来源（与 #215 RTW 互补），接入后库存变化可全量归因。
- 原料库存 MCP（yuanliaokc，McpRawMaterialTools 有 create_raw_inbound_order 等
  本地原料入库工具）目前只管本地台账；京东仓入库线上化后两套入库口径可对齐。
- 写面安全口径沿用出库提交的成熟模式：授权操作人白名单、幂等（request_hash）、
  提交后读回核验（对齐 #210 的方向）。

**文件锚点：**
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/write/JdWriteOpsService.java`（写 seam）
- `backend/src/main/java/cn/zimu/fulfillment/connector/jd/order/JdOrderService.java`（queryPurchase）
- `backend/src/main/java/cn/zimu/fulfillment/fulfillment/ShipmentJdOutboundService.java`（写用例模式参考：幂等/审计/门闩）

**Blocked by:** None (can start immediately)（若与 #210/#209 同期实施，单号与读回核验口径应对齐）

**Status:** ready-for-agent

- [ ] 可在系统内创建采购入库单草稿→提交京东，提交受 write-mode 门闩与操作人授权双重约束
- [ ] 提交幂等：同一入库单重复提交不在京东侧产生重复单据（本方单号防碰撞口径对齐 #209）
- [ ] 提交后自动轮询读回实收数量/批次，计划 vs 实收差异在单据上可见
- [ ] 可关闭未完成采购单，关闭动作留审计
- [ ] 全链路审计（创建/提交/读回/关闭）；MOCK 模式可演示，REAL 由生产演练验证

## 关联既有票

- procurement-price #110（采购台）/#123（工单商业化）：采购入库回执可与工单闭环衔接；原料库存 MCP（rawmaterial 模块）已有自有仓入库先例。
